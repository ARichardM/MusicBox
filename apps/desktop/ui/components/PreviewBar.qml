import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Escucha rápida de un resultado: botón de reproducir/pausar con spinner al abrir, barra de avance y tiempo.
Item {
    id: root
    property string key: ""
    property string url: ""
    property string title: ""
    property bool compact: false
    readonly property var preview: downloads.preview
    readonly property bool active: preview.activeKey === key && key !== ""
    readonly property string state: active ? preview.state : "idle"
    readonly property bool playable: state === "playing" || state === "paused"

    implicitHeight: 32

    function clock(ms) {
        var s = Math.max(0, Math.floor(ms / 1000))
        return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0")
    }
    function trigger() {
        if (playable) preview.toggle(key)
        else preview.play(key, url, title)
    }

    RowLayout {
        anchors.fill: parent
        spacing: 10

        AbstractButton {
            id: button
            Layout.preferredWidth: 32
            Layout.preferredHeight: 32
            hoverEnabled: true
            scale: down ? 0.92 : 1
            Behavior on scale { NumberAnimation { duration: 90 } }
            onClicked: root.trigger()
            background: Rectangle {
                radius: width / 2
                color: button.hovered ? Qt.lighter(Theme.accent, 1.12) : Theme.accent
                Behavior on color { ColorAnimation { duration: 120 } }
            }
            contentItem: Item {
                Spinner {
                    anchors.centerIn: parent
                    size: 18
                    visible: root.state === "loading"
                    progress: -1
                    thickness: 2.2
                }
                Icon {
                    anchors.centerIn: parent
                    visible: root.state !== "loading"
                    name: root.state === "playing" ? "pause" : "play"
                    size: 14
                    tint: "white"
                }
            }
        }

        Item {
            id: bar
            Layout.fillWidth: true
            Layout.preferredHeight: 20
            visible: !root.compact || root.active
            Rectangle {
                id: track
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width
                height: 5
                radius: 3
                color: Theme.dark ? "#2C2E34" : "#D3D9E3"
                Rectangle {
                    height: parent.height
                    radius: 3
                    width: root.playable && root.preview.duration > 0
                           ? parent.width * Math.min(1, root.preview.position / root.preview.duration) : 0
                    gradient: Gradient {
                        orientation: Gradient.Horizontal
                        GradientStop { position: 0; color: Theme.accent }
                        GradientStop { position: 1; color: Theme.cyan }
                    }
                }
            }
            Rectangle {
                visible: root.playable
                width: 12; height: 12; radius: 6
                color: "white"
                border.width: 2
                border.color: Theme.accent
                anchors.verticalCenter: parent.verticalCenter
                x: root.preview.duration > 0
                   ? Math.min(1, root.preview.position / root.preview.duration) * (parent.width - width) : 0
            }
            MouseArea {
                anchors.fill: parent
                enabled: root.playable
                cursorShape: Qt.PointingHandCursor
                onClicked: (mouse) => root.preview.seek(mouse.x / width * root.preview.duration)
                onPositionChanged: (mouse) => { if (pressed) root.preview.seek(Math.max(0, Math.min(1, mouse.x / width)) * root.preview.duration) }
            }
        }

        Text {
            Layout.preferredWidth: 78
            horizontalAlignment: Text.AlignRight
            visible: !root.compact || root.active
            text: root.state === "loading" ? qsTr("Cargando…")
                  : root.playable ? root.clock(root.preview.position) + " / " + root.clock(root.preview.duration)
                  : "0:00"
            color: root.state === "error" ? Theme.danger : Theme.muted
            font.pixelSize: 11
        }
    }
}
