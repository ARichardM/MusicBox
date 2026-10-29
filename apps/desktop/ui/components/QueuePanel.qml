import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Cola de reproducción: arrastrar para reordenar, quitar canciones, guardar como playlist y limpiar.
Item {
    id: panel
    signal closeRequested()

    readonly property int rowHeight: 70
    readonly property int current: musicbox.queueIndex
    readonly property var upcoming: current >= 0 && current + 1 < musicbox.queue.length ? musicbox.queue[current + 1] : null
    property int dragFrom: -1
    property int dropIndex: -1

    function revealCurrent() {
        if (current >= 0) list.positionViewAtIndex(current, ListView.Center)
    }
    onVisibleChanged: if (visible) Qt.callLater(revealCurrent)
    Component.onCompleted: Qt.callLater(revealCurrent)

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Label {
                text: qsTr("Cola")
                color: Theme.fg
                font.pixelSize: 22
                font.bold: true
            }
            Rectangle {
                visible: musicbox.queue.length > 0
                implicitWidth: countLabel.implicitWidth + 16
                implicitHeight: 22
                radius: 11
                color: Theme.chip
                Label {
                    id: countLabel
                    anchors.centerIn: parent
                    text: musicbox.queue.length
                    color: Theme.muted
                    font.pixelSize: 11
                    font.bold: true
                }
            }
            Item { Layout.fillWidth: true }
            PillButton {
                variant: "ghost"
                iconName: "close"
                implicitWidth: 34
                implicitHeight: 34
                sidePadding: 8
                onClicked: panel.closeRequested()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 0
                Label {
                    text: qsTr("Reproducción automática")
                    color: Theme.fg
                    font.pixelSize: 13
                    font.bold: true
                }
                Label {
                    Layout.fillWidth: true
                    text: qsTr("Agrega canciones similares al final de la cola")
                    color: Theme.muted
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }
            }
            Switch {
                checked: musicbox.autoplay
                onToggled: musicbox.setAutoplay(checked)
            }
        }

        Label {
            Layout.fillWidth: true
            text: panel.upcoming ? qsTr("Siguiente: ") + panel.upcoming.title : qsTr("No hay más canciones en la cola")
            color: Theme.muted
            font.pixelSize: 12
            elide: Text.ElideRight
        }
        RowLayout {
            Layout.fillWidth: true
            visible: musicbox.autoplayLoading || musicbox.autoplayError !== ""
            Spinner { size: 18; running: musicbox.autoplayLoading; visible: running }
            Label {
                Layout.fillWidth: true
                text: musicbox.autoplayLoading ? qsTr("Buscando…") : musicbox.autoplayError
                color: Theme.muted
                font.pixelSize: 11
                wrapMode: Text.WordWrap
            }
            PillButton {
                text: qsTr("Reintentar")
                visible: !musicbox.autoplayLoading && musicbox.autoplayError !== ""
                onClicked: musicbox.retryAutoplay()
            }
        }
        Label {
            visible: musicbox.queue.length > 1
            text: qsTr("Arrastra el asa para reordenar")
            color: Theme.muted
            opacity: 0.7
            font.pixelSize: 11
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            model: musicbox.queue
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: panel.dragFrom < 0
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            move: Transition { NumberAnimation { properties: "y"; duration: 160; easing.type: Easing.OutCubic } }
            displaced: Transition { NumberAnimation { properties: "y"; duration: 160; easing.type: Easing.OutCubic } }

            Timer {
                id: autoScroll
                property int step: 0
                interval: 16
                repeat: true
                running: panel.dragFrom >= 0 && step !== 0
                onTriggered: list.contentY = Math.max(0, Math.min(list.contentHeight - list.height, list.contentY + step))
            }

            Rectangle {
                parent: list.contentItem
                visible: panel.dragFrom >= 0 && panel.dropIndex >= 0 && panel.dropIndex !== panel.dragFrom
                x: 0
                width: list.width - 12
                height: 3
                radius: 2
                color: Theme.accent
                y: (panel.dropIndex > panel.dragFrom ? (panel.dropIndex + 1) : panel.dropIndex) * panel.rowHeight - 2
                z: 20
            }

            delegate: Item {
                id: cell
                required property int index
                required property var modelData
                readonly property bool isCurrent: index === panel.current
                width: ListView.view.width - 12
                height: panel.rowHeight
                z: dragArea.pressed ? 10 : 1

                Rectangle {
                    id: card
                    width: parent.width
                    height: panel.rowHeight - 8
                    radius: 14
                    color: cell.isCurrent ? Theme.accentSoft : (hover.hovered || dragArea.pressed ? Theme.cardHover : Theme.card)
                    border.width: cell.isCurrent ? 1 : 0
                    border.color: Theme.accent
                    opacity: dragArea.pressed ? 0.92 : 1
                    scale: dragArea.pressed ? 1.015 : 1
                    Behavior on color { ColorAnimation { duration: 120 } }
                    Behavior on scale { NumberAnimation { duration: 120 } }
                    Behavior on y { enabled: !dragArea.pressed; NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                    HoverHandler { id: hover }

                    TapHandler {
                        onTapped: musicbox.playQueueIndex(cell.index)
                    }

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 8
                        spacing: 10

                        Rectangle {
                            Layout.preferredWidth: 46
                            Layout.preferredHeight: 46
                            radius: 10
                            color: Theme.chip
                            clip: true
                            Image {
                                anchors.fill: parent
                                source: cell.modelData.thumbnail || ""
                                fillMode: Image.PreserveAspectCrop
                                asynchronous: true
                            }
                            Icon {
                                anchors.centerIn: parent
                                visible: !cell.modelData.thumbnail
                                name: "music"
                                size: 20
                                tint: Theme.muted
                            }
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1
                            Label {
                                Layout.fillWidth: true
                                text: (cell.isCurrent ? qsTr("Ahora") + " · " : "") + (cell.modelData.title || "")
                                color: cell.isCurrent ? Theme.accent : Theme.fg
                                font.bold: true
                                font.pixelSize: 13
                                elide: Text.ElideRight
                            }
                            Label {
                                Layout.fillWidth: true
                                text: cell.modelData.artist || cell.modelData.subtitle || ""
                                color: Theme.muted
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }
                        }
                        PillButton {
                            visible: !cell.isCurrent && (hover.hovered || !panel.dragActive)
                            variant: "ghost"
                            iconName: "close"
                            implicitWidth: 30
                            implicitHeight: 30
                            sidePadding: 6
                            opacity: hover.hovered ? 1 : 0.8
                            ToolTip.visible: hovered
                            ToolTip.text: qsTr("Quitar de la cola")
                            onClicked: musicbox.removeQueueItem(cell.index)
                        }
                        // Asa de arrastre: tres líneas.
                        Item {
                            Layout.preferredWidth: 28
                            Layout.preferredHeight: 36
                            Column {
                                anchors.centerIn: parent
                                spacing: 3
                                Repeater {
                                    model: 3
                                    Rectangle { width: 14; height: 2; radius: 1; color: dragArea.containsMouse || dragArea.pressed ? Theme.accent : Theme.muted }
                                }
                            }
                            MouseArea {
                                id: dragArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
                                preventStealing: true
                                property real startY: 0
                                onPressed: (mouse) => { startY = mouse.y; panel.dragFrom = cell.index; panel.dropIndex = cell.index }
                                onPositionChanged: (mouse) => {
                                    if (!pressed) return
                                    card.y = mouse.y - startY
                                    var local = mapToItem(list.contentItem, mouse.x, mouse.y)
                                    panel.dropIndex = Math.max(0, Math.min(musicbox.queue.length - 1, Math.floor(local.y / panel.rowHeight)))
                                    var viewY = mapToItem(list, mouse.x, mouse.y).y
                                    autoScroll.step = viewY < 36 ? -8 : (viewY > list.height - 36 ? 8 : 0)
                                }
                                onReleased: {
                                    autoScroll.step = 0
                                    var from = panel.dragFrom, to = panel.dropIndex
                                    card.y = 0
                                    panel.dragFrom = -1
                                    panel.dropIndex = -1
                                    if (from >= 0 && to >= 0 && to !== from) musicbox.moveQueueItem(from, to)
                                }
                                onCanceled: { autoScroll.step = 0; card.y = 0; panel.dragFrom = -1; panel.dropIndex = -1 }
                            }
                        }
                    }
                }
            }

            EmptyState {
                anchors.centerIn: parent
                visible: list.count === 0
                title: qsTr("La cola está vacía")
                detail: qsTr("Reproduce una canción o usa Aleatorio")
            }
        }

        PillButton {
            Layout.alignment: Qt.AlignHCenter
            variant: "danger"
            iconName: "trash"
            text: qsTr("Limpiar cola")
            visible: musicbox.queue.length > 1
            implicitHeight: 36
            onClicked: musicbox.clearQueue()
        }
    }

    readonly property bool dragActive: dragFrom >= 0
}
