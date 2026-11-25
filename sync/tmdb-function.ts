// Goal 成就板：TMDB 代轉（Supabase Edge Function，build 24）
// 為什麼要代轉：repo 和網頁是公開的，TMDB 金鑰放在網頁裡誰都看得到。放在這裡，只有登入 Goal 的人能用。
//
// 設定方法（只要做一次，大約 5 分鐘）：
// 1. Supabase 後台 → Edge Functions → Deploy a new function → Via Editor。
// 2. 函式名稱填 tmdb（一定要是這個名字），把這整個檔案貼上去，按 Deploy。
// 3. Edge Functions → tmdb → Details（或 Settings）→ 把「Verify JWT with legacy secret / Enforce JWT verification」關掉，存檔。
//    （這個函式自己會檢查登入，見下面的 signedIn()；不關的話，新版的金鑰會被擋成 401。）
// 4. Edge Functions → Secrets → Add new secret：名稱 TMDB_KEY，值貼 TMDB 的「API Read Access Token」（很長的那串）
//    或「API Key」（32 個字）都可以，按 Save。
//
// 網頁只會用到下面 ALLOW 裡的幾個網址（搜尋、電影 / 影集的資料），其他一律拒絕。
// 這個檔案故意不寫 TypeScript 的型別（所以關掉型別檢查），讓 sync/test/tmdb-function-test.mjs 可以直接用 Node 測。
// @ts-nocheck

const ALLOW = [/^\/search\/(multi|movie|tv)$/, /^\/(movie|tv)\/\d{1,9}$/, /^\/tv\/\d{1,9}\/season\/\d{1,3}$/];
const PASS = ['query', 'page', 'year', 'first_air_date_year'];
const LANGS = ['zh-TW', 'zh-CN', 'en-US', 'ja-JP'];
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
};
const json = (body, status = 200, extra = {}) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, 'content-type': 'application/json', ...extra } });

// 用登入的 token 問 Supabase Auth「這是誰」，認得才放行（不管專案用的是舊的 JWT secret 還是新的簽章金鑰都適用）
async function signedIn(req) {
  const auth = req.headers.get('authorization') || '', apikey = req.headers.get('apikey') || '';
  if (!/^Bearer [\w-]+\.[\w-]+\.[\w-]+$/.test(auth) || !apikey) return false;
  const r = await fetch(`${Deno.env.get('SUPABASE_URL')}/auth/v1/user`, { headers: { authorization: auth, apikey } });
  return r.ok;
}

async function handle(req) {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });
  if (req.method !== 'GET') return json({ error: 'method' }, 405);
  const key = (Deno.env.get('TMDB_KEY') || '').trim();
  if (!key) return json({ error: 'no_key' }, 500);
  if (!(await signedIn(req))) return json({ error: 'not_logged_in' }, 401);
  const u = new URL(req.url), path = u.searchParams.get('path') || '';
  if (!ALLOW.some(r => r.test(path))) return json({ error: 'bad_path' }, 400);
  const q = new URLSearchParams();
  for (const k of PASS) { const v = u.searchParams.get(k); if (v) q.set(k, v.slice(0, 100)); }
  const lang = u.searchParams.get('language');
  q.set('language', LANGS.includes(lang) ? lang : 'zh-TW');
  q.set('include_adult', 'false');
  const headers = { accept: 'application/json' };
  if (key.length > 40) headers.authorization = `Bearer ${key}`;   // API Read Access Token（v4）
  else q.set('api_key', key);                                      // API Key（v3）
  const r = await fetch(`https://api.themoviedb.org/3${path}?${q}`, { headers });
  if (!r.ok) return json({ error: 'tmdb', status: r.status }, 502);   // 不回 404：網頁把 404 當成「函式還沒建立」
  return new Response(await r.text(), { status: 200, headers: { ...CORS, 'content-type': 'application/json', 'cache-control': 'private, max-age=3600' } });
}

Deno.serve(req => handle(req).catch(() => json({ error: 'failed' }, 502)));
