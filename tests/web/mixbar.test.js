/* Crossfade con relieve: la mezcla cae sobre sonido (no sobre silencio) y la barra se dibuja. Datos sintéticos y aislados. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const frontend = path.resolve('apps/web/frontend');

function track(hz, lead, sound, tail) {
  const rate = 8000, seconds = lead + sound + tail, samples = rate * seconds;
  const wave = Buffer.alloc(44 + samples * 2);
  wave.write('RIFF'); wave.writeUInt32LE(wave.length - 8, 4); wave.write('WAVEfmt ', 8); wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20);
  wave.writeUInt16LE(1, 22); wave.writeUInt32LE(rate, 24); wave.writeUInt32LE(rate * 2, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34);
  wave.write('data', 36); wave.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) {
    const audible = i >= rate * lead && i < rate * (lead + sound);
    const envelope = .3 + .7 * Math.abs(Math.sin(i / rate * 1.3));
    wave.writeInt16LE(audible ? Math.round(Math.sin(2 * Math.PI * hz * i / rate) * 9000 * envelope) : 0, 44 + i * 2);
  }
  return wave;
}
const first = track(440, 0, 20, 14), second = track(660, 5, 25, 0);
const songs = [
  { id: 'mixTrackA01', title: 'Primera', artist: 'Prueba', duration: 34, thumbnail: 'logo_cube.png' },
  { id: 'mixTrackB02', title: 'Segunda', artist: 'Prueba', duration: 30, thumbnail: 'logo_cube.png' }
];
const server = http.createServer(async (req, res) => {
  try {
    let pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/runtime-config.js') { res.setHeader('Content-Type', 'text/javascript'); return res.end('window.MUSICBOX_CONFIG={}'); }
    if (pathname === '/') pathname = '/index.html';
    const file = path.resolve(frontend, '.' + decodeURIComponent(pathname));
    if (!file.startsWith(frontend + path.sep)) { res.writeHead(403); return res.end(); }
    const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream'); res.end(await fs.readFile(file));
  } catch { res.writeHead(404); res.end(); }
});

(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
  try {
    const mobile = process.env.MIXBAR_MOBILE === '1';
    const context = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1180, height: 760 }, hasTouch: mobile, isMobile: mobile, serviceWorkers: 'block' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.route('**/www.gstatic.com/**', (route) => route.abort());
    await page.route('**/www.youtube.com/**', (route) => route.abort());
    await page.route('**/api/**', (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.startsWith('/api/stream/')) return route.fulfill({ status: 200, contentType: 'audio/wav', body: url.pathname.endsWith('A01') ? first : second });
      return route.fulfill({ json: url.pathname.startsWith('/api/lyrics/') ? { lines: [], synced: false } : url.pathname.startsWith('/api/artwork') ? { url: '' } : [] });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
    await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof MixEnv !== 'undefined' && window.mixBar);
    await page.evaluate(async ({ songs, a, b }) => {
      state.isMuted = true; state.autoplayEnabled = false;
      const blob = (base64) => new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'audio/wav' });
      await storage.saveOfflineTrack({ ...songs[0], folder: 'Prueba' }, blob(a), null);
      await storage.saveOfflineTrack({ ...songs[1], folder: 'Prueba' }, blob(b), null);
      await storage.savePlaylist({ id: 'mix', name: 'Mezcla', tracks: songs });
      localStorage.setItem('musicbox_crossfade', '4'); state.crossfadeSeconds = 4;
      await loadPlaylistView((await storage.getAllPlaylists())[0]);
    }, { songs, a: first.toString('base64'), b: second.toString('base64') });
    await page.locator('#library-content-list .row-item').first().click();
    if (mobile) await page.evaluate(() => document.body.classList.add('has-track'));
    await page.waitForFunction(() => state.isPlaying && audio.readyState >= 2);

    // El relieve se mide: la primera canción deja de sonar a los 20 s y la segunda empieza a los 5 s.
    await page.waitForFunction(() => MixEnv.peek('mixTrackA01') && MixEnv.peek('mixTrackB02'), null, { timeout: 20000 });
    const bounds = await page.evaluate(() => ({ a: MixEnv.peek('mixTrackA01'), b: MixEnv.peek('mixTrackB02') }));
    assert(Math.abs(bounds.a.audibleEndMs - 20000) < 600, 'La primera canción deja de sonar a los 20 s: ' + bounds.a.audibleEndMs);
    assert(Math.abs(bounds.b.audibleStartMs - 5000) < 600, 'La segunda empieza a sonar a los 5 s: ' + bounds.b.audibleStartMs);

    // La barra de progreso pasa a modo relieve y dibuja algo.
    await page.waitForFunction(() => document.getElementById('progress-wrap').classList.contains('wave-on') && window.mixBar.waveAlpha > .95);
    const painted = await page.evaluate(() => {
      const canvas = document.querySelector('#progress-wrap canvas.progress-wave');
      const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      let count = 0; for (let i = 3; i < data.length; i += 4) if (data[i] > 20) count++;
      return count;
    });
    if (painted <= 200) console.log('DEBUG', JSON.stringify(await page.evaluate(() => ({ size: window.mixBar.size, alpha: window.mixBar.waveAlpha, errs: window.__errs || null, cls: document.getElementById('progress-wrap').className, rect: document.getElementById('progress-wrap').getBoundingClientRect().toJSON() }))), errors);
    assert(painted > 200, 'La barra dibuja el relieve: ' + painted);

    if (process.env.MIXBAR_SHOTS) await page.locator('#bottom-player').screenshot({ path: process.env.MIXBAR_SHOTS + '-idle.png' });
    // La mezcla empieza unos 4 s antes de que deje de sonar (16 s), no al final del archivo (34 s).
    // Cada fotograma registra la ventana visible: el zoom debe entrar y salir sin saltos, también al terminar la mezcla.
    await page.evaluate(() => {
      window.__views = [];
      const tick = (t) => { const view = window.mixBar.view; if (view) window.__views.push({ t, v0: view.v0, v1: view.v1, zoom: window.mixBar.zoom }); requestAnimationFrame(tick); };
      requestAnimationFrame(tick);
    });
    await page.evaluate(() => seekTo(12));
    await page.waitForFunction(() => typeof outgoingAudio !== 'undefined' && !!outgoingAudio, null, { timeout: 15000 });
    const mix = await page.evaluate(() => ({ outgoing: outgoingAudio.currentTime, incoming: audio.currentTime, zoomWanted: window.mixBar.zoom }));
    assert(mix.outgoing > 15 && mix.outgoing < 17.5, 'La mezcla empieza sobre el sonido: ' + mix.outgoing);
    assert(mix.incoming >= 4.5, 'La siguiente entra pasado su silencio inicial: ' + mix.incoming);
    // Resize during overlap to exercise the visible interval after the canvas changes size.
    if (!mobile) await page.setViewportSize({width:900,height:620});
    await page.waitForFunction(() => window.mixBar.zoom > .7 && !!outgoingAudio);
    const projected = await page.evaluate(() => ({ view:window.mixBar.view, width:window.mixBar.size.w }));
    assert(projected.view.v0 > .2 && projected.view.v1 < .9 && projected.width > 0,'The resized wave still frames the outgoing end');
    const during = await page.evaluate(() => ({
      time: document.getElementById('current-time').textContent, zoom: window.mixBar.zoom, title: document.getElementById('player-title').textContent
    }));
    if (process.env.MIXBAR_SHOTS) await page.locator('#bottom-player').screenshot({ path: process.env.MIXBAR_SHOTS + '-mix.png' });
    assert(during.zoom > .2, 'La barra se acerca al tramo de la mezcla: ' + during.zoom);
    assert(/^0:1[6-9]|^0:20/.test(during.time), 'El tiempo sigue a la canción que sale: ' + during.time);
    await page.waitForFunction(() => document.getElementById('player-title').textContent === 'Segunda', null, { timeout: 6000 });
    await page.waitForFunction(() => typeof outgoingAudio === 'undefined' || !outgoingAudio, null, { timeout: 8000 });
    await page.waitForFunction(() => window.mixBar.zoom < .05, null, { timeout: 8000 });
    const views = await page.evaluate(() => window.__views);
    let jump = 0, jumpAt = null;
    for (let i = 1; i < views.length; i++) {
      const step = Math.max(Math.abs(views[i].v0 - views[i - 1].v0), Math.abs(views[i].v1 - views[i - 1].v1));
      if (step > jump) { jump = step; jumpAt = { before: views[i - 1], after: views[i] }; }
    }
    assert(views.some(row => row.zoom > .95), 'El zoom llegó a su punto máximo durante la mezcla');
    assert(jump < .08, 'La ventana de la barra no salta entre fotogramas (salto máximo ' + jump.toFixed(3) + '): ' + JSON.stringify(jumpAt));
    assert.deepEqual(errors, [], 'Sin errores de la página: ' + errors.join('; '));
    console.log(JSON.stringify({ ok: true, bounds: { end: bounds.a.audibleEndMs, start: bounds.b.audibleStartMs }, mixAt: mix.outgoing, incomingAt: mix.incoming }));
  } finally {
    await browser.close();
    server.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
