import QtQuick

// Anillo de carga. Con `progress` entre 0 y 1 muestra el avance real (y opcionalmente el porcentaje);
// con -1 gira mientras su arco se estira y se encoge.
Item {
    id: root
    property real progress: -1
    property int size: 28
    property real thickness: Math.max(2, size / 11)
    property bool running: true
    property bool showLabel: false
    property color labelColor: Theme.fg
    property color accentColor: Theme.accent
    property color headColor: Theme.cyan
    readonly property bool determinate: progress >= 0

    implicitWidth: size
    implicitHeight: size
    width: size
    height: size

    property real shown: determinate ? progress : 0
    Behavior on shown { NumberAnimation { duration: 280; easing.type: Easing.OutCubic } }
    property real spin: 0
    property real breathe: 0

    NumberAnimation on spin {
        from: 0; to: 360; duration: 1100; loops: Animation.Infinite
        running: root.running && root.visible && !root.determinate
    }
    SequentialAnimation on breathe {
        loops: Animation.Infinite
        running: root.running && root.visible && !root.determinate
        NumberAnimation { from: 0; to: 1; duration: 760; easing.type: Easing.InOutSine }
        NumberAnimation { from: 1; to: 0; duration: 760; easing.type: Easing.InOutSine }
    }
    onShownChanged: canvas.requestPaint()
    onSpinChanged: canvas.requestPaint()
    onBreatheChanged: canvas.requestPaint()
    onVisibleChanged: canvas.requestPaint()
    Connections { target: Theme; function onDarkChanged() { canvas.requestPaint() } }

    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            var cx = width / 2, cy = height / 2
            var r = (width - root.thickness) / 2
            ctx.lineWidth = root.thickness
            ctx.lineCap = "round"
            ctx.strokeStyle = Qt.rgba(0.55, 0.72, 1, Theme.dark ? 0.16 : 0.28)
            ctx.beginPath()
            ctx.arc(cx, cy, r, 0, Math.PI * 2)
            ctx.stroke()

            var start, sweep
            if (root.determinate) {
                sweep = Math.max(0.001, Math.min(1, root.shown)) * Math.PI * 2
                start = -Math.PI / 2
            } else {
                sweep = (40 + 220 * root.breathe) * Math.PI / 180
                start = root.spin * Math.PI / 180 - sweep
            }
            if (root.determinate && root.shown <= 0.001) return
            var gradient = ctx.createConicalGradient(cx, cy, start)
            var head = Math.min(0.999, sweep / (Math.PI * 2))
            if (root.determinate) {
                gradient.addColorStop(0, root.accentColor)
                gradient.addColorStop(head, root.headColor)
                gradient.addColorStop(1, root.headColor)
            } else {
                gradient.addColorStop(0, Qt.rgba(0.31, 0.91, 1, 0))
                gradient.addColorStop(head, root.headColor)
                gradient.addColorStop(1, root.headColor)
            }
            ctx.strokeStyle = gradient
            ctx.beginPath()
            ctx.arc(cx, cy, r, start, start + sweep)
            ctx.stroke()
        }
    }

    Text {
        visible: root.showLabel && root.determinate
        anchors.centerIn: parent
        text: Math.round(root.shown * 100) + "%"
        color: root.labelColor
        font.pixelSize: Math.max(9, root.size * 0.27)
        font.bold: true
    }
}
