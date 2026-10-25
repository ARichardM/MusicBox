import QtQuick

// Etiqueta de estado compacta. `tone`: neutral, accent, success, danger, warning.
Rectangle {
    id: badge
    property string text: ""
    property string tone: "accent"
    readonly property color toneColor: tone === "success" ? Theme.success : tone === "danger" ? Theme.danger
        : tone === "warning" ? Theme.warning : tone === "neutral" ? Theme.muted : Theme.accent
    readonly property color toneSoft: tone === "success" ? Theme.successSoft : tone === "danger" ? Theme.dangerSoft
        : tone === "warning" ? Theme.warningSoft : tone === "neutral" ? Theme.chip : Theme.accentSoft

    implicitWidth: label.implicitWidth + 22
    implicitHeight: 24
    radius: height / 2
    color: toneSoft
    Behavior on color { ColorAnimation { duration: 200 } }

    Text {
        id: label
        anchors.centerIn: parent
        text: badge.text
        color: badge.toneColor
        font.pixelSize: 10
        font.bold: true
        font.letterSpacing: 0.9
        Behavior on color { ColorAnimation { duration: 200 } }
    }
}
