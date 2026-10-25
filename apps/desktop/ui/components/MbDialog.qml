import QtQuick
import QtQuick.Controls

// Ventana modal redondeada, centrada, con entrada y salida suaves.
Popup {
    id: dialog
    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    focus: true
    padding: 0
    closePolicy: Popup.CloseOnEscape

    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 200 }
            NumberAnimation { property: "scale"; from: 0.95; to: 1; duration: 280; easing.type: Easing.OutBack }
        }
    }
    exit: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; to: 0; duration: 140 }
            NumberAnimation { property: "scale"; to: 0.97; duration: 140 }
        }
    }
    background: Rectangle {
        radius: 26
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
    }
    Overlay.modal: Rectangle { color: Theme.dark ? "#AA000000" : "#66101520" }
}
