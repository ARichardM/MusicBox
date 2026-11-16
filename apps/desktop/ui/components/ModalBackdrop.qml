import QtQuick
import QtQuick.Effects

Item {
    id: root
    property Item sourceItem: null
    ShaderEffectSource {
        id: capture
        anchors.fill: parent
        sourceItem: root.sourceItem
        textureSize: Qt.size(Math.max(1, root.width / 2), Math.max(1, root.height / 2))
        hideSource: false
        visible: false
        live: root.visible
    }
    MultiEffect {
        anchors.fill: parent
        source: capture
        visible: root.sourceItem !== null && root.GraphicsInfo.api !== GraphicsInfo.Software
        autoPaddingEnabled: false
        blurEnabled: true
        blur: .45
        blurMax: 20
    }
    Rectangle { anchors.fill: parent; color: Theme.dark ? "#AA000000" : "#66101520" }
}
