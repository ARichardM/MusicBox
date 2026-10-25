import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Panel de descargas completo: enlaces, progreso, archivos, historial, registro e informe,
// más la configuración de la descarga y el acceso al importador y a la búsqueda con portadas.
Item {
    id: page

    // ---- opciones de la descarga ----
    property string format: "MP3"
    property bool advanced: false
    property string resultTab: "files"
    property bool destinationEdited: false
    readonly property bool busy: musicbox.downloadRunning
    readonly property bool audio: format === "MP3"
    readonly property var audioQualities: ["192 kbps", "128 kbps", "256 kbps", "320 kbps"]
    readonly property var videoQualities: ["1080p", "720p", "480p", "360p", qsTr("Mejor disponible")]
    readonly property string badge: musicbox.downloadBadge
    readonly property bool playlistMode: downloads.mode === "playlist"

    function focusLinks() { linksArea.area.forceActiveFocus() }

    function startDownload() {
        if (busy) return
        downloads.start(downloads.links, playlistMode, itemsField.text, format, qualityBox.currentText,
                        destinationField.text, cookiesField.text)
    }

    function openVideoSearch() {
        if (busy) return
        videoDialog.openWith(searchField.text)
    }

    function openVideoSearchWith(query) { videoDialog.openWith(query) }
    function openImporter() { importerDialog.open() }

    Component.onCompleted: {
        destinationField.text = musicbox.defaultDownloadFolder
        downloads.historyRefresh()
    }
    Connections {
        target: musicbox
        function onLibraryFoldersChanged() {
            if (!page.destinationEdited) destinationField.text = musicbox.defaultDownloadFolder
        }
        function onDownloadFilesChanged() { downloads.historyScheduleRefresh() }
    }
    Connections {
        target: downloads
        function onShowTab(key) { page.resultTab = key }
    }

    function badgeText() {
        switch (badge) {
        case "preparing": return qsTr("PREPARANDO")
        case "downloading": return qsTr("DESCARGANDO")
        case "processing": return qsTr("PROCESANDO")
        case "cancelling": return qsTr("CANCELANDO")
        case "cancelled": return qsTr("CANCELADO")
        case "errors": return qsTr("CON ERRORES")
        case "completed": return qsTr("COMPLETADO")
        default: return qsTr("EN ESPERA")
        }
    }
    function badgeTone() {
        switch (badge) {
        case "cancelling": return "warning"
        case "cancelled": return "neutral"
        case "errors": return "danger"
        case "completed": return "success"
        default: return "accent"
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: width < 980 ? 22 : 34
        anchors.bottomMargin: 14
        spacing: 14

        // ---------- cabecera ----------
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            ColumnLayout {
                spacing: 2
                Text { text: qsTr("Descargas"); color: Theme.fg; font.pixelSize: 22; font.bold: true }
                Text { text: qsTr("Audio y video, a tu manera."); color: Theme.muted; font.pixelSize: 12 }
            }
            Item { Layout.fillWidth: true }
            PillButton {
                text: qsTr("Imágenes y nombres…")
                iconName: "image"
                enabled: !page.busy
                onClicked: importerDialog.open()
            }
            Badge { text: "MP3  /  MP4"; tone: "accent" }
        }

        // ---------- búsqueda con portadas ----------
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Field {
                id: searchField
                Layout.fillWidth: true
                iconName: "search"
                placeholderText: qsTr("Buscar videos por título o artista")
                enabled: !page.busy
                onAccepted: page.openVideoSearch()
            }
            PillButton {
                text: qsTr("Buscar con portadas")
                variant: "primary"
                iconName: "search"
                enabled: !page.busy
                onClicked: page.openVideoSearch()
            }
        }

        // ---------- cuerpo ----------
        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 16

            // ===== columna izquierda =====
            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumWidth: 380
                spacing: 14

                // --- 01 enlaces ---
                Card {
                    Layout.fillWidth: true
                    Layout.preferredHeight: linksColumn.implicitHeight + 32
                    ColumnLayout {
                        id: linksColumn
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 10
                        RowLayout {
                            Layout.fillWidth: true
                            SectionLabel { text: qsTr("01   TUS ENLACES"); Layout.fillWidth: true }
                            PillButton {
                                text: qsTr("Limpiar")
                                variant: "link"
                                enabled: !page.busy && downloads.links.length > 0
                                onClicked: downloads.clearLinks()
                            }
                            PillButton {
                                text: qsTr("Pegar enlaces")
                                variant: "link"
                                iconName: "paste"
                                enabled: !page.busy
                                onClicked: downloads.pasteLinks()
                            }
                        }
                        AreaField {
                            id: linksArea
                            Layout.fillWidth: true
                            Layout.preferredHeight: 92
                            enabled: !page.busy
                            placeholderText: qsTr("Pega aquí tus enlaces de YouTube…")
                            onEdited: if (area.text !== downloads.links) downloads.setLinks(area.text)
                            Connections {
                                target: downloads
                                function onLinksChanged() {
                                    if (linksArea.area.text !== downloads.links) linksArea.area.text = downloads.links
                                }
                            }
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            Text { text: downloads.linkSummary; color: Theme.muted; font.pixelSize: 11 }
                            Item { Layout.fillWidth: true }
                            Text { text: qsTr("Un enlace por línea"); color: Theme.muted; font.pixelSize: 11 }
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            visible: downloads.inputError !== ""
                            spacing: 8
                            Icon { name: "warning"; size: 16; tint: Theme.danger }
                            Text {
                                Layout.fillWidth: true
                                text: downloads.inputError
                                color: Theme.danger
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                            }
                        }
                    }
                }

                // --- 02 descarga ---
                Card {
                    Layout.fillWidth: true
                    Layout.preferredHeight: progressColumn.implicitHeight + 32
                    ColumnLayout {
                        id: progressColumn
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 12
                        RowLayout {
                            Layout.fillWidth: true
                            SectionLabel { text: qsTr("02   TU DESCARGA"); Layout.fillWidth: true }
                            Badge { text: page.badgeText(); tone: page.badgeTone() }
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 14

                            // Anillo de estado: avance real mientras descarga, ícono el resto del tiempo.
                            Item {
                                Layout.preferredWidth: 58
                                Layout.preferredHeight: 58
                                Spinner {
                                    anchors.fill: parent
                                    size: 58
                                    thickness: 5
                                    visible: page.busy
                                    showLabel: true
                                    progress: musicbox.downloadIndeterminate ? -1 : musicbox.downloadProgress
                                }
                                Rectangle {
                                    anchors.fill: parent
                                    radius: width / 2
                                    visible: !page.busy
                                    color: page.badge === "completed" ? Theme.successSoft
                                         : page.badge === "errors" ? Theme.dangerSoft
                                         : page.badge === "cancelled" ? Theme.chip : Theme.accentSoft
                                    Behavior on color { ColorAnimation { duration: 220 } }
                                    Icon {
                                        anchors.centerIn: parent
                                        size: 24
                                        name: page.badge === "completed" ? "check"
                                            : page.badge === "errors" ? "warning"
                                            : page.badge === "cancelled" ? "stop" : "download"
                                        tint: page.badge === "completed" ? Theme.success
                                            : page.badge === "errors" ? Theme.danger
                                            : page.badge === "cancelled" ? Theme.muted : Theme.accent
                                    }
                                }
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 4
                                Text {
                                    Layout.fillWidth: true
                                    text: musicbox.downloadStatus
                                    color: Theme.fg
                                    font.pixelSize: 14
                                    font.bold: true
                                    wrapMode: Text.WordWrap
                                    maximumLineCount: 2
                                    elide: Text.ElideRight
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: musicbox.downloadDetail
                                    color: Theme.muted
                                    font.pixelSize: 12
                                    wrapMode: Text.WordWrap
                                }
                            }
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            SmoothProgress {
                                Layout.fillWidth: true
                                indeterminate: page.busy && musicbox.downloadIndeterminate
                                value: page.badge === "errors" ? 1 : musicbox.downloadProgress
                                fillColor: page.badge === "errors" ? Theme.danger
                                         : page.badge === "completed" ? Theme.success : Theme.accent
                                fillEnd: page.badge === "errors" ? Theme.danger
                                       : page.badge === "completed" ? Theme.success : Theme.cyan
                            }
                            Text {
                                Layout.preferredWidth: 44
                                horizontalAlignment: Text.AlignRight
                                text: musicbox.downloadPercentText
                                color: Theme.accent
                                font.pixelSize: 12
                                font.bold: true
                            }
                        }
                        Text {
                            text: qsTr("Archivos listos: %1 · errores: %2")
                                  .arg(musicbox.downloadCompleted).arg(musicbox.downloadErrorCount)
                            color: Theme.muted
                            font.pixelSize: 11
                        }
                    }
                }

                // --- resultados: archivos, historial, registro, informe ---
                Card {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.minimumHeight: 190
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 14
                        spacing: 10
                        TabStrip {
                            Layout.fillWidth: true
                            tabs: [
                                { key: "files", text: qsTr("Archivos") + (musicbox.downloadFiles.length > 0 ? " (" + musicbox.downloadFiles.length + ")" : "") },
                                { key: "history", text: qsTr("Historial") },
                                { key: "log", text: qsTr("Registro") },
                                { key: "report", text: qsTr("Informe de canciones") }
                            ]
                            current: page.resultTab
                            onPicked: (key) => page.resultTab = key
                        }
                        StackLayout {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            currentIndex: ["files", "history", "log", "report"].indexOf(page.resultTab)

                            FilesList {}
                            HistoryPanel {}
                            LogView {}
                            ReportView {}
                        }
                    }
                }
            }

            // ===== columna derecha: configuración =====
            Card {
                Layout.preferredWidth: 318
                Layout.fillHeight: true
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    ScrollView {
                        id: settingsScroll
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        contentWidth: availableWidth
                        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

                        ColumnLayout {
                            width: settingsScroll.availableWidth
                            spacing: 12

                            SectionLabel { text: qsTr("CONFIGURA TU DESCARGA") }
                            Text { text: qsTr("¿Qué quieres guardar?"); color: Theme.fg; font.pixelSize: 14; font.bold: true }
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 10
                                FormatCard {
                                    Layout.fillWidth: true
                                    title: "MP3"
                                    detail: qsTr("Solo audio")
                                    iconName: "music"
                                    selected: page.audio
                                    enabled: !page.busy
                                    onClicked: page.format = "MP3"
                                }
                                FormatCard {
                                    Layout.fillWidth: true
                                    title: "MP4"
                                    detail: qsTr("Video + audio")
                                    iconName: "playlist"
                                    selected: !page.audio
                                    enabled: !page.busy
                                    onClicked: page.format = "MP4"
                                }
                            }

                            Text { text: qsTr("Calidad"); color: Theme.muted; font.pixelSize: 12 }
                            Combo {
                                id: qualityBox
                                Layout.fillWidth: true
                                enabled: !page.busy
                                model: page.audio ? page.audioQualities : page.videoQualities
                            }
                            Text {
                                Layout.fillWidth: true
                                text: page.audio ? qsTr("Incluye portada y metadatos disponibles.")
                                                 : qsTr("Resolución máxima, según disponibilidad.")
                                color: Theme.muted
                                font.pixelSize: 11
                                wrapMode: Text.WordWrap
                            }

                            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line; opacity: 0.6 }

                            Text { text: qsTr("Cantidad"); color: Theme.muted; font.pixelSize: 12 }
                            Choice {
                                Layout.fillWidth: true
                                title: qsTr("Un video por enlace")
                                selected: !page.playlistMode
                                enabled: !page.busy
                                onClicked: downloads.setMode("single")
                            }
                            Choice {
                                Layout.fillWidth: true
                                title: qsTr("Playlist completa o un rango")
                                selected: page.playlistMode
                                enabled: !page.busy
                                onClicked: downloads.setMode("playlist")
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 6
                                visible: page.playlistMode
                                Field {
                                    id: itemsField
                                    Layout.fillWidth: true
                                    enabled: !page.busy
                                    placeholderText: qsTr("Rango: 1-20,25")
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: qsTr("Vacío: toda la lista · Ej. 1-10 o 1,3,5-8")
                                    color: Theme.muted
                                    font.pixelSize: 11
                                    wrapMode: Text.WordWrap
                                }
                            }

                            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line; opacity: 0.6 }

                            RowLayout {
                                Layout.fillWidth: true
                                Text { Layout.fillWidth: true; text: qsTr("Carpeta de destino"); color: Theme.muted; font.pixelSize: 12 }
                                PillButton {
                                    text: qsTr("Cambiar")
                                    variant: "link"
                                    iconName: "folder"
                                    enabled: !page.busy
                                    onClicked: {
                                        var chosen = musicbox.chooseDownloadFolder()
                                        if (chosen) { destinationField.text = chosen; page.destinationEdited = true }
                                    }
                                }
                            }
                            Field {
                                id: destinationField
                                Layout.fillWidth: true
                                enabled: !page.busy
                                onTextEdited: page.destinationEdited = true
                            }

                            PillButton {
                                Layout.alignment: Qt.AlignLeft
                                text: page.advanced ? qsTr("− Opciones avanzadas") : qsTr("+ Opciones avanzadas")
                                variant: "link"
                                onClicked: page.advanced = !page.advanced
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 6
                                visible: page.advanced
                                Text { text: qsTr("Cookies opcionales (.txt)"); color: Theme.muted; font.pixelSize: 11 }
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 8
                                    Field {
                                        id: cookiesField
                                        Layout.fillWidth: true
                                        iconName: "cookie"
                                        enabled: !page.busy
                                        placeholderText: qsTr("Ruta del archivo de cookies")
                                        onTextChanged: downloads.setCookies(text)
                                    }
                                    PillButton {
                                        text: qsTr("Elegir")
                                        enabled: !page.busy
                                        onClicked: {
                                            var chosen = musicbox.chooseCookieFile()
                                            if (chosen) cookiesField.text = chosen
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // --- acciones fijas ---
                    PillButton {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 48
                        text: page.busy ? qsTr("Descargando…") : qsTr("Descargar")
                        variant: "primary"
                        iconName: "download"
                        busy: page.busy
                        busyProgress: musicbox.downloadIndeterminate ? -1 : musicbox.downloadProgress
                        enabled: !page.busy
                        onClicked: page.startDownload()
                    }
                    PillButton {
                        Layout.fillWidth: true
                        visible: page.busy
                        text: musicbox.downloadBadge === "cancelling" ? qsTr("Cancelando…") : qsTr("Cancelar descarga")
                        variant: "danger"
                        enabled: musicbox.downloadBadge !== "cancelling"
                        onClicked: musicbox.cancelDownload()
                    }
                    PillButton {
                        Layout.alignment: Qt.AlignHCenter
                        text: qsTr("Abrir carpeta de destino")
                        variant: "link"
                        iconName: "folder"
                        onClicked: musicbox.openFolder(destinationField.text)
                    }
                }
            }
        }

        // ---------- pie ----------
        RowLayout {
            Layout.fillWidth: true
            Text {
                text: page.format + " · " + qualityBox.currentText + " · "
                      + (page.playlistMode ? qsTr("Playlist") : qsTr("Videos individuales"))
                color: Theme.muted
                font.pixelSize: 11
            }
            Item { Layout.fillWidth: true }
            Text {
                text: qsTr("Ctrl + Enter  Descargar     Ctrl + L  Enlaces")
                color: Theme.muted
                font.pixelSize: 11
            }
        }
    }

    // ---------- diálogos ----------
    VideoSearchDialog { id: videoDialog }
    ImporterDialog { id: importerDialog }
}
