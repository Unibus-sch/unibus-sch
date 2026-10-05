import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { transpileModule, ScriptTarget, ModuleKind } from 'typescript';

test('remaining APIs use Spring groups with correct session and Supabase headers', async () => {
  const source = await readFile(new URL('../src/app/services/api.ts', import.meta.url), 'utf8');
  const transpiled = transpileModule(source, {
    compilerOptions: { target: ScriptTarget.ES2022, module: ModuleKind.ES2022 },
  }).outputText
    .replace(/import \{ publicAnonKey, supabaseUrl \} from [^;]+;/,
      'const publicAnonKey = "test-public-anon"; const supabaseUrl = "http://supabase.invalid";')
    .replaceAll('import.meta.env', '({DEV:false,VITE_PUBLIC_API_BASE_URL:"http://public.invalid",VITE_AUTH_API_BASE_URL:"http://auth.invalid",VITE_ADMIN_API_BASE_URL:"http://admin.invalid",VITE_DRIVER_API_BASE_URL:"http://driver.invalid"})');
  const original = { storage: globalThis.localStorage, window: globalThis.window, fetch: globalThis.fetch };
  const requests = [];
  globalThis.localStorage = { getItem: () => 'test-session', setItem() {}, removeItem() {} };
  globalThis.window = { setTimeout, clearTimeout, dispatchEvent() {} };
  globalThis.fetch = async (url, options) => {
    requests.push({url, ...options});
    return new Response(JSON.stringify({success:true,data:{path:[],stops:[],id:'report'}}), {headers:{'Content-Type':'application/json'}});
  };
  try {
    const { api } = await import(`data:text/javascript;base64,${Buffer.from(transpiled).toString('base64')}`);
    await api.getCampusRoutePath();
    await api.createReport({category:'other',title:'T',details:'D'});
    await api.updateBusLocation('TEST-BUS', {lat:36,lng:126});
    assert.deepEqual(requests.map(request => request.url), [
      'http://public.invalid/campus/path', 'http://auth.invalid/reports', 'http://driver.invalid/buses/TEST-BUS/location',
    ]);
    assert.equal(requests[0].headers['X-Auth-Token'], undefined);
    assert.equal(requests[1].headers['X-Auth-Token'], 'test-session');
    assert.equal(requests[2].headers['X-Auth-Token'], 'test-session');
    for (const request of requests) assert.equal(request.headers.Authorization, undefined);
    assert.deepEqual(JSON.parse(requests[1].body), {category:'other',title:'T',details:'D'});
    assert.deepEqual(JSON.parse(requests[2].body), {lat:36,lng:126});
  } finally {
    globalThis.localStorage = original.storage;
    globalThis.window = original.window;
    globalThis.fetch = original.fetch;
  }
});
