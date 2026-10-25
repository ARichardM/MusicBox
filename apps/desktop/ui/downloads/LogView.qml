import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Registro de la descarga, con un color por nivel (error, aviso, listo). Sigue el final salvo que subas.
Item {
    id: root
    readonly property var lines: musicbox.downloadLogLines

    function toHtml(entries) {
        var out = []
        for (var i = 0; i < entries.length; i++) {
            var text = entries[i].text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            var level = entries[i].level
            var color = level === "error" ? Theme.danger : level === "warning" ? Theme.warning
                      : level === "ready" ? Theme.success : Theme.muted
            out.push('<span style="color:' + color + '">' + text + "</span>")
        }
        return out.join("<br>")
    }

    Flickable {
        id: flick
        anchors.fill: parent
        anchors.rightMargin: 4
        clip: true
        contentWidth: width
        contentHeight: log.height
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        TextEdit {
            id: log
            width: flick.width
            readOnly: true
            selectByMouse: true
            textFormat: TextEdit.RichText
            wrapMode: TextEdit.Wrap
            font.family: "Consolas"
            font.pixelSize: 11
            color: Theme.muted
            selectionColor: Theme.accent
            text: root.toHtml(root.lines)
            onTextChanged: {
                var atEnd = flick.contentY >= flick.contentHeight - flick.height - 40
                if (atEnd) Qt.callLater(function () { flick.contentY = Math.max(0, flick.contentHeight - flick.height) })
            }
        }
    }

    EmptyState {
        anchors.centerIn: parent
        visible: root.lines.length === 0
        iconName: "history"
        title: qsTr("El registro aparecerá aquí")
        detail: qsTr("Cada paso de la descarga queda anotado, con avisos y errores en color.")
    }

    PillButton {
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.rightMargin: 12
        visible: root.lines.length > 0
        text: qsTr("Limpiar")
        variant: "secondary"
        implicitHeight: 28
        onClicked: musicbox.clearDownloadLog()
    }
}
