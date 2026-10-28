import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Selector compacto de la barra de playlist; el estado lo mantiene el puente.
Combo {
    id: control
    property var artists: []
    property string selectedArtist: ""
    property var artistPhotos: ({})
    readonly property bool filtering: selectedArtist !== ""
    signal artistSelected(string artist)
    signal photoRequested(string artist)
    onSelectedArtistChanged: if (filtering) photoRequested(selectedArtist)
    Component.onCompleted: if (filtering) photoRequested(selectedArtist)

    implicitHeight: 34
    implicitWidth: Math.min(260, Math.max(150, artistLabel.implicitWidth + 74))
    hoverEnabled: true
    font.pixelSize: 12
    focusPolicy: Qt.StrongFocus
    model: [qsTr("Todos los artistas")].concat(artists)
    currentIndex: filtering ? Math.max(0, artists.map(function(a) { return a.toLowerCase() }).indexOf(selectedArtist.toLowerCase()) + 1) : 0
    displayText: filtering ? selectedArtist : qsTr("Artistas")
    onActivated: artistSelected(currentIndex > 0 ? artists[currentIndex - 1] : "")
    popup.objectName: "artistFilterPopup"
    popup.focus: true
    Accessible.name: qsTr("Filtrar por artista")
    Accessible.description: filtering ? selectedArtist : qsTr("Todos los artistas")
    ToolTip.visible: hovered
    ToolTip.delay: 350
    ToolTip.text: filtering ? selectedArtist : qsTr("Filtrar por artista")

    contentItem: RowLayout {
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
            text: control.displayText
            color: control.filtering ? Theme.accent : Theme.fg
            font: control.font
            elide: Text.ElideRight
            verticalAlignment: Text.AlignVCenter
        }
    }
    leftPadding: 12
    rightPadding: 32
    topPadding: 0
    bottomPadding: 0
    background: Rectangle {
        radius: 17
        color: control.filtering ? Theme.accentSoft : (control.hovered ? Theme.cardHover : Theme.card)
        border.width: 1
        border.color: control.activeFocus || control.popup.visible || control.filtering ? Theme.accent : Theme.line
        Behavior on color { ColorAnimation { duration: 140 } }
        Behavior on border.color { ColorAnimation { duration: 140 } }
    }
    delegate: ItemDelegate {
        width: control.popup.width - 12
        height: 38
        highlighted: control.highlightedIndex === index
        contentItem: Text {
            text: modelData
            color: control.currentIndex === index ? Theme.accent : Theme.fg
            font.pixelSize: 12
            font.bold: control.currentIndex === index
            leftPadding: 6
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        background: Rectangle {
            radius: 10
            color: parent.highlighted ? Theme.cardHover : "transparent"
        }
        ToolTip.visible: hovered && contentItem.truncated
        ToolTip.text: modelData
    }
}
