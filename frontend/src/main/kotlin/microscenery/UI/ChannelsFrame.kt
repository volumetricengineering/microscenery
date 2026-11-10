package microscenery.UI

import graphics.scenery.volumes.TransferFunctionEditor
import microscenery.stageSpace.ChannelManager
import microscenery.stageSpace.ViewChannel
import net.miginfocom.swing.MigLayout
import javax.swing.*

/**
 * Lists all channels of [channelManager]. Each channel can be shown or hidden and its transfer function,
 * display range and colormap can be edited with a [TransferFunctionEditor].
 */
class ChannelsFrame(private val channelManager: ChannelManager) {
    private val frame = JFrame("Channels")
    private val panel = JPanel(MigLayout("wrap 3, insets 8", "[grow,fill][][]"))

    init {
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.add(panel)
        rebuild()
        channelManager.changeEvents += { event ->
            if (event.kProperty == ChannelManager::channels) SwingUtilities.invokeLater { rebuild() }
        }
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun rebuild() {
        panel.removeAll()
        val channels = channelManager.channels
        if (channels.isEmpty()) {
            panel.add(JLabel("No channels yet. They appear with the first data."), "span")
        }
        channels.forEach { addRow(it) }
        panel.revalidate()
        frame.pack()
    }

    private fun addRow(channel: ViewChannel) {
        panel.add(JLabel(channel.label))

        val visible = JCheckBox("visible", channel.visible)
        visible.addActionListener { channel.visible = visible.isSelected }
        panel.add(visible)

        val edit = JButton("Edit")
        edit.addActionListener { TransferFunctionEditor.showTFFrame(channel, "Transfer Function – ${channel.label}") }
        panel.add(edit)
    }
}
