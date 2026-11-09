import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Panel minimalista y reactivo de letras: desplazamiento suave al verso activo y salto temporal al tocar una línea.
Item {
    id: panel
    objectName: "lyricsPanel"
    signal closeRequested()

    readonly property var controller: musicbox.lyrics
    readonly property var track: musicbox.currentTrack || ({})
    readonly property string trackArtist: (track.artist || track.channel || track.subtitle || "").split(" · ")[0]
    property bool userScrolling: false

    function formatTime(ms) {
        if (ms === undefined || ms < 0) return ""
        var totalSeconds = Math.max(0, Math.floor(ms / 1000))
        return Math.floor(totalSeconds / 60) + ":" + ("0" + (totalSeconds % 60)).slice(-2)
    }

    function centerActiveLine(animated) {
        if (!controller || !controller.synced || controller.activeIndex < 0 || controller.activeIndex >= list.count)
            return
        if (list.height <= 0)
            return
        list.currentIndex = controller.activeIndex
        var item = list.currentItem
        if (!item) {
            list.positionViewAtIndex(controller.activeIndex, ListView.Center)
            return
        }
        var targetY = item.y - (list.height - item.height) / 2
        var maxY = Math.max(0, list.contentHeight - list.height)
        targetY = Math.max(0, Math.min(maxY, targetY))
        if (animated && panel.visible) {
            scrollAnim.stop()
            scrollAnim.from = list.contentY
            scrollAnim.to = targetY
            scrollAnim.start()
        } else {
            scrollAnim.stop()
            list.contentY = targetY
        }
    }

    onVisibleChanged: {
        if (visible) {
            userScrolling = false
            Qt.callLater(function() { centerActiveLine(false) })
        }
    }

    Connections {
        target: panel.controller
        function onActiveIndexChanged() {
            if (!panel.userScrolling)
                Qt.callLater(function() { panel.centerActiveLine(true) })
        }
        function onChanged() {
            if (panel.controller.state === "ready" && !panel.userScrolling)
                Qt.callLater(function() { panel.centerActiveLine(false) })
        }
    }

    Timer {
        id: resumeScrollTimer
        interval: 3800
        repeat: false
        onTriggered: {
            panel.userScrolling = false
            panel.centerActiveLine(true)
        }
    }

    NumberAnimation {
        id: scrollAnim
        target: list
        property: "contentY"
        duration: 300
        easing.type: Easing.OutCubic
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 12

        // Cabecera minimalista con estado de sincronización y cierre.
        RowLayout {
            Layout.fillWidth: true
            spacing: 10

            Label {
                objectName: "lyricsTitleLabel"
                text: qsTr("Letra")
                color: Theme.fg
                font.pixelSize: 22
                font.bold: true
            }

            Rectangle {
                objectName: "lyricsSyncBadge"
                visible: panel.controller && panel.controller.state === "ready"
                implicitWidth: badgeRow.implicitWidth + 18
                implicitHeight: 22
                radius: 11
                color: panel.controller && panel.controller.synced ? Theme.accentSoft : Theme.chip
                border.width: panel.controller && panel.controller.synced ? 1 : 0
                border.color: Theme.accent

                RowLayout {
                    id: badgeRow
                    anchors.centerIn: parent
                    spacing: 5
                    Rectangle {
                        visible: panel.controller && panel.controller.synced
                        width: 6
                        height: 6
                        radius: 3
                        color: Theme.cyan
                    }
                    Label {
                        objectName: "lyricsSyncBadgeText"
                        text: panel.controller && panel.controller.synced ? qsTr("Sincronizada") : qsTr("Texto")
                        color: panel.controller && panel.controller.synced ? Theme.fg : Theme.muted
                        font.pixelSize: 11
                        font.bold: true
                    }
                }
            }

            Item { Layout.fillWidth: true }

            PillButton {
                objectName: "lyricsFollowButton"
                visible: panel.userScrolling && panel.controller && panel.controller.synced && panel.controller.activeIndex >= 0
                text: qsTr("Seguir")
                implicitHeight: 30
                sidePadding: 12
                onClicked: {
                    resumeScrollTimer.stop()
                    panel.userScrolling = false
                    panel.centerActiveLine(true)
                }
            }

            PillButton {
                objectName: "lyricsCloseButton"
                variant: "ghost"
                iconName: "close"
                implicitWidth: 34
                implicitHeight: 34
                sidePadding: 8
                onClicked: panel.closeRequested()
            }
        }

        // Tarjeta compacta de la canción actual.
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 58
            radius: 14
            color: Theme.card
            visible: (panel.track.title || "").length > 0

            RowLayout {
                anchors.fill: parent
                anchors.margins: 8
                anchors.leftMargin: 10
                anchors.rightMargin: 12
                spacing: 10

                Rectangle {
                    Layout.preferredWidth: 42
                    Layout.preferredHeight: 42
                    radius: 10
                    color: Theme.chip
                    clip: true

                    Image {
                        anchors.fill: parent
                        source: panel.track.thumbnail || ""
                        fillMode: Image.PreserveAspectCrop
                        asynchronous: true
                    }
                    Icon {
                        anchors.centerIn: parent
                        visible: !panel.track.thumbnail
                        name: "music"
                        size: 18
                        tint: Theme.muted
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 1

                    Label {
                        objectName: "lyricsTrackTitle"
                        Layout.fillWidth: true
                        text: panel.track.title || ""
                        color: Theme.fg
                        font.pixelSize: 13
                        font.bold: true
                        elide: Text.ElideRight
                    }
                    Label {
                        objectName: "lyricsTrackArtist"
                        Layout.fillWidth: true
                        visible: text.length > 0
                        text: panel.trackArtist
                        color: Theme.muted
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }
                }
            }
        }

        // Cuerpo principal: lista reactiva, carga o estados vacíos/error.
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            ListView {
                id: list
                objectName: "lyricsListView"
                anchors.fill: parent
                visible: panel.controller && panel.controller.state === "ready"
                model: panel.controller ? panel.controller.lines : []
                clip: true
                spacing: panel.controller && panel.controller.synced ? 6 : 4
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                onMovementStarted: {
                    scrollAnim.stop()
                    panel.userScrolling = true
                    resumeScrollTimer.restart()
                }
                onFlickStarted: {
                    scrollAnim.stop()
                    panel.userScrolling = true
                    resumeScrollTimer.restart()
                }

                WheelHandler {
                    onWheel: {
                        scrollAnim.stop()
                        panel.userScrolling = true
                        resumeScrollTimer.restart()
                    }
                }

                header: Item {
                    width: list.width
                    height: panel.controller && panel.controller.synced ? 18 : 8
                }

                footer: Item {
                    width: list.width
                    height: sourceLabel.visible ? 56 : 28

                    Label {
                        id: sourceLabel
                        objectName: "lyricsSourceLabel"
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.topMargin: 14
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        visible: panel.controller && (panel.controller.source || "").length > 0
                        text: panel.controller ? panel.controller.source : ""
                        color: Theme.muted
                        opacity: 0.65
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }
                }

                delegate: Item {
                    id: cell
                    objectName: "lyricLineDelegate"
                    required property int index
                    required property var modelData
                    readonly property bool syncedMode: panel.controller ? panel.controller.synced : false
                    readonly property int activeIdx: panel.controller ? panel.controller.activeIndex : -1
                    readonly property bool isCurrent: syncedMode && index === activeIdx
                    readonly property bool isPast: syncedMode && activeIdx >= 0 && index < activeIdx
                    readonly property int lineTimeMs: modelData && modelData.timeMs !== undefined ? modelData.timeMs : -1
                    readonly property string lineText: modelData && modelData.text !== undefined ? modelData.text : ""

                    width: ListView.view ? ListView.view.width - 10 : 320
                    height: lineCard.implicitHeight

                    Rectangle {
                        id: lineCard
                        width: parent.width
                        implicitHeight: Math.max(cell.syncedMode ? 42 : 30, verseLabel.implicitHeight + (cell.syncedMode ? 18 : 10))
                        radius: 14
                        color: cell.isCurrent
                               ? Theme.accentSoft
                               : (lineHover.hovered && cell.syncedMode ? Theme.cardHover : "transparent")
                        border.width: cell.isCurrent ? 1 : 0
                        border.color: cell.isCurrent ? Theme.accent : "transparent"
                        Behavior on color { ColorAnimation { duration: 150 } }

                        HoverHandler {
                            id: lineHover
                            cursorShape: cell.syncedMode && cell.lineTimeMs >= 0 ? Qt.PointingHandCursor : Qt.ArrowCursor
                        }

                        TapHandler {
                            enabled: cell.syncedMode && cell.lineTimeMs >= 0
                            onTapped: {
                                resumeScrollTimer.stop()
                                panel.userScrolling = false
                                panel.controller.seekToLine(cell.index)
                            }
                        }

                        // Indicador lateral minimalista en la línea activa.
                        Rectangle {
                            width: 3
                            height: Math.max(16, parent.height - 18)
                            radius: 1.5
                            anchors.left: parent.left
                            anchors.leftMargin: 6
                            anchors.verticalCenter: parent.verticalCenter
                            color: Theme.cyan
                            visible: opacity > 0.01
                            opacity: cell.isCurrent ? 1 : 0
                            Behavior on opacity { NumberAnimation { duration: 180 } }
                        }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: cell.isCurrent ? 16 : 12
                            anchors.rightMargin: 12
                            spacing: 8
                            Behavior on anchors.leftMargin { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

                            Label {
                                id: verseLabel
                                objectName: "lyricLineText"
                                Layout.fillWidth: true
                                text: cell.lineText
                                wrapMode: Text.WordWrap
                                lineHeight: 1.18
                                font.pixelSize: cell.syncedMode ? (cell.isCurrent ? 17 : 15) : 14
                                font.bold: cell.syncedMode ? cell.isCurrent : false
                                color: cell.isCurrent
                                       ? Theme.fg
                                       : (lineHover.hovered && cell.syncedMode
                                          ? Theme.fg
                                          : (cell.isPast ? Theme.muted : Theme.fg))
                                opacity: cell.syncedMode
                                         ? (cell.isCurrent ? 1.0 : (lineHover.hovered ? 0.92 : (cell.isPast ? 0.44 : 0.72)))
                                         : 0.9
                                scale: cell.syncedMode ? (cell.isCurrent ? 1.0 : 0.98) : 1.0
                                transformOrigin: Item.Left
                                Behavior on opacity { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                                Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                                Behavior on color { ColorAnimation { duration: 160 } }
                            }

                            Rectangle {
                                objectName: "lyricTimeBadge"
                                visible: cell.syncedMode && cell.lineTimeMs >= 0 && (lineHover.hovered || cell.isCurrent)
                                implicitWidth: timeLabel.implicitWidth + 12
                                implicitHeight: 20
                                radius: 10
                                color: cell.isCurrent ? Theme.accent : Theme.chip
                                opacity: lineHover.hovered || cell.isCurrent ? 0.92 : 0
                                Behavior on opacity { NumberAnimation { duration: 140 } }

                                Label {
                                    id: timeLabel
                                    anchors.centerIn: parent
                                    text: panel.formatTime(cell.lineTimeMs)
                                    color: cell.isCurrent ? "#FFFFFF" : Theme.muted
                                    font.pixelSize: 10
                                    font.bold: true
                                }
                            }
                        }
                    }
                }
            }

            // Estado de carga con líneas esqueleto minimalistas.
            ColumnLayout {
                objectName: "lyricsLoadingState"
                anchors.fill: parent
                anchors.topMargin: 18
                spacing: 14
                visible: panel.controller && panel.controller.state === "loading"

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    Spinner {
                        size: 20
                        running: parent.visible
                        visible: running
                    }
                    Label {
                        text: qsTr("Buscando letra…")
                        color: Theme.muted
                        font.pixelSize: 12
                    }
                }

                Repeater {
                    model: [0.78, 0.56, 0.86, 0.64, 0.72, 0.48, 0.82]
                    delegate: Shimmer {
                        required property real modelData
                        Layout.fillWidth: true
                        Layout.rightMargin: Math.round((1 - modelData) * 140)
                        Layout.preferredHeight: 18
                        cornerRadius: 8
                    }
                }
                Item { Layout.fillHeight: true }
            }

            // Estado instrumental.
            EmptyState {
                objectName: "lyricsInstrumentalState"
                anchors.centerIn: parent
                width: parent.width - 24
                visible: panel.controller && panel.controller.state === "instrumental"
                iconName: "music"
                title: qsTr("Pista instrumental")
                detail: qsTr("Esta canción no tiene letra")
            }

            // Estado sin letra disponible.
            EmptyState {
                objectName: "lyricsEmptyState"
                anchors.centerIn: parent
                width: parent.width - 24
                visible: !panel.controller || panel.controller.state === "empty" || panel.controller.state === "idle"
                iconName: "lyrics"
                title: qsTr("Letra no disponible")
                detail: qsTr("No encontramos la letra de esta canción")
            }

            // Estado de error con botón para reintentar.
            ColumnLayout {
                objectName: "lyricsErrorState"
                anchors.centerIn: parent
                width: parent.width - 28
                spacing: 12
                visible: panel.controller && panel.controller.state === "error"

                Label {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                    text: panel.controller ? panel.controller.error : ""
                    color: Theme.muted
                    font.pixelSize: 13
                }

                PillButton {
                    objectName: "lyricsRetryButton"
                    Layout.alignment: Qt.AlignHCenter
                    text: qsTr("Reintentar")
                    onClicked: panel.controller.retry()
                }
            }
        }
    }
}
