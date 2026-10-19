import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { previewProjection } from '../src/lib/preview-projection.ts';
const metadata = JSON.parse(
  await readFile(new URL('../src/generated/public-basemap.json', import.meta.url)),
);
const near = (actual, expected) =>
  assert.ok(Math.abs(actual - expected) < 1e-6, `${actual} vs ${expected}`);
test('captured Mercator image corners match metadata without linear-latitude distortion', () => {
  const p = previewProjection(metadata, 640, 480);
  near(p.project(metadata.bounds.west, metadata.bounds.north).x, 0);
  near(p.project(metadata.bounds.west, metadata.bounds.north).y, 0);
  near(p.project(metadata.bounds.east, metadata.bounds.south).x, 640);
  near(p.project(metadata.bounds.east, metadata.bounds.south).y, 480);
  near(p.project(metadata.center[0], metadata.center[1]).x, 320);
  near(p.project(metadata.center[0], metadata.center[1]).y, 240);
});
test('portrait cover crop preserves center and derives true visible viewport', () => {
  const p = previewProjection(metadata, 380, 380);
  near(p.project(metadata.center[0], metadata.center[1]).x, 190);
  near(p.project(metadata.center[0], metadata.center[1]).y, 190);
  near(p.project(p.viewport.west, p.viewport.north).x, 0);
  near(p.project(p.viewport.west, p.viewport.north).y, 0);
  near(p.project(p.viewport.east, p.viewport.south).x, 380);
  near(p.project(p.viewport.east, p.viewport.south).y, 380);
  assert.ok(p.viewport.west > metadata.bounds.west);
  assert.ok(p.viewport.east < metadata.bounds.east);
});
test('wide cover crop trims north/south instead of stretching longitude', () => {
  const p = previewProjection(metadata, 1000, 380);
  near(p.project(p.viewport.west, p.viewport.north).x, 0);
  near(p.project(p.viewport.east, p.viewport.south).y, 380);
  near(p.viewport.west, metadata.bounds.west);
  near(p.viewport.east, metadata.bounds.east);
  assert.ok(p.viewport.north < metadata.bounds.north);
  assert.ok(p.viewport.south > metadata.bounds.south);
});
