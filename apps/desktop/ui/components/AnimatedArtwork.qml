import QtQuick
import QtMultimedia

Item {
    id: artwork
    required property var track
    readonly property string title: track.title || ""
    readonly property string artist: track.artist || ""
    readonly property string album: track.album || ""
    readonly property int duration: Number(track.duration) || 0
    readonly property string key: visible && title.trim() && artist.trim()
                                  ? JSON.stringify([title, artist, album, duration]) : ""
    property bool frameReady: false

    onKeyChanged: {
        player.stop()
        player.source = ""
        frameReady = false
        lookup.restart()
    }
    Timer {
        id: lookup
        interval: 120
        onTriggered: if (artwork.key) musicbox.requestAnimatedArtwork(
                         artwork.key, artwork.title, artwork.artist, artwork.album, artwork.duration)
    }
    Connections {
        target: musicbox
        function onAnimatedArtworkReady(key, url) {
            if (key !== artwork.key || !url) return
            player.source = url
            player.play()
        }
    }
    MediaPlayer {
        id: player
        videoOutput: output
        loops: MediaPlayer.Infinite
        onErrorOccurred: artwork.frameReady = false
    }
    VideoOutput {
        id: output
        anchors.fill: parent
        visible: artwork.frameReady
        fillMode: VideoOutput.PreserveAspectCrop
    }
    Connections {
        target: output.videoSink
        function onVideoSizeChanged() {
            if (output.videoSink.videoSize.width > 0 && player.source.toString())
                artwork.frameReady = true
        }
    }
}
