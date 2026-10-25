import QtQuick
import QtQuick.Controls
import QtQuick.Effects
import QtQuick.Layouts
import "../components"

// Búsqueda visual de YouTube: portadas, escucha rápida y selección para añadir a la cola.
MbDialog {
    id: dialog
    readonly property var search: downloads.videoSearch
    readonly property var preview: downloads.preview
    width: Math.min(980, Overlay.overlay ? Overlay.overlay.width - 60 : 980)
    height: Math.min(780, Overlay.overlay ? Overlay.overlay.height - 60 : 780)

    function openWith(query) {
        queryField.text = query
        open()
        if (query.trim().length > 0) Qt.callLater(function () { search.search(queryField.text, downloads.cookiePath) })
    }

    onOpened: queryField.forceActiveFocus()
    onClosed: { search.reset(); preview.stop() }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 22
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            ColumnLayout {
                spacing: 3
                Text { text: qsTr("Encuentra tu próximo audio"); color: Theme.fg; font.pixelSize: 22; font.bold: true }
                Text {
                    text: qsTr("Mira la portada, escucha en el reproductor del programa y elige qué añadir a descargas.")
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

        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Field {
                id: queryField
                Layout.fillWidth: true
                iconName: "search"
                placeholderText: qsTr("Título o artista")
                enabled: !dialog.search.busy
                onAccepted: dialog.search.search(text, downloads.cookiePath)
            }
            PillButton {
                text: qsTr("Buscar")
                variant: "primary"
                iconName: "search"
                busy: dialog.search.busy && !dialog.search.cancelling
                enabled: !dialog.search.busy
                onClicked: dialog.search.search(queryField.text, downloads.cookiePath)
            }
            PillButton {
                visible: dialog.search.busy
                text: dialog.search.cancelling ? qsTr("Cancelando…") : qsTr("Cancelar")
                variant: "danger"
                enabled: !dialog.search.cancelling
                onClicked: dialog.search.cancel()
            }
        }

        Text {
            Layout.fillWidth: true
            text: dialog.search.status
            color: dialog.search.statusError ? Theme.danger : Theme.muted
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }
        Text {
            Layout.fillWidth: true
            text: dialog.preview.statusText !== "" ? dialog.preview.statusText
                  : qsTr("Escucha integrada: pulsa «Escuchar aquí» en un resultado.")
            color: dialog.preview.state === "error" ? Theme.danger
                 : dialog.preview.state === "playing" ? Theme.success : Theme.muted
            font.pixelSize: 12
            elide: Text.ElideRight
        }
        SmoothProgress {
            Layout.fillWidth: true
            height: 5
            visible: dialog.search.busy
            indeterminate: true
        }

        // ---- resultados ----
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            ListView {
                id: list
                anchors.fill: parent
                model: dialog.search.results
                spacing: 10
                clip: true
                rightMargin: 8
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                add: Transition {
                    NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 220 }
                }
                delegate: Card {
                    id: card
                    readonly property string previewKey: "v:" + modelData.url
                    width: ListView.view.width - 8
                    height: 118
                    radius: 18

                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 12
                        spacing: 14

                        // portada con esquinas redondeadas y marcador animado mientras carga
                        Item {
                            Layout.preferredWidth: 160
                            Layout.preferredHeight: 90
                            Rectangle { id: coverMask; anchors.fill: parent; radius: 12; visible: false; layer.enabled: true }
                            Shimmer {
                                anchors.fill: parent
                                cornerRadius: 12
                                visible: cover.status === Image.Loading || cover.status === Image.Null
                            }
                            Image {
                                id: cover
                                anchors.fill: parent
                                source: modelData.thumbnail
                                asynchronous: true
                                fillMode: Image.PreserveAspectCrop
                                opacity: status === Image.Ready ? 1 : 0
                                Behavior on opacity { NumberAnimation { duration: 240 } }
                                layer.enabled: true
                                layer.effect: MultiEffect { maskEnabled: true; maskSource: coverMask }
                            }
                            Text {
                                visible: cover.status === Image.Error
                                anchors.centerIn: parent
                                text: qsTr("Portada no disponible")
                                color: Theme.muted
                                font.pixelSize: 11
                            }
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Text {
                                Layout.fillWidth: true
                                text: modelData.title
                                color: Theme.fg
                                font.pixelSize: 13
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                            }
                            Text {
                                text: modelData.channel + "  ·  " + modelData.durationText
                                color: Theme.muted
                                font.pixelSize: 12
                            }
                            Item { Layout.fillHeight: true }
                            PreviewBar {
                                Layout.fillWidth: true
                                compact: true
                                visible: dialog.preview.activeKey === card.previewKey
                                key: card.previewKey
                                url: modelData.url
                                title: modelData.title
                            }
                            Check {
                                visible: dialog.preview.activeKey !== card.previewKey
                                text: qsTr("Elegir para descargar")
                                marked: modelData.selected
                                onClicked: dialog.search.setSelected(index, !modelData.selected)
                            }
                        }

                        ColumnLayout {
                            spacing: 6
                            PillButton {
                                Layout.alignment: Qt.AlignRight
                                text: dialog.preview.activeKey === card.previewKey && dialog.preview.state === "playing"
                                      ? qsTr("Pausar")
                                      : dialog.preview.activeKey === card.previewKey && dialog.preview.state === "paused"
                                        ? qsTr("Reanudar")
                                        : dialog.preview.activeKey === card.previewKey && dialog.preview.state === "loading"
                                          ? qsTr("Abriendo…") : qsTr("Escuchar aquí")
                                iconName: "play"
                                variant: "secondary"
                                busy: dialog.preview.activeKey === card.previewKey && dialog.preview.state === "loading"
                                onClicked: {
                                    var playing = dialog.preview.activeKey === card.previewKey
                                                  && (dialog.preview.state === "playing" || dialog.preview.state === "paused")
                                    if (playing) dialog.preview.toggle(card.previewKey)
                                    else dialog.preview.play(card.previewKey, modelData.url, modelData.title)
                                }
                            }
                            PillButton {
                                Layout.alignment: Qt.AlignRight
                                text: qsTr("Abrir YouTube ↗")
                                variant: "link"
                                onClicked: dialog.search.openYoutube(index)
                            }
                            Check {
                                Layout.alignment: Qt.AlignRight
                                visible: dialog.preview.activeKey === card.previewKey
                                text: qsTr("Elegir")
                                marked: modelData.selected
                                onClicked: dialog.search.setSelected(index, !modelData.selected)
                            }
                        }
                    }
                }
            }

            Spinner {
                anchors.centerIn: parent
                size: 46
                visible: dialog.search.busy && list.count === 0
            }
            EmptyState {
                anchors.centerIn: parent
                visible: !dialog.search.busy && list.count === 0
                iconName: "search"
                title: qsTr("Los resultados aparecerán aquí, con su miniatura.")
                detail: ""
            }
        }

        // ---- pie ----
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            PillButton { text: qsTr("Cerrar"); onClicked: dialog.close() }
            PillButton {
                text: qsTr("Marcar todos")
                variant: "ghost"
                enabled: list.count > 0 && !dialog.search.busy
                onClicked: dialog.search.checkAll(true)
            }
            PillButton {
                text: qsTr("Desmarcar")
                variant: "ghost"
                enabled: list.count > 0 && !dialog.search.busy
                onClicked: dialog.search.checkAll(false)
            }
            Item { Layout.fillWidth: true }
            Text {
                text: dialog.search.selectedCount === 1 ? qsTr("1 elegido")
                      : qsTr("%1 elegidos").arg(dialog.search.selectedCount)
                color: Theme.muted
                font.pixelSize: 12
            }
            PillButton {
                text: qsTr("Añadir elegidos a la cola")
                variant: "primary"
                iconName: "plus"
                enabled: dialog.search.selectedCount > 0 && !dialog.search.busy
                onClicked: if (dialog.search.addSelected()) dialog.close()
            }
        }
    }
}
