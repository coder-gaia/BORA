import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import { join, resolve } from 'node:path';
import { chromium } from '@playwright/test';

const base = process.env.BORA_PREVIEW_URL || 'http://127.0.0.1:19115/';
const origin = new URL(base);
assert.ok(['localhost', '127.0.0.1'].includes(origin.hostname) && origin.protocol === 'http:');
const output = resolve(process.env.BORA_BENCHMARK_OUTPUT || 'test-results/map-performance');
await mkdir(output, { recursive: true });
const expectedHtml = await readFile(new URL('../dist/index.html', import.meta.url));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
function summarizePaints(samples) {
  const times = samples.samples.map((sample) => sample.at);
  const gaps = times
    .slice(1)
    .map((at, index) => at - times[index])
    .sort((a, b) => a - b);
  const durations = samples.samples.map((sample) => sample.duration).sort((a, b) => a - b);
  const percentile = (values, fraction) =>
    values[Math.min(values.length - 1, Math.floor(values.length * fraction))];
  return {
    paints: times.length,
    paintsPerSecond: ((times.length - 1) * 1_000) / (times.at(-1) - times[0]),
    gapP95Ms: percentile(gaps, 0.95),
    gapP99Ms: percentile(gaps, 0.99),
    gapMaxMs: gaps.at(-1),
    paintP95Ms: percentile(durations, 0.95),
    paintMaxMs: durations.at(-1),
    allPaintsDraw300:
      samples.samples.length > 0 && samples.samples.every((sample) => sample.circles === 300),
  };
}
const report = {
  startedAt: new Date().toISOString(),
  base,
  htmlSha256: hash(expectedHtml),
  environment: {
    os: `${os.type()} ${os.release()} ${os.arch()}`,
    cpu: os.cpus()[0]?.model,
    logicalCpus: os.cpus().length,
    memoryBytes: os.totalmem(),
  },
  workload: {
    synthetic: true,
    couriers: 300,
    snapshotsHz: 1,
    viewport: [1440, 1000],
    deviceScaleFactor: 1,
    warmupSeconds: 5,
    measurementSeconds: 30,
    method: 'Count actual Canvas2D clear/arc/stroke paints, including the final paint.',
    limitation:
      'Canvas paints/s approximate visual cadence; compositor-presented GPU frames are not measured.',
    scope: 'Production assets with controlled HTTP/STOMP positions; no real dispatch load.',
  },
  errors: [],
};
const browser = await chromium.launch({
  headless: true,
  channel: process.env.BORA_BROWSER_CHANNEL || undefined,
  args: process.platform === 'win32' ? ['--use-angle=d3d11', '--enable-gpu'] : [],
});
const intervals = new Set();
try {
  report.environment.browser = browser.version();
  const gpu = await (await browser.newBrowserCDPSession()).send('SystemInfo.getInfo');
  report.environment.renderer = gpu.gpu.auxAttributes.glRenderer;
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
  });
  page.on('pageerror', (error) => report.errors.push(error.message));
  const frame = () => {
    const phase = Date.now() / 8_000;
    return {
      serverTime: new Date().toISOString(),
      points: Array.from({ length: 300 }, (_, index) => [
        `benchmark-${index}`,
        -46.668 + (index % 20) * 0.0023 + Math.sin(phase + index) * 0.0005,
        -23.573 + Math.floor(index / 20) * 0.0023 + Math.cos(phase + index) * 0.0005,
        index % 5 === 0 ? 'OFFERED' : index % 3 === 0 ? 'BUSY' : 'AVAILABLE',
        0,
      ]),
    };
  };
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    const value = path.endsWith('/map/snapshot')
      ? frame()
      : path.endsWith('/auth/me')
        ? { authenticated: false, user: null }
        : path.endsWith('/auth/csrf')
          ? { headerName: 'X-CSRF-TOKEN', token: 'synthetic-rendering-fixture' }
          : { simulationSpeed: 3 };
    return route.fulfill({ json: value });
  });
  let messageId = 0;
  const viewports = [];
  await page.routeWebSocket('**/ws', (socket) => {
    const subscriptions = new Map();
    const send = () => {
      for (const [id, destination] of subscriptions)
        if (destination === '/user/queue/map')
          socket.send(
            `MESSAGE\nsubscription:${id}\nmessage-id:${++messageId}\ndestination:${destination}\ncontent-type:application/json\n\n${JSON.stringify(frame())}\0`,
          );
    };
    socket.onMessage((raw) => {
      const value = String(raw);
      if (value.startsWith('CONNECT\n'))
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
      if (value.startsWith('SUBSCRIBE\n')) {
        subscriptions.set(
          /\nid:([^\n]+)/.exec(value)?.[1],
          /\ndestination:([^\n]+)/.exec(value)?.[1],
        );
        send();
      }
      if (value.startsWith('SEND\n') && value.includes('destination:/app/viewport')) {
        const body = value.split('\n\n')[1]?.split('\0')[0];
        if (body) viewports.push(JSON.parse(body));
      }
    });
    const timer = setInterval(send, 1_000);
    intervals.add(timer);
    socket.onClose(() => {
      clearInterval(timer);
      intervals.delete(timer);
    });
  });
  await page.addInitScript(() => {
    const stats = (window.__mapBenchmark = {
      active: false,
      samples: [],
      webglContexts: 0,
      gpuDraws: 0,
      totalGpuDraws: 0,
    });
    const contexts = new WeakSet();
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (kind, ...args) {
      const context = getContext.call(this, kind, ...args);
      if (/webgl/.test(kind) && context && !contexts.has(context)) {
        contexts.add(context);
        stats.webglContexts++;
      }
      return context;
    };
    const clear = CanvasRenderingContext2D.prototype.clearRect;
    CanvasRenderingContext2D.prototype.clearRect = function (...args) {
      if (stats.active && this.canvas.className === 'public-map-couriers')
        stats.samples.push({ at: performance.now(), circles: 0, duration: 0 });
      return clear.apply(this, args);
    };
    const arc = CanvasRenderingContext2D.prototype.arc;
    CanvasRenderingContext2D.prototype.arc = function (...args) {
      if (stats.active && this.canvas.className === 'public-map-couriers')
        stats.samples.at(-1).circles++;
      return arc.apply(this, args);
    };
    const stroke = CanvasRenderingContext2D.prototype.stroke;
    CanvasRenderingContext2D.prototype.stroke = function (...args) {
      const value = stroke.apply(this, args);
      if (stats.active && this.canvas.className === 'public-map-couriers') {
        const sample = stats.samples.at(-1);
        sample.duration = performance.now() - sample.at;
      }
      return value;
    };
    const draw = WebGL2RenderingContext.prototype.drawElements;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      stats.totalGpuDraws++;
      if (stats.active) stats.gpuDraws++;
      return draw.apply(this, args);
    };
  });
  const response = await page.goto(base);
  assert.equal(
    hash(await response.body()),
    report.htmlSha256,
    'Serve the current production dist.',
  );
  report.csp = response.headers()['content-security-policy'] || null;
  await page
    .locator('.public-map-summary strong')
    .filter({ hasText: '300 entregadores' })
    .waitFor();
  await page.waitForTimeout(5_000);
  await page.evaluate(() => (window.__mapBenchmark.active = true));
  await page.waitForTimeout(30_000);
  const samples = await page.evaluate(() => {
    window.__mapBenchmark.active = false;
    return window.__mapBenchmark;
  });
  await writeFile(join(output, 'raw-paints.json'), JSON.stringify(samples, null, 2));
  report.result = {
    ...summarizePaints(samples),
    webglContextsBeforeExplore: samples.webglContexts,
    basemapGpuDrawCalls: samples.gpuDraws,
  };
  await page.screenshot({ path: join(output, '300-markers.png') });
  const reset = () =>
    page.evaluate(() => {
      window.__mapBenchmark.samples = [];
      window.__mapBenchmark.active = true;
    });
  const count = () =>
    page.evaluate(() => {
      window.__mapBenchmark.active = false;
      return window.__mapBenchmark.samples.length;
    });
  await page.getByRole('button', { name: 'Pausar movimento', exact: true }).click();
  await page.waitForTimeout(500);
  await reset();
  await page.waitForTimeout(2_200);
  report.result.pausedPaintsOver2200ms = await count();
  await page.getByRole('button', { name: 'Retomar movimento', exact: true }).click();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.waitForTimeout(500);
  await reset();
  await page.waitForTimeout(3_200);
  report.result.reducedMotionPaintsOver3200ms = await count();
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.getByRole('button', { name: 'Explorar mapa', exact: true }).click();
  await page.locator('.interactive-map-layer.is-ready').waitFor({ timeout: 45_000 });
  await page.waitForTimeout(5_000);
  await page.evaluate(() => {
    window.__mapBenchmark.samples = [];
    window.__mapBenchmark.gpuDraws = 0;
    window.__mapBenchmark.active = true;
  });
  await page.waitForTimeout(30_000);
  const interactive = await page.evaluate(() => {
    window.__mapBenchmark.active = false;
    return window.__mapBenchmark;
  });
  await writeFile(
    join(output, 'raw-interactive-paints.json'),
    JSON.stringify(interactive, null, 2),
  );
  report.interactive = {
    ...summarizePaints(interactive),
    camera: 'Static during the 30s paint window; keyboard movement checked separately afterward.',
    webglContexts: interactive.webglContexts,
    gpuDrawCallsSinceExplore: interactive.totalGpuDraws,
    gpuDrawCallsDuringMeasurement: interactive.gpuDraws,
  };
  const previousViewport = viewports.at(-1);
  const previousDraws = interactive.totalGpuDraws;
  const map = page.locator('.maplibregl-canvas');
  await map.focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('-');
  await page.waitForTimeout(1_500);
  report.interactive.keyboardViewportBefore = previousViewport;
  report.interactive.keyboardViewportAfter = viewports.at(-1);
  report.interactive.gpuDrawCallsAfterKeyboard = await page.evaluate(
    () => window.__mapBenchmark.totalGpuDraws,
  );
  await page.screenshot({ path: join(output, '300-markers-interactive.png') });
  assert.deepEqual(report.errors, []);
  assert.equal(report.result.allPaintsDraw300, true);
  assert.equal(report.result.webglContextsBeforeExplore, 0);
  assert.equal(report.result.basemapGpuDrawCalls, 0);
  assert.equal(report.result.pausedPaintsOver2200ms, 0);
  assert.ok(report.result.reducedMotionPaintsOver3200ms <= 5);
  assert.ok(
    report.result.paintsPerSecond >= 50 && report.result.gapP95Ms < 34,
    'Declared fluency gate: >=50 Canvas paints/s and p95 gap <34ms.',
  );
  assert.equal(report.interactive.allPaintsDraw300, true);
  assert.ok(
    report.interactive.webglContexts > 0 && report.interactive.gpuDrawCallsSinceExplore > 0,
  );
  assert.ok(report.interactive.gpuDrawCallsAfterKeyboard > previousDraws);
  assert.notDeepEqual(report.interactive.keyboardViewportAfter, previousViewport);
  assert.ok(report.interactive.paintsPerSecond >= 50 && report.interactive.gapP95Ms < 34);
  report.passed = true;
} catch (error) {
  report.passed = false;
  report.failure = error.stack;
  process.exitCode = 1;
} finally {
  for (const timer of intervals) clearInterval(timer);
  await browser.close();
  report.finishedAt = new Date().toISOString();
  await writeFile(join(output, 'summary.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
}
