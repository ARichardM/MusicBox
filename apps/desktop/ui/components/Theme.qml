pragma Singleton
import QtQuick

// Paleta compartida: se adapta sola al tema claro/oscuro de la aplicación.
QtObject {
    readonly property bool dark: musicbox.darkMode
    readonly property color bg: dark ? "#000000" : "#F3F5F8"
    readonly property color panel: dark ? "#161618" : "#FFFFFF"
    readonly property color card: dark ? "#1C1C1E" : "#E7EBF1"
    readonly property color cardHover: dark ? "#25282E" : "#DDE3EC"
    readonly property color chip: dark ? "#2A2C33" : "#D9DFE8"
    readonly property color field: dark ? "#101012" : "#FFFFFF"
    readonly property color fg: dark ? "#FFFFFF" : "#111318"
    readonly property color muted: dark ? "#8E8E93" : "#667085"
    readonly property color accent: dark ? "#0A84FF" : "#006FE6"
    readonly property color accentSoft: dark ? "#142840" : "#E2EEFF"
    readonly property color line: dark ? "#303137" : "#CBD2DC"
    readonly property color cyan: "#50E9FF"
    readonly property color danger: "#FF453A"
    readonly property color dangerSoft: dark ? "#2A1517" : "#FFE9E7"
    readonly property color success: "#30D158"
    readonly property color successSoft: dark ? "#10261A" : "#E3F7EA"
    readonly property color warning: dark ? "#FF9F0A" : "#B86E00"
    readonly property color warningSoft: dark ? "#2B2010" : "#FFF1D6"
}
