// 用 Node 測 sync/tmdb-function.ts（TMDB 代轉）：假的 Deno.env / Deno.serve / fetch，不會真的連到 TMDB 或 Supabase。
// 用法：node sync/test/tmdb-function-test.mjs
import { readFileSync } from 'node:fs';
const src = readFileSync(new URL('../tmdb-function.ts', import.meta.url), 'utf8');
let handler = null, calls = [], env = { SUPABASE_URL: 'https://proj.supabase.co', TMDB_KEY: 'k'.repeat(32) };
const Deno = { env: { get: k => env[k] }, serve: h => { handler = h; } };
const fakeFetch = async (url, opt = {}) => {
  calls.push({ url: String(url), headers: opt.headers || {} });
  if (String(url).endsWith('/auth/v1/user')) return new Response('{}', { status: opt.headers.authorization === 'Bearer aaa.bbb.ccc' ? 200 : 401 });
  if (String(url).includes('/3/movie/404404')) return new Response('{"status_code":34}', { status: 404 });
  return new Response('{"results":[{"id":603,"media_type":"movie","title":"駭客任務"}]}', { status: 200 });
};
new Function('Deno', 'fetch', src)(Deno, fakeFetch);
const H = { authorization: 'Bearer aaa.bbb.ccc', apikey: 'sb_publishable_x' };
const req = (q, opt = {}) => new Request(`https://proj.supabase.co/functions/v1/tmdb?${q}`, { headers: H, ...opt });
let pass = 0, fail = 0;
async function check(name, r, want, more) {
  const res = await r, body = await res.text(), ok = res.status === want && (!more || more(body, res));
  ok ? pass++ : fail++;
  console.log(`${ok ? '✓' : '✗'} ${name}（${res.status}${ok ? '' : `，應該 ${want}`}）${ok ? '' : ' ' + body.slice(0, 120)}`);
}
const last = () => calls[calls.length - 1];
await check('搜尋', handler(req('path=/search/multi&query=駭客&language=zh-TW')), 200, b => b.includes('駭客任務') && last().url.startsWith('https://api.themoviedb.org/3/search/multi?') && last().url.includes('api_key=kkkk') && last().url.includes('include_adult=false'));
await check('CORS 預檢', handler(new Request('https://proj.supabase.co/functions/v1/tmdb', { method: 'OPTIONS' })), 200, (b, r) => r.headers.get('access-control-allow-origin') === '*');
await check('回應有 CORS', handler(req('path=/movie/603')), 200, (b, r) => r.headers.get('access-control-allow-origin') === '*');
await check('影集的一季', handler(req('path=/tv/1396/season/2')), 200);
calls = [];
await check('沒登入', handler(new Request('https://proj.supabase.co/functions/v1/tmdb?path=/search/multi&query=x')), 401, () => calls.length === 0);
await check('token 不對', handler(req('path=/search/multi&query=x', { headers: { authorization: 'Bearer xxx.yyy.zzz', apikey: 'k' } })), 401, () => !calls.some(c => c.url.includes('themoviedb')));
await check('只帶 publishable key（不是登入的 token）', handler(req('path=/search/multi&query=x', { headers: { authorization: 'Bearer sb_publishable_x', apikey: 'sb_publishable_x' } })), 401);
await check('不在白名單的網址', handler(req('path=/account/1/rated/movies')), 400);
await check('想跳出 /3/', handler(req('path=/../../evil')), 400);
await check('網址夾帶參數', handler(req(`path=${encodeURIComponent('/movie/603?api_key=other')}`)), 400);
await check('POST 不行', handler(req('path=/movie/603', { method: 'POST' })), 405);
await check('TMDB 404 回 502（網頁才不會當成函式沒建立）', handler(req('path=/movie/404404')), 502);
await check('語言只接受清單裡的', handler(req('path=/movie/603&language=xx')), 200, () => last().url.includes('language=zh-TW'));
await check('不轉送其他參數', handler(req('path=/movie/603&api_key=steal&include_adult=true&append_to_response=x')), 200, () => !last().url.includes('steal') && !last().url.includes('append_to_response') && last().url.includes('include_adult=false'));
env.TMDB_KEY = 'eyJ' + 'a'.repeat(80);
await check('長的 token 用 Bearer', handler(req('path=/movie/603')), 200, () => last().headers.authorization?.startsWith('Bearer eyJ') && !last().url.includes('api_key'));
env.TMDB_KEY = '';
await check('還沒設金鑰', handler(req('path=/movie/603')), 500, b => b.includes('no_key'));
console.log(`\n${pass} 項通過，${fail} 項失敗`);
process.exit(fail ? 1 : 0);
