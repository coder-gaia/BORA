import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const webRoot = new URL('../', import.meta.url);
const metadata = JSON.parse(
  await readFile(new URL('src/generated/public-basemap.json', webRoot), 'utf8'),
);
const raster = await readFile(new URL(`public${metadata.assetUrl}`, webRoot));
const font = await readFile(
  new URL('node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2', webRoot),
);
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
assert.equal(sha256(raster), metadata.rasterSha256, 'Use the approved, unchanged basemap.');

function createHtml(width, height) {
  const yellowWidth = Math.round(width * (632 / 1200));
  const mapWidth = width - yellowWidth - 80;
  return `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<style>
  @font-face {
    font-family: 'Inter';
    font-style: normal;
    font-weight: 100 900;
    font-display: block;
    src: url(data:font/woff2;base64,${font.toString('base64')}) format('woff2');
  }
  * { box-sizing: border-box; }
  html, body { width: ${width}px; height: ${height}px; margin: 0; }
  body { position: relative; overflow: hidden; background: #f7f7f5; color: #18181b; font-family: 'Inter', sans-serif; }
  .yellow { position: absolute; inset: 0 auto 0 0; width: ${yellowWidth}px; background: #ffc21a; }
  .copy { position: absolute; top: 48px; left: 64px; width: 512px; height: ${height - 96}px; }
  .brand { margin: 0; font-size: 86px; font-weight: 800; line-height: 1; letter-spacing: -6px; }
  .location { margin: 48px 0 18px; font-size: 15px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; }
  h1 { margin: 0; font-size: 74px; font-weight: 650; line-height: 1.04; letter-spacing: -4.5px; }
  .description { margin: 24px 0 0; font-size: 20px; font-weight: 450; line-height: 1.5; letter-spacing: -0.3px; }
  .address { position: absolute; bottom: 0; left: 0; margin: 0; font-size: 15px; font-weight: 600; }
  .map { position: absolute; top: 48px; right: 48px; width: ${mapWidth}px; height: ${height - 96}px; margin: 0; overflow: hidden; border: 1px solid #d8d5cd; border-radius: 14px; background: white; }
  .map img { display: block; width: 100%; height: ${height - 146}px; object-fit: cover; object-position: center; }
  .map-label { position: absolute; top: 20px; left: 20px; display: flex; align-items: center; gap: 7px; margin: 0; padding: 10px 13px; border: 1px solid #e6e4df; border-radius: 7px; background: #fff; font-size: 13px; font-weight: 600; }
  figcaption { position: absolute; inset: auto 0 0; display: flex; height: 49px; align-items: center; justify-content: center; gap: 8px; border-top: 1px solid #e6e4df; background: white; color: #48484f; font-size: 12px; font-weight: 500; white-space: nowrap; }
</style>
</head>
<body>
  <div class="yellow"></div>
  <main class="copy">
    <p class="brand">bora.</p>
    <p class="location">São Paulo, em movimento</p>
    <h1>Entregas em<br>movimento.</h1>
    <p class="description">Despacho em tempo real.<br>Da loja até a chegada.</p>
    <p class="address">bora.alexandregaia.me</p>
  </main>
  <figure class="map">
    <img src="data:image/webp;base64,${raster.toString('base64')}" alt="Mapa da região central de São Paulo">
    <p class="map-label">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></svg>
      São Paulo
    </p>
    <figcaption>OpenFreeMap <span>/</span> OpenMapTiles <span>/</span> © OpenStreetMap contributors</figcaption>
  </figure>
</body>
</html>`;
}

const browser = await chromium.launch({ headless: true });
try {
  const variants = [
    { width: 1200, height: 630, output: new URL('public/og-image.png', webRoot), limit: 500_000 },
    {
      width: 1280,
      height: 640,
      output: new URL('docs/imagens/github-social-preview.png', new URL('../../', webRoot)),
      limit: 1_000_000,
    },
  ];
  const results = [];
  for (const variant of variants) {
    const { width, height } = variant;
    const context = await browser.newContext({
      viewport: { width, height },
      deviceScaleFactor: 1,
      locale: 'pt-BR',
      reducedMotion: 'reduce',
    });
    const requests = [];
    await context.route(/^https?:\/\//, (route) => {
      requests.push(route.request().url());
      return route.abort();
    });
    const page = await context.newPage();
    await page.setContent(createHtml(width, height), { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.evaluate(() => document.fonts.check('650 74px Inter')), true);
    assert.deepEqual(
      await page.evaluate(() => [
        document.documentElement.scrollWidth,
        document.documentElement.scrollHeight,
      ]),
      [width, height],
    );
    assert.deepEqual(requests, [], 'The cover must render entirely from local, embedded assets.');
    const output = fileURLToPath(variant.output);
    await mkdir(dirname(output), { recursive: true });
    const image = await page.screenshot({ path: output, type: 'png', animations: 'disabled' });
    assert.equal(image.readUInt32BE(16), width);
    assert.equal(image.readUInt32BE(20), height);
    assert.equal(image[25], 2, 'Export an opaque RGB PNG.');
    assert.ok(image.length < variant.limit, 'Keep each share image within its size budget.');
    results.push({
      output,
      width,
      height,
      bytes: image.length,
      sha256: sha256(image),
      rasterSha256: sha256(raster),
      fontSha256: sha256(font),
      browser: browser.version(),
      networkRequests: requests.length,
    });
    await context.close();
  }
  console.log(JSON.stringify({ variants: results }));
} finally {
  await browser.close();
}
