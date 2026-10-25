import QtQuick
import QtQuick.Effects
import QtQuick.Layouts

// Ícono monocromo de ui/icons teñido con el color indicado.
Image {
    id: root
    property string name: ""
    property int size: 18
    property color tint: Theme.dark ? "#FFFFFF" : "#46505E"

    width: size
    height: size
    Layout.preferredWidth: size
    Layout.preferredHeight: size
    Layout.minimumWidth: size
    Layout.minimumHeight: size
    Layout.maximumWidth: size
    Layout.maximumHeight: size
    sourceSize.width: size * 3
    sourceSize.height: size * 3
    source: name === "" ? "" : "../icons/" + name + ".svg"
    fillMode: Image.PreserveAspectFit
    smooth: true
    mipmap: true
    layer.enabled: true
    layer.effect: MultiEffect {
        colorization: 1
        colorizationColor: root.tint
    }
}
