import QtQuick
import QtQuick.Controls

// Opción excluyente con punto animado (equivale a un botón de radio).
AbstractButton {
    id: root
    property bool selected: false
    property string title: ""
    property string detail: ""

    implicitHeight: detail !== "" ? 56 : 44
    hoverEnabled: true
    opacity: enabled ? 1 : 0.55
    focusPolicy: Qt.TabFocus

    background: Rectangle {
        radius: 14
        color: root.selected ? Theme.accentSoft : (root.hovered ? Theme.cardHover : "transparent")
        border.width: root.selected || root.activeFocus ? 1 : 0
        border.color: Theme.accent
        Behavior on color { ColorAnimation { duration: 140 } }
    }

    contentItem: Row {
        spacing: 12
        leftPadding: 14
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            width: 18; height: 18; radius: 9
            color: "transparent"
            border.width: 2
            border.color: root.selected ? Theme.accent : Theme.muted
            Behavior on border.color { ColorAnimation { duration: 140 } }
            Rectangle {
                anchors.centerIn: parent
                width: root.selected ? 8 : 0
                height: width; radius: width / 2
                color: Theme.accent
                Behavior on width { NumberAnimation { duration: 150; easing.type: Easing.OutBack } }
            }
        }
        Column {
            anchors.verticalCenter: parent.verticalCenter
            spacing: 1
            Text { text: root.title; color: Theme.fg; font.pixelSize: 13; font.bold: root.selected }
            Text { visible: root.detail !== ""; text: root.detail; color: Theme.muted; font.pixelSize: 11 }
        }
    }
}
