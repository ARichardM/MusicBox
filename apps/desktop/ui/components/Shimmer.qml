import QtQuick

// Marcador de posición animado mientras algo carga (portadas, listas).
Item {
    id: root
    property real cornerRadius: 12
    clip: true
    Rectangle {
        anchors.fill: parent
        radius: root.cornerRadius
        color: Theme.dark ? "#24262B" : "#E2E5EA"
    }
    Rectangle {
        id: wave
        width: Math.max(60, parent.width * 0.5)
        height: parent.height
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: "#00FFFFFF" }
            GradientStop { position: 0.5; color: Theme.dark ? "#553A3D44" : "#AAF7F8FA" }
            GradientStop { position: 1; color: "#00FFFFFF" }
        }
        NumberAnimation on x {
            from: -wave.width; to: root.width
            duration: 1150; loops: Animation.Infinite
            running: root.visible
        }
    }
}
