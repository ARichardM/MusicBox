import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Importar una playlist completa desde un enlace de Spotify, YouTube, Deezer, Apple Music o SoundCloud.
MbDialog {
    id: dialog
    readonly property var imp: musicbox.playlistImporter
    readonly property string phase: imp.state
    width: 440
    padding: 24
    closePolicy: phase === "importing" ? Popup.NoAutoClose : Popup.CloseOnEscape

    // Abre el diálogo; con un enlace empieza a leerlo de inmediato, sin él pide uno.
    function openFor(text) {
        imp.reset()
        linkField.text = text || ""
        dialog.open()
        if (text && text.length > 0) imp.start(text)
    }

    onClosed: {
        if (imp.state === "reading" || imp.state === "ready") imp.cancel()
        else imp.reset()
    }

    Connections {
        target: dialog.imp
        function onStateChanged() {
            if (dialog.imp.state === "ready") nameField.text = dialog.imp.playlistName
        }
    }

    // Cada fase entra con un fundido suave.
    component Panel: ColumnLayout {
        id: panel
        property bool shown: false
        visible: shown
        Layout.fillWidth: true
        spacing: 12
        onShownChanged: if (shown) { opacity = 0; fade.restart() }
        NumberAnimation { id: fade; target: panel; property: "opacity"; to: 1; duration: 260; easing.type: Easing.OutCubic }
    }

    contentItem: ColumnLayout {
        spacing: 14

        Label {
            text: qsTr("Importar playlist")
            color: Theme.fg
            font.pixelSize: 19
            font.bold: true
        }

        // Pedir el enlace (o mostrar por qué falló).
        Panel {
            shown: dialog.phase === "idle" || dialog.phase === "error"
            Label {
                Layout.fillWidth: true
                text: qsTr("Pega el enlace de una playlist de Spotify, YouTube, Deezer, Apple Music o SoundCloud.")
                color: Theme.muted
                wrapMode: Text.WordWrap
                font.pixelSize: 13
            }
            Label {
                Layout.fillWidth: true
                visible: dialog.phase === "error"
                text: dialog.imp.message
                color: Theme.danger
                wrapMode: Text.WordWrap
                font.pixelSize: 12
            }
            Field {
                id: linkField
                objectName: "importLinkField"
                Layout.fillWidth: true
                iconName: "link"
                placeholderText: qsTr("Enlace de la playlist")
                onAccepted: if (dialog.imp.detect(text)) dialog.imp.start(text)
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cancelar"); variant: "ghost"; onClicked: dialog.close() }
                PillButton {
                    objectName: "importLinkRead"
                    text: qsTr("Leer playlist")
                    variant: "primary"
                    enabled: dialog.imp.detect(linkField.text)
                    onClicked: dialog.imp.start(linkField.text)
                }
            }
        }

        // Leyendo la playlist.
        Panel {
            shown: dialog.phase === "reading"
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Spinner { size: 22; running: dialog.phase === "reading" }
                Label {
                    Layout.fillWidth: true
                    text: dialog.imp.message
                    color: Theme.fg
                    wrapMode: Text.WordWrap
                    font.pixelSize: 13
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cancelar"); variant: "ghost"; onClicked: dialog.close() }
            }
        }

        // Lista para importar: elegir el nombre.
        Panel {
            shown: dialog.phase === "ready"
            Label {
                objectName: "importLinkCount"
                Layout.fillWidth: true
                text: qsTr("%1 canciones de %2").arg(dialog.imp.total).arg(dialog.imp.platform)
                color: Theme.fg
                font.pixelSize: 14
                font.bold: true
            }
            Label {
                Layout.fillWidth: true
                visible: dialog.imp.platform !== "YouTube"
                text: qsTr("Se buscan en YouTube Music y solo entran las coincidencias seguras.")
                color: Theme.muted
                wrapMode: Text.WordWrap
                font.pixelSize: 12
            }
            Field {
                id: nameField
                objectName: "importLinkName"
                Layout.fillWidth: true
                placeholderText: qsTr("Nombre de la playlist")
                onAccepted: dialog.imp.confirm(text)
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cancelar"); variant: "ghost"; onClicked: dialog.close() }
                PillButton {
                    objectName: "importLinkConfirm"
                    text: qsTr("Importar")
                    variant: "primary"
                    onClicked: dialog.imp.confirm(nameField.text)
                }
            }
        }

        // Importando: barra de progreso real.
        Panel {
            shown: dialog.phase === "importing"
            Label {
                Layout.fillWidth: true
                text: qsTr("Buscando canciones… %1 de %2").arg(dialog.imp.done).arg(dialog.imp.total)
                color: Theme.fg
                font.pixelSize: 13
            }
            SmoothProgress {
                objectName: "importLinkProgress"
                Layout.fillWidth: true
                value: dialog.imp.total > 0 ? dialog.imp.done / dialog.imp.total : 0
            }
            RowLayout {
                Layout.fillWidth: true
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Detener"); variant: "ghost"; onClicked: dialog.imp.cancel() }
            }
        }

        // Terminada.
        Panel {
            shown: dialog.phase === "done"
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Icon { name: "check"; size: 22; tint: Theme.success }
                Label {
                    objectName: "importLinkResult"
                    Layout.fillWidth: true
                    text: dialog.imp.message
                    color: Theme.fg
                    wrapMode: Text.WordWrap
                    font.pixelSize: 13
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cerrar"); variant: "primary"; onClicked: dialog.close() }
            }
        }
    }
}
