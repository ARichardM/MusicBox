import QtQuick
import QtQuick.Controls

// Botón redondeado con variantes: primary, secondary, ghost, danger y link.
AbstractButton {
    id: control
    property string variant: "secondary"
    property string iconName: ""
    property bool busy: false
    property real busyProgress: -1
    property int sidePadding: 18

    implicitHeight: variant === "link" ? 30 : 40
    implicitWidth: row.implicitWidth + (variant === "link" ? 8 : sidePadding * 2)
    hoverEnabled: true
    opacity: enabled ? 1 : 0.45
    scale: down && enabled ? 0.97 : 1
    focusPolicy: Qt.TabFocus
    Behavior on scale { NumberAnimation { duration: 90; easing.type: Easing.OutCubic } }
    Behavior on opacity { NumberAnimation { duration: 140 } }

    readonly property color foreground: variant === "primary" ? (enabled ? "white" : Theme.muted)
        : variant === "danger" ? Theme.danger
        : variant === "secondary" ? Theme.fg
        : Theme.accent

    background: Rectangle {
        radius: height / 2
        color: {
            if (control.variant === "primary")
                return !control.enabled ? Theme.chip : control.down ? Qt.darker(Theme.accent, 1.15)
                     : control.hovered ? Qt.lighter(Theme.accent, 1.12) : Theme.accent
            if (control.variant === "secondary")
                return control.down ? Qt.darker(Theme.chip, 1.1) : control.hovered ? Qt.lighter(Theme.chip, 1.12) : Theme.chip
            if (control.variant === "danger")
                return control.hovered ? Theme.dangerSoft : "transparent"
            if (control.variant === "ghost")
                return control.hovered ? Theme.accentSoft : "transparent"
            return "transparent"
        }
        border.width: control.variant === "danger" ? 1 : (control.activeFocus ? 1 : 0)
        border.color: control.variant === "danger" ? Qt.rgba(1, 0.27, 0.23, 0.5) : Theme.accent
        Behavior on color { ColorAnimation { duration: 130 } }
    }

    contentItem: Item {
        implicitWidth: row.implicitWidth
        implicitHeight: row.implicitHeight
        Row {
            id: row
            anchors.centerIn: parent
            spacing: 8
            Spinner {
                visible: control.busy
                size: 16
                progress: control.busyProgress
                anchors.verticalCenter: parent.verticalCenter
            }
            Icon {
                visible: control.iconName !== "" && !control.busy
                name: control.iconName
                size: 16
                tint: control.foreground
                anchors.verticalCenter: parent.verticalCenter
            }
            Text {
                text: control.text
                color: control.foreground
                font.pixelSize: 13
                font.bold: control.variant === "primary" || control.variant === "link" || control.variant === "danger"
                font.underline: control.variant === "link" && control.hovered
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }
}
