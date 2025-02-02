package io.libp2p.pubsub.gossip

import io.libp2p.core.InternalErrorException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.createLRUMap
import io.libp2p.etc.types.median
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.whenTrue
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubProtocol
import pubsub.pb.Rpc
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

typealias Topic = String

const val MaxBackoffEntries = 10 * 1024
const val MaxIAskedEntries = 256
const val MaxPeerIHaveEntries = 256
const val MaxIWantRequestsEntries = 10 * 1024

fun P2PService.PeerHandler.getIP(): String? =
    streamHandler.stream.connection.remoteAddress().getStringComponent(Protocol.IP4)

fun P2PService.PeerHandler.isOutbound() = streamHandler.stream.connection.isInitiator
fun P2PService.PeerHandler.getOutboundProtocol() = getOutboundHandler()?.stream?.getProtocol()?.getNow(null)
    ?: throw InternalErrorException("Outbound gossip stream not initialized or protocol is missing")

fun P2PService.PeerHandler.getOutboundGossipProtocol() = PubsubProtocol.fromProtocol(getOutboundProtocol())

/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter @JvmOverloads constructor(
    val params: GossipParams = GossipParams(),
    val scoreParams: GossipScoreParams = GossipScoreParams(),
    override val protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1
) : AbstractRouter() {

    val score by lazy { GossipScore(scoreParams, executor, curTimeMillis) }
    val fanout: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val mesh: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()

    private val mCache = MCache(params.gossipSize, params.gossipHistoryLength)
    private val lastPublished = linkedMapOf<Topic, Long>()
    private var heartbeatsCount = 0
    private val backoffExpireTimes = createLRUMap<Pair<PeerId, Topic>, Long>(MaxBackoffEntries)
    private val iAsked = createLRUMap<PeerHandler, AtomicInteger>(MaxIAskedEntries)
    private val peerIHave = createLRUMap<PeerHandler, AtomicInteger>(MaxPeerIHaveEntries)
    private val iWantRequests = createLRUMap<Pair<PeerHandler, MessageId>, Long>(MaxIWantRequestsEntries)
    private val heartbeatTask by lazy {
        executor.scheduleWithFixedDelay(
            ::heartBeat,
            params.heartbeatInterval.toMillis(),
            params.heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    private fun setBackOff(peer: PeerHandler, topic: Topic) = setBackOff(peer, topic, params.pruneBackoff.toMillis())
    private fun setBackOff(peer: PeerHandler, topic: Topic, delay: Long) {
        backoffExpireTimes[peer.peerId to topic] = curTimeMillis() + delay
    }

    private fun isBackOff(peer: PeerHandler, topic: Topic) =
        curTimeMillis() < (backoffExpireTimes[peer.peerId to topic] ?: 0)

    private fun isBackOffFlood(peer: PeerHandler, topic: Topic): Boolean {
        val expire = backoffExpireTimes[peer.peerId to topic] ?: return false
        return curTimeMillis() < expire - (params.pruneBackoff + params.graftFloodThreshold).toMillis()
    }

    private fun getDirectPeers() = peers.filter(::isDirect)
    private fun isDirect(peer: PeerHandler) = score.peerParams.isDirect(peer.peerId)
    private fun isConnected(peerId: PeerId) = peers.any { it.peerId == peerId }

    override fun onPeerDisconnected(peer: PeerHandler) {
        score.notifyDisconnected(peer)
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        collectPeerMessage(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: PeerHandler) {
        super.onPeerActive(peer)
        score.notifyConnected(peer)
        heartbeatTask.hashCode() // force lazy initialization
    }

    override fun notifyUnseenMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenMessage(peer, msg)
    }

    override fun notifySeenMessage(peer: PeerHandler, msg: Rpc.Message, validationResult: Optional<ValidationResult>) {
        score.notifySeenMessage(peer, msg, validationResult)
        if (validationResult.isPresent && validationResult.get() != ValidationResult.Invalid) {
            notifyAnyValidMessage(peer, msg)
        }
    }

    override fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenInvalidMessage(peer, msg)
    }

    override fun notifyUnseenValidMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenValidMessage(peer, msg)
        notifyAnyValidMessage(peer, msg)
    }

    private fun notifyAnyValidMessage(peer: PeerHandler, msg: Rpc.Message) {
        iWantRequests -= peer to getMessageId(msg)
    }

    fun notifyMeshed(peer: PeerHandler, topic: Topic) {
        score.notifyMeshed(peer, topic)
    }

    fun notifyPruned(peer: PeerHandler, topic: Topic) {
        score.notifyPruned(peer, topic)
    }

    fun notifyRouterMisbehavior(peer: PeerHandler, penalty: Int) {
        score.notifyRouterMisbehavior(peer, penalty)
    }

    override fun acceptRequestsFrom(peer: PeerHandler): Boolean {
        return isDirect(peer) || score.score(peer) >= score.params.graylistThreshold
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: PeerHandler) {
        when (controlMsg) {
            is Rpc.ControlGraft -> handleGraft(controlMsg, receivedFrom)
            is Rpc.ControlPrune -> handlePrune(controlMsg, receivedFrom)
            is Rpc.ControlIHave -> handleIHave(controlMsg, receivedFrom)
            is Rpc.ControlIWant -> handleIWant(controlMsg, receivedFrom)
        }
    }

    private fun handleGraft(msg: Rpc.ControlGraft, peer: PeerHandler) {
        val topic = msg.topicID
        // ignore GRAFT for unknown topics
        val meshPeers = mesh[topic] ?: return
        when {
            isDirect(peer) ->
                prune(peer, topic)
            isBackOff(peer, topic) -> {
                notifyRouterMisbehavior(peer, 1)
                if (isBackOffFlood(peer, topic)) {
                    notifyRouterMisbehavior(peer, 1)
                }
                prune(peer, topic)
            }
            score.score(peer) < 0 ->
                prune(peer, topic)
            meshPeers.size >= params.DHigh && !peer.isOutbound() ->
                prune(peer, topic)
            peer !in meshPeers ->
                graft(peer, topic)
        }
    }

    private fun handlePrune(msg: Rpc.ControlPrune, peer: PeerHandler) {
        val topic = msg.topicID
        mesh[topic]?.remove(peer)?.also {
            notifyPruned(peer, topic)
        }
        if (this.protocol == PubsubProtocol.Gossip_V_1_1) {
            if (msg.hasBackoff()) {
                setBackOff(peer, topic, msg.backoff.seconds.toMillis())
            } else {
                setBackOff(peer, topic)
            }
            if (score.score(peer) >= scoreParams.acceptPXThreshold) {
                processPrunePeers(msg.peersList)
            }
        } else {
            if (msg.hasBackoff() || msg.peersCount > 0) {
                notifyRouterMisbehavior(peer, 1)
            }
        }
    }

    private fun handleIHave(msg: Rpc.ControlIHave, peer: PeerHandler) {
        val peerScore = score.score(peer)
        // we ignore IHAVE gossip from any peer whose score is below the gossip threshold
        if (peerScore < score.params.gossipThreshold) return
        if (peerIHave.computeIfAbsent(peer) { AtomicInteger() }.incrementAndGet() > params.maxIHaveMessages) {
            // peer has advertised too many times within this heartbeat interval, ignoring
            return
        }
        val asked = iAsked.computeIfAbsent(peer) { AtomicInteger() }
        if (asked.get() >= params.maxIHaveLength) {
            // peer has already advertised too many messages, ignoring
            return
        }

        val iWant = msg.messageIDsList - seenMessages.keys
        val maxToAsk = min(iWant.size, params.maxIHaveLength - asked.get())
        asked.addAndGet(maxToAsk)
        iWant(peer, iWant.shuffled(random).subList(0, maxToAsk))
    }

    private fun handleIWant(msg: Rpc.ControlIWant, peer: PeerHandler) {
        val peerScore = score.score(peer)
        if (peerScore < score.params.gossipThreshold) return
        msg.messageIDsList
            .mapNotNull { mCache.getMessageForPeer(peer.peerId, it) }
            .filter { it.sentCount < params.gossipRetransmission }
            .map { it.msg }
            .forEach { submitPublishMessage(peer, it) }
    }

    private fun processPrunePeers(peersList: List<Rpc.PeerInfo>) {
        peersList.shuffled(random).take(params.maxPrunePeers)
            .map { PeerId(it.peerID.toByteArray()) to it.signedPeerRecord.toByteArray() }
            .filter { (id, _) -> !isConnected(id) }
            .forEach { (id, record) -> params.connectCallback(id, record) }
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        ctrl.run {
            (graftList + pruneList + ihaveList + iwantList)
        }.forEach { processControlMessage(it, receivedFrom) }
    }

    override fun broadcastInbound(msgs: List<Rpc.Message>, receivedFrom: PeerHandler) {
        msgs.forEach { pubMsg ->
            pubMsg.topicIDsList
                .mapNotNull { mesh[it] }
                .flatten()
                .distinct()
                .plus(getDirectPeers())
                .filter { it != receivedFrom }
                .forEach { submitPublishMessage(it, pubMsg) }
            mCache.put(getMessageId(pubMsg), pubMsg)
        }
        flushAllPending()
    }

    override fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit> {
        msg.topicIDsList.forEach { lastPublished[it] = curTimeMillis() }

        val peers =
            if (params.floodPublish) {
                msg.topicIDsList
                    .flatMap { getTopicPeers(it) }
                    .filter { score.score(it) >= score.params.publishThreshold }
                    .plus(getDirectPeers())
            } else {
                msg.topicIDsList
                    .mapNotNull { topic ->
                        mesh[topic] ?: fanout[topic] ?: getTopicPeers(topic).shuffled(random).take(params.D)
                            .also {
                                if (it.isNotEmpty()) fanout[topic] = it.toMutableSet()
                            }
                    }
                    .flatten()
            }
        val list = peers.map { submitPublishMessage(it, msg) }

        mCache.put(getMessageId(msg), msg)
        flushAllPending()
        return anyComplete(list)
    }

    override fun subscribe(topic: Topic) {
        super.subscribe(topic)
        val fanoutPeers = (fanout[topic] ?: mutableSetOf())
            .filter { score.score(it) >= 0 && !isDirect(it) }
        val meshPeers = mesh.getOrPut(topic) { mutableSetOf() }
        val otherPeers = (getTopicPeers(topic) - meshPeers - fanoutPeers)
            .filter { score.score(it) >= 0 && !isDirect(it) }

        if (meshPeers.size < params.D) {
            val addFromFanout = fanoutPeers.shuffled(random)
                .take(params.D - meshPeers.size)
            val addFromOthers = otherPeers.shuffled(random)
                .take(params.D - meshPeers.size - addFromFanout.size)

            (addFromFanout + addFromOthers).forEach {
                graft(it, topic)
            }
            fanout -= topic
            lastPublished -= topic
        }
    }

    override fun unsubscribe(topic: Topic) {
        super.unsubscribe(topic)
        mesh[topic]?.forEach { prune(it, topic) }
    }

    private fun heartBeat() {
        heartbeatsCount++
        iAsked.clear()
        peerIHave.clear()

        val staleIWantTime = this.curTimeMillis() - params.iWantFollowupTime.toMillis()
        iWantRequests.entries.removeIf { (key, time) ->
            (time < staleIWantTime)
                .whenTrue { notifyRouterMisbehavior(key.first, 1) }
        }

        try {
            mesh.entries.forEach { (topic, peers) ->

                // drop underscored peers from mesh
                peers.filter { score.score(it) < 0 }
                    .forEach { prune(it, topic) }

                if (peers.size < params.DLow) {
                    // need more mesh peers
                    (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                        .shuffled(random)
                        .take(params.D - peers.size)
                        .forEach { graft(it, topic) }
                } else if (peers.size > params.DHigh) {
                    // too many mesh peers
                    val sortedPeers = peers
                        .shuffled(random)
                        .sortedBy { score.score(it) }
                        .reversed()

                    val bestDPeers = sortedPeers.take(params.DScore)
                    val restPeers = sortedPeers.drop(params.DScore).shuffled(random)
                    val outboundCount = (bestDPeers + restPeers).take(params.D).count { it.isOutbound() }
                    val outPeers = restPeers
                        .filter { it.isOutbound() }
                        .take(max(0, params.DOut - outboundCount))

                    val toDropPeers = (outPeers + bestDPeers + restPeers).drop(params.D)
                    toDropPeers.forEach { prune(it, topic) }
                }

                // keep outbound peers > DOut
                val outboundCount = peers.count { it.isOutbound() }
                (getTopicPeers(topic) - peers)
                    .filter { it.isOutbound() && score.score(it) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                    .shuffled(random)
                    .take(max(0, params.DOut - outboundCount))
                    .forEach { graft(it, topic) }

                // opportunistic grafting
                if (heartbeatsCount % params.opportunisticGraftTicks == 0 && peers.size > 1) {
                    val scoreMedian = peers.map { score.score(it) }.median()
                    if (scoreMedian < scoreParams.opportunisticGraftThreshold) {
                        (getTopicPeers(topic) - peers)
                            .filter { score.score(it) > scoreMedian && !isDirect(it) && !isBackOff(it, topic) }
                            .take(params.opportunisticGraftPeers)
                            .forEach { graft(it, topic) }
                    }
                }

                emitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf {
                    it !in getTopicPeers(topic) || score.score(it) < scoreParams.publishThreshold
                }
                val needMore = params.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= scoreParams.publishThreshold && !isDirect(it) }
                        .shuffled(random)
                        .take(needMore)
                }
                emitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (curTimeMillis() - lastPub > params.fanoutTTL.toMillis())
                    .whenTrue { fanout.remove(topic) }
            }

            mCache.shift()

            flushAllPending()
        } catch (t: Exception) {
            logger.warn("Exception in gossipsub heartbeat", t)
        }
    }

    private fun emitGossip(topic: Topic, excludePeers: Collection<PeerHandler>) {
        val ids = mCache.getMessageIds(topic)
        if (ids.isEmpty()) return

        val shuffledMessageIds = ids.shuffled(random).take(params.maxIHaveLength)
        val peers = (getTopicPeers(topic) - excludePeers)
            .filter { score.score(it) >= score.params.gossipThreshold && !isDirect(it) }

        peers.shuffled(random)
            .take(max((params.gossipFactor * peers.size).toInt(), params.DLazy))
            .forEach { enqueueIhave(it, shuffledMessageIds) }
    }

    private fun graft(peer: PeerHandler, topic: Topic) {
        mesh.getOrPut(topic) { mutableSetOf() }.add(peer)
        enqueueGraft(peer, topic)
        notifyMeshed(peer, topic)
    }

    private fun prune(peer: PeerHandler, topic: Topic) {
        setBackOff(peer, topic)
        enqueuePrune(peer, topic)
        if (mesh[topic]?.remove(peer) == true) {
            notifyPruned(peer, topic)
        }
    }

    private fun iWant(peer: PeerHandler, messageIds: List<MessageId>) {
        if (messageIds.isEmpty()) return
        messageIds[random.nextInt(messageIds.size)]
            .also { iWantRequests[peer to it] = curTimeMillis() }
        enqueueIwant(peer, messageIds)
    }

    private fun enqueuePrune(peer: PeerHandler, topic: Topic) {
        val pruneBuilder = Rpc.ControlPrune.newBuilder().setTopicID(topic)
        if (peer.getOutboundGossipProtocol() == PubsubProtocol.Gossip_V_1_1 && this.protocol == PubsubProtocol.Gossip_V_1_1) {
            // add v1.1 specific fields
            pruneBuilder.backoff = params.pruneBackoff.seconds
            (getTopicPeers(topic) - peer)
                .filter { score.score(it) >= 0 }
                .forEach {
                    pruneBuilder.addPeers(
                        Rpc.PeerInfo.newBuilder()
                            .setPeerID(it.peerId.bytes.toProtobuf())
                        // TODO skipping address record for now
                    )
                }
        }
        addPendingRpcPart(
            peer, Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addPrune(
                    pruneBuilder
                )
            ).build()
        )
    }

    private fun enqueueGraft(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun enqueueIwant(peer: PeerHandler, messageIds: List<MessageId>) {
        if (messageIds.isNotEmpty()) {
            addPendingRpcPart(
                peer,
                Rpc.RPC.newBuilder().setControl(
                    Rpc.ControlMessage.newBuilder().addIwant(
                        Rpc.ControlIWant.newBuilder().addAllMessageIDs(messageIds)
                    )
                ).build()
            )
        }
    }

    private fun enqueueIhave(peer: PeerHandler, messageIds: List<MessageId>) {
        addPendingRpcPart(
            peer,
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(messageIds)
                )
            ).build()
        )
    }
}
