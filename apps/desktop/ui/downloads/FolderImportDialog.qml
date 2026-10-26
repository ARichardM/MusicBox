import QtQuick
import QtQuick.Controls
import QtQuick.Effects
import QtQuick.Layouts
import "../components"

// De una carpeta a tus playlists: reconoce cada archivo en YouTube, lo vincula y lo agrega a una playlist.
MbDialog {
    id: dialog
    readonly property var imp: downloads.folderImport
    readonly property bool picking: imp.phase === "pick" || imp.phase === "scanning"
    readonly property bool reviewing: imp.phase === "searching" || imp.phase === "review"
    readonly property var playlistNames: {
        var names = []
        for (var i = 0; i < musicbox.playlists.length; i++) names.push(musicbox.playlists[i].name)
        return names
    }
    property bool creating: false
    width: Math.min(1040, Overlay.overlay ? Overlay.overlay.width - 60 : 1040)
    height: Math.min(820, Overlay.overlay ? Overlay.overlay.height - 60 : 820)

    onClosed: { imp.reset(); creating = false; newName.clear(); downloads.preview.stop() }

    Connections {
        target: dialog.imp
        function onFinished(count, playlist) { dialog.close() }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 22
        spacing: 12

        // ---- encabezado ----
        RowLayout {
            Layout.fillWidth: true
            ColumnLayout {
                spacing: 3
                Text { text: qsTr("Importar una carpeta"); color: Theme.fg; font.pixelSize: 22; font.bold: true }
                Text {
                    text: qsTr("Convierte tus archivos en canciones de MusicBox y agrégalos a una playlist.")
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

        // ---- estado ----
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Spinner { visible: dialog.imp.busy; size: 22; progress: dialog.imp.progress }
            Text {
                Layout.fillWidth: true
                text: dialog.imp.status
                color: dialog.imp.statusError ? Theme.danger : Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
            }
        }
        SmoothProgress {
            Layout.fillWidth: true
            height: 5
            visible: dialog.imp.busy
            indeterminate: dialog.imp.progress < 0
            value: Math.max(0, dialog.imp.progress)
        }

        // ================= 1. elegir carpeta =================
        Card {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: dialog.picking || dialog.imp.phase === "ready"
            radius: 22

            ColumnLayout {
                anchors.centerIn: parent
                width: Math.min(parent.width - 60, 520)
                spacing: 16

                Rectangle {
                    Layout.alignment: Qt.AlignHCenter
                    width: 84; height: 84; radius: 26
                    color: Theme.accentSoft
                    Icon { anchors.centerIn: parent; name: "folder"; size: 38; tint: Theme.accent }
                }
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: dialog.imp.folder === "" ? qsTr("Elige una carpeta con tu música") : dialog.imp.folderName
                    color: Theme.fg; font.pixelSize: 18; font.bold: true
                    elide: Text.ElideMiddle
                }
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                    text: dialog.imp.phase === "ready"
                          ? qsTr("%1 canciones listas para reconocer").arg(dialog.imp.total)
                            + (dialog.imp.truncated ? "  ·  " + qsTr("se importan las primeras 500") : "")
                          : qsTr("MusicBox lee las etiquetas de cada archivo y busca la canción en YouTube para unirla con tu archivo.")
                    color: Theme.muted; font.pixelSize: 12
                }
                Check {
                    Layout.alignment: Qt.AlignHCenter
                    text: qsTr("Incluir subcarpetas")
                    marked: dialog.imp.recursive
                    enabled: dialog.imp.phase !== "scanning"
                    onClicked: dialog.imp.setRecursive(!dialog.imp.recursive)
                }
                RowLayout {
                    Layout.alignment: Qt.AlignHCenter
                    spacing: 10
                    PillButton {
                        text: dialog.imp.folder === "" ? qsTr("Elegir carpeta…") : qsTr("Cambiar carpeta")
                        iconName: "folder"
                        variant: dialog.imp.phase === "ready" ? "secondary" : "primary"
                        busy: dialog.imp.phase === "scanning"
                        enabled: dialog.imp.phase !== "scanning"
                        onClicked: {
                            var chosen = dialog.imp.chooseFolder()
                            if (chosen !== "") dialog.imp.scan(chosen)
                        }
                    }
                    PillButton {
                        visible: dialog.imp.phase === "ready"
                        text: qsTr("Buscar en YouTube")
                        iconName: "search"
                        variant: "primary"
                        onClicked: dialog.imp.startSearch()
                    }
                    PillButton {
                        visible: dialog.imp.phase === "scanning"
                        text: qsTr("Cancelar")
                        variant: "danger"
                        onClicked: dialog.imp.cancel()
                    }
                }
            }
        }

        // ================= 2. revisar =================
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: dialog.reviewing
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Repeater {
                    model: [
                        { key: "all", label: qsTr("Todas"), count: dialog.imp.total },
                        { key: "auto", label: qsTr("Reconocidas"), count: dialog.imp.autoCount },
                        { key: "review", label: qsTr("Revisar"), count: dialog.imp.reviewCount },
                        { key: "none", label: qsTr("Sin coincidencia"), count: dialog.imp.noneCount }
                    ]
                    delegate: Rectangle {
                        readonly property bool active: dialog.imp.filter === modelData.key
                        implicitWidth: chipText.implicitWidth + 28
                        implicitHeight: 34
                        radius: 17
                        color: active ? Theme.accent : (chipArea.containsMouse ? Theme.cardHover : Theme.card)
                        border.width: active ? 0 : 1
                        border.color: Theme.line
                        Behavior on color { ColorAnimation { duration: 120 } }
                        Text {
                            id: chipText
                            anchors.centerIn: parent
                            text: modelData.label + "  " + modelData.count
                            color: parent.active ? "white" : Theme.muted
                            font.pixelSize: 12
                            font.bold: parent.active
                        }
                        MouseArea {
                            id: chipArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: dialog.imp.setFilter(modelData.key)
                        }
                    }
                }
                Item { Layout.fillWidth: true }
                PillButton {
                    text: qsTr("Marcar visibles")
                    enabled: dialog.imp.phase === "review"
                    onClicked: dialog.imp.includeAll(true)
                }
                PillButton {
                    text: qsTr("Quitar marcas")
                    enabled: dialog.imp.phase === "review"
                    onClicked: dialog.imp.includeAll(false)
                }
                PillButton {
                    visible: dialog.imp.phase === "searching"
                    text: qsTr("Cancelar")
                    variant: "danger"
                    onClicked: dialog.imp.cancel()
                }
            }

            ListView {
                id: list
                Layout.fillWidth: true
                Layout.fillHeight: true
                model: dialog.imp.rows
                spacing: 8
                clip: true
                rightMargin: 8
                cacheBuffer: 800
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                add: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 180 } }

                delegate: Card {
                    id: card
                    property bool open: false
                    readonly property string rowId: model.id
                    readonly property int chosen: model.selected
                    readonly property var options: model.candidates
                    width: ListView.view.width - 8
                    height: open ? 92 + options.length * 52 : 84
                    radius: 18
                    clip: true
                    Behavior on height { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 10
                        spacing: 8

                        RowLayout {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 64
                            spacing: 12

                            Check {
                                marked: model.include
                                enabled: model.selected >= 0 && dialog.imp.phase === "review"
                                onClicked: dialog.imp.toggle(model.id)
                            }
                            Item {
                                Layout.preferredWidth: 64
                                Layout.preferredHeight: 64
                                Rectangle { id: mask; anchors.fill: parent; radius: 12; visible: false; layer.enabled: true }
                                Rectangle { anchors.fill: parent; radius: 12; color: Theme.chip }
                                Shimmer {
                                    anchors.fill: parent
                                    cornerRadius: 12
                                    visible: model.state === "searching"
                                }
                                Image {
                                    anchors.fill: parent
                                    source: model.thumbnail
                                    asynchronous: true
                                    fillMode: Image.PreserveAspectCrop
                                    opacity: status === Image.Ready ? 1 : 0
                                    Behavior on opacity { NumberAnimation { duration: 220 } }
                                    layer.enabled: true
                                    layer.effect: MultiEffect { maskEnabled: true; maskSource: mask }
                                }
                                Icon {
                                    anchors.centerIn: parent
                                    visible: model.thumbnail === "" && model.state !== "searching"
                                    name: "music"; size: 22; tint: Theme.muted
                                }
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 3
                                Text {
                                    Layout.fillWidth: true
                                    text: model.chosenTitle !== "" ? model.chosenTitle : model.title
                                    color: Theme.fg; font.pixelSize: 13; font.bold: true
                                    elide: Text.ElideRight
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: qsTr("Tu archivo: ") + model.fileName
                                    color: Theme.muted; font.pixelSize: 11
                                    elide: Text.ElideMiddle
                                }
                                Text {
                                    Layout.fillWidth: true
                                    visible: model.reason !== "" || model.chosenChannel !== ""
                                    text: model.reason !== "" ? model.reason : model.chosenChannel
                                    color: model.reason !== "" ? Theme.warning : Theme.muted
                                    font.pixelSize: 11
                                    elide: Text.ElideRight
                                }
                            }
                            PreviewBar {
                                visible: card.chosen >= 0 && card.chosen < card.options.length
                                compact: true
                                Layout.preferredWidth: active ? 250 : 32
                                Behavior on Layout.preferredWidth { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                                key: "fi:" + card.rowId + ":" + card.chosen
                                url: visible ? card.options[card.chosen].url : ""
                                title: visible ? card.options[card.chosen].title : ""
                            }
                            Badge {
                                text: model.label
                                tone: model.state === "auto" ? "success" : model.state === "review" ? "warning"
                                      : model.state === "none" ? "danger" : "neutral"
                            }
                            PillButton {
                                visible: card.options.length > 1
                                variant: "ghost"
                                text: card.open ? qsTr("Ocultar") : qsTr("Alternativas")
                                enabled: dialog.imp.phase === "review"
                                onClicked: card.open = !card.open
                            }
                        }

                        Repeater {
                            model: card.open ? card.options : []
                            delegate: Rectangle {
                                id: option
                                Layout.fillWidth: true
                                Layout.preferredHeight: 46
                                radius: 12
                                color: pick.containsMouse ? Theme.cardHover : "transparent"
                                border.width: 1
                                border.color: index === card.chosen ? Theme.accent : Theme.line
                                Behavior on color { ColorAnimation { duration: 120 } }
                                MouseArea {
                                    id: pick
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: dialog.imp.choose(card.rowId, index)
                                }
                                RowLayout {
                                    anchors.fill: parent
                                    anchors.leftMargin: 8
                                    anchors.rightMargin: 12
                                    spacing: 10
                                    PreviewBar {
                                        compact: true
                                        Layout.preferredWidth: active ? 250 : 32
                                        Behavior on Layout.preferredWidth { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                                        key: "fi:" + card.rowId + ":" + index
                                        url: modelData.url
                                        title: modelData.title
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.title
                                        color: Theme.fg; font.pixelSize: 12
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        text: modelData.channel + "  ·  " + modelData.durationText
                                        color: Theme.muted; font.pixelSize: 11
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- destino ----
            Card {
                Layout.fillWidth: true
                implicitHeight: footer.implicitHeight + 24
                radius: 20
                ColumnLayout {
                    id: footer
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 10
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Icon { name: "playlist"; size: 18; tint: Theme.muted }
                        Text { text: qsTr("Playlist de destino"); color: Theme.fg; font.pixelSize: 13; font.bold: true }
                        Combo {
                            id: target
                            Layout.preferredWidth: 260
                            enabled: !dialog.creating && dialog.imp.phase === "review"
                            model: dialog.playlistNames
                        }
                        PillButton {
                            text: dialog.creating ? qsTr("Usar existente") : qsTr("Nueva playlist")
                            iconName: dialog.creating ? "" : "plus"
                            onClicked: { dialog.creating = !dialog.creating; if (dialog.creating) newName.forceActiveFocus() }
                        }
                        Field {
                            id: newName
                            visible: dialog.creating
                            Layout.fillWidth: true
                            placeholderText: dialog.imp.folderName
                        }
                        Item { visible: !dialog.creating; Layout.fillWidth: true }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Check {
                            id: addFolder
                            visible: !dialog.imp.inLibrary
                            marked: true
                            text: qsTr("Agregar esta carpeta a mi biblioteca (necesario para reproducir los archivos)")
                            onClicked: marked = !marked
                        }
                        Item { Layout.fillWidth: true }
                        PillButton {
                            variant: "primary"
                            iconName: "check"
                            text: qsTr("Agregar %1 canciones").arg(dialog.imp.includedCount)
                            enabled: dialog.imp.phase === "review" && dialog.imp.includedCount > 0
                                     && (dialog.creating ? newName.text.trim().length > 0 : target.currentIndex >= 0)
                            onClicked: dialog.imp.apply(dialog.creating ? newName.text.trim() : target.currentText,
                                                        addFolder.visible && addFolder.marked)
                        }
                    }
                }
            }
        }
    }
}
