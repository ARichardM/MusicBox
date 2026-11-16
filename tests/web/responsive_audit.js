/* Recorre las pantallas principales a varios tamaños de ventana y detecta desbordes horizontales o contenido cortado. */
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const frontend = path.resolve('apps/web/frontend');
const out = process.env.PARITY_OUT || path.resolve('apps/web/.test-artifacts');
const rate = 8000, wave = Buffer.alloc(44 + rate * 2 * 20);
wave.write('RIFF'); wave.writeUInt32LE(wave.length - 8, 4); wave.write('WAVEfmt ', 8); wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20); wave.writeUInt16LE(1, 22);
wave.writeUInt32LE(rate, 24); wave.writeUInt32LE(rate * 2, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34); wave.write('data', 36); wave.writeUInt32LE(rate * 2 * 20, 40);
const songs = Array.from({ length: 12 }, (_, i) => ({ id: 'respTrk' + String(i).padStart(3, '0'), title: 'Canción de prueba con un título bastante largo número ' + i, artist: 'Artista ' + (i % 4), album: 'Álbum ' + (i % 3), duration: 200 + i, thumbnail: 'logo_complete.png' }));
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
const sizes = [[1900, 900], [1400, 800], [1050, 700], [800, 700], [560, 800], [390, 844]];
const views = [['home', () => loadHomeView()], ['library', () => loadLibraryView('songs')], ['playlists', () => loadLibraryView('playlists')], ['downloads', () => loadDownloadsView()], ['history', () => loadHistoryView()]];
(async () => {
  await fs.mkdir(out, { recursive: true });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true });
  const problems = [];
  try {
    const context = await browser.newContext({ viewport: { width: 1180, height: 720 }, serviceWorkers: 'block' });
    const page = await context.newPage();
    await page.route('**/www.gstatic.com/**', (route) => route.abort());
    await page.route('**/www.youtube.com/**', (route) => route.abort());
    await page.route('**/api/**', (route) => route.fulfill({ json: [] }));
    await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
    await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof loadLibraryView === 'function');
    await page.evaluate(async ({ songs, base64 }) => {
      state.isMuted = true;
      const blob = new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'audio/wav' });
      for (const song of songs) await storage.saveOfflineTrack({ ...song, folder: 'Pop' }, blob, null);
      await storage.savePlaylist({ id: 'fav', name: 'Favoritos', tracks: songs.slice(0, 6) });
      localStorage.setItem('musicbox_download_history', JSON.stringify(songs.slice(0, 4).map((s, i) => ({ track: s, format: 'mp3', quality: 192, status: i === 3 ? 'failed' : 'done', at: Date.now() - i * 3600000 }))));
    }, { songs, base64: wave.toString('base64') });
    for (const [width, height] of sizes) {
      await page.setViewportSize({ width, height });
      for (const [name, load] of views) {
        await page.evaluate(load);
        await page.waitForTimeout(500);
        const result = await page.evaluate(() => {
          const main = document.getElementById('main-content');
          const bad = [];
          const area = main.getBoundingClientRect();
          for (const el of main.querySelectorAll('*')) {
            const r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0 || getComputedStyle(el).position === 'fixed') continue;
            if (r.right > area.right + 2 && !el.closest('.artists-carousel,.for-you-grid,.tabs,.dl-tabs,.library-toolbar,.history-rows,.collection-toolbar,[style*="overflow"]')) bad.push((el.className || el.tagName) + ':' + Math.round(r.right - area.right));
            if (bad.length > 4) break;
          }
          return { pageOverflow: document.documentElement.scrollWidth - innerWidth, mainOverflow: main.scrollWidth - main.clientWidth, bad };
        });
        if (result.pageOverflow > 1 || result.mainOverflow > 1 || result.bad.length) problems.push(`${width}x${height} ${name}: ${JSON.stringify(result)}`);
        if (process.env.PARITY_SHOTS) await page.screenshot({ path: path.join(out, `resp-${width}-${name}.png`) });
      }
    }
    console.log(problems.length ? problems.join('\n') : 'sin desbordes');
  } finally { await browser.close(); server.close(); }
})().catch((error) => { console.error(error); process.exit(1); });
