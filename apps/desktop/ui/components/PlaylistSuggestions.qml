import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Tarjeta compacta con un carrusel de canciones sugeridas para la playlist abierta.
Rectangle {
    id: root
    property var suggestions: []
    property bool loading: false
    property bool collapsed: false
    readonly property int headerHeight: 38
    readonly property int slideHeight: 58
    readonly property bool hasContent: suggestions.length > 0 || loading
    // Mientras llegan las primeras sugerencias la tarjeta se queda plegada y se despliega sola al terminar,
    // salvo que el usuario la haya plegado él mismo.
    readonly property bool shut: collapsed || (loading && suggestions.length === 0)
    signal playRequested(var track)
    signal addRequested(var track)
    signal refreshRequested()
    signal collapseToggled(bool collapsed)

    radius: 18
    color: Theme.card
    border.width: 1
    border.color: Theme.dark ? Theme.adaptiveColor("#26282E") : Theme.adaptiveColor("#D5DCE6")
    clip: true
    implicitHeight: shut ? headerHeight : headerHeight + slideHeight + 10
    visible: opacity > 0.01
    opacity: hasContent ? 1 : 0

    Behavior on implicitHeight { NumberAnimation { duration: 380; easing.type: Easing.OutCubic } }
    Behavior on opacity { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

    // Sincroniza el modelo sin recrearlo: quitar una sugerencia la desliza hacia afuera y el resto se acomoda.
    ListModel { id: items }
    onSuggestionsChanged: sync()
    Component.onCompleted: sync()
    function sync() {
        var incoming = suggestions || []
        var known = {}
        for (var i = 0; i < items.count; ++i)
            known[items.get(i).vid] = true
        var wanted = {}
        for (var j = 0; j < incoming.length; ++j)
            wanted[incoming[j].id] = true
        var onlyRemoved = incoming.length < items.count && incoming.length > 0
        for (var k = 0; k < incoming.length && onlyRemoved; ++k)
            if (!known[incoming[k].id]) onlyRemoved = false
        if (onlyRemoved) {
            for (var r = items.count - 1; r >= 0; --r)
                if (!wanted[items.get(r).vid]) items.remove(r)
            return
        }
        items.clear()
        for (var n = 0; n < incoming.length; ++n)
            items.append({ vid: incoming[n].id, track: incoming[n] })
        carousel.currentIndex = 0
        if (incoming.length > 0) sparkleGlow.restart()
    }

    component SmallButton: Item {
        id: button
        property string icon: ""
        property real iconRotation: 0
        property string tip: ""
        property bool spinning: false
        signal clicked()
        implicitWidth: 30
        implicitHeight: 30
        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: buttonArea.containsMouse ? Theme.chip : "transparent"
            Behavior on color { ColorAnimation { duration: 120 } }
        }
        Item {
            anchors.centerIn: parent
            width: 15
            height: 15
            rotation: button.spinning ? button.spinAngle : button.iconRotation
            Behavior on rotation { enabled: !button.spinning; NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
            Icon {
                anchors.fill: parent
                name: button.icon
                size: 15
                tint: buttonArea.containsMouse ? Theme.fg : Theme.muted
            }
        }
        property real spinAngle: 0
        NumberAnimation on spinAngle {
            running: button.spinning
            from: 0; to: 360; duration: 900; loops: Animation.Infinite
        }
        MouseArea {
            id: buttonArea
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: button.clicked()
        }
        ToolTip.visible: buttonArea.containsMouse && tip !== ""
        ToolTip.text: tip
        ToolTip.delay: 500
    }

    HoverHandler { id: cardHover }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: root.headerHeight
            Layout.leftMargin: 14
            Layout.rightMargin: 6
            spacing: 8

            Item {
                Layout.preferredWidth: 18
                Layout.preferredHeight: 18
                Icon {
                    id: sparkle
                    anchors.centerIn: parent
                    name: "sparkle"
                    size: 17
                    tint: Theme.accent
                    // Brilla una vez al llegar sugerencias nuevas y se queda quieto.
                    SequentialAnimation {
                        id: sparkleGlow
                        ParallelAnimation {
                            NumberAnimation { target: sparkle; property: "scale"; to: 1.3; duration: 220; easing.type: Easing.OutCubic }
                            NumberAnimation { target: sparkle; property: "opacity"; to: 0.65; duration: 220 }
                        }
                        ParallelAnimation {
                            NumberAnimation { target: sparkle; property: "scale"; to: 1; duration: 380; easing.type: Easing.OutBack }
                            NumberAnimation { target: sparkle; property: "opacity"; to: 1; duration: 380 }
                        }
                    }
                }
            }
            Label {
                text: qsTr("Para esta playlist")
                color: Theme.fg
                font.pixelSize: 12
                font.bold: true
            }
            Item { Layout.fillWidth: true }

            // Indicador: como mucho 5 puntos, el activo se estira.
            Row {
                id: dots
                visible: items.count > 1 && !root.shut
                spacing: 4
                readonly property int total: Math.min(items.count, 5)
                readonly property int active: items.count <= 5 ? carousel.currentIndex
                    : Math.round(carousel.currentIndex * (total - 1) / Math.max(1, items.count - 1))
                Repeater {
                    model: dots.total
                    Rectangle {
                        height: 5
                        width: index === dots.active ? 16 : 5
                        radius: 2.5
                        color: index === dots.active ? Theme.accent : Theme.chip
                        Behavior on width { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                        Behavior on color { ColorAnimation { duration: 220 } }
                    }
                }
            }
            SmallButton {
                icon: "refresh"
                tip: qsTr("Actualizar sugerencias")
                spinning: root.loading
                onClicked: root.refreshRequested()
            }
            SmallButton {
                icon: "chevron"
                iconRotation: root.collapsed ? 90 : -90
                tip: root.collapsed ? qsTr("Mostrar sugerencias") : qsTr("Ocultar sugerencias")
                onClicked: root.collapseToggled(!root.collapsed)
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            opacity: root.shut ? 0 : 1
            // Al desplegarse, el contenido entra un instante después de que la tarjeta empiece a abrirse.
            Behavior on opacity { SequentialAnimation { PauseAnimation { duration: root.shut ? 0 : 140 } NumberAnimation { duration: 240 } } }

            // Mientras llegan las primeras sugerencias.
            Shimmer {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                anchors.bottomMargin: 10
                cornerRadius: 14
                visible: items.count === 0 && root.loading
            }

            ListView {
                id: carousel
                objectName: "playlistSuggestionsCarousel"
                anchors.fill: parent
                anchors.bottomMargin: 10
                orientation: ListView.Horizontal
                model: items
                spacing: 10
                clip: true
                interactive: items.count > 1
                snapMode: ListView.SnapOneItem
                boundsBehavior: Flickable.StopAtBounds
                highlightRangeMode: ListView.StrictlyEnforceRange
                preferredHighlightBegin: 12
                preferredHighlightEnd: 12 + slideWidth
                highlightMoveDuration: 340
                highlightMoveVelocity: -1
                readonly property real slideWidth: Math.max(220, width - 56)

                add: Transition {
                    SequentialAnimation {
                        PauseAnimation { duration: Math.max(0, Math.min(ViewTransition.index, 4)) * 55 }
                        ParallelAnimation {
                            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 260; easing.type: Easing.OutCubic }
                        }
                    }
                }
                remove: Transition {
                    ParallelAnimation {
                        NumberAnimation { property: "opacity"; to: 0; duration: 180 }
                        NumberAnimation { property: "y"; to: -14; duration: 180; easing.type: Easing.InCubic }
                    }
                }
                displaced: Transition { NumberAnimation { properties: "x"; duration: 260; easing.type: Easing.OutCubic } }

                delegate: Rectangle {
                    id: slide
                    objectName: "playlistSuggestionCard"
                    required property int index
                    required property var track
                    property bool added: false
                    width: carousel.slideWidth
                    height: root.slideHeight
                    radius: 14
                    color: slideArea.containsMouse ? Theme.cardHover : Theme.panel
                    Behavior on color { ColorAnimation { duration: 120 } }
                    scale: slideArea.pressed && !carousel.dragging ? 0.96 : 1
                    Behavior on scale {
                        NumberAnimation {
                            duration: slideArea.pressed || carousel.dragging ? 85 : 190
                            easing.type: slideArea.pressed || carousel.dragging ? Easing.OutCubic : Easing.OutBack
                            easing.overshoot: 1.4
                        }
                    }

                    MouseArea {
                        id: slideArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: root.playRequested(slide.track)
                    }
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 8
                        spacing: 10
                        Rectangle {
                            Layout.preferredWidth: 42
                            Layout.preferredHeight: 42
                            radius: 10
                            color: Theme.chip
                            clip: true
                            Image {
                                id: cover
                                anchors.fill: parent
                                source: slide.track.thumbnail || ""
                                fillMode: Image.PreserveAspectCrop
                                asynchronous: true
                                opacity: status === Image.Ready ? 1 : 0
                                Behavior on opacity { NumberAnimation { duration: 220 } }
                            }
                            Shimmer { anchors.fill: parent; cornerRadius: 10; visible: cover.status === Image.Loading }
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1
                            Label {
                                Layout.fillWidth: true
                                text: slide.track.title || ""
                                color: Theme.fg
                                font.pixelSize: 13
                                font.bold: true
                                elide: Text.ElideRight
                            }
                            Label {
                                Layout.fillWidth: true
                                text: slide.track.artist || slide.track.subtitle || ""
                                color: Theme.muted
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }
                        }
                        Item {
                            Layout.preferredWidth: 32
                            Layout.preferredHeight: 32
                            Rectangle {
                                anchors.fill: parent
                                radius: 16
                                color: slide.added ? Theme.success : (addArea.containsMouse ? Theme.accent : Theme.accentSoft)
                                Behavior on color { ColorAnimation { duration: 160 } }
                            }
                            Icon {
                                id: addIcon
                                anchors.centerIn: parent
                                name: slide.added ? "check" : "plus"
                                size: 16
                                tint: slide.added ? "#FFFFFF" : (addArea.containsMouse ? Theme.accentText : Theme.accent)
                                SequentialAnimation {
                                    running: slide.added
                                    NumberAnimation { target: addIcon; property: "scale"; to: 1.3; duration: 120; easing.type: Easing.OutCubic }
                                    NumberAnimation { target: addIcon; property: "scale"; to: 1; duration: 180; easing.type: Easing.OutBack }
                                }
                            }
                            MouseArea {
                                id: addArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                enabled: !slide.added
                                onClicked: { slide.added = true; confirmAdd.start() }
                            }
                            ToolTip.visible: addArea.containsMouse && !slide.added
                            ToolTip.text: qsTr("Agregar a la playlist")
                            ToolTip.delay: 500
                            Timer {
                                id: confirmAdd
                                interval: 360
                                onTriggered: root.addRequested(slide.track)
                            }
                        }
                    }
                }

                WheelHandler {
                    acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                    onWheel: function(event) {
                        if (wheelCooldown.running || items.count < 2) return
                        var step = (event.angleDelta.y + event.angleDelta.x) < 0 ? 1 : -1
                        carousel.currentIndex = Math.max(0, Math.min(items.count - 1, carousel.currentIndex + step))
                        wheelCooldown.start()
                    }
                }
                Timer { id: wheelCooldown; interval: 260 }
            }

            // Pasa sola cada pocos segundos; se detiene al tocarla o ponerle el mouse encima.
            Timer {
                interval: 6000
                repeat: true
                running: !root.collapsed && root.visible && items.count > 1 && !cardHover.hovered && !carousel.moving
                onTriggered: carousel.currentIndex = (carousel.currentIndex + 1) % items.count
            }

            SmallButton {
                anchors.left: parent.left
                anchors.leftMargin: 14
                anchors.verticalCenter: carousel.verticalCenter
                icon: "prev_page"
                visible: cardHover.hovered && carousel.currentIndex > 0
                onClicked: carousel.currentIndex = carousel.currentIndex - 1
            }
            SmallButton {
                anchors.right: parent.right
                anchors.rightMargin: 4
                anchors.verticalCenter: carousel.verticalCenter
                icon: "next_page"
                visible: cardHover.hovered && carousel.currentIndex < items.count - 1
                onClicked: carousel.currentIndex = carousel.currentIndex + 1
            }
        }
    }
}
