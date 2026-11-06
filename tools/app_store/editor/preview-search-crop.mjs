import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Match the visible 710 × 278 artwork box in the supplied App Store preview.
// Apple may use different crops on other devices; this is a review aid only.
const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, '../../../artifacts/app-store-new');
const manifest = JSON.parse(readFileSync(resolve(out, 'creative-assets/manifest.json'), 'utf8'));
const assets = manifest.filter(a => a.device === 'creative-search' && a.width === 3840);
const html = `<!doctype html><html lang="en"><meta charset="utf-8"><title>Search crop review</title>
<style>*{box-sizing:border-box}body{margin:0;padding:24px;background:#fff;font:16px system-ui;color:#252033}
h1{font-size:22px;margin:0 0 8px}p{margin:0 0 24px;color:#666}main{display:flex;gap:24px}figure{margin:0;width:710px}figcaption{margin-bottom:12px}img{width:710px;height:278px;object-fit:cover;object-position:top;border-radius:24px;display:block}</style>
<h1>Search results · wide crop review</h1><p>Approximation of the supplied App Store preview. Confirm the final placement in App Store Connect.</p><main>${assets.map(a => `<figure><figcaption>${a.locale === 'de' ? 'Deutsch' : 'English'}</figcaption><img src="data:image/png;base64,${readFileSync(resolve(out, 'creative-assets', a.file)).toString('base64')}" alt="${a.locale} search artwork"></figure>`).join('')}</main></html>`;
const browser = await chromium.launch({executablePath:process.env.SCREENSHOT_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
try {
  const page = await browser.newPage({viewport:{width:1492,height:430},deviceScaleFactor:1});
  await page.setContent(html);
  await page.evaluate(() => Promise.all(Array.from(document.images, image => image.decode())));
  await page.screenshot({path:resolve(out, 'search-crop-preview.png'),fullPage:true});
} finally { await browser.close(); }
console.log('Saved search-crop-preview.png');
