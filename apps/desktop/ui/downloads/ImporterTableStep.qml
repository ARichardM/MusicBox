import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Paso 2: tabla editable de canciones. Se marcan las que se quieren buscar y se corrigen artista y título.
Item {
    id: step
    readonly property var imp: downloads.importer
    readonly property bool idle: !imp.busy
    property var selected: ({})          // id de fila -> true
    property int anchorRow: -1
    property int pendingEdit: -1
    signal requestStep(string key)

    function selectedIds() {
        var ids = []
        for (var key in selected) if (selected[key]) ids.push(parseInt(key))
        return ids
    }
    function select(row, id, modifiers) {
        var next = {}
        if (modifiers & Qt.ShiftModifier && anchorRow >= 0) {
            var from = Math.min(anchorRow, row), to = Math.max(anchorRow, row)
            for (var i = from; i <= to; i++) next[list.itemAtIndexId(i)] = true
        } else if (modifiers & Qt.ControlModifier) {
            next = Object.assign({}, selected)
            next[id] = !next[id]
            anchorRow = row
        } else {
            next[id] = true
            anchorRow = row
        }
        selected = next
    }
    function removeSelected() {
        var ids = selectedIds()
        if (ids.length > 0 && idle) { imp.removeRows(ids); selected = ({}) }
    }

    Keys.onDeletePressed: removeSelected()
    Keys.onSpacePressed: {
        if (!idle) return
        var ids = selectedIds()
        for (var i = 0; i < ids.length; i++) imp.toggleRow(ids[i])
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        // ---- fuente: playlist original ----
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Text { text: qsTr("Playlist original (opcional):"); color: Theme.muted; font.pixelSize: 12 }
            Field {
                Layout.fillWidth: true
                implicitHeight: 38
                iconName: "link"
                enabled: step.idle
                text: step.imp.playlistInput
                placeholderText: qsTr("Enlace de la playlist de YouTube")
                onTextEdited: step.imp.setPlaylistInput(text)
            }
            PillButton {
                text: qsTr("Comprobar")
                enabled: step.idle
                busy: step.imp.busy && step.imp.task === "playlist"
                onClicked: step.imp.checkPlaylist()
            }
        }

        // ---- barra de la tabla ----
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Text { text: step.imp.songCountText; color: Theme.muted; font.pixelSize: 12; Layout.fillWidth: true }
            PillButton { text: qsTr("Eliminar filas"); iconName: "trash"; implicitHeight: 34; enabled: step.idle && step.selectedIds().length > 0; onClicked: step.removeSelected() }
            PillButton {
                text: qsTr("Añadir fila"); iconName: "plus"; implicitHeight: 34; enabled: step.idle
                onClicked: { var id = step.imp.addRow(); if (id >= 0) { step.pendingEdit = id; list.positionViewAtEnd() } }
            }
            PillButton { text: qsTr("Desmarcar"); variant: "ghost"; implicitHeight: 34; enabled: step.idle; onClicked: step.imp.checkAll(false) }
            PillButton { text: qsTr("Marcar todas"); variant: "ghost"; implicitHeight: 34; enabled: step.idle; onClicked: step.imp.checkAll(true) }
        }
        Text {
            Layout.fillWidth: true
            text: qsTr("Marca las canciones que quieres buscar. Doble clic en artista o título para corregirlos.")
            color: Theme.muted
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }
        Text {
            Layout.fillWidth: true
            text: qsTr("En videos se muestra el canal si el intérprete no está indicado. El canal no se usa como artista al buscar.")
            color: Theme.muted
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Field {
                Layout.fillWidth: true
                implicitHeight: 38
                iconName: "filter"
                text: step.imp.songFilter
                placeholderText: qsTr("Buscar en la lista")
                onTextEdited: step.imp.setSongFilter(text)
            }
            PillButton {
                text: qsTr("Limpiar")
                variant: "ghost"
                implicitHeight: 36
                enabled: step.imp.songFilter !== ""
                onClicked: step.imp.setSongFilter("")
            }
        }

        // ---- tabla ----
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            radius: 18
            color: Theme.field
            border.width: 1
            border.color: Theme.line
            clip: true

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                // cabecera ordenable
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 36
                    color: Theme.dark ? "#18191C" : "#EDF0F5"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        spacing: 10
                        Check { Layout.preferredWidth: 22; boxSize: 18; marked: step.imp.allChecked; enabled: step.idle; onClicked: step.imp.toggleAll() }
                        Text { Layout.preferredWidth: 28; text: "#"; color: Theme.muted; font.pixelSize: 11; font.bold: true }
                        Repeater {
                            model: [
                                { key: "artist", text: qsTr("ARTISTA / CANAL"), width: 190, fill: true },
                                { key: "title", text: qsTr("CANCIÓN / TÍTULO"), width: 280, fill: true },
                                { key: "duration", text: qsTr("DURACIÓN"), width: 72, fill: false },
                                { key: "source", text: qsTr("ORIGEN"), width: 110, fill: false },
                                { key: "state", text: qsTr("ESTADO"), width: 118, fill: false }
                            ]
                            delegate: Text {
                                Layout.fillWidth: modelData.fill
                                Layout.preferredWidth: modelData.width
                                text: modelData.text
                                color: sortHover.containsMouse ? Theme.fg : Theme.muted
                                font.pixelSize: 11
                                font.bold: true
                                elide: Text.ElideRight
                                MouseArea { id: sortHover; anchors.fill: parent; hoverEnabled: true; enabled: step.idle; cursorShape: Qt.PointingHandCursor; onClicked: step.imp.sortBy(modelData.key) }
                            }
                        }
                    }
                }

                ListView {
                    id: list
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    model: step.imp.songModel
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                    function itemAtIndexId(i) {
                        var item = list.itemAtIndex(i)
                        return item ? item.rowId : -1
                    }

                    delegate: Rectangle {
                        id: row
                        readonly property int rowIndex: index
                        readonly property int rowId: model.id
                        property string editing: ""
                        width: ListView.view.width
                        height: 40
                        color: step.selected[model.id] ? Theme.accentSoft : (rowHover.containsMouse ? Theme.cardHover : "transparent")
                        Behavior on color { ColorAnimation { duration: 100 } }
                        Component.onCompleted: if (step.pendingEdit === model.id) { editing = "title"; step.pendingEdit = -1 }

                        MouseArea {
                            id: rowHover
                            anchors.fill: parent
                            hoverEnabled: true
                            onClicked: (mouse) => { step.forceActiveFocus(); step.select(row.rowIndex, model.id, mouse.modifiers) }
                        }
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 14
                            anchors.rightMargin: 14
                            spacing: 10
                            Check { Layout.preferredWidth: 22; boxSize: 18; marked: model.checked; enabled: step.idle; onClicked: step.imp.toggleRow(model.id) }
                            Text { Layout.preferredWidth: 28; text: model.number; color: Theme.muted; font.pixelSize: 11 }

                            // artista
                            Item {
                                Layout.fillWidth: true; Layout.preferredWidth: 190; Layout.fillHeight: true
                                Text {
                                    anchors.fill: parent
                                    verticalAlignment: Text.AlignVCenter
                                    visible: row.editing !== "artist"
                                    text: model.artist
                                    color: model.rawArtist === "" ? Theme.muted : Theme.fg
                                    font.pixelSize: 12
                                    elide: Text.ElideRight
                                }
                                MouseArea { anchors.fill: parent; enabled: step.idle; onDoubleClicked: row.editing = "artist" }
                                Field {
                                    id: artistEditor
                                    anchors.fill: parent
                                    anchors.topMargin: 4; anchors.bottomMargin: 4
                                    visible: row.editing === "artist"
                                    implicitHeight: 32
                                    text: model.rawArtist
                                    onVisibleChanged: if (visible) { forceActiveFocus(); selectAll() }
                                    onAccepted: { row.editing = ""; step.imp.editField(model.id, "artist", text) }
                                    Keys.onEscapePressed: row.editing = ""
                                    onActiveFocusChanged: if (!activeFocus && row.editing === "artist") { row.editing = ""; step.imp.editField(model.id, "artist", text) }
                                }
                            }
                            // título
                            Item {
                                Layout.fillWidth: true; Layout.preferredWidth: 280; Layout.fillHeight: true
                                Text {
                                    anchors.fill: parent
                                    verticalAlignment: Text.AlignVCenter
                                    visible: row.editing !== "title"
                                    text: model.title === "" ? qsTr("(sin título)") : model.title
                                    color: model.title === "" ? Theme.warning : Theme.fg
                                    font.pixelSize: 12
                                    elide: Text.ElideRight
                                }
                                MouseArea { anchors.fill: parent; enabled: step.idle; onDoubleClicked: row.editing = "title" }
                                Field {
                                    id: titleEditor
                                    anchors.fill: parent
                                    anchors.topMargin: 4; anchors.bottomMargin: 4
                                    visible: row.editing === "title"
                                    implicitHeight: 32
                                    text: model.title
                                    onVisibleChanged: if (visible) { forceActiveFocus(); selectAll() }
                                    onAccepted: { row.editing = ""; step.imp.editField(model.id, "title", text) }
                                    Keys.onEscapePressed: row.editing = ""
                                    onActiveFocusChanged: if (!activeFocus && row.editing === "title") { row.editing = ""; step.imp.editField(model.id, "title", text) }
                                }
                            }
                            Text { Layout.preferredWidth: 72; text: model.duration; color: Theme.muted; font.pixelSize: 12 }
                            Text { Layout.preferredWidth: 110; text: qsTr(model.source); color: Theme.muted; font.pixelSize: 12; elide: Text.ElideRight }
                            Text {
                                Layout.preferredWidth: 118
                                text: qsTr(model.state)
                                font.pixelSize: 12
                                font.bold: model.tag !== ""
                                elide: Text.ElideRight
                                color: model.tag === "chosen" ? Theme.success : model.tag === "error" ? Theme.danger
                                     : model.tag === "review" ? Theme.warning : Theme.muted
                            }
                        }
                    }
                }
            }

            EmptyState {
                anchors.centerIn: parent
                anchors.verticalCenterOffset: 18
                visible: list.count === 0
                iconName: "queue"
                title: step.imp.songFilter !== "" ? qsTr("Ninguna canción coincide con el filtro.") : qsTr("Todavía no hay canciones.")
                detail: step.imp.songFilter !== "" ? "" : qsTr("Escribe o pega nombres en el paso 1, o añade filas aquí.")
            }
        }

        Text {
            Layout.fillWidth: true
            text: qsTr("Hasta %1 consultas por búsqueda. El filtro no cambia las canciones marcadas.").arg(step.imp.maxQueries)
            color: Theme.muted
            font.pixelSize: 11
        }
        RowLayout {
            Layout.fillWidth: true
            PillButton { text: qsTr("← Ver imágenes y texto"); variant: "link"; onClicked: step.requestStep("images") }
            Item { Layout.fillWidth: true }
            PillButton {
                text: qsTr("Buscar canciones marcadas →")
                variant: "primary"
                enabled: step.idle
                busy: step.imp.busy && step.imp.task === "search"
                busyProgress: step.imp.progress
                onClicked: step.imp.startSearch()
            }
        }
    }
}
