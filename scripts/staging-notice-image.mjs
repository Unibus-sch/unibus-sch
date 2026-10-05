import { readFile } from 'node:fs/promises';

const project = 'srxzkpdtxmqrtcxgpeyl';
const noticeId = 'e2e00000-0000-4000-8000-000000000021';
const adminId = 'e2e00000-0000-4000-8000-000000000001';

export async function prepareNoticeImage(environment, image, request = fetch) {
  if (environment.UNIBUS_TARGET_ENV !== 'staging' || environment.STAGING_PROJECT_REF !== project
      || environment.STAGING_API_URL !== 'https://api-staging.unibus-sch.com'
      || environment.STAGING_FIXTURE_MODE !== 'apply') {
    throw new Error('Explicit staging project, API and apply mode are required');
  }
  if (!environment.STAGING_ADMIN_EMAIL?.endsWith('@example.invalid') || !environment.STAGING_ADMIN_PASSWORD) {
    throw new Error('New synthetic staging admin credentials are required');
  }
  const api = environment.STAGING_API_URL;
  async function json(path, options) {
    const response = await request(api + path, {...options, redirect:'error', signal:AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error(`Staging request failed (${response.status})`);
    const body = await response.json();
    if (!body.success) throw new Error('Staging API rejected the request');
    return body;
  }
  const login = await json('/auth/login', {method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify({email:environment.STAGING_ADMIN_EMAIL,password:environment.STAGING_ADMIN_PASSWORD})});
  if (!login.token) throw new Error('Staging login did not return a session');
  const headers = {'X-Auth-Token':login.token};
  try {
    if (login.user?.id !== adminId || login.user?.role !== 'admin') {
      throw new Error('Login must resolve to the reserved staging fixture admin');
    }
    const form = new FormData();
    form.set('file', new Blob([image], {type:'image/png'}), 'staging-notice.png');
    const uploaded = await json('/notices/images', {method:'POST', headers, body:form});
    const url = uploaded.data?.url;
    if (!url?.startsWith(`https://${project}.supabase.co/storage/v1/object/public/notice-images/`)) {
      throw new Error('Uploaded image must belong to staging Storage');
    }
    await json(`/notices/${noticeId}`, {method:'PUT', headers:{...headers,'Content-Type':'application/json'},
      body:JSON.stringify({imageUrls:[url]})});
    return {noticeId, imageUrl:url};
  } finally {
    // Revoke only the session this script just created; unrelated tokens remain untouched.
    const logout = await request(api + '/auth/logout', {method:'POST',headers,redirect:'error',signal:AbortSignal.timeout(15000)});
    if (!logout.ok) throw new Error('Script-created staging session could not be revoked');
  }
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  try {
    const image = await readFile(new URL('../backend/build/staging-fixtures/notice-image.png', import.meta.url));
    await prepareNoticeImage(process.env, image);
    console.log('Staging notice image uploaded and linked; temporary login session revoked.');
  } catch {
    console.error('Staging image preparation failed. Check local credentials, deployed API and Storage configuration. No secrets are printed.');
    process.exitCode = 1;
  }
}
