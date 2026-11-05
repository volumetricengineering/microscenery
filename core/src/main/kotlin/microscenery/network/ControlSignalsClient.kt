package microscenery.network

import fromScenery.lazyLogger
import kotlinx.event.event
import microscenery.Agent
import microscenery.signals.BaseClientSignal
import microscenery.signals.BaseServerSignal
import microscenery.signals.BaseServerSignal.Companion.toPoko
import microscenery.signals.PROTOCOL_VERSION
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Client to send control [BaseClientSignal]s to [ControlSignalsServer] and receive [BaseServerSignal]s.
 *
 * Receive [BaseServerSignal]s via subscribing with a listener by [addListener].
 * Don't add too elaborate listeners. They get executed by the network thread.
 *
 * Send via [sendSignal].
 */
class ControlSignalsClient(
    val zContext: ZContext,
    val port: Int,
    val host: String,
    listeners: List<(BaseServerSignal) -> Unit> = emptyList()
) : Agent() {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    private val socket: ZMQ.Socket

    private val signalsOut = ArrayBlockingQueue<org.withXR.network.v4.BaseClientSignal>(1000)
    private val signalsIn = event<BaseServerSignal>()

    /** The last compatible hello of the server. Null until the handshake is done. */
    @Volatile
    var serverHello: BaseServerSignal.ServerHello? = null
        private set

    init {
        listeners.forEach { addListener(it) }

        val timeout = 200 //ms

        socket = zContext.createSocket(SocketType.DEALER)
        socket.receiveTimeOut = timeout
        if (socket.connect("tcp://${host}:${port}")) {
            logger.info("${ControlSignalsClient::class.simpleName} connected to tcp://${host}:${port}")
        } else {
            throw IllegalStateException("Could not connect to ${ControlSignalsClient::class.simpleName} connected to tcp://${host}:${port}")
        }

        signalsOut += BaseClientSignal.ClientSignOn().toProto()

        startAgent()
    }

    /**
     * Don't add too elaborate listeners. They get executed by the network thread.
     */
    fun addListener(listener: (BaseServerSignal) -> Unit) {
        synchronized(signalsIn) {
            signalsIn += { listener(it) }
        }
    }

    fun sendSignal(signal: BaseClientSignal): Boolean {
        if (!signalsOut.offer(signal.toProto(), 5000, TimeUnit.MILLISECONDS)) {
            logger.warn("Dropped ${signal::class.simpleName} package because of full queue.")
            return false
        }
        return true
    }

    override fun onLoop() {

        val payloadIn = socket.recv(ZMQ.DONTWAIT)
        val outSignal = signalsOut.poll()

        // process incoming messages first.
        // First frame in each message is the sender identity
        if (payloadIn != null) {
            processIncoming(org.withXR.network.v4.BaseServerSignal.parseFrom(payloadIn).toPoko())
        }

        // process outgoing messages
        if (outSignal != null) {
            if (!socket.send(outSignal.toByteArray())) {
                logger.error("ZMQ is busy and dropped a message")
            }
        }

        if (payloadIn == null && outSignal == null)
            Thread.sleep(200)
    }

    private fun processIncoming(signal: BaseServerSignal) {
        if (signal is BaseServerSignal.ServerHello) {
            if (signal.isCompatible) {
                serverHello = signal
            } else {
                logger.error(
                    "Server speaks protocol version ${signal.protocolVersion} but this client speaks " +
                            "$PROTOCOL_VERSION. Disconnecting."
                )
                serverHello = null
                // forward the hello so higher layers can react, then disconnect
                synchronized(signalsIn) { signalsIn(signal) }
                close()
                return
            }
        } else if (serverHello == null) {
            logger.warn("Ignoring ${signal::class.simpleName} received before a compatible server hello.")
            return
        }

        synchronized(signalsIn) {
            signalsIn(signal)
        }
    }

    override fun onClose() {
        // flush signals queued before closing, e.g. a shutdown command
        if (serverHello != null) {
            generateSequence { signalsOut.poll() }.forEach { socket.send(it.toByteArray(), ZMQ.DONTWAIT) }
            socket.linger = 500
        } else {
            socket.linger = 0
        }
        socket.close()
    }
}