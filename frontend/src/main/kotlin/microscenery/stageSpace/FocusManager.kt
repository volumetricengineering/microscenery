package microscenery.stageSpace

import graphics.scenery.RichNode
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import microscenery.*
import microscenery.UI.UIModel
import org.joml.Vector3f
import kotlin.math.absoluteValue

class FocusManager(val stageSpaceManager: StageSpaceManager, val msHub: MicrosceneryHub) {
    private val stageSpaceModel = msHub.getAttribute(StageSpaceModel::class.java)
    private val uiModel = msHub.getAttribute(UIModel::class.java)

    val focus: Frame
    val focusTargetIndicator: Frame

    var focusTarget: RichNode = RichNode("Focus target")
    var mode
        get() = stageSpaceModel.focusMode
        set(value) {stageSpaceModel.focusMode = value}

    enum class Mode{
        PASSIVE, STEERING, STACK_SELECTION
    }
    var stackStartPos = Vector3f()
        private set

    private var stackStartIndicator: Frame? = null

    init {

        focus = Frame(msHub, Vector3f(1f)).apply {
            spatial().position = stageSpaceManager.hardware.stagePosition.copy()
            stageSpaceManager.stageRoot.addChild(this)
            visible = !MicroscenerySettings.get(Settings.StageSpace.HideFocusFrame,false)
            children.first()?.spatialOrNull()?.rotation = stageSpaceManager.layout.sheetRotation()
            // POSTSTUDY reactivate initVRInteraction(this,true)
        }

        stageSpaceManager.stageRoot.addChild(focusTarget)
        focusTarget.spatial().position = stageSpaceManager.hardware.stagePosition.copy()

        focusTargetIndicator = Frame(msHub, Vector3f(0.2f,0.2f,1f)) { focusTarget.spatial().position }.also {
            focusTarget.addChild(it)
            it.spatialOrNull()?.rotation = stageSpaceManager.layout.sheetRotation()
        }

        focus.update += {
            focusTargetIndicator.visible = !MicroscenerySettings.get(Settings.StageSpace.HideFocusTargetFrame, false)
                    && focus.spatial().position != focusTarget.spatial().position
        }

        var lastUpdate = 0L

        focusTarget.update += {
            focusTarget.spatial {

                val coerced = stageSpaceManager.hardware.hardwareDimensions().coercePosition(position,null)
                if (position != coerced) position = coerced

                when(mode){
                    Mode.PASSIVE -> {}
                    Mode.STEERING ->
                        if (position != stageSpaceManager.stagePosition
                            && lastUpdate + MicroscenerySettings.get("Stage.PositionUpdateRate", 200) < nowMillis()
                        ) {
                            stageSpaceManager.stagePosition = position
                            lastUpdate = nowMillis()
                        }
                    Mode.STACK_SELECTION -> {
                        val scanAxis = stageSpaceManager.layout.sheet.vector
                        val lockedAxis = stackStartPos * (Vector3f(1f) - scanAxis)
                        position = position * scanAxis + lockedAxis
                    }
                }
            }
        }

        stageSpaceModel.registerListener<Mode>(StageSpaceModel::focusMode) { _, new ->
            new?.let {modeChanged(new) }
        }
    }

    private fun modeChanged(mode: Mode) {
        when (mode){
            Mode.PASSIVE -> {
                stackStartIndicator?.detach()
            }
            Mode.STEERING -> {
                stackStartIndicator?.detach()
            }
            Mode.STACK_SELECTION -> {
                stackStartPos = focusTarget.spatial().position.copy()
                stackStartIndicator?.detach()
                stackStartIndicator = Frame(msHub,Vector3f(0.1f,0.8f,0.8f)).apply {
                    spatial().position = stackStartPos
                    stageSpaceManager.stageRoot.addChild(this)
                }
            }
        }
    }

    fun newStagePosition(pos: Vector3f){
        focus.spatial().position = pos
    }
}