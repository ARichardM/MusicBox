import QtQuick
import QtQuick.Controls
import QtMultimedia

// Video de la canción actual, dentro del marco del reproductor.
Rectangle {
    id: panel
    objectName: "videoPanel"
    property bool zoomed: false
    readonly property bool shown: musicbox.videoMode && musicbox.videoAvailable

    color: "#000000"
    clip: true
    visible: shown

    VideoOutput {
        id: output
        objectName: "videoOutput"
        anchors.fill: parent
        fillMode: panel.zoomed ? VideoOutput.PreserveAspectCrop : VideoOutput.PreserveAspectFit
        Component.onCompleted: musicbox.attachVideoOutput(output)
    }

    Spinner {
        anchors.centerIn: parent
        size: 30
        running: panel.shown && musicbox.playerLoading
        visible: running
    }

}
