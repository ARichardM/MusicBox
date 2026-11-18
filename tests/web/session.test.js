/* Al reabrir la web todo queda donde se dejó: pantalla, canción, cola y minuto. Datos sintéticos y aislados. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const frontend = path.resolve('apps/web/frontend');
const rate = 8000, seconds = 60, wave = Buffer.alloc(44 + rate * 2 * seconds);
wave.write('RIFF'); wave.writeUInt32LE(wave.length - 8, 4); wave.write('WAVEfmt ', 8); wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20);
wave.writeUInt16LE(1, 22); wave.writeUInt32LE(rate, 24); wave.writeUInt32LE(rate * 2, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34);
wave.write('data', 36); wave.writeUInt32LE(rate * 2 * seconds, 40);
const songs = [0, 1].map((i) => ({ id: 'sessionTrk0' + i, title: 'Canción ' + i, artist: 'Artista ' + i, duration: 60, thumbnail: 'logo_cube.png' }));
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
    const context = await browser.newContext({ viewport: { width: 1180, height: 760 }, serviceWorkers: 'block' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const routes = async (target) => {
      await target.route('**/www.gstatic.com/**', (route) => route.abort());
      await target.route('**/www.youtube.com/**', (route) => route.abort());
      await target.route('**/api/**', (route) => {
        const url = new URL(route.request().url());
        if (url.pathname.startsWith('/api/stream/')) return route.fulfill({ status: 200, contentType: 'audio/wav', body: wave });
        return route.fulfill({ json: url.pathname.startsWith('/api/lyrics/') ? { lines: [], synced: false } : url.pathname.startsWith('/api/artwork') ? { url: '' } : [] });
      });
    };
    await routes(page);
    const url = `http://127.0.0.1:${server.address().port}/?verification=1`;
    await page.goto(url);
    await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && window.musicboxSession);
    await page.evaluate(async ({ songs, base64 }) => {
      state.isMuted = true; state.autoplayEnabled = false;
      const blob = new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'audio/wav' });
      for (const song of songs) await storage.saveOfflineTrack({ ...song, folder: 'Prueba' }, blob, null);
      await storage.savePlaylist({ id: 'sesion', name: 'Sesión', tracks: songs });
      await loadPlaylistView((await storage.getAllPlaylists())[0]);
    }, { songs, base64: wave.toString('base64') });
    await page.locator('#library-content-list .row-item').nth(1).click();
    await page.waitForFunction(() => state.isPlaying && audio.readyState >= 2);
    await page.evaluate(() => seekTo(23));
    await page.waitForFunction(() => Math.abs(audio.currentTime - 23) < 1.5);
    await page.evaluate(() => musicboxSession.save());
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('musicbox_session_v1')));
    assert.equal(saved.track.id, 'sessionTrk01', 'Se guarda la canción en curso');
    assert(saved.position > 21 && saved.position < 26, 'Se guarda el minuto: ' + saved.position);
    assert.equal(saved.view.kind, 'playlist', 'Se guarda la playlist abierta');

    // "Cerrar" y volver a abrir la web.
    await page.close();
    const reopened = await context.newPage();
    reopened.on('pageerror', (error) => errors.push(error.message));
    await routes(reopened);
    await reopened.goto(url);
    await reopened.waitForFunction(() => typeof storage !== 'undefined' && storage.db && window.musicboxSession);
    await reopened.waitForFunction(() => document.getElementById('player-title').textContent === 'Canción 1', null, { timeout: 8000 });
    await reopened.waitForFunction(() => activePlaylist?.id === 'sesion', null, { timeout: 8000 });
    // La posición se restaura de forma asíncrona (espera a que el audio tenga metadatos): se espera la etiqueta, no se lee de golpe.
    await reopened.waitForFunction(() => /^0:2[2-5]$/.test(document.getElementById('current-time').textContent), null, { timeout: 6000 }).catch(() => {});
    const restored = await reopened.evaluate(() => ({ time: document.getElementById('current-time').textContent, playing: state.isPlaying }));
    assert.match(restored.time, /^0:2[2-5]$/, 'La barra muestra el minuto guardado: ' + restored.time);
    assert.equal(restored.playing, false, 'No arranca sola');
    await reopened.evaluate(() => { state.isMuted = true; });
    await reopened.locator('#play-pause-btn').click();
    await reopened.waitForFunction(() => state.isPlaying && Math.abs(audio.currentTime - 23) < 2.5, null, { timeout: 12000 });
    assert.deepEqual(errors, [], 'Sin errores: ' + errors.join('; '));
    console.log(JSON.stringify({ ok: true, savedPosition: saved.position, restoredLabel: restored.time }));
  } finally {
    await browser.close();
    server.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
