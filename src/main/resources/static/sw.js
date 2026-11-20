/*
 * 서비스 워커 (docs/45 M-1, 2.1·2.2) — 범위 '/'. 정적 자원만 다룬다: 네트워크 먼저, 끊겼을 때만 캐시.
 * API·HTML 은 respondWith 를 부르지 않는다 = 브라우저 기본 동작 그대로(캐시에 남지 않는다).
 * 판정 규칙은 /js/common/hb.pwa.js 의 isCacheable·isStorable 하나(화면 쪽 단위 시험과 같은 코드).
 * CSP: 같은 출처 파일만 importScripts — script-src 'self' 그대로(worker-src 없음 → script-src 로 떨어짐).
 * 캐시 이름을 바꾸면(규칙이 바뀌면) activate 때 옛 캐시를 지운다.
 */
/* global self, caches, fetch, importScripts */
'use strict';

importScripts('/js/common/hb.pwa.js');
var pwa = self.hb.pwa;

self.addEventListener('install', function () {
    self.skipWaiting();
});

self.addEventListener('activate', function (event) {
    event.waitUntil(caches.keys().then(function (names) {
        return Promise.all(names.filter(function (n) { return n.indexOf(pwa.PREFIX) === 0 && n !== pwa.CACHE; })
            .map(function (n) { return caches.delete(n); }));
    }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (event) {
    var req = event.request;
    if (!pwa.isCacheable(req.url, req.method, self.location.origin)) {
        return;   // API·HTML·POP·다른 출처 — 손대지 않는다
    }
    event.respondWith(fetch(req).then(function (res) {
        if (pwa.isStorable(res)) {
            var copy = res.clone();
            event.waitUntil(caches.open(pwa.CACHE).then(function (c) { return c.put(req, copy); }));
        }
        return res;
    }, function (err) {
        return caches.open(pwa.CACHE).then(function (c) { return c.match(req); }).then(function (hit) {
            if (hit) { return hit; }
            throw err;
        });
    }));
});

/*
 * Web Push (docs/45 9장 N4·N11) — 서버가 보낸 것은 {t: 알림 종류 이름, u: 여는 주소}. 잠금 화면에는 앱 이름 + 알림 종류만 보인다.
 * 누르면 같은 출처의 주소만 연다(다른 사이트로 보내지 못하게) — 아니면 셸 /main. 이미 열린 창이 있으면 그 창을 앞으로
 */
self.addEventListener('push', function (event) {
    var d = {};
    try { d = event.data ? event.data.json() : {}; } catch (e) { d = {}; }
    var body = typeof d.t === 'string' ? d.t.slice(0, 120) : '';
    event.waitUntil(self.registration.showNotification('YeonOS', {
        body: body, icon: '/images/pwa/icon-192.png', badge: '/images/pwa/icon-192.png', tag: 'yeonos-notify', renotify: true,
        data: {u: pwa.safeOpenPath(d.u)}
    }));
});

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    var path = pwa.safeOpenPath(event.notification.data && event.notification.data.u);
    event.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(function (list) {
        for (var i = 0; i < list.length; i++) {
            if (new URL(list[i].url).origin === self.location.origin && 'focus' in list[i]) {
                return list[i].focus().then(function (c) { return c && 'navigate' in c ? c.navigate(path) : c; });
            }
        }
        return self.clients.openWindow(path);
    }));
});
