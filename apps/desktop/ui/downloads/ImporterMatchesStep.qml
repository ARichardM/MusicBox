import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Paso 3: por cada canción buscada, las alternativas de YouTube. Solo se marcan solas las coincidencias verificables.
Item {
    id: step
    readonly property var imp: downloads.importer
    readonly property var preview: downloads.preview
    readonly property bool idle: !imp.busy
    property string selectedKey: ""
    property string selectedTitle: ""
    property string selectedUrl: ""
    signal requestStep(string key)

    function pick(key, title, url) { selectedKey = key; selectedTitle = title; selectedUrl = url }

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: qsTr("Solo se marcan automáticamente coincidencias verificables. Las dudosas quedan para revisión; puedes elegirlas con doble clic y escucharlas con ▶.")
            color: Theme.muted
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Field {
                Layout.fillWidth: true
                implicitHeight: 38
                iconName: "filter"
                text: step.imp.resultFilter
                placeholderText: qsTr("Buscar en la lista")
                onTextEdited: step.imp.setResultFilter(text)
            }
            PillButton {
                text: qsTr("Limpiar")
                variant: "ghost"
                implicitHeight: 36
                enabled: step.imp.resultFilter !== ""
                onClicked: step.imp.setResultFilter("")
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            radius: 18
            color: Theme.field
            border.width: 1
            border.color: Theme.line
            clip: true

            ListView {
                id: groups
                anchors.fill: parent
                anchors.margins: 8
                model: step.imp.groupModel
                spacing: 12
                clip: true
                rightMargin: 6
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                add: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 220 } }

                delegate: Column {
                    id: group
                    readonly property var groupCandidates: model.candidates
                    width: ListView.view.width - 8
                    spacing: 4

                    // cabecera de la consulta
                    RowLayout {
                        width: parent.width
                        spacing: 8
                        Icon { name: model.failed ? "warning" : "search"; size: 14; tint: model.failed ? Theme.danger : Theme.accent }
                        Text {
                            Layout.fillWidth: true
                            text: model.query
                            color: Theme.accent
                            font.pixelSize: 13
                            font.bold: true
                            elide: Text.ElideRight
                        }
                        Text {
                            text: qsTr("%1 resultados").arg(model.candidates.length)
                            visible: model.candidates.length > 0
                            color: Theme.muted
                            font.pixelSize: 11
                        }
                    }
                    Text {
                        width: parent.width
                        visible: model.message !== ""
                        text: model.message
                        color: model.failed ? Theme.danger : Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        leftPadding: 22
                    }

                    Repeater {
                        model: group.groupCandidates
                        delegate: Rectangle {
                            id: candidate
                            width: group.width
                            height: 62
                            radius: 14
                            readonly property bool chosen: modelData.chosen
                            color: step.selectedKey === modelData.key ? Theme.accentSoft
                                 : (candHover.containsMouse ? Theme.cardHover : "transparent")
                            border.width: chosen ? 1 : 0
                            border.color: Theme.success
                            Behavior on color { ColorAnimation { duration: 110 } }
                            MouseArea {
                                id: candHover
                                anchors.fill: parent
                                hoverEnabled: true
                                onClicked: step.pick(modelData.key, modelData.title, modelData.url)
                                onDoubleClicked: if (step.idle) step.imp.choose(modelData.key)
                            }
                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                spacing: 12
                                Check {
                                    marked: modelData.chosen
                                    enabled: step.idle
                                    onClicked: step.imp.choose(modelData.key)
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Text { Layout.fillWidth: true; text: modelData.title; color: Theme.fg; font.pixelSize: 12; font.bold: modelData.chosen; elide: Text.ElideRight }
                                    Text { text: modelData.channel + "  ·  " + modelData.durationText; color: Theme.muted; font.pixelSize: 11 }
                                }
                                Text {
                                    Layout.preferredWidth: 118
                                    horizontalAlignment: Text.AlignRight
                                    text: modelData.label
                                    color: modelData.chosen ? Theme.success : Theme.warning
                                    font.pixelSize: 11
                                    font.bold: true
                                    elide: Text.ElideRight
                                }
                                PreviewBar {
                                    Layout.preferredWidth: step.preview.activeKey === "m:" + modelData.key ? 250 : 40
                                    compact: true
                                    key: "m:" + modelData.key
                                    url: modelData.url
                                    title: modelData.title
                                    Behavior on Layout.preferredWidth { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                                }
                            }
                        }
                    }
                }
            }

            Spinner {
                anchors.centerIn: parent
                size: 46
                visible: step.imp.busy && step.imp.task === "search" && groups.count === 0
            }
            EmptyState {
                anchors.centerIn: parent
                visible: !(step.imp.busy && step.imp.task === "search") && groups.count === 0
                iconName: "search"
                title: qsTr("Aún no hay coincidencias")
                detail: qsTr("Marca canciones en la tabla y pulsa «Buscar canciones marcadas».")
            }
        }

        Text {
            Layout.fillWidth: true
            text: step.preview.statusText !== "" ? step.preview.statusText
                  : qsTr("Selecciona un resultado y pulsa Reproducir para escucharlo.")
            color: step.preview.state === "error" ? Theme.danger : Theme.muted
            font.pixelSize: 11
            elide: Text.ElideRight
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            PillButton { text: qsTr("← Tabla"); variant: "link"; onClicked: step.requestStep("table") }
            PillButton {
                text: qsTr("▶ Reproducir")
                iconName: "play"
                enabled: step.selectedKey !== ""
                onClicked: step.preview.play("m:" + step.selectedKey, step.selectedUrl, step.selectedTitle)
            }
            PillButton {
                text: qsTr("Detener")
                iconName: "stop"
                enabled: step.preview.state !== "idle"
                onClicked: step.preview.stop()
            }
            Item { Layout.fillWidth: true }
            PillButton {
                text: qsTr("Ver en YouTube")
                iconName: "external"
                enabled: step.selectedKey !== ""
                onClicked: step.imp.viewResult(step.selectedKey)
            }
            PillButton {
                text: qsTr("Elegir / quitar resultado")
                variant: "primary"
                enabled: step.idle && step.selectedKey !== ""
                onClicked: step.imp.choose(step.selectedKey)
            }
        }
    }
}
