import QtQuick
import QtQuick.Layouts

// Mensaje centrado para listas vacías.
ColumnLayout {
    property string iconName: "music"
    property string title: ""
    property string detail: ""
    spacing: 6
    Icon {
        Layout.alignment: Qt.AlignHCenter
        name: parent.iconName
        size: 28
        tint: Theme.muted
        opacity: 0.7
    }
    Text {
        Layout.alignment: Qt.AlignHCenter
        text: parent.title
        color: Theme.fg
        font.pixelSize: 15
        font.bold: true
    }
    Text {
        Layout.alignment: Qt.AlignHCenter
        Layout.maximumWidth: 320
        visible: text !== ""
        text: parent.detail
        color: Theme.muted
        font.pixelSize: 12
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.WordWrap
    }
}
