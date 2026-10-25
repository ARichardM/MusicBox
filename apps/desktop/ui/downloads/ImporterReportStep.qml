import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Paso 4: informe de lo que falta, los motivos y las entradas no disponibles de la playlist comprobada.
Item {
    id: step
    readonly property var imp: downloads.importer
    signal requestStep(string key)

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: qsTr("Aquí se muestran todas las canciones pendientes, los motivos y las entradas no disponibles de la playlist comprobada.")
            color: Theme.muted
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }
        AreaField {
            Layout.fillWidth: true
            Layout.fillHeight: true
            readOnly: true
            mono: true
            pixelSize: 12
            text: step.imp.reportText
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            PillButton { text: qsTr("Ver coincidencias"); onClicked: step.requestStep("matches") }
            PillButton { text: qsTr("Copiar informe"); iconName: "copy"; onClicked: step.imp.copyReport() }
            PillButton { text: qsTr("Guardar informe…"); iconName: "save"; onClicked: step.imp.saveReport() }
            Item { Layout.fillWidth: true }
            PillButton {
                text: qsTr("Reintentar pendientes")
                variant: "primary"
                iconName: "retry"
                enabled: step.imp.canRetry
                busy: step.imp.busy && step.imp.task === "search"
                onClicked: step.imp.retryPending()
            }
        }
    }
}
