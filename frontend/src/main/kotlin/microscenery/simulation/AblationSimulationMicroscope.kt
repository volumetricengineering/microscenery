package microscenery.simulation

import fromScenery.lazyLogger
import graphics.scenery.Origin
import microscenery.MicroscenerySettings
import microscenery.Settings
import microscenery.hardware.MicroscopeHardware
import microscenery.hardware.MicroscopeHardwareAgent
import microscenery.signals.*
import microscenery.toReadableString
import org.joml.Vector3f
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


/**
 * Wraps around a microscope and simulates ablation by subtracting pixel values aground ablation points, like a negative SDF rendering.
 */
class AblationSimulationMicroscope (
    val microscope: MicroscopeHardware,
    val actionAfterAblation: (AblationSimulationMicroscope) -> Unit = {it.startAcquisition()},
    var imgOrigin: Origin = Origin.Center
): MicroscopeHardwareAgent() {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    init {
        MicroscenerySettings.setIfUnset(Settings.Ablation.SizeUM,153f)
    }

    private var ablationPoints: List<MicroscopeControlSignal.AblationPoint> = emptyList()

    init {
        startAgent()
    }

    override fun ablatePoints(signal: MicroscopeControlSignal.PhotoManipulation) {
        logger.info("Adding ablation points to simulation: "+signal.points.map { it.position.toReadableString() })
        ablationPoints += signal.points
        actionAfterAblation(this)
    }

    override fun stop() {
        ablationPoints = emptyList()
        microscope.stop()
    }

    /** datasets of the wrapped microscope, needed for the geometry of the slices */
    private var datasets = emptyMap<Long, Dataset>()

    override fun onLoop() {
        val signal = microscope.output.poll(200, TimeUnit.MILLISECONDS) ?: return

        if (signal is MicroscopeDataset) {
            datasets = datasets + (signal.dataset.Id to signal.dataset)
        }

        if (signal is MicroscopeSlice && ablationPoints.isNotEmpty()) {
            simulateAblation(signal.slice)
        }
        // all changes where in-place therefore we just forward the signal
        this.output.put(signal)
    }

    private fun simulateAblation(slice: Slice) {
        //logger.info("Processing Slice ${slice.Id}")
        val dataset = datasets[slice.datasetId] ?: run {
            logger.warn("Got slice ${slice.Id} of unknown dataset ${slice.datasetId}. Not simulating ablation.")
            return
        }
        val bytes = when (dataset.numericType) {
            NumericType.INT8, NumericType.UINT8 -> 1
            NumericType.INT16, NumericType.UINT16 -> 2
            NumericType.FLOAT32 -> {
                logger.warn("Ablation simulation does not support FLOAT32. Not simulating ablation.")
                return
            }
        }
        val unsigned = dataset.numericType == NumericType.UINT8 || dataset.numericType == NumericType.UINT16
        val imageSize = dataset.imageSize
        val pixelSize = dataset.voxelSize
        val ablationRadius = MicroscenerySettings.get(Settings.Ablation.SizeUM,153f) * ablationPoints.first().laserPower

        val raw = slice.data ?: throw IllegalArgumentException("got slice signal without data buffer")
        if (raw.isReadOnly) throw IllegalArgumentException("read only data buffer currently not supported")
        raw.rewind()
        val data8 = raw
        val data16 = raw.asShortBuffer()

        for (imgY in 0 until imageSize.y) for (imgX in 0 until imageSize.x){
            val value = when {
                bytes == 1 && unsigned -> data8.get().toInt() and 0xFF
                bytes == 1 -> data8.get().toInt()
                unsigned -> data16.get().toInt() and 0xFFFF
                else -> data16.get().toInt()
            }

            var newValue = value
            val pixelStagePos = Vector3f(slice.stagePos)
            pixelStagePos.x += imgX * pixelSize.x
            pixelStagePos.y += imgY * pixelSize.y
            if (imgOrigin == Origin.Center){
                pixelStagePos.x -= imageSize.x * 0.5f * pixelSize.x
                pixelStagePos.y -= imageSize.y * 0.5f * pixelSize.y
            }

            ablationPoints.forEach { ablationPoint ->
                val diff = ablationPoint.position.distance(pixelStagePos)
                if (diff <= ablationRadius) {
                    newValue = (newValue * (diff / ablationRadius)).toInt()
                }
            }
            if (newValue != value) {
                if (bytes == 1) {
                    data8.put(data8.position() - 1, (newValue).toByte())
                } else {
                    data16.put(data16.position() - 1, (newValue).toShort())
                }
            }
        }
        raw.rewind()
    }

    // ### wrap this.microscope for all other microscope functions ###

    override var stagePosition: Vector3f by microscope::stagePosition
    override var hardwareDimensions: HardwareDimensions
        get() = microscope.hardwareDimensions()
        set(_) {}

    override fun goLive() {
        microscope.goLive()
    }

    override fun snapSlice() {
        microscope.snapSlice()
    }

    override fun shutdown() {
        this.close()
        microscope.shutdown()
    }

    override fun acquireStack(meta: MicroscopeControlSignal.AcquireStack) {
        microscope.acquireStack(meta)
    }

    override fun status(): MicroscopeStatus {
        return microscope.status()
    }

    override fun hardwareDimensions(): HardwareDimensions {
        return microscope.hardwareDimensions()
    }

    override fun moveStage(target: Vector3f) {
        microscope.stagePosition = target
    }

    override fun startAcquisition() {
        microscope.startAcquisition()
    }

    override fun sync(): Semaphore {
        return microscope.sync()
    }

}