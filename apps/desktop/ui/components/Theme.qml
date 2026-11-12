pragma Singleton
import QtQuick

// Paleta compartida: se adapta sola al tema claro/oscuro de la aplicación.
QtObject {
    function adaptiveColor(value) {
        if (!orange) return value
        var c = Qt.color(value)
        return Qt.hsla(0.075, c.hslSaturation, c.hslLightness, c.a)
    }
    readonly property bool dark: musicbox.darkMode
    readonly property color bg: dark ? adaptiveColor("#000000") : adaptiveColor("#F3F5F8")
    readonly property color panel: dark ? "#161618" : "#FFFFFF"
    readonly property color card: dark ? adaptiveColor("#1C1C1E") : adaptiveColor("#E7EBF1")
    readonly property color cardHover: dark ? adaptiveColor("#25282E") : adaptiveColor("#DDE3EC")
    readonly property color chip: dark ? adaptiveColor("#2A2C33") : adaptiveColor("#D9DFE8")
    readonly property color field: dark ? adaptiveColor("#101012") : adaptiveColor("#FFFFFF")
    readonly property color fg: dark ? adaptiveColor("#FFFFFF") : adaptiveColor("#111318")
    readonly property color muted: dark ? adaptiveColor("#8E8E93") : adaptiveColor("#667085")
    readonly property bool orange: musicbox.accentColor === "orange"
    readonly property color accent: orange ? (dark ? "#FF9F43" : "#B94700") : (dark ? "#0A84FF" : "#006FE6")
    readonly property color accentText: orange && dark ? "#211000" : "#FFFFFF"
    readonly property color accentHover: Qt.lighter(accent, 1.12)
    readonly property color accentPressed: Qt.darker(accent, 1.15)
    readonly property color accentSoft: orange ? (dark ? "#382416" : "#FFF0E5") : (dark ? "#142840" : "#E2EEFF")
    readonly property color line: dark ? adaptiveColor("#303137") : adaptiveColor("#CBD2DC")
    readonly property color cyan: orange ? (dark ? "#FFBD7A" : "#B94700") : "#50E9FF"
    readonly property color danger: "#FF453A"
    readonly property color dangerSoft: dark ? "#2A1517" : "#FFE9E7"
    readonly property color success: "#30D158"
    readonly property color successSoft: dark ? "#10261A" : "#E3F7EA"
    readonly property color warning: dark ? "#FF9F0A" : "#B86E00"
    readonly property color warningSoft: dark ? "#2B2010" : "#FFF1D6"
}
