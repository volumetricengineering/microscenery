package microscenery.stageSpace

import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.HasCustomSpatial
import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.extensions.xyz
import graphics.scenery.utils.lazyLogger
import microscenery.*
import microscenery.Settings
import microscenery.UI.UIModel
import microscenery.hardware.MicroscopeHardware
import microscenery.primitives.Pyramid
import microscenery.signals.*
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.concurrent.TimeUnit

/**
 * Handles in and output events concerning the microscope.
 *
 * Constructor waits until microscope is ready.
 */
class StageSpaceManager(
    val hardware: MicroscopeHardware,
    val scene: Scene,
    val msHub: MicrosceneryHub,
    val layout: MicroscopeLayout = MicroscopeLayout.Default(),
    val viewMode: Boolean = MicroscenerySettings.get(Settings.StageSpace.viewMode,false)
) : Agent() {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    val uiModel = msHub.getAttribute(UIModel::class.java)
    val stageSpaceModel = msHub.getAttribute(StageSpaceModel::class.java)

    val stageRoot = RichNode("stage root")
    val scaleAndRotationPivot = RichNode("scaleAndRotationPivot")

    val selectionIndicator: HasCustomSpatial<*>

    internal val stageAreaBorders: Box
    var stageAreaCenter = Vector3f()
        private set

    val datasetManager = DatasetManager(stageRoot, scene, msHub)
    val focusManager = FocusManager(this,msHub)

    /** Extent of one image in stage coordinates of the most recent dataset. Zero until the first dataset. */
    private val imageExtent: Vector2f
        get() = stageSpaceModel.currentDataset?.imageExtent ?: Vector2f()

    var stagePosition: Vector3f
        get() = hardware.status().stagePosition
        set(value) {
            hardware.stagePosition = value
        }

    init {
        MicroscenerySettings.setIfUnset(Settings.StageSpace.CameraDependendZSorting, true)

        //init hub TODO: move out of ssmanager
        msHub.addAttribute(Scene::class.java, scene)
        msHub.addAttribute(MicroscopeHardware::class.java,hardware)
        msHub.addAttribute(StageSpaceManager::class.java,this)
        msHub.addAttribute(DatasetManager::class.java, datasetManager)

        scene.addChild(scaleAndRotationPivot)
        scaleAndRotationPivot.addChild(stageRoot)

        stageAreaBorders = Box(Vector3f(1f), insideNormals = true)
        stageAreaBorders.name = "stageAreaBorders"
        stageAreaBorders.material {
            ambient = Vector3f(0.6f, 0.6f, 0.6f)
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
            specular = Vector3f(0.0f, 0.0f, 0.0f)
            cullingMode = if (viewMode) Material.CullingMode.FrontAndBack else Material.CullingMode.Front
        }
        stageRoot.addChild(stageAreaBorders)
        BoundingGrid().node = stageAreaBorders
        stageAreaBorders.visible = MicroscenerySettings.get(Settings.StageSpace.ShowStageAreaBorders,true)

        selectionIndicator = initSelectionIndicator()

        startAgent()
    }

    override fun onLoop() {
        val signal = hardware.output.poll(200, TimeUnit.MILLISECONDS)
        signal?.let { logger.trace("got a {} signal:\n{}", signal::class.simpleName, signal) }
        when (signal) {
            is MicroscopeDataset -> {
                handleDatasetSignal(signal.dataset)
            }
            is MicroscopeSlice -> {
                datasetManager.handleSliceSignal(signal.slice, layout)
            }
            is HardwareDimensions -> {
                stageSpaceModel.hardwareDimensions = signal
                updateStageLayout()
            }
            is MicroscopeStatus -> {
                focusManager.newStagePosition(signal.stagePosition)
            }
            is MicroscopeStack -> {
                datasetManager.handleStackSignal(signal.stack, msHub.getAttribute(Hub::class.java))
            }
            is AblationResults -> {
                logger.info("Ablation took ${signal.totalTimeMillis}ms for ${signal.perPointTime.size} points " +
                        "(${signal.mean()}ms mean)")
            }
            else -> {}
        }
    }

    private fun handleDatasetSignal(dataset: Dataset) {
        val oldExtent = imageExtent
        datasetManager.handleDatasetSignal(dataset)
        stageSpaceModel.currentDataset = dataset
        if (imageExtent != oldExtent) updateStageLayout()
    }

    /**
     * Scales and positions the stage space according to the stage limits and the image extent of the current dataset.
     */
    private fun updateStageLayout() {
        val hwd = stageSpaceModel.hardwareDimensions
        val imgExtent = imageExtent
        stageAreaCenter = (hwd.stageMax + hwd.stageMin).times(0.5f)

        stageRoot.spatial {
            // scale the space in such a way that is initially always the same visual size and easy to handle by GUI
            val xSize = (hwd.stageMax.x - hwd.stageMin.x + imgExtent.x)
            // avoid division by zero before stage limits and image size are known
            scale = Vector3f(1 / (xSize.takeIf { it > 0f } ?: 1f))
            position = Vector3f(-1f) * stageAreaCenter * scale
        }
        stageAreaBorders.spatial {
            position = stageAreaCenter
            scale = (hwd.stageMax - hwd.stageMin).apply {
                // extra space for images at the edge of stage space
                val imageSize = when (layout.sheet) {
                    MicroscopeLayout.Axis.X -> Vector3f(0f, imgExtent.y, imgExtent.x)
                    MicroscopeLayout.Axis.Y -> Vector3f(imgExtent.x, 0f, imgExtent.y)
                    MicroscopeLayout.Axis.Z -> Vector3f(imgExtent, 0f)
                }
                this.add(imageSize)
                this.mul(1.1f)
            }
        }
    }

    fun stack(from: Vector3f, to: Vector3f, live: Boolean = MicroscenerySettings.get(Settings.Stage.NextStackLive, false)) {
        hardware.acquireStack(
            MicroscopeControlSignal.AcquireStack(
                from,
                to,
                MicroscenerySettings.get(Settings.Stage.PrecisionZ, stageSpaceModel.currentDataset?.voxelSize?.z ?: 1f),
                live
            )
        )
    }

    fun snapSlice() {
        hardware.snapSlice()
    }

    fun goLive() {
        logger.info("going live")
        hardware.goLive()
    }

    fun stop() {
        logger.info("stopping")
        hardware.stop()
    }

    fun sync() {
        logger.info("starting sync")
        hardware.sync().acquire()
    }

    fun exploreCubeStageSpace(
        p1: Vector3f,
        p2: Vector3f,
        resolution: Vector3f = MicroscenerySettings.getVector3(Settings.Stage.ExploreResolution) ?: Vector3f()
    ) {
        when (hardware.status().state) {
            ServerState.MANUAL -> {}// this is good, continue
            ServerState.LIVE -> {
                logger.warn("Server is in LIVE state. Stopping it now and continue with explore operation.")
                this.stop()
            }
            else ->  {
                logger.warn("Server is in ${hardware.status().state} state. Aborting explore operation.")
                return
            }
        }

        // if a component of resolution is 0 replace with sensible default (eg. image size)
        val imgExtent = imageExtent
        for (d in listOf(0,1)){
            if (resolution[d] <=  0f){
                resolution.setComponent(d, imgExtent[d].takeIf { it > 0f } ?: 50f)
            }
        }
        if (resolution.z <= 0f) resolution.z = 50f

        val from = Vector3f(
            p1.x.coerceAtMost(p2.x),
            p1.y.coerceAtMost(p2.y),
            p1.z.coerceAtMost(p2.z),
        )
        val to = Vector3f(
            p1.x.coerceAtLeast(p2.x),
            p1.y.coerceAtLeast(p2.y),
            p1.z.coerceAtLeast(p2.z),
        )

        // I'm missing classic for loops, kotlin :,(
        val steps = (to-from).div(resolution).absolute()
        val xPositions = (0 .. steps.x.toInt()).map{from.x+resolution.x*it}
        val yPositions = (0 .. steps.y.toInt()).map{from.y+resolution.y*it}
        val zPositions = (0 .. steps.z.toInt()).map{from.z+resolution.z*it}

        for (z in zPositions.withIndex()) {
            val tmpX = if (z.index % 2 == 0) xPositions else xPositions.reversed()
            for (x in tmpX.withIndex()) {
                val tmpY = if (x.index % 2 == 0) yPositions else yPositions.reversed()
                for (y in tmpY) {
                    this.stagePosition = Vector3f(x.value, y, z.value)
                    this.snapSlice()
                }
            }
        }
    }

    fun clearStage() {
        datasetManager.clearSlices()
    }

    fun worldToStageSpace(s: Spatial){
        s.position = worldToStageSpace(s.worldPosition())
        s.scale *= getInverseWorldScale()
    }

    fun worldToStageSpace(v: Vector3f,isPosition: Boolean = true): Vector3f =
        Matrix4f(stageRoot.spatial().world).invertAffine().transform(Vector4f().set(v, if(isPosition) 1f else 0f)).xyz()

    fun getInverseWorldScale(): Vector3f = Matrix4f(stageRoot.spatial().world).invertAffine().getScale(Vector3f())

    private fun initSelectionIndicator(): Pyramid {
        val selectionIndicator = Pyramid().apply {
            this.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
            this.name = "Selection Indicator"
            this.material().diffuse = Vector3f(1f)
            this.update += {
                if (this.metadata["animated"] == true) {
                    this.spatial().rotation.rotateLocalY(0.01f)
                    this.spatial().needsUpdate = true
                }
            }
            this.metadata["animated"] = true

            this.visible = MicroscenerySettings.setIfUnset(Settings.UI.ShowSelectionIndicator, true)
            MicroscenerySettings.addUpdateRoutine(Settings.UI.ShowSelectionIndicator) {
                this.visible = MicroscenerySettings.get(Settings.UI.ShowSelectionIndicator, true)
            }

        }
        // update position or visibility of selection indicator on change
        uiModel.changeEvents += { event ->
            when (event.kProperty) {
                UIModel::selected -> {
                    selectionIndicator.detach()
                    (event.new as? Node)?.let { node ->
                        val bb = node.boundingBox ?: return@let
                        val pos = Vector3f(bb.localCenter)
                        pos.y = bb.asWorld().max.y
                        selectionIndicator.spatial().position = pos
                        scene.addChild(selectionIndicator)

                        if (MicroscenerySettings.get(Settings.UI.ShowBorderOfSelected, false)) {
                            (event.new as? SliceRenderNode)?.setBorderVisibility(true)
                            (event.old as? SliceRenderNode)?.setBorderVisibility(false)
                        }
                    }
                }
            }
        }
        return selectionIndicator
    }
}
