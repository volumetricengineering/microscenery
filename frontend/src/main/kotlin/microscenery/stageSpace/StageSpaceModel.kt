package microscenery.stageSpace

import microscenery.PropertyChangeObservable
import microscenery.signals.Dataset
import microscenery.signals.HardwareDimensions
import microscenery.stageSpace.FocusManager.Mode
import org.joml.Vector2f

class StageSpaceModel : PropertyChangeObservable() {

    var hardwareDimensions: HardwareDimensions by propertyObservable(HardwareDimensions.EMPTY)
    var focusMode: FocusManager.Mode by propertyObservable(Mode.PASSIVE)

    /** The most recently received dataset. Null until the hardware sent one. */
    var currentDataset: Dataset? by propertyObservable(null)
}

/** Extent of one image in stage coordinates */
val Dataset.imageExtent: Vector2f
    get() = Vector2f(imageSize.x * voxelSize.x, imageSize.y * voxelSize.y)
