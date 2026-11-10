package microscenery.stageSpace

import graphics.scenery.utils.lazyLogger
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import microscenery.MicrosceneryHub
import microscenery.MicroscenerySettings
import microscenery.PropertyChangeObservable
import microscenery.Settings
import microscenery.signals.Dataset
import org.joml.Vector4f

/**
 * Global collection of [ViewChannel]s. Channels are created when a dataset introduces them.
 *
 * The initial look of a new channel is taken from, in this order: its [microscenery.signals.ChannelMetadata],
 * the global settings [Settings.StageSpace.ColorMap], "TransferFunction.DisplayRangeMin/Max", and hard defaults.
 */
class ChannelManager(private val datasetManager: DatasetManager, private val msHub: MicrosceneryHub) :
    PropertyChangeObservable() {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    /** All known channels in order of appearance. Listen via [changeEvents]. */
    var channels: List<ViewChannel> by propertyObservable(emptyList())
        private set

    private var configurations = emptyList<ViewChannel.() -> Unit>()

    init {
        MicroscenerySettings.addUpdateRoutine(Settings.StageSpace.ColorMap) {
            val colormap = settingsColormap() ?: return@addUpdateRoutine
            channels.forEach { it.colormap = colormap }
        }
    }

    /**
     * The channel [channelIndex] of [dataset] belongs to. Creates it on first sight.
     */
    @Synchronized
    fun channelFor(dataset: Dataset, channelIndex: Int): ViewChannel {
        val key = channelKey(dataset, channelIndex)
        channels.firstOrNull { it.key == key }?.let { return it }

        val meta = dataset.channels.getOrNull(channelIndex)
        val maxValue = when (dataset.numericType.bytes) {
            1 -> 255f
            else -> 65535f
        }
        val channel = ViewChannel(
            key = key,
            label = key,
            datasetManager = datasetManager,
            msHub = msHub,
            transferFunction = TransferFunction.ramp(0.0f, 1.0f, 0.5f),
            minDisplayRange = meta?.displayMin?.toFloat()
                ?: MicroscenerySettings.get("TransferFunction.DisplayRangeMin", 0f),
            maxDisplayRange = meta?.displayMax?.toFloat()
                ?: MicroscenerySettings.get("TransferFunction.DisplayRangeMax", Short.MAX_VALUE.toFloat()),
            colormap = meta?.color?.let { Colormap.fromColor(Vector4f(it, 1f)) }
                ?: settingsColormap()
                ?: Colormap.get("hot"),
            visible = meta?.active ?: true,
            displayRangeLimits = 0f to maxValue
        )
        configurations.forEach { channel.it() }
        channels = channels + channel
        logger.info("New channel ${channel.key}")
        return channel
    }

    /**
     * Applies [block] to all existing channels and to every channel created later.
     */
    @Synchronized
    fun configureAll(block: ViewChannel.() -> Unit) {
        configurations = configurations + block
        channels.forEach { it.block() }
    }

    private fun settingsColormap(): Colormap? {
        val colorName = MicroscenerySettings.getOrNull<String>(Settings.StageSpace.ColorMap) ?: return null
        return try {
            Colormap.get(colorName)
        } catch (t: Throwable) {
            logger.error("Could not find color $colorName")
            logger.debug(t.toString())
            null
        }
    }

    companion object {
        /** Channels with the same label are the same channel. Unlabelled channels are identified by their index. */
        fun channelKey(dataset: Dataset, channelIndex: Int): String =
            dataset.channels.getOrNull(channelIndex)?.label?.takeIf { it.isNotBlank() } ?: "Channel $channelIndex"
    }
}
