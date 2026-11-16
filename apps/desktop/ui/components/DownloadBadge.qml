import QtQuick

// Marca de disponibilidad local, sin placa ni borde visible.
Rectangle {
    id: root
    property bool fromFolder: false
    width: 24
    height: 24
    radius: 7
    color: "transparent"
    border.width: 0
    scale: visible ? 1 : 0.8
    opacity: visible ? 1 : 0
    Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
    Behavior on opacity { NumberAnimation { duration: 140 } }
    Icon {
        anchors.centerIn: parent
        size: 19
        name: root.fromFolder ? "folder" : "downloaded"
        tint: Qt.lighter(Theme.accent, 2.0)
    }
}
