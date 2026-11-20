// hb.pop.scanner 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/50 7장 C6)
// hb.scan.listen(…, {input}) 위의 감싸기: 호출 모양 (input, onScan) → onScan(값), 반환 {pause, resume} 그대로,
// 웨지(칸 + Enter)·native(hb:scan) 한 경로, 숨은 칸 포커스 되돌리기, pause 동안 듣지 않음, hb.scan 없으면 바로 알림
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeScan} = require('../../main/resources/static/js/common/hb.scan.js');
const {makeScanner} = require('../../main/resources/static/js/common/hb.pop.js');

/** 가짜 이벤트 대상 */
function target(extra) {
    const ls = [];
    return Object.assign({
        ls,
        addEventListener: (type, fn, cap) => ls.push({type, fn, cap}),
        removeEventListener: (type, fn) => { const i = ls.findIndex((l) => l.type === type && l.fn === fn); if (i >= 0) { ls.splice(i, 1); } },
        fire(type, ev) { ls.filter((l) => l.type === type).slice().forEach((l) => l.fn(ev)); return ev; }
    }, extra || {});
}
function key(k) {
    return {key: k, prevented: false, preventDefault() { this.prevented = true; }};
}
/** 가짜 화면: body·숨은 칸·다른 칸, 미룬 일은 run() 으로 */
function env() {
    const body = {tagName: 'BODY'};
    const doc = target({body, activeElement: body});
    const win = target();
    const scan = makeScan({doc, win, now: () => 0});
    const deferred = [];
    const input = target({
        tagName: 'INPUT', value: '', focusCount: 0,
        focus() { this.focusCount++; doc.activeElement = input; },
        blur() { if (doc.activeElement === input) { doc.activeElement = body; } }
    });
    const scanner = makeScanner({scan: () => scan, doc, defer: (fn) => deferred.push(fn)});
    return {doc, win, input, scanner, run: () => deferred.splice(0).forEach((f) => f())};
}
function wedge(input, text) {
    input.value = text;
    return input.fire('keydown', key('Enter'));
}

test('웨지: 칸 값 + Enter → onScan(정리된 값), 칸 비움, 처음에 칸에 포커스', () => {
    const e = env();
    const got = [];
    e.scanner(e.input, (v) => got.push(v));
    assert.equal(e.doc.activeElement, e.input);
    const ev = wedge(e.input, ' LOT-001\u0000 ');
    assert.deepEqual(got, ['LOT-001']);
    assert.equal(e.input.value, '');
    assert.equal(ev.prevented, true);
    wedge(e.input, '   ');
    assert.deepEqual(got, ['LOT-001'], '빈 값은 버린다');
});

test('native(APK 스캐너 SDK hb:scan 이벤트)도 같은 onScan 으로 한 번만', () => {
    const e = env();
    const got = [];
    e.scanner(e.input, (v) => got.push(v));
    e.win.fire('hb:scan', {detail: {value: 'BADGE-0001'}});
    assert.deepEqual(got, ['BADGE-0001']);
});

test('포커스 되돌리기: 빈 곳 클릭·칸 blur 뒤엔 칸으로, 다른 입력 칸에 있으면 그대로', () => {
    const e = env();
    e.scanner(e.input, () => {});
    e.doc.activeElement = e.doc.body;
    e.doc.fire('click', {});
    e.run();
    assert.equal(e.doc.activeElement, e.input);
    const other = {tagName: 'TEXTAREA'};
    e.doc.activeElement = other;
    e.input.fire('blur', {});
    e.run();
    assert.equal(e.doc.activeElement, other, '사람이 치는 칸을 빼앗지 않는다');
});

test('pause 동안은 듣지도 포커스를 되돌리지도 않고, resume 하면 칸을 비우고 다시', () => {
    const e = env();
    const got = [];
    const h = e.scanner(e.input, (v) => got.push(v));
    assert.deepEqual(Object.keys(h).sort(), ['pause', 'resume']);
    h.pause();
    assert.equal(e.doc.activeElement, e.doc.body);
    wedge(e.input, 'X-1');
    e.win.fire('hb:scan', {detail: {value: 'X-2'}});
    e.doc.fire('click', {});
    e.run();
    assert.deepEqual(got, []);
    assert.equal(e.doc.activeElement, e.doc.body);
    e.input.value = '남은 글자';
    h.resume();
    assert.equal(e.input.value, '');
    assert.equal(e.doc.activeElement, e.input);
    wedge(e.input, 'X-3');
    e.win.fire('hb:scan', {detail: {value: 'X-4'}});
    assert.deepEqual(got, ['X-3', 'X-4']);
    h.resume();
    wedge(e.input, 'X-5');
    assert.deepEqual(got, ['X-3', 'X-4', 'X-5'], 'resume 두 번이어도 한 번만 받는다');
});

test('hb.scan.js 를 넣지 않은 화면에서 부르면 바로 알린다(조용히 스캔이 안 되는 것보다)', () => {
    const doc = target({body: {}, activeElement: null});
    const scanner = makeScanner({scan: () => undefined, doc, defer: () => {}});
    assert.throws(() => scanner(target({}), () => {}), /hb\.scan\.js/);
});
