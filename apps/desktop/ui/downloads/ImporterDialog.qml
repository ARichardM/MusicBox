import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// De una imagen a tu música: cuatro pasos (imágenes y texto, tabla, coincidencias, informe).
MbDialog {
    id: dialog
    readonly property var imp: downloads.importer
    readonly property var steps: ["images", "table", "matches", "report"]
    property string step: "images"
    property string duplicatesText: ""
    width: Math.min(1180, Overlay.overlay ? Overlay.overlay.width - 60 : 1180)
    height: Math.min(860, Overlay.overlay ? Overlay.overlay.height - 60 : 860)

    onClosed: { imp.reset(); step = "images" }

    Connections {
        target: dialog.imp
        function onGoToStep(index) { dialog.step = dialog.steps[index] }
        function onConfirmDuplicates(text) { dialog.duplicatesText = text; duplicatesDialog.open() }
        function onTransferDone() { dialog.close() }
    }

    // Ctrl+V pega una imagen del portapapeles salvo que se esté escribiendo en un campo de texto.
    Shortcut {
        sequence: "Ctrl+V"
        enabled: dialog.opened && dialog.step === "images"
        onActivated: {
            var item = dialog.Window.activeFocusItem
            if (item && item.hasOwnProperty("selectByMouse")) return
            dialog.imp.pasteImage()
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 22
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            ColumnLayout {
                spacing: 3
                Text { text: qsTr("De una imagen a tu música"); color: Theme.fg; font.pixelSize: 22; font.bold: true }
                Text {
                    text: qsTr("Recorta lo que te interesa, revisa los nombres y elige las coincidencias.")
                    color: Theme.muted
                    font.pixelSize: 12
                }
            }
            Item { Layout.fillWidth: true }
            PillButton {
                variant: "ghost"
                iconName: "close"
                implicitWidth: 40
                sidePadding: 0
                onClicked: dialog.close()
            }
        }

        TabStrip {
            Layout.fillWidth: true
            equalWidth: true
            tabs: [
                { key: "images", text: qsTr("1 · Imágenes y texto") },
                { key: "table", text: qsTr("2 · Tabla de canciones") },
                { key: "matches", text: qsTr("3 · Coincidencias") },
                { key: "report", text: qsTr("4 · Informe y pendientes") }
            ]
            current: dialog.step
            onPicked: (key) => dialog.step = key
        }

        Card {
            Layout.fillWidth: true
            Layout.fillHeight: true
            StackLayout {
                anchors.fill: parent
                anchors.margins: 16
                currentIndex: dialog.steps.indexOf(dialog.step)
                ImporterImagesStep { onRequestStep: (key) => dialog.step = key }
                ImporterTableStep { onRequestStep: (key) => dialog.step = key }
                ImporterMatchesStep { onRequestStep: (key) => dialog.step = key }
                ImporterReportStep { onRequestStep: (key) => dialog.step = key }
            }
        }

        // ---- estado, progreso y acciones ----
        Text {
            Layout.fillWidth: true
            text: dialog.imp.status
            color: dialog.imp.statusError ? Theme.danger : Theme.muted
            font.pixelSize: 12
            wrapMode: Text.WordWrap
            maximumLineCount: 3
            elide: Text.ElideRight
        }
        SmoothProgress {
            Layout.fillWidth: true
            height: 6
            indeterminate: dialog.imp.busy && dialog.imp.progress < 0
            value: dialog.imp.progress < 0 ? 0 : dialog.imp.progress
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            PillButton { text: qsTr("Cerrar"); onClicked: dialog.close() }
            PillButton {
                text: dialog.imp.cancelling ? qsTr("Cancelando…") : qsTr("Cancelar operación")
                variant: "danger"
                visible: dialog.imp.busy
                enabled: !dialog.imp.cancelling
                onClicked: dialog.imp.cancelJob()
            }
            Item { Layout.fillWidth: true }
            Text { text: dialog.imp.choiceText; color: Theme.muted; font.pixelSize: 12 }
            PillButton {
                text: qsTr("Añadir elegidas a descargas")
                variant: "primary"
                iconName: "plus"
                enabled: dialog.imp.choiceCount > 0 && !dialog.imp.busy
                onClicked: dialog.imp.addSelected()
            }
        }
    }

    // ---- aviso cuando varias elecciones comparten el mismo video ----
    MbDialog {
        id: duplicatesDialog
        width: Math.min(560, Overlay.overlay ? Overlay.overlay.width - 80 : 560)
        height: dupColumn.implicitHeight + 48
        ColumnLayout {
            id: dupColumn
            anchors.fill: parent
            anchors.margins: 24
            spacing: 14
            RowLayout {
                spacing: 10
                Icon { name: "warning"; size: 20; tint: Theme.warning }
                Text { text: qsTr("Hay elecciones con el mismo enlace"); color: Theme.fg; font.pixelSize: 17; font.bold: true }
            }
            ScrollView {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(260, dupText.implicitHeight + 8)
                clip: true
                Text {
                    id: dupText
                    width: parent.width
                    text: dialog.duplicatesText
                    color: Theme.muted
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("No, revisar"); onClicked: duplicatesDialog.close() }
                PillButton {
                    text: qsTr("Sí, añadir")
                    variant: "primary"
                    onClicked: { duplicatesDialog.close(); dialog.imp.confirmAdd() }
                }
            }
        }
    }
}
