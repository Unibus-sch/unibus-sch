import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareNoticeImage } from './staging-notice-image.mjs';

const environment = {
  UNIBUS_TARGET_ENV:'staging', STAGING_PROJECT_REF:'srxzkpdtxmqrtcxgpeyl',
  STAGING_API_URL:'https://api-staging.unibus-sch.com', STAGING_FIXTURE_MODE:'apply',
  STAGING_ADMIN_EMAIL:'admin@example.invalid', STAGING_ADMIN_PASSWORD:'local-test-password',
};
const success = body => new Response(JSON.stringify({success:true,...body}), {headers:{'Content-Type':'application/json'}});

test('rejects a wrong target before any login or upload', async () => {
  await assert.rejects(() => prepareNoticeImage({...environment,STAGING_API_URL:'https://production.invalid'},new Uint8Array(),
    () => {throw new Error('must not request');}), /Explicit staging/);
});

test('logs in with fixture account, uploads multipart, links only fixture notice, revokes only its own session', async () => {
  const calls = [];
  const imageUrl = 'https://srxzkpdtxmqrtcxgpeyl.supabase.co/storage/v1/object/public/notice-images/test.png';
  await prepareNoticeImage(environment, new Uint8Array([137,80,78,71]), async (url, options) => {
    calls.push({url,options});
    if (url.endsWith('/auth/login')) return success({token:'local-session',user:{id:'e2e00000-0000-4000-8000-000000000001',role:'admin'}});
    if (url.endsWith('/notices/images')) return success({data:{url:imageUrl}});
    return success({});
  });
  assert.equal(calls.length,4);
  assert.equal(calls[1].options.body.get('file').type,'image/png');
  assert.equal(calls[1].options.headers['X-Auth-Token'],'local-session');
  assert.deepEqual(JSON.parse(calls[2].options.body),{imageUrls:[imageUrl]});
  assert.ok(calls[2].url.endsWith('/notices/e2e00000-0000-4000-8000-000000000021'));
  assert.ok(calls[3].url.endsWith('/auth/logout'));
});

test('upload failure still revokes script session and does not update notice', async () => {
  const calls = [];
  await assert.rejects(() => prepareNoticeImage(environment,new Uint8Array(),async (url) => {
    calls.push(url);
    if (url.endsWith('/auth/login')) return success({token:'local-session',user:{id:'e2e00000-0000-4000-8000-000000000001',role:'admin'}});
    if (url.endsWith('/notices/images')) return new Response('failure',{status:503});
    return success({});
  }), /503/);
  assert.equal(calls.length,3);
  assert.ok(calls[2].endsWith('/auth/logout'));
});
