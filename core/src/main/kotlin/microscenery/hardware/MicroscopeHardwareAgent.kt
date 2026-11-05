package microscenery.hardware

import microscenery.Agent
import microscenery.signals.*
import org.joml.Vector3f
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates

/**
 * Agent class to handle common microscopy handling stuff.
 *
 * !! ATTENTION!! Call [startAgent] eg. in the Init method of your class
 */
abstract class MicroscopeHardwareAgent : Agent(), MicroscopeHardware {

    override val output: BlockingQueue<MicroscopeSignal> = ArrayBlockingQueue(50)


    protected var status: MicroscopeStatus by Delegates.observable(
        MicroscopeStatus(ServerState.STARTUP, Vector3f(), false)
    ) { _, _, _ ->
        output.put(status)
    }

    override var stagePosition: Vector3f
        get() = status.stagePosition
        set(value) {
            moveStage(value)
        }

    protected open var hardwareDimensions: HardwareDimensions by Delegates.observable(
        HardwareDimensions.EMPTY.copy()
    ) { _, old, new ->
        if (old != new) output.put(hardwareDimensions)
    }
    override fun status(): MicroscopeStatus = status
    override fun hardwareDimensions(): HardwareDimensions = hardwareDimensions

    private val idCounter = AtomicLong(NO_ID)

    /**
     * Next id for a [Dataset], [Stack] or [Slice]. All share one id space. Ids are > 0 and unique for the
     * lifetime of this object.
     */
    protected fun nextId(): Long = idCounter.incrementAndGet()

    /**
     * New stage position has to be announced in status in implementing method.
     */
    protected abstract fun moveStage(target: Vector3f)
}