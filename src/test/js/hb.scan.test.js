// hb.scan 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/50 7장 C1~C4)
// 웨지(포커스 칸 + Enter / 문서 전체 빠른 입력 + Enter), native(hb:scan 이벤트), 값 정리, 한 화면 듣는 곳 하나
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeScan, clean} = require('../../main/resources/static/js/common/hb.scan.js');

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
function key(k, more) {
    return Object.assign({key: k, prevented: false, preventDefault() { this.prevented = true; }}, more || {});
}
function env() {
    const clock = {t: 1000};
    const doc = target({activeElement: {tagName: 'BODY'}});
    const win = target();
    return {doc, win, clock, scan: makeScan({doc, win, now: () => clock.t})};
}
/** 문서 전체로 글자를 gap ms 간격으로 친다 */
function type(e, text, gap) {
    for (const ch of text) { e.clock.t += gap; e.doc.fire('keydown', key(ch)); }
    e.clock.t += gap;
    return e.doc.fire('keydown', key('Enter'));
}

test('값 정리: 제어·방향 문자 제거, GS(\\u001d) 유지, 앞뒤 공백, 200자, 문자열 아님은 빈 값', () => {
    assert.equal(clean('  (01)123\u001d(10)LOT\u0000‮  '), '(01)123\u001d(10)LOT');
    assert.equal(clean('x'.repeat(250)).length, 200);
    assert.equal(clean(null), '');
    assert.equal(clean(42), '');
});

test('포커스 칸 + Enter: 칸 값을 받아 비우고 Enter 는 막는다, 조합 중 Enter·다른 키는 무시', () => {
    const e = env();
    const input = target({value: ''});
    const got = [];
    e.scan.listen((r) => got.push(r), {input});
    input.value = ' LOT-001 ';
    const ev = input.fire('keydown', key('Enter'));
    assert.deepEqual(got, [{value: 'LOT-001', source: 'wedge'}]);
    assert.equal(input.value, '');
    assert.equal(ev.prevented, true);
    input.value = 'ABC';
    input.fire('keydown', key('Enter', {isComposing: true}));
    input.fire('keydown', key('a'));
    assert.equal(got.length, 1);
    input.value = '   ';
    input.fire('keydown', key('Enter'));
    assert.equal(got.length, 1, '빈 값은 버린다');
});

test('문서 전체: 빠른 입력 + Enter 만 스캔, 느린 입력·짧은 값은 무시', () => {
    const e = env();
    const got = [];
    e.scan.listen((r) => got.push(r));
    const ev = type(e, 'B-123', 20);
    assert.deepEqual(got, [{value: 'B-123', source: 'wedge'}]);
    assert.equal(ev.prevented, true, '스캔 Enter 는 버튼을 누르지 않는다');
    const slow = type(e, 'ABCD', 200);
    assert.equal(got.length, 1, '사람 속도');
    assert.equal(slow.prevented, false);
    type(e, 'AB', 10);
    assert.equal(got.length, 1, '최소 길이 3');
});

test('문서 전체: 입력 칸에 포커스가 있으면 가로채지 않는다(사람이 치는 글자 그대로)', () => {
    const e = env();
    const got = [];
    e.scan.listen((r) => got.push(r));
    e.doc.activeElement = {tagName: 'INPUT'};
    const ev = type(e, 'B-123', 10);
    assert.equal(got.length, 0);
    assert.equal(ev.prevented, false);
    e.doc.activeElement = {tagName: 'DIV', isContentEditable: true};
    type(e, 'B-123', 10);
    assert.equal(got.length, 0);
    e.doc.activeElement = {tagName: 'BUTTON'};
    type(e, 'B-123', 10);
    assert.equal(got.length, 1);
});

test('native: hb:scan 이벤트 → source native, 값 정리, detail 없으면 무시', () => {
    const e = env();
    const got = [];
    e.scan.listen((r) => got.push(r));
    e.win.fire('hb:scan', {detail: {value: ' SN-9\u0007 '}});
    e.win.fire('hb:scan', {detail: null});
    e.win.fire('hb:scan', {});
    assert.deepEqual(got, [{value: 'SN-9', source: 'native'}]);
});

test('한 화면 듣는 곳 하나: 두 번째 listen 은 앞 것을 해제 — 값은 한 곳에만', () => {
    const e = env();
    const a = [];
    const b = [];
    const offA = e.scan.listen((r) => a.push(r));
    e.scan.listen((r) => b.push(r));
    e.win.fire('hb:scan', {detail: {value: 'X-1'}});
    type(e, 'Y-22', 10);
    assert.deepEqual(a, []);
    assert.deepEqual(b.map((r) => r.value), ['X-1', 'Y-22']);
    offA();   // 이미 밀려난 해제는 지금 듣는 곳을 끄지 않는다
    e.win.fire('hb:scan', {detail: {value: 'Z-3'}});
    assert.equal(b.length, 3);
});

test('해제하면 문서·창·칸 듣기를 모두 뗀다(두 번 불러도 안전)', () => {
    const e = env();
    const input = target({value: ''});
    const off = e.scan.listen(() => assert.fail('해제 뒤 호출'), {input});
    assert.equal(input.ls.length, 1);
    assert.equal(e.win.ls.length, 1);
    off();
    off();
    assert.equal(input.ls.length, 0);
    assert.equal(e.win.ls.length, 0);
    assert.equal(e.doc.ls.length, 0);
    e.win.fire('hb:scan', {detail: {value: 'A'}});
    const off2 = e.scan.listen(() => {});
    assert.equal(e.doc.ls.length, 1, '칸 없이 listen 하면 문서 전체');
    off2();
    assert.equal(e.doc.ls.length, 0);
});
