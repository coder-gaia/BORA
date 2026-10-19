import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';

test('the versioned basemap URL and metadata refer to the exact shipped WebP bytes', async () => {
  const metadata = JSON.parse(
    await readFile(new URL('../src/generated/public-basemap.json', import.meta.url)),
  );
  const match = /^\/assets\/saopaulo-basemap-([a-f0-9]{64})\.webp$/.exec(metadata.assetUrl);
  assert.ok(match, 'basemap must use the shared retained assets directory and a content hash');
  const bytes = await readFile(new URL(`../public${metadata.assetUrl}`, import.meta.url));
  const actual = createHash('sha256').update(bytes).digest('hex');
  assert.equal(actual, metadata.rasterSha256, 'capture metadata must match the actual image');
  assert.equal(actual, match[1], 'the image URL must change whenever the bytes change');
  assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
  assert.equal(bytes.toString('ascii', 8, 12), 'WEBP');
});
