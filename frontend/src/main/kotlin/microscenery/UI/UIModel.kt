package microscenery.UI

import graphics.scenery.Node
import microscenery.PropertyChangeObservable

class UIModel : PropertyChangeObservable() {
    var selected: Node? by propertyObservable(null)
    fun updateSelected() {
        selected = selected
    }

}
