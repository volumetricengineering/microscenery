package microscenery

import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.xy
import graphics.scenery.utils.lazyLogger
import ij.IJ
import ij.ImagePlus
import microscenery.hardware.MicroscopeHardwareAgent
import microscenery.signals.*
import microscenery.signals.Stack
import net.imglib2.RandomAccess
import net.imglib2.img.Img
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Dummy microscope hardware implementation that reads a volume from file.
 */
class FileMicroscopeHardware(
    file: String,
    stagePosition: Vector3f = Vector3f(),
    var zPerXY: Float = 1f,
) : MicroscopeHardwareAgent() {
    protected val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    lateinit var imp: ImagePlus
    lateinit var img: Img<*>
    var is16Bit = false

    val dimensions: Vector3i
        get() {
            return Vector3i(img.dimension(0).toInt(), img.dimension(1).toInt(), img.dimension(2).toInt())
        }

    override val output: BlockingQueue<MicroscopeSignal> = ArrayBlockingQueue(10)

    var liveThread: Thread? = null

    /** dataset of single slices, replaced by [loadImg] */
    private lateinit var liveDataset: Dataset

    override var stagePosition = stagePosition
        set(target) {
            val safeTarget = hardwareDimensions.coercePosition(target, logger)
            field = safeTarget
            status = status.copy(stagePosition = safeTarget)
        }


    fun loadImg(file:String){
        imp = IJ.openImage(file)
        is16Bit = imp.bitDepth == 16

        img = if (is16Bit)
            ImageJFunctions.wrap<UnsignedShortType>(imp)
        else
            ImageJFunctions.wrap<UnsignedByteType>(imp)


        liveDataset = Dataset(
            Id = nextId(),
            dimensions = Vector3i(dimensions.x, dimensions.y, 1),
            voxelSize = Vector3f(1f, 1f, zPerXY),
            numericType = if (is16Bit) NumericType.UINT16 else NumericType.UINT8,
            from = Vector3f(),
            to = Vector3f(),
            channels = listOf(ChannelMetadata(active = true)),
            sourceFormat = SourceFormat.TIFF,
            displayName = imp.title ?: "File"
        )
        hardwareDimensions = HardwareDimensions(
            stageMin = Vector3f(0f),
            stageMax = Vector3f(0f, 0f, dimensions.z.toFloat()*zPerXY)
        )
        output.put(MicroscopeDataset(liveDataset))
        status = MicroscopeStatus(
            ServerState.MANUAL,
            stagePosition,
            false
        )
    }

    init {
        loadImg(file)

        //no need to start the agent
    }

    override fun snapSlice() {
        snapSlice(null)
    }

    private data class StackTarget(val datasetId: Long, val stackId: Long, val sliceIndex: Int)

    /**
     * @param target the dataset and stack the slice belongs to. Null for a standalone slice of [liveDataset].
     */
    private fun snapSlice(target: StackTarget?) {
        val imgX = liveDataset.imageSize.x
        val imgY = liveDataset.imageSize.y
        val sliceBuffer = MemoryUtil.memAlloc(imgX * imgY * if (is16Bit) 2 else 1)
        val shortBuffer = sliceBuffer.asShortBuffer()

        val z = stagePosition.z.toInt()

        val ra = img.randomAccess()

        val index = IntArray(ra.numDimensions()) { 0 }
        index[2] = z
        for (y in 0 until imgY) {
            index[1] = y
            for (x in 0 until imgX) {
                index[0] = x
                if (is16Bit) shortBuffer.put((ra as RandomAccess<UnsignedShortType>).setPositionAndGet(*index).get().toShort())
                else sliceBuffer.put((ra as RandomAccess<UnsignedByteType>).setPositionAndGet(*index).get().toByte())
            }
        }

        sliceBuffer.rewind()

        val signal = Slice(
            Id = nextId(),
            datasetId = target?.datasetId ?: liveDataset.Id,
            channelIndex = 0,
            stackId = target?.stackId ?: NO_ID,
            stackSliceIndex = target?.sliceIndex ?: 0,
            created = System.currentTimeMillis(),
            stagePos = Vector3f(stagePosition.xy(),stagePosition.z * zPerXY),
            sizeBytes = sliceBuffer.capacity().toLong(),
            data = sliceBuffer
        )
        output.put(MicroscopeSlice(signal))
    }

    override fun goLive() {
        logger.warn("File Microscope does not support live mode")
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

            val start = Vector3f(0f)
            val steps = dimensions.z

            val dataset = liveDataset.copy(
                Id = nextId(),
                dimensions = Vector3i(dimensions.x, dimensions.y, steps),
                from = start,
                to = Vector3f(0f, 0f, (steps - 1) * zPerXY),
                timepointCount = 1,
                displayName = "${liveDataset.displayName} stack",
                created = nowMillis()
            )
            output.put(MicroscopeDataset(dataset))
            val stack = Stack(nextId(), dataset.Id, 0, 0, nowMillis())
            output.put(MicroscopeStack(stack))

            for (i in 0 until steps) {
                stagePosition = start + Vector3f(0f, 0f, i.toFloat())
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
        logger.warn("File Microscope does not support stage handling")
    }

    override fun startAcquisition() {
        acquireStack(MicroscopeControlSignal.AcquireStack(Vector3f(0f),Vector3f(0f),1f))
    }
}
