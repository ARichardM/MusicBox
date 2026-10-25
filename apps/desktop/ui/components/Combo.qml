import QtQuick
import QtQuick.Controls

// Lista desplegable redondeada.
ComboBox {
    id: control
    implicitHeight: 42
    font.pixelSize: 13
    opacity: enabled ? 1 : 0.55

    delegate: ItemDelegate {
        width: control.width - 12
        height: 38
        highlighted: control.highlightedIndex === index
        contentItem: Text {
            text: modelData
            color: Theme.fg
            font.pixelSize: 13
            font.bold: control.currentIndex === index
            verticalAlignment: Text.AlignVCenter
            leftPadding: 6
        }
        background: Rectangle {
            radius: 10
            color: parent.highlighted ? Theme.cardHover : "transparent"
        }
    }

    indicator: Icon {
        name: "next_page"
        size: 14
        tint: Theme.muted
        rotation: control.popup.visible ? -90 : 90
        x: control.width - width - 14
        anchors.verticalCenter: parent.verticalCenter
        Behavior on rotation { NumberAnimation { duration: 160 } }
    }

    contentItem: Text {
        leftPadding: 14
        rightPadding: 38
        text: control.displayText
        color: Theme.fg
        font: control.font
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: 14
        color: Theme.field
        border.width: 1
        border.color: control.activeFocus || control.popup.visible ? Theme.accent : Theme.line
        Behavior on border.color { ColorAnimation { duration: 140 } }
    }

    popup: Popup {
        y: control.height + 4
        width: control.width
        padding: 6
        implicitHeight: Math.min(contentItem.implicitHeight + 12, 280)
        contentItem: ListView {
            clip: true
            implicitHeight: contentHeight
            model: control.popup.visible ? control.delegateModel : null
            currentIndex: control.highlightedIndex
            ScrollIndicator.vertical: ScrollIndicator {}
        }
        background: Rectangle {
            radius: 14
            color: Theme.panel
            border.width: 1
            border.color: Theme.line
        }
        enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 120 } }
    }
}
