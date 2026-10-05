import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('mobile viewport uses device width and safe areas without blocking user zoom', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const tags = html.match(/<meta\b[^>]*name="viewport"[^>]*>/g) ?? [];
  assert.equal(tags.length, 1);
  const content = tags[0].match(/content="([^"]+)"/)[1];
  const settings = Object.fromEntries(content.split(',').map(part => part.trim().split('=')));
  assert.equal(settings.width, 'device-width');
  assert.equal(settings['initial-scale'], '1');
  assert.equal(settings['viewport-fit'], 'cover');
  assert.equal(settings['user-scalable'], undefined);
  assert.equal(settings['maximum-scale'], undefined);
});
