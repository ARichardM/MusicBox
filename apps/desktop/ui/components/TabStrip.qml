import QtQuick

// Pestañas en forma de píldora con un indicador que se desliza hasta la pestaña activa.
// `tabs`: [{key: "files", text: "Archivos"}, ...]
Item {
    id: root
    property var tabs: []
    property string current: ""
    property bool equalWidth: false
    signal picked(string key)

    implicitHeight: 38
    height: 38

    function indexOfKey(key) {
        for (var i = 0; i < tabs.length; i++)
            if (tabs[i].key === key) return i
        return -1
    }

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Theme.dark ? "#202125" : "#DCE2EB"
    }

    Rectangle {
        id: indicator
        readonly property Item target: { rep.count; row.width; return rep.itemAt(root.indexOfKey(root.current)) }
        visible: target !== null
        y: 3
        height: root.height - 6
        radius: height / 2
        x: target ? row.x + target.x : 0
        width: target ? target.width : 0
        color: Theme.dark ? "#34363D" : "#FFFFFF"
        border.width: Theme.dark ? 0 : 1
        border.color: Theme.line
        Behavior on x { NumberAnimation { duration: 230; easing.type: Easing.OutCubic } }
        Behavior on width { NumberAnimation { duration: 230; easing.type: Easing.OutCubic } }
    }

    Row {
        id: row
        x: 3
        width: parent.width - 6
        height: parent.height
        Repeater {
            id: rep
            model: root.tabs
            delegate: Item {
                width: root.equalWidth ? row.width / Math.max(1, root.tabs.length) : label.implicitWidth + 30
                height: root.height
                Text {
                    id: label
                    anchors.centerIn: parent
                    text: modelData.text
                    font.pixelSize: 12
                    font.bold: root.current === modelData.key
                    color: root.current === modelData.key ? Theme.fg : (hover.containsMouse ? Theme.fg : Theme.muted)
                    Behavior on color { ColorAnimation { duration: 140 } }
                }
                MouseArea {
                    id: hover
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.picked(modelData.key)
                }
            }
        }
    }
}
