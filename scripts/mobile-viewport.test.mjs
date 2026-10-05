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

test('app shell reserves navigation space instead of overlaying route content', () => {
  const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
  const shell = read('../src/app/components/AnimatedMobileLayout.tsx');
  const nav = read('../src/app/components/BottomNav.tsx');
  assert.match(shell, /data-testid="app-content"[^\n]*min-h-0[^\n]*flex-1/);
  const navClass = nav.match(/className="(unibus-bottom-nav[^"]+)"/)[1];
  assert.match(navClass, /shrink-0/);
  assert.doesNotMatch(navClass, /\b(?:absolute|fixed)\b/);
  for (const name of ['Home', 'Settings', 'Notice', 'CommuterBus']) {
    assert.doesNotMatch(read(`../src/app/screens/${name}Wrapper.tsx`), /pb-\[120px\]/);
  }
  const css = read('../src/styles/globals.css');
  assert.match(css, /@supports \(height: 100dvh\)/);
  assert.doesNotMatch(css, /\.unibus-bottom-nav\s*\{[^}]*position:\s*fixed/s);
});

test('login scroll is separate from safe-area header and dialogs escape the route layer', () => {
  const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
  const login = read('../src/app/screens/LoginWrapper.tsx');
  assert.match(login, /data-testid="login-header"[^\n]*shrink-0[^\n]*pt-safe/);
  assert.match(login, /data-testid="login-scroll"[^\n]*min-h-0[^\n]*flex-1[^\n]*overflow-y-auto/);
  for (const name of ['Settings', 'Notice']) {
    const screen = read(`../src/app/screens/${name}Wrapper.tsx`);
    assert.match(screen, /createPortal\(<AnimatePresence/);
    assert.match(screen, /<\/AnimatePresence>, document\.body\)/);
    assert.match(screen, /fixed inset-0 z-\[100\]/);
  }
});
