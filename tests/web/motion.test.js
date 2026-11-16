/* El reproductor grande se despliega y se pliega con una transición de vista, y los selectores se ven como los del escritorio. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const frontend = path.resolve('apps/web/frontend');
const rate = 8000, wave = Buffer.alloc(44 + rate * 2 * 20);
wave.write('RIFF'); wave.writeUInt32LE(wave.length - 8, 4); wave.write('WAVEfmt ', 8); wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20); wave.writeUInt16LE(1, 22);
wave.writeUInt32LE(rate, 24); wave.writeUInt32LE(rate * 2, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34); wave.write('data', 36); wave.writeUInt32LE(rate * 2 * 20, 40);
const songs = [0, 1, 2].map((i) => ({ id: 'motionTrk0' + i, title: 'Canción ' + i, artist: 'Artista ' + i, duration: 20, thumbnail: 'logo_complete.png' }));
const server = http.createServer(async (req, res) => {
  try {
    let pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/runtime-config.js') { res.setHeader('Content-Type', 'text/javascript'); return res.end('window.MUSICBOX_CONFIG={}'); }
    if (pathname === '/') pathname = '/index.html';
    const file = path.resolve(frontend, '.' + decodeURIComponent(pathname));
    const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream'); res.end(await fs.readFile(file));
  } catch { res.writeHead(404); res.end(); }
});
(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
  try {
    const page = await (await browser.newContext({ viewport: { width: 1180, height: 720 }, serviceWorkers: 'block' })).newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.route('**/www.gstatic.com/**', (route) => route.abort());
    await page.route('**/www.youtube.com/**', (route) => route.abort());
    await page.route('**/api/**', (route) => route.fulfill({ json: [] }));
    await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
    await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof loadLibraryView === 'function');
    await page.evaluate(async ({ songs, base64 }) => {
      state.isMuted = true;
      const blob = new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'audio/wav' });
      for (const song of songs) await storage.saveOfflineTrack({ ...song, folder: 'Prueba' }, blob, null);
      await loadLibraryView('songs');
    }, { songs, base64: wave.toString('base64') });

    // Selectores: el nativo queda oculto y el botón abre una lista como la del escritorio.
    await page.waitForSelector('.mb-sort .mb-select-button');
    assert.equal(await page.locator('select.mb-native').first().evaluate((el) => getComputedStyle(el).opacity), '0', 'El selector nativo no se ve');
    await page.locator('.mb-sort .mb-select-button').click();
    await page.waitForSelector('.mb-select-popup.shown');
    const items = await page.locator('.mb-select-item').allTextContents();
    assert(items.length >= 4 && items[0].length > 0, 'La lista muestra las opciones: ' + items.join(','));
    assert.equal(await page.locator('.mb-select-item.current').count(), 1, 'La opción elegida va marcada');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => !document.querySelector('.mb-select-popup'));
    const chosen = await page.evaluate(() => document.querySelector('#library-sort').value);
    assert.notEqual(chosen, 'recent', 'Elegir con el teclado cambia el valor: ' + chosen);

    // El reproductor grande se anima al abrir y al cerrar.
    await page.locator('#library-content-list .row-item').first().click();
    await page.waitForFunction(() => state.isPlaying);
    const sawMorph = async (action) => {
      await page.evaluate(action);
      return page.evaluate(() => new Promise((resolve) => {
        let seen = document.documentElement.classList.contains('player-morph');
        const until = performance.now() + 500;
        const tick = () => { seen = seen || document.documentElement.classList.contains('player-morph'); if (performance.now() < until) requestAnimationFrame(tick); else resolve(seen); };
        tick();
      }));
    };
    assert(await sawMorph(() => document.body.classList.add('player-expanded')), 'Al abrir hay transición');
    await page.waitForFunction(() => !document.documentElement.classList.contains('player-morph') && document.body.classList.contains('player-expanded'));
    assert(await sawMorph(() => document.body.classList.remove('player-expanded')), 'Al cerrar hay transición');
    await page.waitForFunction(() => !document.body.classList.contains('player-expanded') && !document.documentElement.classList.contains('player-morph'));
    assert.deepEqual(errors, [], 'Sin errores: ' + errors.join('; '));
    console.log(JSON.stringify({ ok: true, options: items.length }));
  } finally {
    await browser.close();
    server.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
