import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Historial paginado de descargas: búsqueda, abrir, ver carpeta y volver a ponerlas en cola.
ColumnLayout {
    id: root
    property bool expanded: false
    property int selectedId: -1
    readonly property var selected: {
        for (var i = 0; i < downloads.historyRows.length; i++)
            if (downloads.historyRows[i].id === selectedId) return downloads.historyRows[i]
        return null
    }
    spacing: 8

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        Field {
            id: searchBox
            Layout.fillWidth: true
            implicitHeight: 38
            iconName: "search"
            placeholderText: qsTr("Buscar en el historial")
            onAccepted: downloads.historySearchFor(text)
        }
        PillButton {
            text: qsTr("Buscar")
            variant: "primary"
            implicitHeight: 36
            onClicked: downloads.historySearchFor(searchBox.text)
        }
        PillButton {
            text: qsTr("Todos")
            variant: "ghost"
            implicitHeight: 36
            onClicked: { searchBox.text = ""; downloads.historyShowAll() }
        }
        PillButton {
            visible: !root.expanded
            text: qsTr("Ampliar")
            iconName: "expand_window"
            variant: "ghost"
            implicitHeight: 36
            onClicked: bigDialog.open()
        }
    }

    // cabecera de la tabla
    RowLayout {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        spacing: 10
        SectionLabel { text: qsTr("FECHA"); Layout.preferredWidth: 96 }
        SectionLabel { text: qsTr("CANCIÓN / VIDEO"); Layout.fillWidth: true }
        SectionLabel { text: qsTr("TIPO"); Layout.preferredWidth: 44 }
        SectionLabel { text: qsTr("ESTADO"); Layout.preferredWidth: 78 }
    }

    Item {
        Layout.fillWidth: true
        Layout.fillHeight: true

        ListView {
            id: list
            anchors.fill: parent
            model: downloads.historyRows
            clip: true
            spacing: 4
            rightMargin: 6
            opacity: downloads.historyLoading && count === 0 ? 0 : 1
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            delegate: Rectangle {
                width: ListView.view.width - 6
                height: 40
                radius: 12
                color: root.selectedId === modelData.id ? Theme.accentSoft
                     : (rowHover.containsMouse ? Theme.cardHover : "transparent")
                border.width: root.selectedId === modelData.id ? 1 : 0
                border.color: Theme.accent
                Behavior on color { ColorAnimation { duration: 110 } }
                MouseArea {
                    id: rowHover
                    anchors.fill: parent
                    hoverEnabled: true
                    onClicked: root.selectedId = modelData.id
                    onDoubleClicked: downloads.historyOpen(modelData.id, false)
                }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    spacing: 10
                    Text { Layout.preferredWidth: 96; text: modelData.date; color: Theme.muted; font.pixelSize: 11 }
                    Text { Layout.fillWidth: true; text: modelData.title; color: Theme.fg; font.pixelSize: 12; elide: Text.ElideRight }
                    Text { Layout.preferredWidth: 44; text: modelData.format; color: Theme.muted; font.pixelSize: 11 }
                    Text {
                        Layout.preferredWidth: 78
                        text: modelData.stateText
                        color: modelData.existing ? Theme.warning : Theme.success
                        font.pixelSize: 11
                        font.bold: true
                    }
                }
            }
        }

        Spinner {
            anchors.centerIn: parent
            size: 34
            visible: downloads.historyLoading && list.count === 0
        }
        EmptyState {
            anchors.centerIn: parent
            visible: !downloads.historyLoading && list.count === 0
            iconName: "history"
            title: downloads.historyStatus
            detail: ""
        }
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        PillButton {
            text: qsTr("Abrir")
            iconName: "play"
            implicitHeight: 34
            enabled: root.selected !== null
            onClicked: downloads.historyOpen(root.selectedId, false)
        }
        PillButton {
            text: qsTr("Carpeta")
            iconName: "folder"
            implicitHeight: 34
            enabled: root.selected !== null
            onClicked: downloads.historyOpen(root.selectedId, true)
        }
        PillButton {
            text: qsTr("A la cola")
            iconName: "plus"
            implicitHeight: 34
            enabled: root.selected !== null && root.selected.sourceUrl !== "" && !musicbox.downloadRunning
            onClicked: downloads.historyQueue(root.selectedId)
        }
        Item { Layout.fillWidth: true }
        Spinner { size: 16; visible: downloads.historyLoading && list.count > 0 }
        Text {
            text: list.count > 0 ? downloads.historyStatus : ""
            color: Theme.muted
            font.pixelSize: 11
        }
        PillButton {
            iconName: "prev_page"
            implicitHeight: 34
            implicitWidth: 38
            sidePadding: 0
            enabled: downloads.historyHasPrevious
            onClicked: downloads.historyMove(-1)
        }
        PillButton {
            iconName: "next_page"
            implicitHeight: 34
            implicitWidth: 38
            sidePadding: 0
            enabled: downloads.historyHasNext
            onClicked: downloads.historyMove(1)
        }
    }

    // Vista ampliada del mismo historial
    MbDialog {
        id: bigDialog
        width: Math.min(900, Overlay.overlay ? Overlay.overlay.width - 80 : 900)
        height: Math.min(640, Overlay.overlay ? Overlay.overlay.height - 80 : 640)
        onAboutToShow: downloads.historyRefresh()
        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 22
            spacing: 14
            RowLayout {
                Layout.fillWidth: true
                Text { text: qsTr("Historial"); color: Theme.fg; font.pixelSize: 20; font.bold: true }
                Item { Layout.fillWidth: true }
                PillButton { text: qsTr("Cerrar"); onClicked: bigDialog.close() }
            }
            Loader {
                Layout.fillWidth: true
                Layout.fillHeight: true
                active: bigDialog.visible
                source: "HistoryPanel.qml"
                onLoaded: item.expanded = true
            }
        }
    }
}
