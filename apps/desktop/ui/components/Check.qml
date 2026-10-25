import QtQuick
import QtQuick.Controls

// Casilla redondeada con marca animada.
AbstractButton {
    id: root
    property bool marked: false
    property int boxSize: 20
    implicitHeight: Math.max(boxSize, label.implicitHeight)
    implicitWidth: boxSize + (text !== "" ? 10 + label.implicitWidth : 0)
    hoverEnabled: true
    opacity: enabled ? 1 : 0.5
    focusPolicy: Qt.TabFocus

    contentItem: Item {
        Rectangle {
            id: box
            width: root.boxSize; height: root.boxSize
            radius: 7
            anchors.verticalCenter: parent.verticalCenter
            color: root.marked ? Theme.accent : "transparent"
            border.width: root.marked ? 0 : 2
            border.color: root.hovered || root.activeFocus ? Theme.accent : Theme.muted
            Behavior on color { ColorAnimation { duration: 130 } }
            Icon {
                anchors.centerIn: parent
                name: "check"
                size: root.boxSize - 8
                tint: "white"
                scale: root.marked ? 1 : 0
                opacity: root.marked ? 1 : 0
                Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutBack } }
                Behavior on opacity { NumberAnimation { duration: 120 } }
            }
        }
        Text {
            id: label
            visible: root.text !== ""
            anchors.left: box.right
            anchors.leftMargin: 10
            anchors.verticalCenter: parent.verticalCenter
            text: root.text
            color: Theme.fg
            font.pixelSize: 12
        }
    }
}
