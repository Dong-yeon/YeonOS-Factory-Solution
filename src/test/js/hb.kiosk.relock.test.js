// hb.kiosk 늦은 응답·해제 실패 알림 — 실행: node --test src/test/js/*.test.js (docs/50 KS8·KS9, QA 회차 4 K-c·K-d)
// 빠져나오기 응답을 기다리는 사이 다시 잠갔으면 풀지 않는다, 서버는 성공으로 적었는데 풀지 못했으면 exitRef 로 한 번 알린다
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeKiosk} = require('../../main/resources/static/js/common/hb.kiosk.js');

const tick = () => new Promise((res) => setImmediate(res));

/** 서버 응답을 손으로 풀어 주는 가짜. refuseOff = 기기가 kiosk(false) 거절, slowOff = kiosk(false) 끝도 손으로 */
function fixture(opts) {
    const o = Object.assign({exitRef: 'ex-1'}, opts);
    const calls = [];
    const reports = [];
    const timers = new Map();
    let seq = 0;
    let answer = null;
    let offDone = null;
    const bridge = {
        kiosk: (x) => {
            calls.push(x.on);
            if (!x.on && o.refuseOff) { return Promise.reject(new Error('lock task refused')); }
            if (!x.on && o.slowOff) { return new Promise((res) => { offDone = res; }); }
            return Promise.resolve();
        }
    };
    const k = makeKiosk({
        bridge: () => bridge,
        post: () => new Promise((res) => { answer = res; }),
        report: (body) => { reports.push(body); return o.reportFails ? Promise.reject(new Error('404')) : Promise.resolve(); },
        setTimer: (fn, ms) => { const id = ++seq; timers.set(id, {fn, ms}); return id; },
        clearTimer: (id) => timers.delete(id)
    });
    const reply = () => answer({data: Object.assign({approverCd: 'S1', approverNm: '감독', relockAfterSec: 600},
        o.exitRef === null ? {} : {exitRef: o.exitRef})});
    return {k, calls, reports, timers, reply, finishOff: () => offDone()};
}

test('K-c: 응답 전에 작업자가 바뀌면(다시 잠금) 늦게 온 성공으로 풀지 않는다 — KIOSK_STALE, 다리 false 없음, 타이머 없음', async () => {
    const f = fixture();
    f.k.worker('A');
    const p = f.k.exit('S-BADGE', '2468');
    f.k.worker('B');                 // 응답 전 작업자 바뀜 → 다시 잠금
    f.reply();
    await assert.rejects(p, (e) => e.code === 'KIOSK_STALE');
    await tick();
    assert.deepEqual(f.calls, [true], '잠금만 — 고정을 풀지 않았다');
    assert.equal(f.timers.size, 0);
    assert.equal(f.k.unlocked(), false);
    assert.deepEqual(f.reports, [{exitRef: 'ex-1', reason: 'stale'}], '서버는 성공으로 적었으니 한 번 알린다');
});

test('K-c: 응답 전 홈이 다시 열려도(lock) 같다', async () => {
    const f = fixture();
    const p = f.k.exit('S', '1');
    await f.k.lock();
    f.reply();
    await assert.rejects(p, (e) => e.code === 'KIOSK_STALE');
    assert.deepEqual(f.calls, [true]);
});

test('K-c: 같은 작업자 알림(변화 없음)은 다시 잠금이 아니다 — 그대로 풀린다', async () => {
    const f = fixture();
    f.k.worker('A');
    const p = f.k.exit('S', '1');
    f.k.worker('A');
    f.reply();
    assert.equal((await p).approverCd, 'S1');
    assert.deepEqual(f.calls, [false]);
    assert.equal(f.k.unlocked(), true);
    assert.deepEqual(f.reports, []);
});

test('K-c: 다리가 kiosk(false) 를 처리하는 사이 다시 잠그면 — 다리 순서 false → true 로 잠긴 채, 타이머·알림 없음', async () => {
    const f = fixture({slowOff: true});
    f.k.worker('A');
    const p = f.k.exit('S', '1');
    f.reply();
    await tick();
    assert.deepEqual(f.calls, [false]);
    f.k.worker('B');
    f.finishOff();
    await assert.rejects(p, (e) => e.code === 'KIOSK_STALE');
    await tick();
    assert.deepEqual(f.calls, [false, true]);
    assert.equal(f.timers.size, 0);
    assert.deepEqual(f.reports, [], '잠깐 풀렸다 — 서버 기록과 어긋나지 않는다');
});

test('K-d: 기기가 kiosk(false) 를 거절하면 그 오류로 reject, 잠긴 채, exitRef 로 refused 한 번', async () => {
    const f = fixture({refuseOff: true});
    const p = f.k.exit('S', '1');
    f.reply();
    await assert.rejects(p, /lock task refused/);
    await tick();
    assert.deepEqual(f.calls, [false]);
    assert.equal(f.timers.size, 0);
    assert.deepEqual(f.reports, [{exitRef: 'ex-1', reason: 'refused'}]);
});

test('KS9: 응답에 exitRef 가 없으면(지금 서버) 알리지 않는다 — 동작은 같다', async () => {
    for (const o of [{refuseOff: true}, {}]) {
        const f = fixture(Object.assign({exitRef: null}, o));
        f.k.worker('A');
        const p = f.k.exit('S', '1');
        if (!o.refuseOff) { f.k.worker('B'); }
        f.reply();
        await assert.rejects(p);
        await tick();
        assert.deepEqual(f.reports, []);
    }
});

test('KS9: 알림이 실패해도(서버 API 없음·끊김) 화면 흐름은 같다', async () => {
    const f = fixture({refuseOff: true, reportFails: true});
    const p = f.k.exit('S', '1');
    f.reply();
    await assert.rejects(p, /lock task refused/);
    await tick();
    assert.equal(f.reports.length, 1);
});
