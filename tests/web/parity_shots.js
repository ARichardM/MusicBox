/* Capturas de la web con datos sintéticos, a 1180x720, para compararlas con las del escritorio. */
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const frontend = path.resolve('apps/web/frontend');
const out = process.env.PARITY_OUT || path.resolve('apps/web/.test-artifacts');
const rate = 8000, seconds = 30, wave = Buffer.alloc(44 + rate * 2 * seconds);
wave.write('RIFF'); wave.writeUInt32LE(wave.length - 8, 4); wave.write('WAVEfmt ', 8); wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20);
wave.writeUInt16LE(1, 22); wave.writeUInt32LE(rate, 24); wave.writeUInt32LE(rate * 2, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34);
wave.write('data', 36); wave.writeUInt32LE(rate * 2 * seconds, 40);
const songs = Array.from({ length: 14 }, (_, i) => ({ id: 'parityTrk' + String(i).padStart(2, '0'), title: 'Canción ' + i, artist: 'Artista ' + (i % 4), album: 'Álbum ' + (i % 3), duration: 200 + i, thumbnail: 'logo_complete.png' }));
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
  await fs.mkdir(out, { recursive: true });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1180, height: 720 }, locale: 'es-CL', serviceWorkers: 'block' });
    const page = await context.newPage();
    await page.route('**/www.gstatic.com/**', (route) => route.abort());
    await page.route('**/www.youtube.com/**', (route) => route.abort());
    await page.route('**/api/**', (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.startsWith('/api/stream/')) return route.fulfill({ status: 200, contentType: 'audio/wav', body: wave });
      return route.fulfill({ json: url.pathname.startsWith('/api/recommendations') ? { songs, artists: [] } : url.pathname.startsWith('/api/artwork') ? { url: '' } : [] });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
    await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof loadLibraryView === 'function');
    await page.evaluate(async ({ songs, base64 }) => {
      state.isMuted = true;
      const blob = new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'audio/wav' });
      for (const song of songs) await storage.saveOfflineTrack({ ...song, folder: song.id.endsWith('1') ? 'Rock' : 'Pop' }, blob, null);
      await storage.savePlaylist({ id: 'fav', name: 'Favoritos', tracks: songs.slice(0, 6) });
      await storage.savePlaylist({ id: 'trip', name: 'Viaje', tracks: songs.slice(6, 10) });
    }, { songs, base64: wave.toString('base64') });
    const shot = async (name) => { await page.waitForTimeout(700); await page.screenshot({ path: path.join(out, `web-${name}.png`) }); };
    await page.evaluate(() => loadHomeView()); await shot('home');
    await page.evaluate(() => loadLibraryView('songs')); await shot('library-songs');
    await page.evaluate(() => loadLibraryView('playlists')); await shot('library-playlists');
    await page.evaluate(() => loadLibraryView('folders')); await shot('library-folders');
    await page.evaluate(async () => loadPlaylistView((await storage.getAllPlaylists())[0])); await shot('playlist-open');
    await page.evaluate(() => loadDownloadsView()); await shot('downloads');
    await page.evaluate(() => { localStorage.setItem('musicbox_download_history', JSON.stringify([{track:{id:'a',title:'With Or Without You',artist:'U2'},format:'mp3',quality:192,status:'done',at:Date.now()},{track:{id:'b',title:'Daylight',artist:'David Kushner'},format:'mp3',quality:192,status:'done',at:Date.now()-60000},{track:{id:'c',title:'Video con error',artist:''},format:'mp4',quality:720,status:'failed',message:'Sin acceso',at:Date.now()-3600000}])); });
    await page.evaluate(() => loadHistoryView()); await shot('history');
    await page.evaluate(() => { document.getElementById('settings-btn').click(); }); await shot('settings');
    console.log('ok', out);
  } finally { await browser.close(); server.close(); }
})().catch((error) => { console.error(error); process.exit(1); });
