// Local UI layout fixtures only: no Supabase, DB, real sessions or outbound requests.
import { createServer } from 'node:http';

const user = {id:'ui-layout-test',name:'화면 테스트',email:'ui@example.invalid',role:'user',provider:'local'};
const notices = Array.from({length:25}, (_, i) => ({
  id:`ui-notice-${i + 1}`,title:`화면 검증용 공지 ${i + 1}`,content:'스크롤과 화면 크기를 확인하는 로컬 테스트 데이터입니다.',
  category:'general',priority:'low',isPinned:false,viewCount:0,authorId:'ui-layout-test',authorName:'테스트',
  imageUrls:[],createdAt:'2026-10-05T00:00:00Z',updatedAt:'2026-10-05T00:00:00Z',
}));
const server = createServer(async (req, res) => {
  const origin = req.headers.origin;
  if (origin && !['http://127.0.0.1:4185','http://localhost:4185'].includes(origin)) {
    res.writeHead(403); res.end(); return;
  }
  res.setHeader('Access-Control-Allow-Origin', origin || 'http://127.0.0.1:4185');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,X-Auth-Token,Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Cache-Control','no-store');
  if (req.method === 'OPTIONS') {res.writeHead(204);res.end();return;}
  const path = new URL(req.url, 'http://127.0.0.1').pathname;
  let response;
  if (path === '/auth/login' && req.method === 'POST') {
    let body='';for await (const chunk of req) {body+=chunk;if(body.length>4096){res.writeHead(413);res.end();return;}}
    try {
      const login = JSON.parse(body);
      if (login.email !== user.email || login.password !== 'local-ui-test-only') throw Error();
      response={success:true,token:'local-ui-layout-token',user};
    } catch {res.writeHead(401);res.end(JSON.stringify({success:false,error:'Local fixture credentials required'}));return;}
  } else if (path === '/auth/logout' && req.method === 'POST') response={success:true};
  else if (req.method === 'GET' && path === '/notices') response={success:true,data:notices};
  else if (req.method === 'GET' && ['/routes','/buses','/buses/locations/latest'].includes(path)) response={success:true,data:[]};
  else if (req.method === 'GET' && path === '/campus/path') response={success:true,data:{path:[],stops:[],cached:true}};
  else {res.writeHead(404);res.end(JSON.stringify({success:false,error:'Not a UI fixture endpoint'}));return;}
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify(response));
});
server.listen(4186,'127.0.0.1',()=>console.log('Local-only UI fixtures ready on 127.0.0.1:4186; no remote data is used.'));
