import QtQuick

// Barra de progreso redondeada: animada al avanzar y con un destello deslizante cuando es indeterminada.
Item {
    id: root
    property real value: 0
    property bool indeterminate: false
    property color fillColor: Theme.accent
    property color fillEnd: Theme.cyan
    implicitHeight: 8
    height: 8

    Rectangle {
        id: track
        anchors.fill: parent
        radius: height / 2
        color: Theme.dark ? "#26272C" : "#D5DBE5"
        clip: true

        Rectangle {
            visible: !root.indeterminate
            height: parent.height
            width: Math.max(root.value > 0 ? height : 0, parent.width * Math.max(0, Math.min(1, root.value)))
            radius: height / 2
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: root.fillColor }
                GradientStop { position: 1; color: root.fillEnd }
            }
            Behavior on width { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
        }

        Rectangle {
            id: sweep
            visible: root.indeterminate
            height: parent.height
            width: parent.width * 0.38
            radius: height / 2
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: 0.5; color: root.fillEnd }
                GradientStop { position: 1; color: "transparent" }
            }
            NumberAnimation on x {
                from: -sweep.width; to: track.width
                duration: 1250; loops: Animation.Infinite
                running: root.indeterminate && root.visible
            }
        }
    }
}
