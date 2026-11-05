import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: root
    property var rows: []
    function openFor(track) { rows = musicbox.trackInfo(track); open() }
    anchors.centerIn: parent
    width: Math.min(520, parent.width - 40)
    height: Math.min(560, parent.height - 40)
    modal: true
    title: qsTr("Información de la canción")
    standardButtons: Dialog.Close
    background: Rectangle { radius: 20; color: musicbox.darkMode ? "#161618" : "white" }
    contentItem: ScrollView {
        clip: true
        contentWidth: availableWidth
        ColumnLayout {
            width: parent.width
            spacing: 14
            Repeater {
                model: root.rows
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 3
                    Label { text: modelData.label; opacity: .65; font.pixelSize: 12 }
                    TextEdit {
                        Layout.fillWidth: true
                        text: modelData.value
                        color: musicbox.darkMode ? "white" : "#111318"
                        font.pixelSize: 14
                        readOnly: true
                        selectByMouse: true
                        wrapMode: TextEdit.Wrap
                    }
                }
            }
        }
    }
}
