import QtQuick
import QtQuick.Controls
import QtQuick.Effects

Item {
    id: avatar
    property url photo: ""
    property string artistName: ""
    readonly property bool ready: portrait.status === Image.Ready
    implicitWidth: 22
    implicitHeight: 22
    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: Theme.accentSoft
        visible: avatar.artistName !== ""
    }
    Image {
        id: portrait
        anchors.fill: parent
        source: avatar.photo
        sourceSize.width: 64
        sourceSize.height: 64
        asynchronous: true
        fillMode: Image.PreserveAspectCrop
        visible: false
    }
    Rectangle {
        id: mask
        anchors.fill: parent
        radius: width / 2
        layer.enabled: true
        visible: false
    }
    MultiEffect {
        anchors.fill: parent
        source: portrait
        maskEnabled: true
        maskSource: mask
        visible: avatar.ready
    }
    Text {
        anchors.centerIn: parent
        visible: !avatar.ready && avatar.artistName !== ""
        text: avatar.artistName.charAt(0).toUpperCase()
        color: Theme.accent
        font.pixelSize: 11
        font.bold: true
    }
    Icon {
        anchors.centerIn: parent
        visible: avatar.artistName === ""
        name: "filter"
        size: 14
        tint: Theme.muted
    }
}
