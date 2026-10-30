import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Selector de artistas con búsqueda y retratos, pensado para mouse y teclado.
FocusScope {
    id: control
    property var artists: []
    property string selectedArtist: ""
    property var artistPhotos: ({})
    property int currentIndex: indexForArtist(selectedArtist)
    property alias searchText: artistSearch.text
    readonly property bool filtering: selectedArtist !== ""
    readonly property var entries: [qsTr("Todos los artistas")].concat(artists)
    readonly property int count: entries.length
    readonly property var filteredEntries: {
        var query = artistSearch.text.trim().toLocaleLowerCase()
        if (!query)
            return entries
        return entries.filter(function(name, index) {
            return index > 0 && name.toLocaleLowerCase().indexOf(query) !== -1
        })
    }
    readonly property int filteredCount: filteredEntries.length
    signal artistSelected(string artist)
    signal photoRequested(string artist)
    // Se conserva para automatización y accesibilidad programática.
    signal activated(int index)

    function indexForArtist(artist) {
        if (!artist)
            return 0
        var needle = artist.toLocaleLowerCase()
        for (var i = 0; i < artists.length; ++i) {
            if (String(artists[i]).toLocaleLowerCase() === needle)
                return i + 1
        }
        return 0
    }
    function chooseArtist(artist) {
        var value = artist === qsTr("Todos los artistas") ? "" : artist
        currentIndex = indexForArtist(value)
        artistSelected(value)
        artistPopup.close()
    }
    function open() {
        artistSearch.clear()
        artistPopup.open()
        artistSearch.forceActiveFocus()
    }

    onActivated: function(index) {
        if (index >= 0 && index < entries.length)
            chooseArtist(index === 0 ? "" : entries[index])
    }
    onSelectedArtistChanged: {
        currentIndex = indexForArtist(selectedArtist)
        if (filtering)
            photoRequested(selectedArtist)
    }
    Component.onCompleted: if (filtering) photoRequested(selectedArtist)

    implicitHeight: 36
    implicitWidth: Math.min(270, Math.max(158, artistLabel.implicitWidth + 82))
    focus: true
    Accessible.role: Accessible.Button
    Accessible.name: qsTr("Filtrar por artista")
    Accessible.description: filtering ? selectedArtist : qsTr("Todos los artistas")
    Keys.onSpacePressed: open()
    Keys.onEnterPressed: open()
    Keys.onReturnPressed: open()

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: control.filtering ? Theme.accentSoft : (artistMouse.containsMouse ? Theme.cardHover : Theme.card)
        border.width: 1
        border.color: control.activeFocus || artistPopup.visible || control.filtering ? Theme.accent : Theme.line
        Behavior on color { ColorAnimation { duration: 140 } }
        Behavior on border.color { ColorAnimation { duration: 140 } }
    }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 10
        anchors.rightMargin: 10
        spacing: 7
        ArtistAvatar {
            objectName: "selectedArtistAvatar"
            Layout.preferredWidth: 22
            Layout.preferredHeight: 22
            artistName: control.selectedArtist
            photo: control.artistPhotos[control.selectedArtist] || ""
        }
        Text {
            id: artistLabel
            Layout.fillWidth: true
            text: control.filtering ? control.selectedArtist : qsTr("Artistas")
            color: control.filtering ? Theme.accent : Theme.fg
            font.pixelSize: 12
            elide: Text.ElideRight
            verticalAlignment: Text.AlignVCenter
        }
        Icon {
            name: "chevron"
            size: 13
            tint: control.filtering ? Theme.accent : Theme.muted
            rotation: artistPopup.visible ? 180 : 0
            Behavior on rotation { NumberAnimation { duration: 170; easing.type: Easing.OutCubic } }
        }
    }
    MouseArea {
        id: artistMouse
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: control.open()
    }
    ToolTip.visible: artistMouse.containsMouse
    ToolTip.delay: 350
    ToolTip.text: filtering ? selectedArtist : qsTr("Filtrar por artista")

    Popup {
        id: artistPopup
        objectName: "artistFilterPopup"
        readonly property real windowX: control.mapToItem(null, 0, 0).x
        readonly property real safeMargin: 16
        x: Math.max(safeMargin - windowX,
                    Math.min(control.width - width,
                             (control.Window.window ? control.Window.window.width : width)
                             - safeMargin - windowX - width))
        y: control.height + 7
        width: Math.max(220, Math.min(360,
                    control.Window.window ? control.Window.window.width - safeMargin * 2 : 360))
        height: Math.min(control.Window.window
                         ? Math.max(174, control.Window.window.height - mapToItem(null, 0, 0).y - 24)
                         : 360,
                         Math.min(360, Math.max(174, pickerColumn.implicitHeight + 24)))
        padding: 12
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onClosed: artistSearch.clear()
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 150 }
                NumberAnimation { property: "scale"; from: 0.97; to: 1; duration: 170; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition { NumberAnimation { property: "opacity"; to: 0; duration: 100 } }
        background: Rectangle {
            radius: 18
            color: Theme.panel
            border.width: 1
            border.color: Theme.line
        }
        contentItem: ColumnLayout {
            id: pickerColumn
            spacing: 10
            RowLayout {
                Layout.fillWidth: true
                Label {
                    Layout.fillWidth: true
                    text: qsTr("Filtrar por artista")
                    color: Theme.fg
                    font.pixelSize: 18
                    font.bold: true
                }
                ToolButton {
                    implicitWidth: 30
                    implicitHeight: 30
                    Accessible.name: qsTr("Cerrar")
                    onClicked: artistPopup.close()
                    contentItem: Icon { name: "close"; size: 14; tint: Theme.muted }
                    background: Rectangle { radius: 9; color: parent.hovered ? Theme.cardHover : "transparent" }
                }
            }
            Field {
                id: artistSearch
                objectName: "artistFilterSearch"
                Layout.fillWidth: true
                iconName: "search"
                placeholderText: qsTr("Buscar artistas")
            }
            ListView {
                id: artistList
                objectName: "artistFilterList"
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 82
                Layout.preferredHeight: Math.min(contentHeight, 268)
                clip: true
                spacing: 3
                model: control.filteredEntries
                currentIndex: -1
                boundsBehavior: Flickable.StopAtBounds
                ScrollIndicator.vertical: ScrollIndicator { }
                delegate: ItemDelegate {
                    id: artistRow
                    required property string modelData
                    required property int index
                    width: artistList.width - (artistList.contentHeight > artistList.height ? 9 : 0)
                    height: 48
                    hoverEnabled: true
                    readonly property bool allArtists: modelData === qsTr("Todos los artistas")
                    readonly property bool selected: allArtists ? !control.filtering
                        : modelData.toLocaleLowerCase() === control.selectedArtist.toLocaleLowerCase()
                    Component.onCompleted: if (!allArtists) control.photoRequested(modelData)
                    onClicked: control.chooseArtist(allArtists ? "" : modelData)
                    contentItem: RowLayout {
                        spacing: 10
                        ArtistAvatar {
                            Layout.preferredWidth: 32
                            Layout.preferredHeight: 32
                            artistName: artistRow.allArtists ? "" : artistRow.modelData
                            photo: control.artistPhotos[artistRow.modelData] || ""
                        }
                        Label {
                            Layout.fillWidth: true
                            text: artistRow.modelData
                            color: artistRow.selected ? Theme.accent : Theme.fg
                            font.pixelSize: 13
                            font.bold: artistRow.selected
                            elide: Text.ElideRight
                        }
                        Icon {
                            visible: artistRow.selected
                            name: "check"
                            size: 16
                            tint: Theme.accent
                        }
                    }
                    background: Rectangle {
                        radius: 12
                        color: artistRow.down ? Theme.accentSoft
                            : artistRow.hovered || artistRow.selected ? Theme.cardHover : "transparent"
                        Behavior on color { ColorAnimation { duration: 110 } }
                    }
                }
                Label {
                    anchors.centerIn: parent
                    visible: artistList.count === 0
                    text: qsTr("No se encontraron artistas")
                    color: Theme.muted
                    font.pixelSize: 12
                }
            }
        }
    }
}
