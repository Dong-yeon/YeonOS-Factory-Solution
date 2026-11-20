// hb.pop.keepAwake 화면 꺼짐 방지 — 실행: node --test src/test/js/*.test.js (docs/50 K7·6.1·6.2, docs/27 U12)
// APK = 껍데기 몫(아무것도 안 함), 브라우저 = Screen Wake Lock(가려지면 풀리고 다시 보이면 다시 잡음), 지원 안 함·거절은 오류 없이 상태만
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeKeepAwake} = require('../../main/resources/static/js/common/hb.pop.js');

const tick = () => new Promise((res) => setImmediate(res));

function fixture(opts) {
    const o = Object.assign({native: false, wakeLock: true, refuse: false}, opts);
    const listeners = new Map();
    const doc = {
        visibilityState: 'visible',
        addEventListener: (t, fn) => listeners.set(t, fn),
        removeEventListener: (t, fn) => { if (listeners.get(t) === fn) { listeners.delete(t); } }
    };
    const sentinels = [];
    let requests = 0;
    const nav = o.wakeLock ? {
        wakeLock: {
            request: (type) => {
                requests++;
                assert.equal(type, 'screen');
                if (o.refuse) { return Promise.reject(Object.assign(new Error('denied'), {name: 'NotAllowedError'})); }
                const handlers = [];
                const s = {
                    released: false,
                    addEventListener: (t, fn) => { if (t === 'release') { handlers.push(fn); } },
                    release: () => { if (!s.released) { s.released = true; handlers.forEach((h) => h()); } return Promise.resolve(); }
                };
                sentinels.push(s);
                return Promise.resolve(s);
            }
        }
    } : {};
    const keepAwake = makeKeepAwake({nav: nav, doc: doc, native: () => o.native});
    const states = [];
    function fire() { const fn = listeners.get('visibilitychange'); if (fn) { fn(); } }
    /** 가려짐 — 브라우저처럼 잠금을 먼저 풀고 이벤트 */
    function hide() { doc.visibilityState = 'hidden'; sentinels.filter((s) => !s.released).forEach((s) => s.release()); fire(); }
    function show() { doc.visibilityState = 'visible'; fire(); }
    return {keepAwake, states, sentinels, hide, show, listeners, requests: () => requests, o};
}

test('브라우저: 처음에 잡고, 가려지면(OS 가 풂) released, 다시 보이면 다시 잡는다', async () => {
    const f = fixture();
    const w = f.keepAwake({onChange: (s) => f.states.push(s)});
    await tick();
    assert.equal(w.state(), 'active');
    f.hide();
    await tick();
    assert.equal(w.state(), 'released');
    f.show();
    await tick();
    assert.equal(w.state(), 'active');
    assert.equal(f.requests(), 2);
    assert.deepEqual(f.states, ['active', 'released', 'active']);
});

test('보이는 동안 visibilitychange 가 또 와도 두 번 잡지 않는다', async () => {
    const f = fixture();
    const w = f.keepAwake();
    f.show();
    await tick();
    f.show();
    await tick();
    assert.equal(f.requests(), 1);
    assert.equal(w.state(), 'active');
});

test('release(): 풀고 더는 다시 잡지 않는다(듣기 해제)', async () => {
    const f = fixture();
    const w = f.keepAwake();
    await tick();
    w.release();
    assert.equal(f.sentinels[0].released, true);
    assert.equal(w.state(), 'off');
    f.hide();
    f.show();
    await tick();
    assert.equal(f.requests(), 1);
    assert.equal(f.listeners.has('visibilitychange'), false);
});

test('지원 안 함(Wake Lock 없음·http): 오류 없이 unsupported, 듣지 않는다', async () => {
    const f = fixture({wakeLock: false});
    const w = f.keepAwake({onChange: (s) => f.states.push(s)});
    await tick();
    assert.equal(w.state(), 'unsupported');
    assert.deepEqual(f.states, ['unsupported']);
    assert.equal(f.listeners.has('visibilitychange'), false);
    w.release();
});

test('거절(배터리 절약 등): failed — 다음에 보일 때 다시 시도', async () => {
    const f = fixture({refuse: true});
    const w = f.keepAwake();
    await tick();
    assert.equal(w.state(), 'failed');
    f.o.refuse = false;
    f.hide();
    f.show();
    await tick();
    assert.equal(w.state(), 'active');
});

test('APK(다리 있음): 껍데기가 FLAG_KEEP_SCREEN_ON(K7) — Wake Lock 을 부르지 않고 native', async () => {
    const f = fixture({native: true});
    const w = f.keepAwake();
    await tick();
    assert.equal(w.state(), 'native');
    assert.equal(f.requests(), 0);
});

test('가려진 채로 시작하면 보일 때 잡는다', async () => {
    const f = fixture();
    f.hide();
    const w = f.keepAwake();
    await tick();
    assert.equal(f.requests(), 0);
    f.show();
    await tick();
    assert.equal(w.state(), 'active');
});

test('잡는 중 release() 하면 받은 잠금을 바로 놓는다', async () => {
    const f = fixture();
    const w = f.keepAwake();
    w.release();
    await tick();
    assert.equal(f.sentinels[0].released, true);
    assert.equal(w.state(), 'off');
});
