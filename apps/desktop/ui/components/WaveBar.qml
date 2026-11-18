import QtQuick

// Barra de progreso con relieve de volumen y mezcla de crossfade (equivalente de WaveSeekBar en Android y de mixbar.js en la web).
Item {
    id: root
    property var wave: ({})
    property var nextWave: ({})
    property real position: 0          // ms
    property real duration: 1          // ms
    property real zoneMs: 0
    property real endMs: 0
    property bool mixing: false
    property bool playing: false
    property bool active: false
    property string seed: ""
    property color ink: "#FFFFFF"
    property color accent: "#FF9F43"

    readonly property int cols: 120
    readonly property int fineSeconds: 16
    property var display: []
    property var fromValues: []
    property var toValues: []
    property real morph: 1
    property real realness: 1
    property real realFrom: 1
    property real realTo: 1
    property real waveAlpha: 0
    property real zoneShown: 0
    property real zoom: mixing && zoneMs > 0 ? 1 : 0
    property real anchorPosition: 0
    property real anchorTime: Date.now()
    property bool hasReal: !!(wave && wave.coarse)
    // Tramo visible de la pista (0..1): toda la barra, o el tramo de la mezcla cuando el zoom está activo.
    property real viewStart: 0
    property real viewEnd: 1
    property var mixFraming: null

    function updateView() {
        var dur = Math.max(1, duration)
        var endFraction = endMs > 0 ? Math.min(1, endMs / dur) : 1
        var zoneTarget = zoneMs > 0 ? Math.min(1, zoneMs / dur) : 0
        var zoomStart = Math.max(0, endFraction - zoneTarget * 2.2), zoomEnd = Math.min(1, endFraction + Math.max(.01, zoneTarget * .15))
        // Al terminar la mezcla el zoom tarda en salir: se conserva el encuadre de la mezcla en vez de recalcularlo con la
        // canción nueva, para que la ventana se abra suave y no dé un salto.
        if (mixing) mixFraming = { start: zoomStart, end: zoomEnd }
        else if (zoom <= .001) mixFraming = null
        else if (mixFraming) { zoomStart = mixFraming.start; zoomEnd = mixFraming.end }
        var e = zoom < .5 ? 2 * zoom * zoom : 1 - Math.pow(-2 * zoom + 2, 2) / 2
        viewStart = e * zoomStart
        viewEnd = 1 + (zoomEnd - 1) * e
    }

    function approximate(key) {
        var s = 17, i
        for (i = 0; i < key.length; i++) s = (s * 31 + key.charCodeAt(i)) | 0
        var rnd = function() { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return ((s >>> 0) % 100000) / 100000 }
        var a = .09 + rnd() * .07, b = .31 + rnd() * .2, c = .017 + rnd() * .02
        var pa = rnd() * 6.28, pb = rnd() * 6.28, pc = rnd() * 6.28, out = []
        for (i = 0; i < cols; i++) {
            var slow = .5 + .5 * Math.sin(a * i + pa), mid = .5 + .5 * Math.sin(b * i + pb), section = .5 + .5 * Math.sin(c * i + pc)
            out.push(Math.max(.16, Math.min(.88, .22 + .42 * slow * (.55 + .45 * mid) + .16 * section + .12 * rnd())))
        }
        for (i = 0; i < 4; i++) { var k = (i + 1) / 5; out[i] *= k; out[cols - 1 - i] *= k }
        return out
    }

    function retarget() {
        fromValues = display.slice()
        toValues = hasReal ? wave.coarse.slice() : approximate(seed)
        realFrom = realness
        realTo = hasReal ? 1 : 0
        morph = 0
    }
    onSeedChanged: retarget()
    onHasRealChanged: retarget()
    Component.onCompleted: {
        display = approximate(seed); retarget(); display = toValues.slice(); morph = 1; realness = hasReal ? 1 : 0
        waveAlpha = active ? 1 : 0
        zoneShown = duration > 0 ? Math.min(1, zoneMs / duration) : 0
    }

    onPositionChanged: { anchorPosition = position; anchorTime = Date.now() }

    Behavior on waveAlpha { NumberAnimation { duration: 460; easing.type: Easing.OutCubic } }
    Behavior on zoneShown { NumberAnimation { duration: 560; easing.type: Easing.OutCubic } }
    Behavior on zoom { NumberAnimation { duration: root.mixing ? 720 : 880; easing.type: Easing.InOutQuad } }
    onActiveChanged: waveAlpha = active ? 1 : 0
    onZoneMsChanged: zoneShown = duration > 0 ? Math.min(1, zoneMs / duration) : 0
    onDurationChanged: zoneShown = duration > 0 ? Math.min(1, zoneMs / duration) : 0

    FrameAnimation {
        running: root.waveAlpha > .005 || root.active
        onTriggered: {
            if (root.morph < 1) {
                root.morph = Math.min(1, root.morph + frameTime / (root.hasReal ? 1.0 : .9))
                root.realness = root.realFrom + (root.realTo - root.realFrom) * root.morph
                var out = []
                for (var i = 0; i < root.cols; i++) {
                    var local = Math.max(0, Math.min(1, root.morph * 2.2 - (i / root.cols) * 1.2))
                    var eased = 1 - Math.pow(1 - local, 3) + Math.sin(local * Math.PI) * .06
                    out.push(root.fromValues[i] + (root.toValues[i] - root.fromValues[i]) * eased)
                }
                root.display = out
            }
            canvas.requestPaint()
        }
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            if (root.waveAlpha < .005 || width < 2) return
            var dur = Math.max(1, root.duration), progressMs = root.position
            if (root.playing && !root.mixing) progressMs = Math.min(dur, root.anchorPosition + (Date.now() - root.anchorTime))
            var progress = Math.max(0, Math.min(1, progressMs / dur))
            var endFraction = root.endMs > 0 ? Math.min(1, root.endMs / dur) : 1
            var zone = root.zoneShown, zoneTarget = root.zoneMs > 0 ? Math.min(1, root.zoneMs / dur) : 0
            root.updateView()
            var v0 = root.viewStart, v1 = root.viewEnd
            var centre = height / 2, maxHalf = Math.max(2, Math.min(centre - 1, 14))
            var pitch = width / root.cols, barW = Math.max(1.3, pitch * .6)
            var certainty = (.74 + .26 * root.realness) * root.waveAlpha
            var zoneStart = endFraction - zone
            var tail = root.wave && root.wave.tail ? root.wave.tail : null
            var head = root.nextWave && root.nextWave.head ? root.nextWave.head : null
            var inkRgb = Qt.rgba(root.ink.r, root.ink.g, root.ink.b, 1)
            function bar(x, half, w, color) {
                ctx.fillStyle = color
                ctx.beginPath()
                var r = w / 2, left = x - w / 2, top = centre - half, h = half * 2
                ctx.moveTo(left + r, top); ctx.lineTo(left + w - r, top); ctx.arc(left + w - r, top + r, r, -Math.PI / 2, 0)
                ctx.lineTo(left + w, top + h - r); ctx.arc(left + w - r, top + h - r, r, 0, Math.PI / 2)
                ctx.lineTo(left + r, top + h); ctx.arc(left + r, top + h - r, r, Math.PI / 2, Math.PI)
                ctx.lineTo(left, top + r); ctx.arc(left + r, top + r, r, Math.PI, 1.5 * Math.PI); ctx.closePath(); ctx.fill()
            }
            function tailAt(timeMs, end) {
                if (!tail) return -1
                var index = (timeMs - (end - root.fineSeconds * 1000)) / (root.fineSeconds * 1000) * tail.length
                return tail[Math.max(0, Math.min(tail.length - 1, Math.floor(index)))]
            }
            for (var i = 0; i < root.cols; i++) {
                var f = v0 + (i + .5) / root.cols * (v1 - v0), x = pitch * (i + .5), played = f <= progress
                var pos = Math.max(0, Math.min(1, f)) * root.cols - .5
                var i0 = Math.max(0, Math.min(root.cols - 1, Math.floor(pos))), i1 = Math.min(root.cols - 1, i0 + 1), kk = Math.max(0, Math.min(1, pos - Math.floor(pos)))
                var level = root.display[i0] + (root.display[i1] - root.display[i0]) * kk
                if (root.zoom > .01 && tail) {
                    var t0 = tailAt(f * dur, endFraction * dur)
                    if (t0 >= 0 && f * dur >= endFraction * dur - root.fineSeconds * 1000) level += (Math.max(.08, t0) - level) * root.zoom
                }
                var incoming = 0
                if (zone > .001 && f >= zoneStart && f <= endFraction) {
                    var t = Math.max(0, Math.min(1, (f - zoneStart) / Math.max(.0001, zone)))
                    var zoneMs = zone * dur
                    var tv = tailAt(endFraction * dur - zoneMs + t * zoneMs, endFraction * dur)
                    var outgoing = tv >= 0 ? Math.max(.08, tv) : level
                    level = outgoing * Math.cos(t * Math.PI / 2)
                    var source = .5
                    if (head) source = Math.max(.08, head[Math.max(0, Math.min(head.length - 1, Math.floor(t * zoneMs / (root.fineSeconds * 1000) * head.length)))])
                    incoming = source * Math.sin(t * Math.PI / 2)
                }
                bar(x, Math.max(1, level * maxHalf), barW, Qt.rgba(inkRgb.r, inkRgb.g, inkRgb.b, (played ? 1 : .38) * certainty))
                if (incoming > 0) bar(x, Math.max(1, incoming * maxHalf), barW * .56, played ? Qt.rgba(root.accent.r, root.accent.g, root.accent.b, root.waveAlpha) : Qt.rgba(root.accent.r, root.accent.g, root.accent.b, .6 * root.waveAlpha))
            }
        }
    }
}
