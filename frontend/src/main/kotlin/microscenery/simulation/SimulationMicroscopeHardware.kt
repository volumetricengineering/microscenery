package microscenery.simulation

import graphics.scenery.attribute.material.Material
import graphics.scenery.primitives.Plane
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.lazyLogger
import microscenery.MicrosceneryHub
import microscenery.copy
import microscenery.discover
import microscenery.hardware.MicroscopeHardwareAgent
import microscenery.nowMillis
import microscenery.signals.*
import microscenery.stageSpace.StageSpaceManager
import org.joml.Vector2f
import org.joml.Vector2i
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.math.roundToInt

/**

 */
class SimulationMicroscopeHardware(
    val msHub: MicrosceneryHub,
    stagePosition: Vector3f? = null,
    imageSize: Vector2i = Vector2i(250),
    stageSize: Vector3f = Vector3f(300f),
    val maxIntensity: Short = Short.MAX_VALUE
) : MicroscopeHardwareAgent() {
    protected val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    override val output: BlockingQueue<MicroscopeSignal> = ArrayBlockingQueue(10)

    val focalPlane = Plane(Vector3f(Vector2f(imageSize), 1f))

    private val hardwareCommandsQueue = ArrayBlockingQueue<HardwareCommand>(5000)

    // this lock is only relevant for self replicating commands e.g. snapSlice(live=true)
    private val stopLock = Any()

    var lastSnap = 0L

    /** dataset of all standalone slices */
    private val liveDataset = Dataset(
        Id = nextId(),
        dimensions = Vector3i(imageSize.x, imageSize.y, 1),
        voxelSize = Vector3f(1f),
        numericType = NUMERIC_TYPE,
        from = Vector3f(),
        to = Vector3f(),
        channels = listOf(ChannelMetadata(active = true)),
        sourceFormat = SourceFormat.PROCEDURAL,
        displayName = "Live"
    )

    var fastMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                exposureMS = 1L
                stageSpeedumPerSek = 50000000f
            } else {
                exposureMS = 100L
                stageSpeedumPerSek = 400f
            }
        }

    private var timeBetweenLiveImagesMS = 200L
    private var exposureMS = 100L
    private var stageSpeedumPerSek = 400f


    init {

        focalPlane.material().cullingMode = Material.CullingMode.FrontAndBack

        hardwareDimensions = HardwareDimensions(
            stageMin = Vector3f(0f),
            stageMax = stageSize
        )
        output.put(MicroscopeDataset(liveDataset))

        status = MicroscopeStatus(
            ServerState.MANUAL,
            stagePosition ?: (stageSize * 0.5f),
            false
        )

        startAgent()
    }

    //############################## called from external threads ##############################
    // to following functions are called from external threads and not from this agents thread

    override fun snapSlice() {
        hardwareCommandsQueue.add(HardwareCommand.SnapImage(false))
    }

    override fun moveStage(target: Vector3f) {
        hardwareCommandsQueue.add(HardwareCommand.MoveStage(target, hardwareDimensions))
    }

    override fun acquireStack(meta: MicroscopeControlSignal.AcquireStack) {
        stop()
        hardwareCommandsQueue.add(HardwareCommand.GenerateStackCommands(meta))
    }

    override fun ablatePoints(signal: MicroscopeControlSignal.PhotoManipulation) {
    }

    override fun goLive() {
        hardwareCommandsQueue.add(HardwareCommand.SnapImage(true))
    }

    override fun sync(): Semaphore {
        val lock = Semaphore(1)
        lock.acquire()
        hardwareCommandsQueue.add(HardwareCommand.Sync(lock))
        return lock
    }

    override fun stop() {
        synchronized(stopLock) {
            hardwareCommandsQueue.clear()
            hardwareCommandsQueue.add(HardwareCommand.Stop)
        }
    }

    override fun shutdown() {
        status = status.copy(state = ServerState.SHUTTING_DOWN)
        synchronized(stopLock) {
            hardwareCommandsQueue.clear()
            hardwareCommandsQueue.add(HardwareCommand.Shutdown)
        }
    }

    override fun startAcquisition() {
    }

    override fun deviceSpecificCommands(data: ByteArray) {
    }


    //############################## end of called from external threads ##############################

    override fun onLoop() {
        when (val hwCommand = hardwareCommandsQueue.poll(50, MILLISECONDS)) {
            is HardwareCommand.GenerateStackCommands -> {
                executeGenerateStackCommands(hwCommand)
            }

            is HardwareCommand.MoveStage -> {
                executeMoveStage(hwCommand.safeTarget)
            }

            is HardwareCommand.SnapImage -> {
                executeSnapImage(hwCommand)
            }

            is HardwareCommand.Stop -> {
                status = status.copy(state = ServerState.MANUAL)
            }

            is HardwareCommand.Sync -> hwCommand.lock.release()
            HardwareCommand.Shutdown -> {}
        }
    }

    private fun executeSnapImage(hwCommand: HardwareCommand.SnapImage) {
        if (hwCommand.live) {
            when (status.state) {
                ServerState.LIVE -> {}
                ServerState.MANUAL -> status = status.copy(state = ServerState.LIVE)
                else -> {
                    stop()
                    logger.error("Want to go live but server is ${status.state}. Stopping.")
                    return
                }
            }

            hardwareCommandsQueue.add(HardwareCommand.SnapImage(true))

            if (System.currentTimeMillis() - lastSnap < timeBetweenLiveImagesMS) {
                return
            }
        }

        val imgX = liveDataset.imageSize.x
        val imgY = liveDataset.imageSize.y
        val sliceBuffer = MemoryUtil.memAlloc(imgX * imgY * NUMERIC_TYPE.bytes)
        val shortBuffer = sliceBuffer.asShortBuffer()

        val microDummys = getMicroDummysInFocus()

        for (y in 0 until imgY) {
            for (x in 0 until imgX) {
                shortBuffer.put(
                    microDummys
                        .map {
                            it.intensity(
                                Vector3f(
                                    x.toFloat() - imgX / 2,
                                    y.toFloat() - imgY / 2,
                                    0f
                                ) + stagePosition
                            )
                        }
                        .sum()
                        .toShort()
                        .coerceAtMost(maxIntensity)
                )
            }
        }


        val target = hwCommand.target
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
        Thread.sleep(exposureMS)
        output.put(MicroscopeSlice(signal))
    }

    private fun getMicroDummysInFocus(): List<Simulatable> {
        val stageSpaceManager = msHub.getAttribute(StageSpaceManager::class.java)
        if (focalPlane.parent == null) stageSpaceManager.stageRoot.addChild(focalPlane)

        return focalPlane.getScene()?.discover { it.getAttributeOrNull(Simulatable::class.java) != null }
            ?.filter {
                it.boundingBox?.intersects(focalPlane.boundingBox!!) == true
            }?.map {
                it.getAttribute(Simulatable::class.java)
            } ?: emptyList()
    }

    private fun executeGenerateStackCommands(hwCommand: HardwareCommand.GenerateStackCommands) {
        val meta = hwCommand.signal

        val start = hardwareDimensions.coercePosition(meta.startPosition, logger)
        val end = hardwareDimensions.coercePosition(meta.endPosition, logger)
        val dist = end - start
        val steps = (dist.length() / meta.stepSize).roundToInt()
        // steps == 0 if start and end are (almost) equal. Avoid NaN in the dataset then.
        val step = if (steps > 0) dist * (1f / steps) else Vector3f()

        // a live series continues its dataset with the next timepoint
        val series = hwCommand.series
        val (dataset, timepointIndex) = if (series != null && series.dataset.from == start) {
            series.dataset to series.timepointIndex
        } else {
            val newDataset = Dataset(
                Id = nextId(),
                dimensions = Vector3i(liveDataset.imageSize.x, liveDataset.imageSize.y, steps),
                voxelSize = Vector3f(liveDataset.voxelSize.x, liveDataset.voxelSize.y,
                    if (steps > 0) step.length() else meta.stepSize),
                numericType = NUMERIC_TYPE,
                from = start,
                to = start + (step * (steps - 1).coerceAtLeast(0).toFloat()),
                channels = liveDataset.channels,
                timepointCount = if (meta.live) 0 else 1,
                sourceFormat = SourceFormat.PROCEDURAL,
                displayName = "Stack"
            )
            output.put(MicroscopeDataset(newDataset))
            newDataset to 0
        }

        val currentStack = Stack(nextId(), dataset.Id, 0, timepointIndex, nowMillis())
        output.put(MicroscopeStack(currentStack))
        status = status.copy(state = ServerState.STACK)

        for (i in 0 until steps) {
            hardwareCommandsQueue.add(
                HardwareCommand.MoveStage(
                    start + (step * i.toFloat()),
                    hardwareDimensions
                )
            )
            hardwareCommandsQueue.add(
                HardwareCommand.SnapImage(false, HardwareCommand.SliceTarget(dataset.Id, currentStack.Id, i))
            )
        }

        if (hwCommand.signal.live) {
            addToCommandQueueIfNotStopped(
                HardwareCommand.GenerateStackCommands(
                    signal = meta,
                    series = HardwareCommand.StackSeries(dataset, timepointIndex + 1)
                )
            )
        }
    }

    private fun executeMoveStage(target: Vector3f) {
        // skip if next command is also move
        if (hardwareCommandsQueue.peek() is HardwareCommand.MoveStage) return

        val safeTarget = hardwareDimensions.coercePosition(target, logger)
        var currentPos = stagePosition
        while (currentPos != safeTarget) {
            val diff = safeTarget - stagePosition
            val step = diff.copy()
            if (step.length() > stageSpeedumPerSek / 100) step.normalize(stageSpeedumPerSek / 100)
            currentPos = stagePosition + step
            focalPlane.spatial().position = currentPos
            status = status.copy(stagePosition = currentPos)
            Thread.sleep(1000 / 100)
        }
    }

    private fun addToCommandQueueIfNotStopped(vararg commands: HardwareCommand) {
        synchronized(stopLock) {
            if (hardwareCommandsQueue.peek() !is HardwareCommand.Stop) {
                commands.forEach { hardwareCommandsQueue.add(it) }
            }
        }
    }

    private sealed class HardwareCommand {
        val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

        class MoveStage(target: Vector3f, hwd: HardwareDimensions) :
            HardwareCommand() {
            val safeTarget = hwd.coercePosition(target, logger)
        }

        /** @param target null for standalone slices of the live dataset */
        data class SnapImage(val live: Boolean, val target: SliceTarget? = null) : HardwareCommand()

        /** @param series set when continuing a live stack series with its next timepoint */
        data class GenerateStackCommands(
            val signal: MicroscopeControlSignal.AcquireStack,
            val series: StackSeries? = null
        ) : HardwareCommand()
        data class Sync(val lock: Semaphore) : HardwareCommand()
        object Stop : HardwareCommand()
        object Shutdown : HardwareCommand()

        data class SliceTarget(val datasetId: Long, val stackId: Long, val sliceIndex: Int)
        data class StackSeries(val dataset: Dataset, val timepointIndex: Int)
    }

    companion object {
        /** the simulation writes positive shorts */
        private val NUMERIC_TYPE = NumericType.UINT16
    }
}
