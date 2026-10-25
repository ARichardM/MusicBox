import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Paso 1: adjuntar imágenes, recortarlas, leerlas con OCR y editar el texto resultante.
Item {
    id: step
    readonly property var imp: downloads.importer
    readonly property bool idle: !imp.busy
    property string textTab: "organized"
    // recorte seleccionado, en píxeles del visor
    property point cropStart: Qt.point(0, 0)
    property point cropEnd: Qt.point(0, 0)
    property bool cropping: false
    readonly property bool hasCrop: Math.abs(cropEnd.x - cropStart.x) > 6 && Math.abs(cropEnd.y - cropStart.y) > 6
    signal requestStep(string key)

    function clearCrop() { cropStart = Qt.point(0, 0); cropEnd = Qt.point(0, 0); cropping = false }
    Connections { target: step.imp; function onImagesChanged() { step.clearCrop() } }
    Keys.onEscapePressed: clearCrop()

    RowLayout {
        anchors.fill: parent
        spacing: 16

        // ---------- imágenes adjuntas ----------
        ColumnLayout {
            Layout.preferredWidth: 200
            Layout.fillHeight: true
            spacing: 8
            SectionLabel { text: qsTr("IMÁGENES ADJUNTAS") }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Adjuntar imágenes…")
                iconName: "image"
                variant: "primary"
                enabled: step.idle
                onClicked: step.imp.attachFiles()
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Pegar imagen")
                iconName: "paste"
                enabled: step.idle
                busy: step.imp.busy && step.imp.task === "load"
                onClicked: step.imp.pasteImage()
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 16
                color: Theme.field
                border.width: 1
                border.color: Theme.line
                ListView {
                    id: attachments
                    anchors.fill: parent
                    anchors.margins: 6
                    model: step.imp.attachments
                    clip: true
                    spacing: 4
                    delegate: Rectangle {
                        width: ListView.view.width
                        height: 36
                        radius: 10
                        color: index === step.imp.activeIndex ? Theme.accentSoft : (hover.containsMouse ? Theme.cardHover : "transparent")
                        Behavior on color { ColorAnimation { duration: 110 } }
                        MouseArea { id: hover; anchors.fill: parent; hoverEnabled: true; enabled: step.idle; onClicked: step.imp.selectImage(index) }
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 10
                            spacing: 8
                            Icon { name: "image"; size: 14; tint: index === step.imp.activeIndex ? Theme.accent : Theme.muted }
                            Text {
                                Layout.fillWidth: true
                                text: modelData.name
                                color: Theme.fg
                                font.pixelSize: 12
                                font.bold: index === step.imp.activeIndex
                                elide: Text.ElideMiddle
                            }
                        }
                    }
                }
                Text {
                    anchors.centerIn: parent
                    width: parent.width - 24
                    visible: attachments.count === 0
                    text: qsTr("Sin imágenes")
                    color: Theme.muted
                    font.pixelSize: 12
                    horizontalAlignment: Text.AlignHCenter
                }
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Quitar imagen")
                iconName: "trash"
                enabled: step.idle && step.imp.activeIndex >= 0
                onClicked: step.imp.removeImage()
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Quitar todas")
                variant: "link"
                enabled: step.idle && attachments.count > 0
                onClicked: step.imp.clearImages()
            }
            Text {
                Layout.fillWidth: true
                text: qsTr("Los originales se conservan.")
                color: Theme.muted
                font.pixelSize: 11
                wrapMode: Text.WordWrap
            }
        }

        // ---------- vista previa y recorte ----------
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumWidth: 260
            spacing: 8
            SectionLabel { text: qsTr("VISTA PREVIA") }
            Rectangle {
                id: viewer
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 18
                color: Theme.field
                border.width: dropArea.containsDrag ? 2 : 1
                border.color: dropArea.containsDrag ? Theme.accent : Theme.line
                clip: true
                Behavior on border.color { ColorAnimation { duration: 120 } }

                Image {
                    id: preview
                    anchors.fill: parent
                    anchors.margins: 8
                    visible: step.imp.activeIndex >= 0
                    source: step.imp.activeIndex >= 0 ? "image://importer/" + step.imp.imageVersion : ""
                    cache: false
                    asynchronous: true
                    fillMode: Image.PreserveAspectFit
                    opacity: status === Image.Ready ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 200 } }
                    readonly property real offsetX: x + (width - paintedWidth) / 2
                    readonly property real offsetY: y + (height - paintedHeight) / 2
                }
                Spinner {
                    anchors.centerIn: parent
                    size: 40
                    visible: (step.imp.busy && step.imp.task === "load") || preview.status === Image.Loading
                }
                EmptyState {
                    anchors.centerIn: parent
                    visible: step.imp.activeIndex < 0 && !(step.imp.busy && step.imp.task === "load")
                    iconName: "image"
                    title: qsTr("Adjunta o pega una imagen")
                    detail: qsTr("También puedes arrastrarla hasta aquí.")
                }

                // rectángulo de recorte
                Rectangle {
                    visible: step.hasCrop
                    x: Math.min(step.cropStart.x, step.cropEnd.x)
                    y: Math.min(step.cropStart.y, step.cropEnd.y)
                    width: Math.abs(step.cropEnd.x - step.cropStart.x)
                    height: Math.abs(step.cropEnd.y - step.cropStart.y)
                    color: Qt.rgba(0.04, 0.52, 1, 0.14)
                    border.width: 2
                    border.color: Theme.accent
                    radius: 3
                }

                MouseArea {
                    anchors.fill: parent
                    enabled: step.idle && step.imp.activeIndex >= 0 && preview.status === Image.Ready
                    cursorShape: Qt.CrossCursor
                    function clampX(v) { return Math.max(preview.offsetX, Math.min(preview.offsetX + preview.paintedWidth, v)) }
                    function clampY(v) { return Math.max(preview.offsetY, Math.min(preview.offsetY + preview.paintedHeight, v)) }
                    onPressed: (mouse) => {
                        step.forceActiveFocus()
                        step.cropStart = Qt.point(clampX(mouse.x), clampY(mouse.y))
                        step.cropEnd = step.cropStart
                        step.cropping = true
                    }
                    onPositionChanged: (mouse) => { if (step.cropping) step.cropEnd = Qt.point(clampX(mouse.x), clampY(mouse.y)) }
                    onReleased: step.cropping = false
                }

                DropArea {
                    id: dropArea
                    anchors.fill: parent
                    keys: ["text/uri-list"]
                    onDropped: (drop) => {
                        if (drop.hasUrls && step.idle) {
                            var paths = []
                            for (var i = 0; i < drop.urls.length; i++) paths.push(drop.urls[i].toString())
                            step.imp.attachPaths(paths)
                        }
                    }
                }
            }
            Text { text: step.imp.imageInfo; color: Theme.muted; font.pixelSize: 11 }
            Text {
                Layout.fillWidth: true
                text: qsTr("Arrastra sobre la imagen para marcar un recorte.")
                color: Theme.muted
                font.pixelSize: 11
                wrapMode: Text.WordWrap
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                PillButton {
                    text: qsTr("Recortar selección")
                    iconName: "crop"
                    enabled: step.idle && step.hasCrop
                    onClicked: {
                        var w = preview.paintedWidth, h = preview.paintedHeight
                        step.imp.crop((step.cropStart.x - preview.offsetX) / w, (step.cropStart.y - preview.offsetY) / h,
                                      (step.cropEnd.x - preview.offsetX) / w, (step.cropEnd.y - preview.offsetY) / h)
                    }
                }
                Item { Layout.fillWidth: true }
                PillButton {
                    text: qsTr("Restaurar")
                    variant: "link"
                    iconName: "retry"
                    enabled: step.idle && step.imp.activeIndex >= 0
                    onClicked: step.imp.restoreImage()
                }
            }
        }

        // ---------- texto ----------
        ColumnLayout {
            Layout.preferredWidth: 300
            Layout.fillHeight: true
            spacing: 8
            SectionLabel { text: qsTr("TEXTO DE LA IMAGEN") }
            Text {
                Layout.fillWidth: true
                text: qsTr("Pega nombres por línea o una lista con título, artista, álbum y duración.")
                color: Theme.muted
                font.pixelSize: 11
                wrapMode: Text.WordWrap
            }
            TabStrip {
                Layout.fillWidth: true
                equalWidth: true
                height: 34
                tabs: [{ key: "organized", text: qsTr("Texto organizado") }, { key: "raw", text: qsTr("OCR original") }]
                current: step.textTab
                onPicked: (key) => step.textTab = key
            }
            StackLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                currentIndex: step.textTab === "organized" ? 0 : 1
                AreaField {
                    id: namesArea
                    enabled: step.idle
                    placeholderText: qsTr("Un nombre por línea, por ejemplo:\nSoda Stereo - De música ligera")
                    onEdited: if (area.text !== step.imp.names) step.imp.setNames(area.text)
                    Connections {
                        target: step.imp
                        function onNamesChanged() { if (namesArea.area.text !== step.imp.names) namesArea.area.text = step.imp.names }
                    }
                }
                AreaField {
                    readOnly: true
                    text: step.imp.rawText
                    pixelSize: 12
                    placeholderText: qsTr("Aquí aparece el texto tal como lo leyó el OCR.")
                }
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Limpiar texto de lista")
                variant: "link"
                enabled: step.idle
                onClicked: step.imp.cleanText()
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Leer imágenes → texto")
                iconName: "scan"
                enabled: step.idle && step.imp.attachments.length > 0
                busy: step.imp.busy && step.imp.task === "ocr"
                busyProgress: step.imp.progress
                onClicked: step.imp.startOcr()
            }
            PillButton {
                Layout.fillWidth: true
                text: qsTr("Revisar tabla →")
                variant: "primary"
                enabled: step.idle
                onClicked: step.imp.reviewTable()
            }
        }
    }
}
