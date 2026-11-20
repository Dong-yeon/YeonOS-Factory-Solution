// beforeSelect 답으로 진행 — 동기(기존 동작)·Promise(hb.dialog) (docs/40 단계 3 공통 보강). 실행: node --test src/test/js/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {gate} = require('../../main/resources/static/js/common/hb.gate.js');

test('동기 답: false 만 멈추고 true·없음은 바로 진행(기존 화면 그대로)', () => {
    let n = 0;
    assert.equal(gate(false, () => n++), false);
    assert.equal(n, 0);
    assert.equal(gate(true, () => n++), true);
    assert.equal(gate(undefined, () => n++), true);
    assert.equal(gate(null, () => n++), true);
    assert.equal(n, 3);   // 같은 틱에 진행 — onSelect 가 바로 불린다
});

test('Promise 답: 기다렸다가 true 면 진행, false·거부면 머문다', async () => {
    let n = 0;
    const p = gate(Promise.resolve(true), () => n++);
    assert.equal(n, 0);   // 답 전에는 진행하지 않는다
    assert.equal(await p, true);
    assert.equal(n, 1);
    assert.equal(await gate(Promise.resolve(false), () => n++), false);
    assert.equal(await gate(Promise.reject(new Error('대화상자 실패')), () => n++), false);
    assert.equal(n, 1);
});

test('Promise 가 없음(undefined)으로 끝나면 동기와 같게 진행', async () => {
    let n = 0;
    assert.equal(await gate(Promise.resolve(undefined), () => n++), true);
    assert.equal(n, 1);
});
