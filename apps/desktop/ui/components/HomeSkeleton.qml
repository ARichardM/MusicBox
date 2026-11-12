import QtQuick
import QtQuick.Layouts

// Esqueleto del Inicio: las mismas secciones que verá el usuario (Artistas, Para ti, carpetas, recientes y
// más escuchadas) como marcadores animados. Se muestra solo la primera vez, mientras todavía no hay nada.
ColumnLayout {
    id: root
    spacing: 24
    readonly property color card: Theme.dark ? Theme.adaptiveColor("#1B1C20") : "#FFFFFF"

    // Artistas para ti
    ColumnLayout {
        Layout.fillWidth: true
        spacing: 10
        Shimmer { Layout.preferredWidth: 130; Layout.preferredHeight: 18; cornerRadius: 6 }
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 150
            clip: true
            Row {
                spacing: 8
                Repeater {
                    model: 8
                    delegate: Item {
                        width: 124; height: 150
                        Shimmer { x: 14; y: 6; width: 96; height: 96; cornerRadius: 48 }
                        Shimmer { x: 28; y: 116; width: 68; height: 11; cornerRadius: 5 }
                    }
                }
            }
        }
    }

    // Para ti
    ColumnLayout {
        Layout.fillWidth: true
        spacing: 12
        ColumnLayout {
            spacing: 6
            Shimmer { Layout.preferredWidth: 90; Layout.preferredHeight: 18; cornerRadius: 6 }
            Shimmer { Layout.preferredWidth: 170; Layout.preferredHeight: 11; cornerRadius: 5 }
        }
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 226
            clip: true
            Row {
                spacing: 12
                Repeater {
                    model: 8
                    delegate: Rectangle {
                        width: 172; height: 226; radius: 16; color: root.card
                        Shimmer { x: 8; y: 8; width: 156; height: 156; cornerRadius: 12 }
                        Shimmer { x: 12; y: 174; width: 120; height: 13; cornerRadius: 6 }
                        Shimmer { x: 12; y: 196; width: 80; height: 11; cornerRadius: 5 }
                    }
                }
            }
        }
    }

    // Tus carpetas más escuchadas
    ColumnLayout {
        Layout.fillWidth: true
        spacing: 10
        Shimmer { Layout.preferredWidth: 190; Layout.preferredHeight: 18; cornerRadius: 6 }
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 88
            clip: true
            Row {
                spacing: 10
                Repeater {
                    model: 5
                    delegate: Rectangle {
                        width: 210; height: 88; radius: 16; color: root.card
                        Shimmer { x: 10; y: 15; width: 58; height: 58; cornerRadius: 12 }
                        Shimmer { x: 80; y: 22; width: 108; height: 13; cornerRadius: 6 }
                        Shimmer { x: 80; y: 46; width: 70; height: 11; cornerRadius: 5 }
                    }
                }
            }
        }
    }

    // Escuchadas recientemente
    ColumnLayout {
        Layout.fillWidth: true
        spacing: 10
        Shimmer { Layout.preferredWidth: 180; Layout.preferredHeight: 18; cornerRadius: 6 }
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 150
            clip: true
            Row {
                spacing: 12
                Repeater {
                    model: 9
                    delegate: Item {
                        width: 126; height: 150
                        Shimmer { width: 126; height: 112; cornerRadius: 15 }
                        Shimmer { y: 120; width: 96; height: 12; cornerRadius: 6 }
                    }
                }
            }
        }
    }

    // Más escuchadas en tu biblioteca
    ColumnLayout {
        Layout.fillWidth: true
        spacing: 8
        Shimmer { Layout.preferredWidth: 230; Layout.preferredHeight: 18; cornerRadius: 6 }
        Repeater {
            model: 4
            delegate: Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 74
                radius: 16
                color: root.card
                Shimmer { x: 10; y: 11; width: 52; height: 52; cornerRadius: 12 }
                Shimmer { x: 74; y: 16; width: Math.max(120, parent.width * 0.5); height: 15; cornerRadius: 7 }
                Shimmer { x: 74; y: 43; width: Math.max(90, parent.width * 0.3); height: 11; cornerRadius: 6 }
            }
        }
    }
}
