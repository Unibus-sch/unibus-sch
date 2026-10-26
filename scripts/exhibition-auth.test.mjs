import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transpileModule, ModuleKind, ScriptTarget } from 'typescript';

test('exhibition login sends no displayed credentials and rejects privileged sessions', async () => {
  const source = await readFile(new URL('../src/app/services/api.ts', import.meta.url), 'utf8');
  const code = transpileModule(source, {compilerOptions:{target:ScriptTarget.ES2022,module:ModuleKind.ES2022}}).outputText
    .replace(/import \{ publicAnonKey, supabaseUrl \} from [^;]+;/,
      'const publicAnonKey="anon"; const supabaseUrl="http://supabase.invalid";')
    .replaceAll('import.meta.env','({DEV:false,VITE_AUTH_API_BASE_URL:"http://auth.invalid"})');
  const original = {storage:globalThis.localStorage,window:globalThis.window,fetch:globalThis.fetch};
  let role='user';
  const requests=[], stored=[];
  globalThis.localStorage={getItem:()=>null,setItem:(...args)=>stored.push(args),removeItem(){}};
  globalThis.window={setTimeout,clearTimeout,dispatchEvent(){}};
  globalThis.fetch=async (url, options)=>{
    requests.push({url,...options});
    return new Response(JSON.stringify({success:true,token:'issued-token',user:{role}}),
      {headers:{'Content-Type':'application/json'}});
  };
  try {
    const {api}=await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
    const result=await api.exhibitionLogin();
    assert.equal(result.user.role,'user');
    assert.equal(requests[0].url,'http://auth.invalid/auth/exhibition');
    assert.equal(requests[0].method,'POST');
    assert.equal(requests[0].body,undefined);
    for(role of ['admin','driver']) await assert.rejects(()=>api.exhibitionLogin());
    assert.deepEqual(stored,[]);
    assert.equal(api.getToken(),null);
  } finally {
    globalThis.localStorage=original.storage;
    globalThis.window=original.window;
    globalThis.fetch=original.fetch;
  }
});
