import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Informe de las canciones enviadas desde el importador: duplicados, pendientes y motivos.
Item {
    AreaField {
        anchors.fill: parent
        readOnly: true
        mono: true
        pixelSize: 12
        text: downloads.importReport
    }

    EmptyState {
        anchors.centerIn: parent
        visible: downloads.importReport === ""
        iconName: "scan"
        title: qsTr("Aún no hay informe")
        detail: qsTr("Cuando añadas canciones desde «Imágenes y nombres», aquí verás qué pasó con cada una.")
    }

    PillButton {
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 8
        anchors.rightMargin: 18
        visible: downloads.importReport !== ""
        text: qsTr("Limpiar")
        implicitHeight: 28
        onClicked: downloads.clearReport()
    }
}
