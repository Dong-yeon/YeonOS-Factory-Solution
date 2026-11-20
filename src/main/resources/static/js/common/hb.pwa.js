// @ts-check
/*
 * PWA 셸 hb.pwa (docs/45 5장 M-1, 2.1·2.2 CSP 실측) — 로그인 화면·셸(main)만 싣는다. 업무 화면(iframe)은 같은 워커 범위라 따로 싣지 않는다.
 *
 * 정한 것(사용자 2026-10-29):
 *   - 서비스 워커는 **정적 자원만** 캐시한다 — /css/·/js/·/vendor/·/images/·manifest. API·HTML(/main·/page/**·/login·/pop/**)은
 *     가로채지도 않는다(다른 계정·회사 데이터가 단말에 남지 않게). 판정은 isCacheable 하나 — 워커(/sw.js)도 importScripts 로 같은 함수를 쓴다
 *   - 네트워크 먼저, 끊겼을 때만 캐시 — 배포 뒤에도 옛 JS 가 새 API 와 섞이지 않는다
 *   - 로그아웃(로그인 화면 도착 = 세션 없음)마다 캐시를 비운다(clearCaches) — 로그인 화면이 부른다
 *
 * hb.pwa.register()      서비스 워커 등록(지원·보안 출처일 때만, 실패는 조용히 — 앱은 워커 없이도 그대로 동작)
 * hb.pwa.clearCaches()   이 앱이 만든 캐시(yeonos-*)를 지운다 → 지운 수
 * hb.pwa.isCacheable(url, method, origin)
 */
(function (global) {
    'use strict';

    var PREFIX = 'yeonos-';
    var CACHE = PREFIX + 'static-v1';
    var STATIC_DIRS = ['/css/', '/js/', '/vendor/', '/images/'];
    var STATIC_FILES = ['/manifest.webmanifest'];

    /**
     * 캐시해도 되는 요청인가 — 같은 출처 GET 의 정적 경로만. 쿼리는 무시하고 경로로 본다.
     * 경로 조작(`/js/../api/x`, 인코딩한 점)은 URL 이 정규화한 경로로 판정한다
     * @param {string} url @param {string} method @param {string} origin
     */
    function isCacheable(url, method, origin) {
        if (method !== 'GET') { return false; }
        var u;
        try { u = new URL(url, origin); } catch (e) { return false; }
        if (u.origin !== origin) { return false; }
        var path = u.pathname;
        if (path.indexOf('%2e') >= 0 || path.indexOf('%2E') >= 0 || path.indexOf('/..') >= 0) { return false; }
        if (STATIC_FILES.indexOf(path) >= 0) { return true; }
        for (var i = 0; i < STATIC_DIRS.length; i++) {
            if (path.indexOf(STATIC_DIRS[i]) === 0 && path.length > STATIC_DIRS[i].length) { return true; }
        }
        return false;
    }

    /**
     * 받은 응답을 캐시에 넣어도 되는가 — 200·같은 출처(basic)·리다이렉트 아님·HTML 아님
     * (정적 경로가 로그인 화면으로 돌려졌거나 오류 화면이면 넣지 않는다)
     * @param {{status: number, type: string, redirected: boolean, headers: {get(name: string): string | null}}} res
     */
    function isStorable(res) {
        if (!res || res.status !== 200 || res.type !== 'basic' || res.redirected) { return false; }
        var ct = (res.headers.get('Content-Type') || '').toLowerCase();
        return ct.indexOf('text/html') < 0;
    }

    /**
     * 푸시 알림을 눌렀을 때 열 경로 — 같은 출처의 "/…" 경로만(스킴·호스트·"//"·역슬래시·제어 문자 거부), 아니면 셸 /main (docs/45 9장 N11)
     * @param {unknown} u
     */
    function safeOpenPath(u) {
        if (typeof u !== 'string' || u.length > 200 || u.charAt(0) !== '/' || u.charAt(1) === '/' || /[\\\u0000-\u001f]/.test(u)) {
            return '/main';
        }
        return u;
    }

    /** @param {{keys(): Promise<string[]>, delete(name: string): Promise<boolean>} | undefined} caches */
    function clearCaches(caches) {
        if (!caches) { return Promise.resolve(0); }
        return caches.keys().then(function (names) {
            var mine = names.filter(function (n) { return n.indexOf(PREFIX) === 0; });
            return Promise.all(mine.map(function (n) { return caches.delete(n); })).then(function () { return mine.length; });
        }, function () { return 0; });
    }

    var api = {
        CACHE: CACHE, PREFIX: PREFIX, isCacheable: isCacheable, isStorable: isStorable, safeOpenPath: safeOpenPath,
        /** @returns {Promise<number>} */
        clearCaches: function () {
            return clearCaches(typeof caches === 'undefined' ? undefined : caches);
        },
        /** @returns {Promise<boolean>} */
        register: function () {
            if (typeof navigator === 'undefined' || !('serviceWorker' in navigator) || !global.isSecureContext) {
                return Promise.resolve(false);
            }
            return navigator.serviceWorker.register('/sw.js', {scope: '/'}).then(function () { return true; },
                function () { return false; });
        }
    };

    global.hb = global.hb || {};
    global.hb.pwa = api;
    // node 단위 시험(src/test/js/hb.pwa.test.js) — 브라우저·워커에는 module 이 없다
    if (typeof module === 'object' && module && module.exports) {
        module.exports = {isCacheable: isCacheable, isStorable: isStorable, clearCaches: clearCaches, safeOpenPath: safeOpenPath, CACHE: CACHE};
    }
})(/** @type {any} */ (typeof self !== 'undefined' ? self : globalThis));
