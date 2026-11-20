// hb.kiosk 단위 시험 — 실행: node --test src/test/js/*.test.js (docs/50 K3~K6, MES 계약 docs/25 4.52)
// 다리 있음·없음, 서버 확인 성공 뒤에만 kiosk(false), 실패면 안 부름, 다시 잠금(타이머·작업자 바뀜), 구석 판정
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeKiosk, findBridge, inCorner} = require('../../main/resources/static/js/common/hb.kiosk.js');

/** 가짜 다리·서버·타이머. reply = 서버 응답({data}) 또는 {code} 면 거부 */
function fixture(opts) {
    const o = Object.assign({bridge: true, reply: {data: {approverCd: 'S1', approverNm: '감독', relockAfterSec: 600}}}, opts);
    const calls = [];
    const posted = [];
    const timers = new Map();
    let seq = 0;
    const bridge = o.bridge ? {kiosk: (x) => { calls.push(x.on); return o.kioskFails ? Promise.reject(new Error('os')) : Promise.resolve(); }} : null;
    const k = makeKiosk({
        bridge: () => bridge,
        post: (body) => { posted.push(body); return o.reply && o.reply.code ? Promise.reject(o.reply) : Promise.resolve(o.reply); },
        setTimer: (fn, ms) => { const id = ++seq; timers.set(id, {fn, ms}); return id; },
        clearTimer: (id) => timers.delete(id)
    });
    /** 걸린 타이머를 모두 울린다 */
    function fire() { const all = [...timers.values()]; timers.clear(); all.forEach((t) => t.fn()); }
    return {k, calls, posted, timers, fire};
}

test('다리 없음: lock 은 false, exit 는 서버를 부르지 않고 null, worker 도 아무 일 없음', async () => {
    const f = fixture({bridge: false});
    assert.equal(f.k.available(), false);
    assert.equal(await f.k.lock(), false);
    assert.equal(await f.k.exit('B-1', '1234'), null);
    f.k.worker('W1');
    f.k.worker('W2');
    assert.deepEqual(f.posted, []);
    assert.equal(f.timers.size, 0);
});

test('다리 있음: lock → kiosk(true)', async () => {
    const f = fixture();
    assert.equal(f.k.available(), true);
    assert.equal(await f.k.lock(), true);
    assert.deepEqual(f.calls, [true]);
});

test('OS 가 고정을 거절해도 lock 은 false 로 끝난다(화면은 계속)', async () => {
    const f = fixture({kioskFails: true});
    assert.equal(await f.k.lock(), false);
});

test('빠져나오기 성공: 서버 응답 뒤 kiosk(false), relockAfterSec 뒤 다시 kiosk(true)', async () => {
    const f = fixture();
    const r = await f.k.exit('B-9', '0000');
    assert.equal(r.approverCd, 'S1');
    assert.deepEqual(f.posted, [{badge: 'B-9', pin: '0000'}]);
    assert.deepEqual(f.calls, [false]);
    assert.equal(f.k.unlocked(), true);
    assert.deepEqual([...f.timers.values()].map((t) => t.ms), [600000]);
    f.fire();
    await new Promise((res) => setImmediate(res));
    assert.deepEqual(f.calls, [false, true]);
    assert.equal(f.k.unlocked(), false);
});

test('실패 응답(PIN 틀림·감독자 아님·잠김)이면 kiosk(false) 를 부르지 않고 그대로 거부', async () => {
    for (const code of ['POP_PIN_INVALID', 'FORBIDDEN', 'TOO_MANY_REQUESTS']) {
        const f = fixture({reply: {code}});
        await assert.rejects(f.k.exit('B-1', '9999'), (e) => e.code === code);
        assert.deepEqual(f.calls, [], code);
        assert.equal(f.timers.size, 0);
    }
});

test('빈 응답은 확인이 아니다 — 풀지 않는다', async () => {
    const f = fixture({reply: {data: null}});
    await assert.rejects(f.k.exit('B-1', '1'));
    assert.deepEqual(f.calls, []);
});

test('relockAfterSec 이 없거나 이상하면 600초, 너무 길면 3600초로', async () => {
    for (const [sec, ms] of [[undefined, 600000], [0, 600000], [-5, 600000], ['600', 600000], [30, 30000], [86400, 3600000]]) {
        const f = fixture({reply: {data: {approverCd: 'S1', approverNm: 'x', relockAfterSec: sec}}});
        await f.k.exit('B', '1');
        assert.deepEqual([...f.timers.values()].map((t) => t.ms), [ms], String(sec));
    }
});

test('빠져나온 뒤 작업자가 바뀌면 타이머를 기다리지 않고 바로 다시 잠근다(승인 없음)', async () => {
    const f = fixture();
    f.k.worker('W1');                 // 처음 알림 = 기준만
    assert.deepEqual(f.calls, []);
    await f.k.exit('B', '1');
    f.k.worker('W1');                 // 같은 작업자 — 그대로
    assert.deepEqual(f.calls, [false]);
    f.k.worker(null);                 // 작업자 내려감 = 바뀜
    await new Promise((res) => setImmediate(res));
    assert.deepEqual(f.calls, [false, true]);
    assert.equal(f.timers.size, 0, '다시 잠그면 타이머는 정리');
    assert.deepEqual(f.posted.length, 1, '다시 잠금은 서버를 부르지 않는다');
});

test('홈 다시 열림(lock) 도 빠져나온 상태의 타이머를 정리하고 잠근다', async () => {
    const f = fixture();
    await f.k.exit('B', '1');
    await f.k.lock();
    assert.deepEqual(f.calls, [false, true]);
    assert.equal(f.k.unlocked(), false);
});

test('다리 찾기: Capacitor.Plugins.YeonShell.kiosk 가 함수일 때만', () => {
    const saved = globalThis.Capacitor;
    try {
        delete globalThis.Capacitor;
        assert.equal(findBridge(), null);
        globalThis.Capacitor = {Plugins: {YeonShell: {kiosk: 'x'}}};
        assert.equal(findBridge(), null);
        const s = {kiosk: () => undefined};
        globalThis.Capacitor = {Plugins: {YeonShell: s}};
        assert.equal(findBridge(), s);
    } finally {
        if (saved === undefined) { delete globalThis.Capacitor; } else { globalThis.Capacitor = saved; }
    }
});

test('구석 판정: 오른쪽 위 48px 안만', () => {
    assert.equal(inCorner(1270, 10, 1280), true);
    assert.equal(inCorner(1232, 48, 1280), true);
    assert.equal(inCorner(1231, 10, 1280), false);
    assert.equal(inCorner(1270, 49, 1280), false);
    assert.equal(inCorner(10, 10, 1280), false);
});
