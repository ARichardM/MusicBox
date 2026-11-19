"""Rendered settings geometry at the supported Windows window sizes."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QObject, QSettings, QUrl, QMetaObject, Qt
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP=QApplication.instance() or QApplication([])

def child(item,name):
    if item.objectName()==name:return item
    for candidate in item.childItems():
        result=child(candidate,name)
        if result is not None:return result
    return None

class SettingsLayoutTests(unittest.TestCase):
    def test_account_appearance_playback_and_downloads_do_not_overlap(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            settings=QSettings(str(Path(directory)/"settings.ini"),QSettings.IniFormat)
            settings.setValue("language","es")
            with patch.object(module,"FirebaseDesktopClient",return_value=SimpleNamespace(refresh_token="",configured=False)):
                bridge=module.MusicBoxBridge(settings=settings,load_startup=False)
            bridge._user={"name":"Cuenta de demostración","email":"demo@example.invalid"}
            bridge._schedule_cloud_save=lambda:None
            engine=QQmlApplicationEngine();errors=[]
            engine.warnings.connect(lambda rows:errors.extend(row.toString() for row in rows))
            bridge.attach_engine(engine);engine.rootContext().setContextProperty("musicbox",bridge)
            downloads=DownloadsController(bridge);bridge.downloads=downloads
            engine.rootContext().setContextProperty("downloads",downloads)
            engine.addImageProvider("importer",ImporterImageProvider(downloads.importer))
            engine.load(QUrl.fromLocalFile(str(module.CODE_DIR/"ui/Main.qml")))
            self.assertTrue(engine.rootObjects(),errors)
            window=engine.rootObjects()[0];window.setProperty("splashVisible",False)
            self.assertTrue(QTest.qWaitForWindowExposed(window));window.requestActivate();QTest.qWait(500)
            dialog=window.findChild(QObject,"settingsDialog")
            try:
                for width,height in ((900,620),(1180,760)):
                    window.setWidth(width);window.setHeight(height)
                    for dark in (True,False):
                        if bridge.darkMode!=dark:bridge.toggleTheme()
                        QMetaObject.invokeMethod(dialog,"open",Qt.DirectConnection);QTest.qWait(380)
                        content=dialog.property("contentItem");column=child(content,"settingsColumn")
                        account=child(content,"accountSettingsCard");playback=child(content,"playbackSettingsCard");downloads_card=child(content,"downloadSettingsCard")
                        self.assertIsNotNone(column);self.assertIsNotNone(account);self.assertIsNotNone(playback)
                        self.assertLess(account.y()+account.height(),playback.y())
                        self.assertLess(playback.y()+playback.height(),downloads_card.y())
                        visible=[item for item in column.childItems() if item.isVisible() and item.height()>1 and item.width()>20]
                        visible.sort(key=lambda item:item.y())
                        for before,after in zip(visible,visible[1:]):
                            self.assertLessEqual(before.y()+before.height(),after.y()+1,
                                                 f"Overlapping settings: {before.objectName()} / {after.objectName()}")
                        self.assertGreater(column.height(),dialog.property("height"))
                        image=window.grabWindow();self.assertFalse(image.isNull())
                        colors={image.pixelColor(x,y).rgba() for x in range(0,image.width(),24) for y in range(0,image.height(),24)}
                        if os.environ.get("QT_QPA_PLATFORM")=="windows":self.assertGreater(len(colors),10,"Settings actually rendered")
                        if os.environ.get("MUSICBOX_SETTINGS_PREVIEW_DIR"):
                            output=Path(os.environ["MUSICBOX_SETTINGS_PREVIEW_DIR"]);output.mkdir(parents=True,exist_ok=True)
                            image.save(str(output/f"desktop-settings-{width}-{'dark' if dark else 'light'}.png"))
                        QMetaObject.invokeMethod(dialog,"close",Qt.DirectConnection);QTest.qWait(160)
                # A changed scroll position collapses the tools, stays settled,
                # and reveals them again on an upward change.
                bridge._library_all=[{"path":f"/verification/{i}.mp3","title":f"Tema {i}","artist":"Prueba"} for i in range(60)]
                bridge._playlists["Prueba"]=[row["path"] for row in bridge._library_all]
                bridge.setLibraryPlaylist("Prueba")
                window.setProperty("libraryPlaylist","Prueba");window.setProperty("libraryFilter","playlists");window.setProperty("page",1)
                window.raise_();window.requestActivate()
                QTest.qWait(400)
                rows=window.findChild(QObject,"libraryList");controls=window.findChild(QObject,"collectionControls")
                for delta in (300,-100,300,-100):
                    before=rows.property("contentY")
                    rows.setProperty("contentY",before+delta)
                    QTest.qWait(600)
                    details=f"scroll={delta}, y={before} -> {rows.property('contentY')}, reveal={controls.property('reveal')}"
                    if delta>0:
                        self.assertTrue(window.property("collectionToolsHidden"),details);self.assertLess(controls.height(),1)
                    else:
                        self.assertFalse(window.property("collectionToolsHidden"),details);self.assertGreater(controls.height(),20)
                self.assertFalse(errors,"\n".join(errors))
            finally:
                bridge._device_timer.stop();bridge._listening_timer.stop();window.close();engine.deleteLater();APP.processEvents()

if __name__=="__main__":unittest.main()
