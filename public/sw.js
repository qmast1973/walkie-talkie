// 앱 설치용 서비스 워커: 화면 파일만 캐시(무전 음성·실시간 연결은 캐시하지 않음)
const CACHE = 'wt-shell-v1';
const SHELL = ['/', '/capture-worklet.js', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/socket.io')) return;
  // 항상 최신 화면을 먼저 받고, 네트워크가 없을 때만 캐시 사용
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok && SHELL.includes(url.pathname)) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
        return res;
      })
      .catch(() => caches.match(e.request, { ignoreSearch: true }).then((r) => r || caches.match('/')))
  );
});
