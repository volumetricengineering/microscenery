package microscenery.stageSpace

import fromScenery.utils.extensions.minus
import graphics.scenery.*
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.extensions.toFloatArray
import graphics.scenery.utils.lazyLogger
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.Volume
import microscenery.MicrosceneryHub
import microscenery.MicroscenerySettings
import microscenery.Settings
import microscenery.UI.UIModel
import microscenery.detach
import microscenery.signals.*
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Keeps the received [Dataset]s and visualises their [Stack]s as volumes and standalone [Slice]s as slices.
 *
 * There is one volume per dataset and channel. The timepoints of a dataset are the timepoints of its volumes.
 * The look of volumes and slices is controlled per channel by [channelManager].
 */
class DatasetManager(val stageRoot: RichNode, val scene: Scene, val msHub: MicrosceneryHub) {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    internal val sortingSlicesLock = ReentrantLock()

    internal val sortedSlices = ArrayList<SliceRenderNode>()

    /** Known datasets by id */
    var datasets = emptyMap<Long, Dataset>()
        private set

    /** One per dataset and channel */
    internal var stacks = emptyList<StackContainer>()

    /** Buffers of the received stacks by stack id. Used to put the stack slices into place. */
    private var stackBuffers = emptyMap<Long, Pair<StackContainer, ByteBuffer>>()

    val channelManager = ChannelManager(this, msHub)

    private val flipVector: Vector3f
        get() {return Vector3f(
            if (MicroscenerySettings.get("Stage.Image.FlipX",false)) -1f else 1f,
            if (MicroscenerySettings.get("Stage.Image.FlipY",false)) -1f else 1f,
            if (MicroscenerySettings.get("Stage.Image.FlipZ",false)) -1f else 1f)}

    init {
        MicroscenerySettings.setIfUnset(Settings.StageSpace.CameraDependendZSorting, true)

        MicroscenerySettings.setIfUnset("Stage.Image.FlipX", false)
        MicroscenerySettings.setIfUnset("Stage.Image.FlipY", false)
        MicroscenerySettings.setIfUnset("Stage.Image.FlipZ", false)

        MicroscenerySettings.setIfUnset("Stage.ToggleSliceBorder", false)
        MicroscenerySettings.addUpdateRoutine(
            "Stage.ToggleSliceBorder"
        ) { setSliceBorderVisibility(MicroscenerySettings.get("Stage.ToggleSliceBorder",false)) }
    }

    /** All volumes of [channel] */
    fun volumesOf(channel: ViewChannel): List<Volume> =
        stacks.filter { it.channel == channel }.map { it.volume }

    /** All standalone slices of [channel] */
    fun slicesOf(channel: ViewChannel): List<SliceRenderNode> =
        sortingSlicesLock.withLock { sortedSlices.filter { it.viewChannel == channel } }

    /**
     * @return the dataset and channel index [volume] shows or null if it is not managed by this
     */
    fun getVolumeInfo(volume: Volume): Pair<Dataset, Int>? =
        stacks.firstOrNull { it.volume == volume }?.let { it.dataset to it.channelIndex }

    fun deleteStack(volume: Volume){
        val stackContainer = stacks.firstOrNull { it.volume == volume }?:return
        stacks = stacks - stackContainer
        removeAndFree(stackContainer)
    }

    fun deleteSlice(slice: SliceRenderNode){
        sortingSlicesLock.withLock {
            sortedSlices -= slice
            slice.detach()
        }
    }

    fun clearSlices() {
        sortingSlicesLock.withLock {
            sortedSlices.forEach {
                it.parent?.removeChild(it)
            }
            sortedSlices.clear()
        }

        val tmp = stacks
        stacks = emptyList()
        tmp.forEach { removeAndFree(it) }
    }

    private fun removeAndFree(container: StackContainer) {
        stackBuffers = stackBuffers.filterValues { it.first != container }
        container.volume.parent?.removeChild(container.volume)
        container.volume.volumeManager.remove(container.volume)
        container.timepoints.values.forEach { MemoryUtil.memFree(it) }
    }

    /**
     * Stores or replaces the dataset. Already created volumes stay as they are.
     */
    fun handleDatasetSignal(dataset: Dataset) {
        datasets = datasets + (dataset.Id to dataset)
    }

    fun handleSliceSignal(sliceSignal: Slice, layout: MicroscopeLayout) {
        if (sliceSignal.data == null) return

        if (!sliceSignal.isStandalone && stackBuffers.containsKey(sliceSignal.stackId)) {
            // slice belongs to a stack
            handleStackSlice(sliceSignal)
            return
        }
        // this slice does not belong to a stack and should be visualised on its own
        handleSingleSlice(sliceSignal, layout)
    }

    fun handleStackSignal(stack: Stack, hub: Hub) {
        val dataset = datasets[stack.datasetId]
        if (dataset == null) {
            logger.error("Got stack ${stack.Id} of unknown dataset ${stack.datasetId}. Dropping it.")
            return
        }
        if (!isSupported(dataset)) return

        val x = dataset.dimensions.x
        val y = dataset.dimensions.y
        val z = dataset.dimensions.z
        val existing = stacks.firstOrNull { it.dataset.Id == dataset.Id && it.channelIndex == stack.channelIndex }

        if (existing != null) {
            val buffer = existing.timepoints[stack.timepointIndex] ?: run {
                // new timepoint
                val buffer = MemoryUtil.memAlloc(x * y * z * dataset.numericType.bytes)
                existing.timepoints[stack.timepointIndex] = buffer
                existing.volume.addTimepoint(stack.created.toString(), buffer)
                existing.volume.goToLastTimepoint()
                msHub.getAttributeOrNull(UIModel::class.java)?.updateSelected()
                buffer
            }
            stackBuffers = stackBuffers + (stack.Id to (existing to buffer))
            return
        }

        val buffer = MemoryUtil.memAlloc(x * y * z * dataset.numericType.bytes)
        // the voxel size is always positive, the direction of the stack is given by from and to
        val sliceThickness = dataset.voxelSize.z * (dataset.to.z - dataset.from.z).sign.let { if (it == 0f) 1f else it }

        val timepoints = listOf(BufferedVolume.Timepoint(stack.created.toString(), buffer))
        val voxelSize = Vector3f(1f, 1f, sliceThickness).toFloatArray() // conversion is done by stage root
        val volume = when (dataset.numericType) {
            NumericType.INT8, NumericType.UINT8 ->
                Volume.fromBuffer(timepoints, x, y, z, UnsignedByteType(), hub, voxelSize)
            NumericType.INT16, NumericType.UINT16 ->
                Volume.fromBuffer(timepoints, x, y, z, UnsignedShortType(), hub, voxelSize)
            NumericType.FLOAT32 -> throw IllegalStateException("FLOAT32 should have been filtered before")
        }
        val channel = channelManager.channelFor(dataset, stack.channelIndex)

        volume.goToLastTimepoint()
        channel.applyTo(volume)
        volume.name = listOf(dataset.displayName.ifBlank { "Dataset ${dataset.Id}" }, channel.label).joinToString(" ")
        volume.origin = Origin.Center
        volume.spatial {
            position = (dataset.from + dataset.to).mul(0.5f)
            scale = Vector3f(1f, -1f, sliceThickness)
            scale *= Vector3f(dataset.voxelSize.x, dataset.voxelSize.y, 1f)
            scale *= flipVector
        }
        volume.pixelToWorldRatio = 1f // conversion is done by stage root

        stageRoot.addChild(volume)

        val container = StackContainer(dataset, stack.channelIndex, channel, volume, mutableMapOf(stack.timepointIndex to buffer))
        stacks = stacks + container
        stackBuffers = stackBuffers + (stack.Id to (container to buffer))

        msHub.getAttributeOrNull(UIModel::class.java)?.selected = volume
    }

    private fun isSupported(dataset: Dataset): Boolean {
        if (dataset.numericType == NumericType.FLOAT32) {
            logger.warn("Dataset ${dataset.Id} has numeric type FLOAT32 which can not be displayed yet. Dropping data.")
            return false
        }
        return true
    }

    /**
     * Sets the slice border visibility according to [visibility]
     */
    private fun setSliceBorderVisibility(visibility: Boolean) {
        sortedSlices.forEach {
            it.setBorderVisibility(visibility)
        }
    }

    private var camUpdateLambda: (() -> Unit)? = null

    /**
     * Set up the painters algorithm routine for the camera if not already setup.
     * We cant do that at initialization because then the camera might not be initialized.
     */
    private fun setupCameraPainterAlgorithmUpdate(){
        if (camUpdateLambda == null){
            val cam = scene.findObserver()
            if (cam == null) {
                logger.warn("No cam found, cant setup painters algorithm for slices.")
                return
            }
            var oldRotationHash = cam.spatial().rotation.hashCode()
            camUpdateLambda = {
                if (MicroscenerySettings.get("Stage.CameraDependendZSorting",true) && oldRotationHash != cam.spatial().rotation.hashCode()) {
                    sortSlices(cam)
                    oldRotationHash = cam.spatial().rotation.hashCode()
                }
            }
            cam.update.add(camUpdateLambda!!)
        }
    }

    /**
     * @param epsilon slices closer than this to the new slice get replaced
     */
    private fun sortAndInsertSlices(cam: Camera, newSlice: SliceRenderNode, epsilon: Float) {
        // detect too close slices to replace them
        sortingSlicesLock.withLock {
            stageRoot.children.filter {
                it is SliceRenderNode && it.spatialOrNull()?.position
                    ?.equals(
                        newSlice.spatial().position,
                        epsilon
                ) ?: false
            }.toList() // get out of children.iterator or something, might be bad to do manipulation within an iterator
                .forEach {
                    stageRoot.removeChild(it)
                    sortedSlices.remove(it)
                }
        }
        //insert slice
        sortSlices(cam, newSlice)
    }

    /**
     * Sorts slices with an adjusted painters algorithm.
     * This causes the slices to be rendered in a back to front order so that their transparency is displayed correctly.
     */
    private fun sortSlices(cam: Camera, newSlice: SliceRenderNode? = null) {
        setupCameraPainterAlgorithmUpdate()

        sortingSlicesLock.withLock {
            newSlice?.let {
                sortedSlices.add(it)
                stageRoot.addChild(it)
                it.spatial().updateWorld(false,true)
            }
            if (sortedSlices.isEmpty()) return

            // Adjusted Painters algorithm for uniformly leaning planes
            // General Idea: sort the planes roughly along their normal

            val sliceNormal = Vector3f(0f,0f,1f).rotate(sortedSlices[0].spatial().worldRotation())
            val camNormal = Vector3f(0f,0f,1f).rotate(cam.spatial().worldRotation())

            // multiply normal with a lage factor to serve as a point at "infinity" in world space.
            // if the camera view direction aligns with the normal invert it
            val normalDiff = (camNormal - sliceNormal).length()
            val camAndNormalInSameDirection = normalDiff < sqrt(2f)
            val sliceNormalInv = sliceNormal * 100000f * if(camAndNormalInSameDirection) -1f else 1f

            // Sorts the [sortedSlices] container using the distance between "infinite normal point"
            // and slice in order (the clostest first).
            sortedSlices.sortBy { it.spatial().worldPosition().distance(sliceNormalInv) }
            // remove and add in new order to get correct render ordering
            sortedSlices.forEach {
                stageRoot.children.remove(it)
            }
            sortedSlices.forEach {
                stageRoot.children.add(it)
            }
            // Resulting in a back to front rendering
            // Possible improvement: instead of using the distance to a point at infinite,
            // project on a line spanned by the normal
        }
    }

    private fun handleStackSlice(slice: Slice) {
        val data = slice.data ?: return
        val (container, buffer) = stackBuffers[slice.stackId] ?: run {
            logger.error("Did not find stack id: ${slice.stackId}")
            return
        }

        val offset = container.dataset.sliceByteSize * slice.stackSliceIndex
        if (offset + data.remaining() > buffer.capacity()) {
            logger.error("Slice ${slice.Id} with index ${slice.stackSliceIndex} does not fit into stack ${slice.stackId}. Dropping it.")
            return
        }
        val buf = buffer.duplicate()
        buf.position(offset)
        buf.put(data)
        container.volume.goToLastTimepoint()
        container.volume.volumeManager.notifyUpdate(container.volume)
    }

    private fun handleSingleSlice(signal: Slice, layout: MicroscopeLayout) {
        val data = signal.data ?: return
        val dataset = datasets[signal.datasetId]
        if (dataset == null) {
            logger.error("Got slice ${signal.Id} of unknown dataset ${signal.datasetId}. Dropping it.")
            return
        }
        if (!isSupported(dataset)) return
        val channel = channelManager.channelFor(dataset, signal.channelIndex)

        val node = SliceRenderNode(
            data,
            dataset.imageSize.x,
            dataset.imageSize.y,
            dataset.voxelSize.x,
            dataset.numericType.bytes,
            channel.transferFunction,
            channel.minDisplayRange,
            channel.maxDisplayRange,
            channel.colormap
        )
        node.viewChannel = channel
        channel.applyTo(node)
        // random offset might be set to avoid z-fighting for close slices
        val randomOffset = MicroscenerySettings.getOrNull<Float>(Settings.StageSpace.RandomSliceOffset)
        node.spatial {
            position = signal.stagePos + (randomOffset?.let { Vector3f(it*Random.nextFloat())} ?: Vector3f(0f))
            rotation = layout.sheetRotation()
            scale *= flipVector
        }

        scene.findObserver()?.let { sortAndInsertSlices(it, node, dataset.voxelSize.x) }
    }

    /**
     * Volume of one channel of a dataset.
     *
     * @param timepoints buffers by timepoint index. They might be not completely filled yet.
     */
    internal class StackContainer(
        val dataset: Dataset,
        val channelIndex: Int,
        val channel: ViewChannel,
        val volume: BufferedVolume,
        val timepoints: MutableMap<Int, ByteBuffer>
    )
}
