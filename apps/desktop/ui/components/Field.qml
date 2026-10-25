import QtQuick
import QtQuick.Controls

// Campo de texto de una línea con borde que reacciona al foco.
TextField {
    id: field
    property string iconName: ""
    implicitHeight: 42
    leftPadding: iconName !== "" ? 40 : 14
    rightPadding: 14
    color: Theme.fg
    // El texto de ayuda se dibuja aparte: el estilo Material lo convierte en una etiqueta flotante.
    placeholderTextColor: "transparent"
    selectionColor: Theme.accent
    selectedTextColor: "white"
    selectByMouse: true
    font.pixelSize: 13
    verticalAlignment: TextInput.AlignVCenter
    opacity: enabled ? 1 : 0.55

    Text {
        visible: field.text.length === 0 && field.preeditText.length === 0
        x: field.leftPadding
        anchors.verticalCenter: parent.verticalCenter
        width: field.width - field.leftPadding - field.rightPadding
        text: field.placeholderText
        color: Theme.muted
        font: field.font
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: 14
        color: Theme.field
        border.width: 1
        border.color: field.activeFocus ? Theme.accent : Theme.line
        Behavior on border.color { ColorAnimation { duration: 140 } }
        Icon {
            visible: field.iconName !== ""
            name: field.iconName
            size: 16
            tint: Theme.muted
            x: 14
            anchors.verticalCenter: parent.verticalCenter
        }
    }
}
