package microscenery.stageSpace

import microscenery.PropertyChangeObservable
import microscenery.signals.HardwareDimensions
import microscenery.stageSpace.FocusManager.Mode

class StageSpaceModel : PropertyChangeObservable() {

    var hardwareDimensions: HardwareDimensions by propertyObservable(HardwareDimensions.EMPTY)
    var focusMode: FocusManager.Mode by propertyObservable(Mode.PASSIVE)

}
