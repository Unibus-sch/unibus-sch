// Execute the unchanged Edge campus handler with an empty, in-memory DB adapter, then
// compare it to a local Spring runtime. No Supabase access or external Naver calls occur.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transpileModule, ModuleKind, ScriptTarget } from 'typescript';

const rawUrl = process.env.CAMPUS_SPRING_URL;
if (!rawUrl) throw new Error('CAMPUS_SPRING_URL is required');
const url = new URL(rawUrl);
if (!['127.0.0.1','localhost','[::1]'].includes(url.hostname) || url.username || url.password
    || !['http:','https:'].includes(url.protocol) || url.search || url.hash || url.pathname !== '/') {
  throw new Error('Campus contract comparison requires a loopback Spring origin');
}
const source = await readFile(new URL('../supabase/functions/make-server/routes/campus.tsx', import.meta.url), 'utf8');
const compiled = transpileModule(source, {
  compilerOptions:{module:ModuleKind.ES2022,target:ScriptTarget.ES2022},
}).outputText.replace(/^import .+;$/gm,'').replace('export default campus;','return campus;');

class LocalHono {
  get(path, handler) { assert.equal(path,'/path'); this.handler = handler; }
}
const query = {
  select() { return this; }, eq() { return this; }, ilike() { return this; }, limit() { return this; },
  async maybeSingle() { return {data:null}; },
};
const edge = new Function('Hono','db','Deno','enforceRateLimit','getRequestIdentity',compiled)(
  LocalHono, {from:() => query}, {env:{get:() => ''}}, async () => null, () => 'local-client');
const context = {header() {}, json(body) { return body; }};
await edge.handler(context); // Warm each implementation so cached flags compare fairly.
await fetch(new URL('/campus/path',url),{signal:AbortSignal.timeout(10000)});
const expected = await edge.handler(context);
const response = await fetch(new URL('/campus/path',url),{signal:AbortSignal.timeout(10000)});
assert.equal(response.status,200);
assert.deepEqual(await response.json(),expected);
assert.equal(response.headers.get('cache-control'),'public, max-age=300, stale-while-revalidate=3600');
console.log('Campus Edge-source vs local Spring: exact fallback JSON, coordinates, stops, cached flag and cache header matched');
