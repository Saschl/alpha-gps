import { createRequire } from 'node:module';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.SCREENSHOT_PLAYWRIGHT || 'playwright-core');
const sharp = require(process.env.SCREENSHOT_SHARP || 'sharp');
const here = dirname(fileURLToPath(import.meta.url));
const output = resolve(here, '../../artifacts/app-store');
const copy = JSON.parse(readFileSync(resolve(here, 'copy.json')));
const escape = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const lines = value => escape(value).replaceAll('\n', '<br>');
const browser = await chromium.launch({ executablePath: process.env.SCREENSHOT_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
const width = 1320, height = 2868;
// Keep the approved layout in its design coordinates, then fit it uniformly
// into the 6.5-inch upload size. Contain preserves the phone and text ratios.
const exportWidth = 1284, exportHeight = 2778;
const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
const thumbnails = [];
try {
  for (const [locale, screens] of Object.entries(copy)) {
    mkdirSync(resolve(output, locale), { recursive: true });
    for (const [index, screen] of screens.entries()) {
      const raw = readFileSync(resolve(output, 'raw', locale, `${screen.id}.png`));
      const source = await sharp(raw).metadata();
      if (!source.width || !source.height) throw new Error(`Invalid raw screenshot: ${locale}/${screen.id}`);
      // Fit the entire image between the captions and footer. The bezel adds
      // 16px per edge; it must not impose a different aspect ratio on the screen.
      const bezel = 16;
      const scale = Math.min((1030 - 2 * bezel) / source.width, (1770 - 2 * bezel) / source.height);
      const deviceWidth = source.width * scale + 2 * bezel;
      const image = raw.toString('base64');
      const html = `<!doctype html><html lang="${locale}"><meta charset="utf-8"><style>
*{box-sizing:border-box}html,body{margin:0;width:${width}px;height:${height}px;overflow:hidden}
body{font-family:-apple-system,BlinkMacSystemFont,"Helvetica Neue",sans-serif;background:#201635;color:#fff}
.poster{position:relative;width:100%;height:100%;background:radial-gradient(ellipse at 90% 60%,#6d4c9a66,transparent 55%),linear-gradient(160deg,#352146,#211830 65%)}
.rings{position:absolute;left:640px;top:830px;width:1500px;height:1500px;border:2px solid #b49ac422;border-radius:50%;box-shadow:0 0 0 145px #b49ac409,0 0 0 290px #b49ac408}
.brand{position:absolute;top:88px;left:92px;display:flex;align-items:center;gap:20px;font-size:32px;font-weight:650;letter-spacing:.5px;color:#f2e9fa}
.mark{width:48px;height:48px;border:3px solid #c9b4e9;border-radius:50%;display:grid;place-items:center}.mark:after{content:'';width:14px;height:14px;background:#c9b4e9;border-radius:50%}
.number{position:absolute;right:94px;top:101px;font-size:23px;font-weight:500;letter-spacing:3px;color:#bfa8d0}
.copy{position:absolute;top:242px;left:92px;right:72px}
.eyebrow{font-size:25px;font-weight:650;letter-spacing:4px;color:#cbb5e5;margin-bottom:35px}
h1{font-size:${locale === 'de-DE' ? 100 : 116}px;line-height:1.05;letter-spacing:-5px;margin:0;font-weight:750;white-space:nowrap}
.subtitle{font-size:38px;line-height:1.4;color:#ddd1e7;margin-top:36px;font-weight:400;letter-spacing:-.3px}
.device{position:absolute;top:800px;left:50%;transform:translateX(-50%);width:${deviceWidth}px;padding:14px;background:#17131c;border:2px solid #c4b2cc;border-radius:80px;box-shadow:0 45px 95px #09061199}
.screen{aspect-ratio:${source.width}/${source.height};overflow:hidden;border-radius:64px;background:#fffbfe}.screen img{display:block;width:100%;height:auto}
.chip{position:absolute;top:2630px;left:92px;right:92px;height:78px;display:flex;align-items:center;justify-content:center;gap:18px;border:1px solid #cbb5e53d;border-radius:50px;background:#cbb5e512;font-size:29px;font-weight:550;color:#f1e9f8}
.dot{width:12px;height:12px;border-radius:50%;background:#b6dcc7;box-shadow:0 0 20px #b6dcc740}
.note{position:absolute;top:2756px;left:0;width:100%;text-align:center;font-size:24px;color:#bfaed0}
</style><body class="${screen.id}"><main class="poster"><div class="rings"></div><div class="brand"><span class="mark"></span>Alpha GPS</div><div class="number">0${index + 1} / 05</div><div class="copy"><div class="eyebrow">${escape(screen.eyebrow)}</div><h1>${lines(screen.title)}</h1><div class="subtitle">${lines(screen.subtitle)}</div></div><div class="device"><div class="screen"><img src="data:image/png;base64,${image}"></div></div><div class="chip"><span class="dot"></span>${escape(screen.chip)}</div><div class="note">${escape(screen.note)}</div></main></body></html>`;
      await page.setContent(html);
      await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map(image => image.decode())); });
      const overflow = await page.evaluate(() => [...document.querySelectorAll('h1,.subtitle,.chip')].filter(e => e.scrollWidth > e.clientWidth || e.getBoundingClientRect().right > innerWidth).map(e => e.textContent));
      if (overflow.length) throw new Error(`${locale}/${screen.id} text overflow: ${overflow}`);
      const geometryErrors = await page.evaluate(() => {
        const image = document.querySelector('.screen img');
        const frame = document.querySelector('.screen').getBoundingClientRect();
        const rendered = image.getBoundingClientRect();
        const device = document.querySelector('.device').getBoundingClientRect();
        const errors = [];
        const expectedHeight = rendered.width * image.naturalHeight / image.naturalWidth;
        if (Math.abs(rendered.height - expectedHeight) > 1) errors.push('image aspect ratio changed');
        if (Math.abs(frame.width - rendered.width) > 1 || Math.abs(frame.height - rendered.height) > 1 ||
            Math.abs(frame.top - rendered.top) > 1 || Math.abs(frame.left - rendered.left) > 1) errors.push('screen is cropped or offset');
        if (device.top < document.querySelector('.copy').getBoundingClientRect().bottom ||
            device.bottom > document.querySelector('.chip').getBoundingClientRect().top ||
            device.left < 0 || device.right > innerWidth) errors.push('device overlaps captions or poster edge');
        return errors;
      });
      if (geometryErrors.length) throw new Error(`${locale}/${screen.id}: ${geometryErrors.join(', ')}`);
      const file = `${String(index + 1).padStart(2, '0')}-${screen.id}.png`;
      const png = await page.screenshot({ type: 'png' });
      const exported = await sharp(png)
        .resize(exportWidth, exportHeight, { fit: 'contain', background: '#201635' })
        .flatten({ background: '#201635' }).png().toBuffer();
      writeFileSync(resolve(output, locale, file), exported);
      const metadata = await sharp(resolve(output, locale, file)).metadata();
      if (metadata.width !== exportWidth || metadata.height !== exportHeight || metadata.hasAlpha) throw new Error(`Invalid App Store PNG: ${file}`);
      const thumb = await sharp(exported).resize(264, 574, { fit: 'contain', background: '#201635' }).png().toBuffer();
      thumbnails.push({ input: thumb, left: index * 284, top: locale === 'en-US' ? 0 : 594 });
      console.log(`Rendered ${locale}/${file} (${exportWidth} × ${exportHeight}, opaque RGB)`);
    }
  }
  await sharp({ create: { width: 1400, height: 1168, channels: 3, background: '#eee9f2' } }).composite(thumbnails).png().toFile(resolve(output, 'preview.png'));
  writeFileSync(resolve(output, 'index.html'), `<!doctype html><meta charset="utf-8"><title>Alpha GPS — App Store screenshots</title><style>body{font:18px system-ui;margin:40px;background:#eee9f2;color:#241936}.grid{display:grid;grid-template-columns:repeat(5,1fr);gap:16px}img{width:100%;border-radius:16px}a{color:inherit}</style><h1>Alpha GPS — App Store screenshots</h1><p>${exportWidth} × ${exportHeight} · PNG · opaque RGB. Click an image to open the full-resolution export.</p>${Object.entries(copy).map(([locale, screens]) => `<h2>${locale}</h2><div class="grid">${screens.map((s,i) => { const path = `${locale}/${String(i+1).padStart(2,'0')}-${s.id}.png`; return `<a href="${path}"><img src="${path}"></a>`; }).join('')}</div>`).join('')}`);
} finally { await browser.close(); }
