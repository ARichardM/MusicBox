import QtQuick

// Marca de disponibilidad local, sin placa ni borde visible.
Rectangle {
    id: root
    property bool fromFolder: false
    width: 24
    height: 24
    radius: 7
    color: "#14000000"
    border.width: 0
    scale: visible ? 1 : 0.8
    opacity: visible ? 1 : 0
    Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
    Behavior on opacity { NumberAnimation { duration: 140 } }
    Image {
        anchors.centerIn: parent
        width: 16
        height: 16
        sourceSize.width: 48
        sourceSize.height: 48
        source: root.fromFolder ? "../icons/folder.svg" : "../icons/downloaded.svg"
        fillMode: Image.PreserveAspectFit
    }
}
