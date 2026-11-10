package microscenery

import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.lazyLogger
import microscenery.hardware.MicroscopeHardwareAgent
import microscenery.signals.*
import microscenery.signals.Stack
import microscenery.simulation.Procedural
import org.joml.Vector2i
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Demo Hardware that returns generated data like [Volume.generateProceduralVolume]
 *
 * @param binning also scales the stage area by the binning amount.
 */
class DemoMicroscopeHardware(
    stagePosition: Vector3f = Vector3f(),
    var timeBetweenUpdatesMilli: Int = 200,
    val size: Vector3f = Vector3f(300f),
    val binning: Int = 1,
    seed: Long? = null
) : MicroscopeHardwareAgent() {
    protected val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    override val output: BlockingQueue<MicroscopeSignal> = ArrayBlockingQueue(10)

    val procedural = Procedural(seed = seed ?: Random().nextLong())

    var liveThread: Thread? = null

    /** in pixels */
    private val imageSize = Vector2i(50 / binning, 50 / binning)

    /** dataset of all standalone slices */
    private val liveDataset = Dataset(
        Id = nextId(),
        dimensions = Vector3i(imageSize.x, imageSize.y, 1),
        voxelSize = Vector3f(1f * binning),
        numericType = NumericType.UINT8,
        from = Vector3f(),
        to = Vector3f(),
        channels = listOf(ChannelMetadata(active = true)),
        sourceFormat = SourceFormat.PROCEDURAL,
        displayName = "Live"
    )

    init {
        hardwareDimensions = HardwareDimensions(
            stageMin = Vector3f(0f),
            stageMax = size
        )
        output.put(MicroscopeDataset(liveDataset))
        status = MicroscopeStatus(
            ServerState.MANUAL,
            stagePosition,
            false
        )

        //no need to start the agent
    }

    override var stagePosition = stagePosition
        set(target) {
            val safeTarget = hardwareDimensions.coercePosition(target, logger)
            field = safeTarget
            status = status.copy(stagePosition = safeTarget)
        }

    override fun snapSlice() {
        snapSlice(null)
    }

    /**
     * @param target the dataset and stack the slice belongs to. Null for a standalone slice of [liveDataset].
     */
    private fun snapSlice(target: StackTarget?) {
        val sliceBuffer = when (binning) {
            1 -> {
                procedural.slice(stagePosition, imageSize)
            }

            else -> {
                val imgX = imageSize.x
                val imgY = imageSize.y
                val sliceBuffer = MemoryUtil.memAlloc(imgX * imgY)

                val dataX = imgX * binning
                val dataY = imgY * binning
                val dataBuffer = procedural.slice(stagePosition, Vector2i(dataX, dataY))

                for (imgYPos in 0 until imgY) for (imgXPos in 0 until imgX) {
                    var collector = 0
                    for (binY in 0 until binning) for (binX in 0 until binning) {

                        val dataSliceXPos = imgXPos * binning + binX
                        val dataSliceYPos = imgYPos * binning + binY

                        val yOffset = dataY * (stagePosition.y.toInt() + dataSliceYPos)
                        val xOffset = stagePosition.x.toInt() + dataSliceXPos
                        val offset = yOffset + xOffset
                        collector += dataBuffer.get(offset)
                    }
                    sliceBuffer.put((collector / binning).toByte())
                }
                MemoryUtil.memFree(dataBuffer)
                sliceBuffer
            }
        }

        val signal = Slice(
            Id = nextId(),
            datasetId = target?.datasetId ?: liveDataset.Id,
            channelIndex = 0,
            stackId = target?.stackId ?: NO_ID,
            stackSliceIndex = target?.sliceIndex ?: 0,
            created = System.currentTimeMillis(),
            stagePos = stagePosition,
            sizeBytes = sliceBuffer.capacity().toLong(),
            data = sliceBuffer
        )
        output.put(MicroscopeSlice(signal))
    }

    private data class StackTarget(val datasetId: Long, val stackId: Long, val sliceIndex: Int)

    override fun goLive() {
        if (
            status.state == ServerState.MANUAL && liveThread == null) {
            liveThread = thread(isDaemon = true) {
                while (!Thread.currentThread().isInterrupted) {
                    snapSlice()
                    Thread.sleep(timeBetweenUpdatesMilli.toLong())
                }
            }
            status = status.copy(state = ServerState.LIVE)
        } else {
            logger.warn("Microscope not Manual (is ${status.state}) -> not going live")
        }
    }

    override fun sync(): Semaphore {
        return Semaphore(1) // It's already free. Come get it!
    }

    override fun stop() {

        if (status.state == ServerState.LIVE) {
            liveThread?.interrupt()
            liveThread = null
            status = status.copy(state = ServerState.MANUAL)
        }
    }

    override fun shutdown() {
        stop()
    }

    override fun acquireStack(meta: MicroscopeControlSignal.AcquireStack) {
        if (status.state != ServerState.MANUAL) {
            logger.warn("Ignoring Stack command because microscope is busy.")
        }

        status = status.copy(state = ServerState.STACK)
        thread {

            val start = meta.startPosition
            val end = meta.endPosition
            val dist = end - start
            val steps = (dist.length() / meta.stepSize).roundToInt()
            val step = if (steps > 0) dist * (1f / steps) else Vector3f()

            val dataset = Dataset(
                Id = nextId(),
                dimensions = Vector3i(imageSize.x, imageSize.y, steps),
                voxelSize = Vector3f(liveDataset.voxelSize.x, liveDataset.voxelSize.y,
                    if (steps > 0) step.length() else meta.stepSize),
                numericType = liveDataset.numericType,
                from = start,
                to = start + (step * (steps - 1).coerceAtLeast(0).toFloat()),
                channels = liveDataset.channels,
                timepointCount = 1,
                sourceFormat = SourceFormat.PROCEDURAL,
                displayName = "Stack"
            )
            output.put(MicroscopeDataset(dataset))
            val stack = Stack(nextId(), dataset.Id, 0, 0, nowMillis())
            output.put(MicroscopeStack(stack))

            for (i in 0 until steps) {
                stagePosition = start + (step * i.toFloat())
                snapSlice(StackTarget(dataset.Id, stack.Id, i))
            }

            status = status.copy(state = ServerState.MANUAL)
        }
    }

    override fun ablatePoints(signal: MicroscopeControlSignal.PhotoManipulation) {
        for (p in signal.points)
            logger.info("Ablating $p")
        output.put(AblationResults(signal.points.size * 50, (1..signal.points.size).map { Random().nextInt(20) + 40 }))
    }

    override fun onLoop() {
        throw NotImplementedError("demo hardware has no active agent")
    }

    override fun moveStage(target: Vector3f) {
        throw NotImplementedError("demo does not use MicroscopeAgents stage handling")
    }

    override fun startAcquisition() {
        snapSlice()
    }
}
