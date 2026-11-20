// hb.pwa 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/45 M-1)
// 서비스 워커가 다룰 요청은 같은 출처 GET 정적 경로뿐(API·HTML·POP·다른 출처·경로 조작 제외), 넣을 응답은 200·basic·리다이렉트·HTML 아님,
// 캐시 비우기는 이 앱(yeonos-*) 것만
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {isCacheable, isStorable, clearCaches, safeOpenPath, CACHE} = require('../../main/resources/static/js/common/hb.pwa.js');

const O = 'https://mes.example.test';

test('정적 경로만 — css·js·vendor·images·manifest', () => {
    for (const p of ['/css/page.css', '/js/common/hb.js', '/js/shell.js?v=3', '/vendor/kendo/2025.1.1/kendo.js',
        '/images/pwa/icon-192.png', '/manifest.webmanifest']) {
        assert.equal(isCacheable(O + p, 'GET', O), true, p);
    }
    assert.ok(CACHE.startsWith('yeonos-'));
});

test('API·HTML·POP·로그인·워커 자신은 다루지 않는다', () => {
    for (const p of ['/api/home/cards', '/api/platform/messages', '/main', '/page/sys-outbox', '/login', '/login?logout',
        '/pop', '/pop/home', '/api/pop/badge', '/sw.js', '/', '/status.json', '/pda/version.json', '/css/', '/js',
        '/jsx/a.js', '/manifest.webmanifest/x']) {
        assert.equal(isCacheable(O + p, 'GET', O), false, p);
    }
});

test('GET 아닌 요청·다른 출처·경로 조작은 다루지 않는다', () => {
    assert.equal(isCacheable(O + '/js/a.js', 'POST', O), false);
    assert.equal(isCacheable(O + '/js/a.js', 'HEAD', O), false);
    assert.equal(isCacheable('https://cdn.example.com/js/a.js', 'GET', O), false);
    assert.equal(isCacheable('http://mes.example.test/js/a.js', 'GET', O), false, '스킴이 다르면 다른 출처');
    // URL 이 정규화하는 점은 API 로 풀린다 → 제외, 인코딩한 점은 남겨 둔다 → 제외
    assert.equal(isCacheable(O + '/js/../api/home/cards', 'GET', O), false);
    assert.equal(isCacheable(O + '/js/%2e%2e/api/home/cards', 'GET', O), false);
    assert.equal(isCacheable(O + '/js/%2E%2E/main', 'GET', O), false);
    assert.equal(isCacheable('not a url', 'GET', 'also bad'), false);
});

function res(status, type, ct, redirected) {
    return {status, type, redirected: !!redirected, headers: {get: (n) => (n.toLowerCase() === 'content-type' ? ct : null)}};
}

test('넣을 응답 — 200·basic·리다이렉트 아님·HTML 아님', () => {
    assert.equal(isStorable(res(200, 'basic', 'text/javascript')), true);
    assert.equal(isStorable(res(200, 'basic', 'text/css;charset=UTF-8')), true);
    assert.equal(isStorable(res(200, 'basic', 'image/png')), true);
    assert.equal(isStorable(res(304, 'basic', 'text/css')), false);
    assert.equal(isStorable(res(404, 'basic', 'text/html')), false);
    assert.equal(isStorable(res(200, 'opaque', '')), false);
    assert.equal(isStorable(res(200, 'basic', 'text/css', true)), false, '로그인 화면으로 돌려진 정적 경로');
    assert.equal(isStorable(res(200, 'basic', 'TEXT/HTML; charset=utf-8')), false, '정적 경로인데 HTML(오류 화면)');
    assert.equal(isStorable(null), false);
});

test('캐시 비우기는 이 앱(yeonos-*) 것만, 캐시 API 가 없거나 실패해도 0', async () => {
    const deleted = [];
    const fake = {keys: async () => ['yeonos-static-v1', 'yeonos-static-v0', 'other-app'], delete: async (n) => { deleted.push(n); return true; }};
    assert.equal(await clearCaches(fake), 2);
    assert.deepEqual(deleted.sort(), ['yeonos-static-v0', 'yeonos-static-v1']);
    assert.equal(await clearCaches(undefined), 0);
    assert.equal(await clearCaches({keys: async () => { throw new Error('denied'); }, delete: async () => true}), 0);
});

test('푸시 알림을 누르면 같은 출처 경로만 연다 — 다른 사이트·스킴·"//"·역슬래시·제어 문자는 /main', () => {
    assert.equal(safeOpenPath('/main'), '/main');
    assert.equal(safeOpenPath('/page/sys-approval'), '/page/sys-approval');
    for (const bad of ['https://evil.example/', '//evil.example/x', '/\\evil.example', 'javascript:alert(1)', 'main', '',
        '/a\nb', null, undefined, 42, {}, '/' + 'x'.repeat(300)]) {
        assert.equal(safeOpenPath(bad), '/main', String(bad));
    }
});
