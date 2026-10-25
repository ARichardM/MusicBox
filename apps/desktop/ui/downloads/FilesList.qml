import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Archivos terminados en esta sesión. Doble clic abre el archivo.
Item {
    ListView {
        id: list
        anchors.fill: parent
        model: musicbox.downloadFiles
        spacing: 8
        clip: true
        rightMargin: 6
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
        add: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 240 }
                NumberAnimation { property: "scale"; from: 0.94; to: 1; duration: 280; easing.type: Easing.OutBack }
            }
        }
        onCountChanged: Qt.callLater(positionViewAtEnd)

        delegate: Rectangle {
            id: row
            width: ListView.view.width - 6
            height: 54
            radius: 16
            color: hover.containsMouse ? Theme.cardHover : Theme.field
            Behavior on color { ColorAnimation { duration: 120 } }

            MouseArea {
                id: hover
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onDoubleClicked: downloads.openPath(modelData.path)
            }
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 8
                spacing: 12
                Rectangle {
                    Layout.preferredWidth: 34; Layout.preferredHeight: 34; radius: 17
                    color: Theme.successSoft
                    Icon { anchors.centerIn: parent; name: "check"; size: 15; tint: Theme.success }
                }
                Text {
                    Layout.fillWidth: true
                    text: modelData.title
                    color: Theme.fg
                    font.pixelSize: 13
                    elide: Text.ElideMiddle
                }
                Badge { text: modelData.format; tone: "neutral" }
                PillButton {
                    variant: "ghost"
                    iconName: "folder"
                    implicitWidth: 34
                    sidePadding: 0
                    onClicked: musicbox.showInFolder(modelData.path)
                }
            }
        }
    }

    EmptyState {
        anchors.centerIn: parent
        visible: list.count === 0
        iconName: "download"
        title: musicbox.downloadRunning ? qsTr("Descargando…") : qsTr("Tus archivos aparecerán aquí al terminar.")
        detail: musicbox.downloadRunning ? qsTr("Aparecerán aquí al terminar") : ""
    }
}
