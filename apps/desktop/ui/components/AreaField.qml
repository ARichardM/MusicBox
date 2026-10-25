import QtQuick
import QtQuick.Controls

// Cuadro de texto de varias líneas, con desplazamiento y borde que reacciona al foco.
Item {
    id: root
    property alias text: area.text
    property string placeholderText: ""
    property alias readOnly: area.readOnly
    property alias area: area
    property bool mono: false
    property int pixelSize: 13
    signal edited()

    implicitHeight: 110

    Rectangle {
        anchors.fill: parent
        radius: 14
        color: Theme.field
        border.width: 1
        border.color: area.activeFocus ? Theme.accent : Theme.line
        Behavior on border.color { ColorAnimation { duration: 140 } }
        opacity: root.enabled ? 1 : 0.55
    }

    // Texto de ayuda propio: el estilo Material no lo muestra sin la etiqueta flotante.
    Text {
        visible: area.text.length === 0 && root.placeholderText !== ""
        x: 14
        y: 10
        width: parent.width - 28
        text: root.placeholderText
        color: Theme.muted
        font.pixelSize: root.pixelSize
        wrapMode: Text.WordWrap
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 2
        clip: true
        ScrollBar.vertical.policy: ScrollBar.AsNeeded

        TextArea {
            id: area
            wrapMode: TextEdit.Wrap
            color: Theme.fg
            selectionColor: Theme.accent
            selectedTextColor: "white"
            selectByMouse: true
            font.pixelSize: root.pixelSize
            font.family: root.mono ? "Consolas" : font.family
            leftPadding: 12
            rightPadding: 12
            topPadding: 10
            bottomPadding: 10
            background: null
            onTextChanged: root.edited()
        }
    }
}
