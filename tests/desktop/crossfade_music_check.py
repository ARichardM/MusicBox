"""Opt-in real-music check: pass two audio files and optionally two HTTP stream URLs."""
import json
import os
import sys
import tempfile
import time
import urllib.request
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'apps/desktop'))
from PySide6.QtCore import QSettings, QEventLoop
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module

app = QApplication.instance() or QApplication([])
files = [str(Path(path).resolve()) for path in sys.argv[1:3]]
assert len(files) == 2 and all(Path(path).is_file() for path in files)
reports = []
native_resolver = module.youtube_streams.resolve_stream
with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
    for mode in ('local', 'stream') if len(sys.argv) == 5 else ('local',):
        print('Checking ' + mode, flush=True)
        settings = QSettings(str(Path(directory) / (mode + '.ini')), QSettings.IniFormat)
        settings.setValue('muted', True)
        with patch.object(module, 'ROOT', Path(directory)), patch.object(module, 'STREAM_CACHE_DIR', Path(directory) / 'cache'), \
                patch.object(module, 'FirebaseDesktopClient'), patch.object(module.MusicBoxBridge, '_maybe_autoplay'), \
                patch.object(module.youtube_streams, 'resolve_stream', side_effect=lambda url, *args, **kwargs:
                    {'url': url} if url.startswith('http://127.0.0.1:') else native_resolver(url, *args, **kwargs)):
            bridge = module.MusicBoxBridge(settings=settings, load_startup=False)
            try:
                bridge.setCrossfadeSeconds(3)
                if mode == 'local':
                    tracks = [{'path': path, 'title': 'Real music ' + str(i)} for i, path in enumerate(files)]
                    bridge._library = tracks
                    bridge.playTrack(tracks[0])
                else:
                    urls = sys.argv[3:5]
                    tracks = [{'id': str(i), 'url': url, 'title': 'Real HTTP music ' + str(i)} for i, url in enumerate(urls)]
                    bridge._online_queue, bridge._online_index = tracks, 0
                    # Full real HTTP media is prepared ahead, with the production prefetch publication path.
                    def download(options, url):
                        target = Path(options['outtmpl']).parent / 'music.mp4'
                        with urllib.request.urlopen(urllib.request.Request(url, headers={'Range': 'bytes=0-'}), timeout=45) as response:
                            target.write_bytes(response.read(50_000_000))
                        return str(target)
                    if all(url.startswith('http://127.0.0.1:') for url in urls):
                        bridge._download_audio = download
                    bridge._play_online(tracks[0])
                    bridge._prefetch_upcoming()
                deadline = time.monotonic() + 45
                while (not bridge.playing or bridge._player.duration() <= 0
                       or mode == 'stream' and not bridge._prefetched.get(tracks[1]['url'])) and time.monotonic() < deadline:
                    app.processEvents(QEventLoop.AllEvents, 10)
                    time.sleep(.05)
                assert bridge.playing and bridge._player.duration() > 0, mode + ': real media did not start'
                print(mode + ': media and prefetch ready', flush=True)
                if mode == 'stream':
                    assert Path(bridge._prefetched[tracks[1]['url']]).is_file(), 'Next HTTP track must be cached before fading'
                bridge.seek(max(0, bridge._player.duration() - 4000))
                samples = []
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    fade = bridge._crossfade
                    if fade.outgoing and bridge.playing:
                        samples.append({'outgoing': fade.output.volume(), 'incoming': bridge._audio.volume()})
                    app.processEvents(QEventLoop.AllEvents, 10)
                    time.sleep(.04)
                assert len(samples) > 5, mode + ': no sustained overlap'
                assert any(row['outgoing'] > .1 and row['incoming'] > .1 for row in samples), mode + ': gains did not cross'
                assert all(abs(row['outgoing'] + row['incoming'] - .8) < .02 for row in samples)
                reports.append({'mode': mode, 'overlap_samples': len(samples), 'prepared_before_fade': True})
            finally:
                bridge._crossfade.cancel()
                bridge._player.stop()
                for timer in (bridge._listening_timer, bridge._device_timer, bridge._playback_session_timer, bridge._prefetch_timer):
                    timer.stop()
                deadline = time.monotonic() + 45
                while bridge._prefetching and time.monotonic() < deadline:
                    app.processEvents(QEventLoop.AllEvents, 10)
                    time.sleep(.05)
print(json.dumps(reports))
