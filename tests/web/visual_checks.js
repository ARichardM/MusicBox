const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.MUSICBOX_TEST_URL || 'http://127.0.0.1:8015';
const artifacts = path.resolve('apps/web/.test-artifacts');

(async () => {
  fs.mkdirSync(artifacts, { recursive: true });
  const browser = await chromium.launch({ headless: false });
  let checked = 0;
  try {
    for (const width of [1280, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 844 }, locale: 'es-CL', serviceWorkers: 'block' });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.goto(base + '/?verification=1');
      await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof applyPrimaryColor === 'function');
      await page.evaluate(() => document.getElementById('guest-login-button')?.click());
      for (const dark of [true, false]) for (const accent of ['blue', 'orange']) {
        await page.evaluate(({ dark, accent }) => {
          setDarkMode(dark, false); applyPrimaryColor(accent, false);
          document.getElementById('login-overlay').style.display = 'none';
          document.getElementById('settings-btn').click();
        }, { dark, accent });
        await page.locator('#logout-button').scrollIntoViewIfNeeded();
        const result = await page.evaluate(() => {
          const get = selector => getComputedStyle(document.querySelector(selector));
          const button = document.getElementById('logout-button').getBoundingClientRect();
          const body = document.querySelector('.modal-scroll-body').getBoundingClientRect();
          const sidebar = document.getElementById('app-sidebar').getBoundingClientRect();
          const player = document.getElementById('bottom-player').getBoundingClientRect();
          return { height: button.height, bottom: button.bottom, bodyBottom: body.bottom,
            logo: document.getElementById('player-thumb').getAttribute('src'), radius: get('#logout-button').borderRadius, fit: get('#player-thumb').objectFit,
            border: get('#bottom-player').borderTopWidth, gap: player.top - sidebar.bottom,
            sidebar: get('#app-sidebar').backgroundColor, player: get('#bottom-player').backgroundColor,
            danger: get('#clear-offline-btn').backgroundColor, choice: get('#color-orange-btn').borderRadius,
            accent: get(':root').getPropertyValue('--accent').trim(),
            icon: get('.settings-icon-box img').filter, iconToken: get(':root').getPropertyValue('--icon-filter').trim() };
        });
        assert.equal(result.height, 48, 'Logout must retain its native height while scrolling');
        assert.equal(result.radius, '24px');
        assert(result.bottom <= result.bodyBottom + 1, 'Logout is completely visible');
        assert.equal(result.logo, 'logo_complete.png', 'The player includes both cube and waveform');
        assert.equal(result.fit, 'contain', 'Fallback logo must remain complete');
        assert.equal(result.choice, '14px');
        assert.equal(result.danger, 'rgba(0, 0, 0, 0)', 'Native danger action is outlined');
        if (width > 680) {
          assert.equal(result.border, '0px');
          assert(result.gap <= 0, 'Sidebar touches or sits under player');
          assert.equal(result.sidebar, result.player, 'Panels share a continuous color');
        }
        assert.equal(result.accent.toLowerCase(), accent === 'orange' ? (dark ? '#ff9f43' : '#b94700') : (dark ? '#0a84ff' : '#006fe6'));
        await page.screenshot({ path: path.join(artifacts, `settings-${width}-${dark ? 'dark' : 'light'}-${accent}.png`), animations: 'disabled' });
        await page.locator('#close-settings-btn').click();
        await page.screenshot({ path: path.join(artifacts, `shell-${width}-${dark ? 'dark' : 'light'}-${accent}.png`), animations: 'disabled' });
        checked++;
      }
      assert.deepEqual(errors, []);
      await context.close();
    }
    console.log(JSON.stringify({ passed: checked, themes: 'dark/light × blue/orange', widths: [1280, 390], errors: 0 }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
