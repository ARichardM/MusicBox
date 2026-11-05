import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// «Ver alternativas»: otras versiones de una canción. Cada una se escucha sin tocar la cola y puede reemplazar a la actual.
MbDialog {
    id: dialog
    objectName: "alternativesDialog"
    readonly property var alts: musicbox.alternatives
    readonly property var current: alts.track
    property var pending: null
    signal saveRequested(var track)

    width: 540
    padding: 22

    function openFor(track) {
        alts.load(track)
        dialog.open()
    }
    onClosed: {
        downloads.preview.stop()
        alts.reset()
    }

    // Aviso de confirmación antes de reemplazar.
    MbDialog {
        id: confirm
        width: 400
        padding: 22
        contentItem: ColumnLayout {
            spacing: 14
            Label {
                text: qsTr("¿Reemplazar la canción?")
                color: Theme.fg
                font.pixelSize: 18
                font.bold: true
            }
            Label {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                color: Theme.muted
                font.pixelSize: 13
                text: qsTr("«%1» se cambiará por esta versión en tus playlists y en la cola. No se borra ningún archivo.")
                      .arg(dialog.current.title || "")
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cancelar"); variant: "ghost"; onClicked: confirm.close() }
                PillButton {
                    objectName: "alternativesConfirm"
                    text: qsTr("Reemplazar")
                    variant: "primary"
                    onClicked: {
                        downloads.preview.stop()
                        dialog.alts.replaceWith(dialog.pending)
                        confirm.close()
                    }
                }
            }
        }
    }

    contentItem: ColumnLayout {
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            Label {
                text: qsTr("Alternativas")
                color: Theme.fg
                font.pixelSize: 19
                font.bold: true
            }
            Item { Layout.fillWidth: true }
            PillButton {
                variant: "ghost"
                iconName: "close"
                implicitWidth: 34
                implicitHeight: 34
                sidePadding: 8
                onClicked: dialog.close()
            }
        }

        // La canción actual, fija, para comparar.
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 54
            radius: 14
            color: Theme.card
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 10
                anchors.rightMargin: 14
                spacing: 12
                Rectangle {
                    Layout.preferredWidth: 38
                    Layout.preferredHeight: 38
                    radius: 9
                    color: Theme.chip
                    clip: true
                    Image {
                        anchors.fill: parent
                        source: dialog.current.thumbnail || ""
                        fillMode: Image.PreserveAspectCrop
                        asynchronous: true
                    }
                    Icon { anchors.centerIn: parent; visible: !dialog.current.thumbnail; name: "music"; size: 18; tint: Theme.muted }
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0
                    Label {
                        Layout.fillWidth: true
                        text: dialog.current.title || ""
                        color: Theme.fg
                        font.bold: true
                        font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    Label {
                        Layout.fillWidth: true
                        text: dialog.current.artist || dialog.current.subtitle || ""
                        color: Theme.muted
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }
                }
                Label { text: qsTr("Actual"); color: Theme.accent; font.pixelSize: 12; font.bold: true }
            }
        }

        // Buscando.
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8
            visible: dialog.alts.state === "loading"
            Repeater {
                model: 5
                delegate: Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 62
                    radius: 14
                    color: Theme.card
                    Shimmer { x: 10; y: 9; width: 44; height: 44; cornerRadius: 10 }
                    Shimmer { x: 66; y: 15; width: Math.max(140, parent.width * 0.45); height: 14; cornerRadius: 7 }
                    Shimmer { x: 66; y: 38; width: Math.max(90, parent.width * 0.25); height: 10; cornerRadius: 5 }
                }
            }
        }

        // Sin resultados o con error.
        ColumnLayout {
            Layout.fillWidth: true
            Layout.topMargin: 14
            Layout.bottomMargin: 14
            spacing: 10
            visible: dialog.alts.state === "empty" || dialog.alts.state === "error"
            Label {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                color: Theme.muted
                text: dialog.alts.state === "error" ? qsTr("No se pudieron cargar las alternativas.") : qsTr("No encontramos otras versiones de esta canción.")
            }
            PillButton {
                Layout.alignment: Qt.AlignHCenter
                visible: dialog.alts.state === "error"
                text: qsTr("Reintentar")
                onClicked: dialog.alts.load(dialog.current)
            }
        }

        // Resultados.
        ListView {
            id: list
            objectName: "alternativesList"
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(contentHeight, 380)
            visible: dialog.alts.state === "ready"
            model: dialog.alts.results
            spacing: 8
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            // Las filas entran con un deslizamiento corto escalonado.
            add: Transition { NumberAnimation { properties: "opacity"; from: 0; to: 1; duration: 220 } }

            delegate: Rectangle {
                id: row
                required property int index
                required property var modelData
                readonly property bool done: dialog.alts.replacedIds.indexOf(modelData.id) >= 0
                readonly property bool playing: downloads.preview.activeKey === modelData.id
                width: ListView.view.width - 10
                height: 62
                radius: 14
                color: playing ? Theme.accentSoft : (rowHover.hovered ? Theme.cardHover : Theme.card)
                border.width: playing ? 1 : 0
                border.color: Theme.accent
                Behavior on color { ColorAnimation { duration: 140 } }
                opacity: 0
                Component.onCompleted: appear.start()
                SequentialAnimation {
                    id: appear
                    PauseAnimation { duration: Math.min(row.index, 8) * 28 }
                    NumberAnimation { target: row; property: "opacity"; to: 1; duration: 240; easing.type: Easing.OutCubic }
                }
                HoverHandler { id: rowHover }

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 9
                    anchors.rightMargin: 10
                    spacing: 10
                    Rectangle {
                        Layout.preferredWidth: 44
                        Layout.preferredHeight: 44
                        radius: 10
                        color: Theme.chip
                        clip: true
                        Image {
                            anchors.fill: parent
                            source: row.modelData.thumbnail || ""
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                        }
                        Shimmer { anchors.fill: parent; cornerRadius: 10; visible: parent.children[0].status === Image.Loading }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        Label {
                            Layout.fillWidth: true
                            text: row.modelData.title || ""
                            color: Theme.fg
                            font.bold: true
                            font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                        Label {
                            Layout.fillWidth: true
                            text: dialog.alts.detail(row.modelData)
                            color: Theme.muted
                            font.pixelSize: 11
                            elide: Text.ElideRight
                        }
                    }
                    PreviewBar {
                        Layout.preferredWidth: row.playing ? 200 : 32
                        Layout.preferredHeight: 32
                        Behavior on Layout.preferredWidth { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                        key: row.modelData.id
                        url: row.modelData.url || ""
                        title: row.modelData.title || ""
                        compact: true
                    }
                    PillButton {
                        objectName: "alternativesAction"
                        Layout.preferredWidth: Math.max(104, implicitWidth)
                        implicitHeight: 34
                        sidePadding: 12
                        variant: row.done ? "ghost" : "secondary"
                        enabled: !row.done
                        text: row.done ? qsTr("✓ Lista") : (dialog.alts.replaceable ? qsTr("Reemplazar") : qsTr("Guardar"))
                        onClicked: {
                            if (dialog.alts.replaceable) {
                                dialog.pending = row.modelData
                                confirm.open()
                            } else {
                                dialog.saveRequested(row.modelData)
                            }
                        }
                    }
                }
            }
        }
    }
}
