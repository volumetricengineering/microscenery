package microscenery.network

import fromScenery.lazyLogger
import kotlinx.event.event
import microscenery.Agent
import microscenery.MicroscenerySettings
import microscenery.Settings
import microscenery.signals.BaseClientSignal
import microscenery.signals.BaseClientSignal.Companion.toPoko
import microscenery.signals.BaseServerSignal
import microscenery.signals.NO_ID
import microscenery.signals.PROTOCOL_VERSION
import microscenery.signals.ServerType
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A server to receive [BaseClientSignal]s and send [BaseServerSignal]s from.
 *
 * Handles the protocol handshake: every [BaseClientSignal.ClientSignOn] is answered with [serverHello] to that
 * client only. Clients with an incompatible protocol version and probes are not admitted. Only admitted clients
 * receive broadcasts from [sendSignal] and only their signals are passed on to the listeners.
 *
 * Tries the send the rest of the queue once shutdown is = true.
 *
 * Receive [BaseClientSignal]s via subscribing with a listener by [addListener].
 * Don't add too elaborate listeners. They get executed by the network thread.
 *
 * Send via [sendSignal].
 *
 * @param serverHello provides the hello sent to signing on clients. Is evaluated on each sign on.
 */
class ControlSignalsServer(
    zContext: ZContext, val port: Int = MicroscenerySettings.get(Settings.Network.BasePort, 4000),
    host: String = MicroscenerySettings.get(Settings.Network.Host,"*").trim(),
    listeners: List<(BaseClientSignal) -> Unit> = emptyList(),
    private val serverHello: () -> BaseServerSignal.ServerHello = genericServerHello(newSessionId())
) : Agent() {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    private val socket: ZMQ.Socket

    private val signalsOut = ArrayBlockingQueue<org.withXR.network.v4.BaseServerSignal>(10000)
    private val signalsIn = event<BaseClientSignal>()

    /** Admitted clients. Identities are wrapped in [ByteBuffer] for content based equality. */
    private val clients = mutableSetOf<ByteBuffer>()

    internal var shutdown = false

    @Suppress("unused")
    val connectedClients
        get() = clients.size

    init {
        listeners.forEach { addListener(it) }

        socket = zContext.createSocket(SocketType.ROUTER)
        val adr = "tcp://${host}:${port}"
        socket.bind(adr)
        logger.info("${ControlSignalsServer::class.simpleName} bound to $adr")

        startAgent()
    }

    /**
     * Don't add too elaborate listeners. They get executed by the network thread.
     */
    fun addListener(listener: (BaseClientSignal) -> Unit) {
        synchronized(signalsIn) {
            signalsIn += { listener(it) }
        }
    }

    /** Queues signal to be sent to all admitted clients in main loop */
    fun sendSignal(signal: BaseServerSignal): Boolean {
        if (!signalsOut.offer(signal.toProto(), 5000, TimeUnit.MILLISECONDS)) {
            logger.warn("Dropped ${signal::class.simpleName} package because of full queue.")
            return false
        }
        return true
    }

    fun sendInternalSignals(signals: List<BaseClientSignal>) {
        synchronized(signalsIn) {
            signals.forEach { signalsIn(it) }
        }
    }

    override fun onLoop() {
        val identity = socket.recv(ZMQ.DONTWAIT)
        val outSignal = signalsOut.poll()

        // process incoming messages first.
        // First frame in each message is the sender identity
        if (identity != null && identity.size == 5) {
            //zmq identities always seem to have size==5 still not a perfect protection against garbage request but the odds are in our favor
            try {
                val event = org.withXR.network.v4.BaseClientSignal.parseFrom(socket.recv()).toPoko()
                processIncoming(identity, event)
            } catch (t: com.google.protobuf.InvalidProtocolBufferException){
                logger.warn("Error parsing message as protobuf. Ignoring it. ${t.message}", )
            }
        }

        // process outgoing messages
        if (outSignal != null) {
            val payload = outSignal.toByteArray()

            // publish to all clients
            clients.forEach { ident ->
                val isQueued = socket.sendMore(ident.array()) && socket.send(payload)
                if (!isQueued) logger.error("ZMQ dropped messages :(")
                Thread.sleep(1)
            }
        }

        if (identity == null && outSignal == null)
            Thread.sleep(200)
        if (shutdown) {
            this.close()
        }
    }

    private fun processIncoming(identity: ByteArray, signal: BaseClientSignal) {
        val key = ByteBuffer.wrap(identity)

        if (signal is BaseClientSignal.ClientSignOn) {
            // the hello goes out to this client only and before anything else
            if (!(socket.sendMore(identity) && socket.send(serverHello().toProto().toByteArray()))) {
                logger.error("ZMQ dropped server hello")
            }

            when {
                !signal.isCompatible -> {
                    logger.warn(
                        "Rejecting client with protocol version ${signal.protocolVersion}, " +
                                "server speaks $PROTOCOL_VERSION."
                    )
                    clients -= key
                    return
                }
                signal.isProbe -> return // probes only get a server hello
                else -> clients += key
            }
        } else if (key !in clients) {
            logger.warn("Ignoring ${signal::class.simpleName} from client that has not signed on.")
            return
        }

        synchronized(signalsIn) {
            signalsIn(signal)
        }
    }

    override fun onClose() {
        socket.linger = 0
        socket.close()
    }

    companion object {
        /** Random, non-zero session id. Pick once when the server starts. */
        fun newSessionId(): Long {
            var id = NO_ID
            while (id == NO_ID) id = Random.nextLong()
            return id
        }

        private fun genericServerHello(sessionId: Long): () -> BaseServerSignal.ServerHello = {
            BaseServerSignal.ServerHello("server", ServerType.OTHER, "", sessionId)
        }
    }
}
