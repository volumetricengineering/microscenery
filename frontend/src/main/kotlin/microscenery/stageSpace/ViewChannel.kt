package microscenery.stageSpace

import graphics.scenery.Hub
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.volumes.*
import microscenery.MicrosceneryHub
import microscenery.UI.UIModel
import org.jfree.data.statistics.SimpleHistogramDataset

/**
 * A channel as displayed by the viewer. Channels with the same [key] share one look across all datasets.
 *
 * Changing a property applies it to every volume and slice of this channel.
 * Implements the interfaces needed by [TransferFunctionEditor].
 *
 * @param key identity of the channel, see [ChannelManager.channelKey]
 */
class ViewChannel(
    val key: String,
    val label: String,
    private val datasetManager: DatasetManager,
    private val msHub: MicrosceneryHub,
    transferFunction: TransferFunction,
    minDisplayRange: Float,
    maxDisplayRange: Float,
    colormap: Colormap,
    visible: Boolean,
    override var displayRangeLimits: Pair<Float, Float>
) : HasTransferFunction, HasHistogram, HasColormap {

    override var transferFunction: TransferFunction = transferFunction
        set(value) {
            field = value
            forEachVolume { it.transferFunction = value }
            forEachSlice { it.transferFunction = value }
        }

    override var minDisplayRange: Float = minDisplayRange
        set(value) {
            field = value
            forEachVolume { it.minDisplayRange = value }
            forEachSlice { it.minDisplayRange = value }
        }

    override var maxDisplayRange: Float = maxDisplayRange
        set(value) {
            field = value
            forEachVolume { it.maxDisplayRange = value }
            forEachSlice { it.maxDisplayRange = value }
        }

    override var colormap: Colormap = colormap
        set(value) {
            field = value
            forEachVolume { it.colormap = value }
            forEachSlice { it.colormap = value }
        }

    var visible: Boolean = visible
        set(value) {
            field = value
            forEachVolume { it.visible = value }
            forEachSlice { it.visible = value }
        }

    /** Applies the look of this channel to a newly created volume. */
    internal fun applyTo(volume: Volume) {
        volume.transferFunction = transferFunction
        volume.colormap = colormap
        volume.setTransferFunctionRange(minDisplayRange, maxDisplayRange)
        volume.visible = visible
    }

    /** Applies the look of this channel to a newly created slice. */
    internal fun applyTo(slice: SliceRenderNode) {
        slice.visible = visible
    }

    private fun forEachVolume(action: (Volume) -> Unit) =
        datasetManager.volumesOf(this).forEach(action)

    private fun forEachSlice(action: (SliceRenderNode) -> Unit) =
        datasetManager.slicesOf(this).forEach(action)

    override fun generateHistogram(volumeHistogramData: SimpleHistogramDataset): Int? {
        val render = msHub.getAttribute(Hub::class.java).get<Renderer>(SceneryElement.Renderer) ?: return null
        val selected = msHub.getAttributeOrNull(UIModel::class.java)?.selected

        val volumes = datasetManager.volumesOf(this)
        val slices = datasetManager.slicesOf(this)
        val vol = (selected as? Volume)?.takeIf { it in volumes } ?: volumes.firstOrNull()
        val slice = (selected as? SliceRenderNode)?.takeIf { it in slices } ?: slices.firstOrNull()

        return vol?.generateHistogram(volumeHistogramData) ?: slice?.generateHistogram(volumeHistogramData, render)
    }

    override fun toString() = label
}
