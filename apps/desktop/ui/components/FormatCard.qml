import QtQuick
import QtQuick.Controls

// Tarjeta grande para elegir el formato (MP3 o MP4).
AbstractButton {
    id: root
    property bool selected: false
    property string title: ""
    property string detail: ""
    property string iconName: "music"

    implicitHeight: 74
    hoverEnabled: true
    opacity: enabled ? 1 : 0.55
    scale: down ? 0.98 : 1
    focusPolicy: Qt.TabFocus
    Behavior on scale { NumberAnimation { duration: 90 } }

    background: Rectangle {
        radius: 18
        color: root.selected ? Theme.accentSoft : (root.hovered ? Theme.cardHover : Theme.field)
        border.width: root.selected || root.activeFocus ? 2 : 1
        border.color: root.selected ? Theme.accent : Theme.line
        Behavior on color { ColorAnimation { duration: 150 } }
        Behavior on border.color { ColorAnimation { duration: 150 } }
    }

    contentItem: Item {
        Column {
            anchors.centerIn: parent
            spacing: 3
            Icon {
                anchors.horizontalCenter: parent.horizontalCenter
                name: root.iconName
                size: 18
                tint: root.selected ? Theme.accent : Theme.muted
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: root.title
                color: root.selected ? Theme.accent : Theme.fg
                font.pixelSize: 14
                font.bold: true
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: root.detail
                color: Theme.muted
                font.pixelSize: 10
            }
        }
        Rectangle {
            visible: root.selected
            width: 18; height: 18; radius: 9
            color: Theme.accent
            anchors.top: parent.top; anchors.right: parent.right
            anchors.topMargin: 8; anchors.rightMargin: 8
            Icon { anchors.centerIn: parent; name: "check"; size: 11; tint: "white" }
        }
    }
}
