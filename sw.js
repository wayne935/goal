/* Goal 成就板 service worker：離線可用。
   頁面本身用「網路優先」，確保更新後馬上拿到新版；離線時才用快取。 */
const CACHE = 'goal-v3';
const CORE = ['./', './index.html', './manifest.json', './icons/icon-192.png', './icons/icon-512.png', './icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // 資料檔與 inbox 不快取（它們由網頁直接讀寫資料夾）
  if (url.origin === location.origin && /\/(inbox|data)\//.test(url.pathname)) return;
  const isPage = req.mode === 'navigate' || (url.origin === location.origin && url.pathname.endsWith('.html'));
  if (isPage) {
    e.respondWith(fetch(req).then(res => { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return res; })
      .catch(() => caches.match(req).then(r => r || caches.match('./index.html'))));
    return;
  }
  if (/fonts\.(googleapis|gstatic)\.com$/.test(url.hostname) || url.origin === location.origin) {
    e.respondWith(caches.match(req).then(hit => {
      const net = fetch(req).then(res => { if (res.ok || res.type === 'opaque') { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); } return res; }).catch(() => hit);
      return hit || net;
    }));
  }
});
