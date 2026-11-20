// hb.pop 배지(+PIN) 로그인 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/25 4.34)
// POP 작업장 홈·PDA 창고 홈이 같이 쓰는 흐름: PIN 요구 → PIN 화면, 틀린 PIN 은 머묾, 그 밖 오류는 배지 화면, 작업자 끊김
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeBadgeLogin} = require('../../main/resources/static/js/common/hb.pop.js');

/** 가짜 서버(응답을 차례로 꺼낸다: {data} | {code}) + 화면 기록 */
function fixture(responses) {
    const log = [];
    const posted = [];
    const login = makeBadgeLogin({
        post: (body) => {
            posted.push(body);
            const r = responses.shift();
            return r && r.code ? Promise.reject(r) : Promise.resolve(r);
        },
        busy: (button, fn) => { log.push('busy'); return fn(); },
        pad: {clear: () => log.push('clear')},
        showPanel: (p) => log.push('panel:' + p),
        hello: (r) => log.push('hello:' + r.workerNm),
        onPinAsked: (r) => log.push('pin?' + r.workerNm),
        onSignedIn: (r) => { log.push('in:' + r.workerNm); return Promise.resolve(); },
        onGone: () => log.push('gone'),
        fail: (e) => log.push('fail:' + e.code)
    });
    return {login, log, posted};
}

test('배지만으로 들어가면 인사·onSignedIn, 받아 둔 배지 없음', async () => {
    const f = fixture([{data: {workerNm: '홍작업', needPin: false}}]);
    await f.login.submit('B-1', null, null);
    assert.deepEqual(f.posted, [{badge: 'B-1', pin: null}]);
    assert.deepEqual(f.log, ['clear', 'hello:홍작업', 'in:홍작업']);
    assert.equal(f.login.pending(), false);
});

test('PIN 요구 → PIN 화면, 키패드 확정은 받아 둔 배지 + PIN 으로(버튼 busy)', async () => {
    const f = fixture([{data: {workerNm: '홍작업', needPin: true}}, {data: {workerNm: '홍작업', needPin: false}}]);
    await f.login.submit('B-1', null, null);
    assert.equal(f.login.pending(), true);
    assert.deepEqual(f.log, ['clear', 'pin?홍작업', 'panel:pin']);
    await f.login.onPin('1234', {});
    assert.deepEqual(f.posted[1], {badge: 'B-1', pin: '1234'});
    assert.equal(f.login.pending(), false);
    assert.deepEqual(f.log.slice(3), ['busy', 'clear', 'hello:홍작업', 'in:홍작업']);
});

test('틀린 PIN 은 PIN 화면에 머물고(받아 둔 배지 그대로), 그 밖 오류는 배지 화면으로', async () => {
    const f = fixture([{data: {workerNm: '홍작업', needPin: true}}, {code: 'PIN_WRONG'}, {code: 'PIN_LOCKED'}]);
    await f.login.submit('B-1', null, null);
    await f.login.onPin('0000', null);
    assert.equal(f.login.pending(), true);
    assert.deepEqual(f.log.slice(3), ['clear', 'fail:PIN_WRONG']);
    await f.login.onPin('0000', null);
    assert.equal(f.login.pending(), false);
    assert.deepEqual(f.log.slice(5), ['clear', 'panel:badge', 'fail:PIN_LOCKED']);
});

test('받아 둔 배지가 없으면 키패드 확정은 아무것도 보내지 않는다 / 취소는 배지를 버린다', async () => {
    const f = fixture([{data: {workerNm: '홍작업', needPin: true}}]);
    await f.login.onPin('1234', null);
    assert.equal(f.posted.length, 0);
    await f.login.submit('B-1', null, null);
    f.login.cancel();
    assert.equal(f.login.pending(), false);
    await f.login.onPin('1234', null);
    assert.equal(f.posted.length, 1);
    assert.deepEqual(f.log.slice(-2), ['clear', 'panel:badge']);
});

test('작업자 끊김(POP_NO_WORKER)만 배지 화면 + onGone, 다른 오류는 그대로 둔다', () => {
    const f = fixture([]);
    assert.equal(f.login.gone({code: 'INSUFFICIENT_STOCK'}), false);
    assert.equal(f.login.gone(null), false);
    assert.deepEqual(f.log, []);
    assert.equal(f.login.gone({code: 'POP_NO_WORKER'}), true);
    assert.deepEqual(f.log, ['panel:badge', 'gone']);
});
