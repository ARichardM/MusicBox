import QtQuick
import QtQuick.Controls
import QtMultimedia

// Panel flotante con el video de la canción actual (modo MP4). Entra con un fundido y un leve zoom.
Rectangle {
    id: panel
    objectName: "videoPanel"
    readonly property bool shown: musicbox.videoMode && musicbox.videoAvailable

    width: Math.min(460, (parent ? parent.width : 800) * 0.42)
    height: Math.round(width * 9 / 16)
    radius: 16
    color: "#000000"
    clip: true
    border.width: 1
    border.color: Theme.line
    visible: opacity > 0.01
    opacity: shown ? 1 : 0
    scale: shown ? 1 : 0.94
    transformOrigin: Item.BottomRight
    Behavior on opacity { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
    Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }

    VideoOutput {
        id: output
        objectName: "videoOutput"
        anchors.fill: parent
        fillMode: VideoOutput.PreserveAspectFit
        Component.onCompleted: musicbox.attachVideoOutput(output)
    }

    Spinner {
        anchors.centerIn: parent
        size: 30
        running: panel.shown && musicbox.playerLoading
        visible: running
    }

    PillButton {
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.margins: 6
        variant: "ghost"
        iconName: "close"
        implicitWidth: 32
        implicitHeight: 32
        sidePadding: 6
        opacity: hovered || panel.shown ? 0.85 : 0
        onClicked: musicbox.setVideoMode(false)
    }
}
