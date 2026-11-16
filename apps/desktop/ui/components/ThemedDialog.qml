import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

Dialog {
    id: root
    property Item backdropSource: null
    Material.theme: Theme.dark ? Material.Dark : Material.Light
    Material.accent: Theme.accent
    Material.foreground: Theme.fg
    Material.background: Theme.panel
    background: Rectangle { radius: 22; color: Theme.panel; border.width: 1; border.color: Theme.line }
    header: Label {
        visible: text.length > 0
        text: root.title
        padding: 22
        bottomPadding: 14
        color: Theme.fg
        font.pixelSize: 20
        font.bold: true
        background: Item {}
    }
    footer: DialogButtonBox {
        visible: root.standardButtons !== Dialog.NoButton
        standardButtons: root.standardButtons
        background: Item {}
        onAccepted: root.accept()
        onRejected: root.reject()
    }
    Overlay.modal: ModalBackdrop { sourceItem: root.backdropSource }
}
