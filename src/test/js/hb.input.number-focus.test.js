// hb.number 포커스 때 선택 유지 — 실행: node --test src/test/js/*.test.js (docs/16 6.5, 2026-10-29 QMS 관찰)
// 포커스하면 표시값(1,234.500)을 원값(1234.5)으로 바꾸는데, value 를 넣으면 브라우저가 선택을 끝으로 접는다.
// Tab 으로 들어와 전체가 선택된 칸에 치면 바꾸기가 아니라 뒤에 붙어 1234.5 + 7 = 1234.57 로 조용히 저장되던 것 — 전체 선택이었으면 다시 전체 선택
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const numbers = require('../../main/resources/static/js/common/hb.numbers.js');

const SRC = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/common/hb.input.js'), 'utf8');

/** 브라우저 input 흉내: value 를 넣으면 선택이 끝으로 접힌다(Chrome 과 같음), select() = 전체 선택 */
function fakeInput(initial) {
    const listeners = {};
    const attrs = {};
    const el = {
        _value: initial, selectionStart: initial.length, selectionEnd: initial.length, required: false,
        type: '', inputMode: '', autocomplete: '', spellcheck: true,
        classList: {add() {}},
        get value() { return this._value; },
        set value(v) { this._value = String(v); this.selectionStart = this.selectionEnd = this._value.length; },
        getAttribute: (n) => (n in attrs ? attrs[n] : null),
        setAttribute: (n, v) => { attrs[n] = String(v); },
        removeAttribute: (n) => { delete attrs[n]; },
        addEventListener: (t, fn) => { (listeners[t] = listeners[t] || []).push(fn); },
        insertAdjacentElement() {},
        select() { this.selectionStart = 0; this.selectionEnd = this._value.length; },
        fire(t) { (listeners[t] || []).forEach((fn) => fn({})); },
        /** 선택 영역을 글자로 바꾼다(타이핑·붙여넣기) */
        typeText(text) {
            this._value = this._value.slice(0, this.selectionStart) + text + this._value.slice(this.selectionEnd);
            this.selectionStart = this.selectionEnd = this.selectionStart + text.length;
            this.fire('input');
        }
    };
    return el;
}

function load() {
    const doc = {activeElement: null, getElementById: () => null};
    const win = {hb: {numbers, t: (k) => k, el: () => ({setAttribute() {}, hidden: false, textContent: ''})}};
    vm.runInNewContext(SRC, {window: win, document: doc, URLSearchParams});
    return {hb: win.hb, doc};
}

/** 칸에 들어가기(focus) — selectAll = Tab 처럼 브라우저가 전체를 고른 상태로 */
function focus(env, el, selectAll) {
    if (selectAll) { el.select(); }
    env.doc.activeElement = el;
    el.fire('focus');
}

function blur(env, el) {
    env.doc.activeElement = null;
    el.fire('blur');
}

test('Tab 으로 들어와 전체 선택된 칸에 치면 바꾼다 — 옛 값 뒤에 붙지 않는다', () => {
    const env = load();
    const el = fakeInput('1234.5');
    const n = env.hb.number(el, {scale: 3});
    assert.equal(el.value, '1,234.500');
    focus(env, el, true);
    assert.equal(el.value, '1234.5');
    assert.deepEqual([el.selectionStart, el.selectionEnd], [0, 6], '원값으로 바꾼 뒤에도 전체 선택');
    el.typeText('7');
    blur(env, el);
    assert.equal(n.value(), '7.000');
});

test('붙여넣기·웨지처럼 전체 선택 뒤 한 번에 들어와도 바꾼다', () => {
    const env = load();
    const el = fakeInput('12');
    const n = env.hb.number(el, {scale: 3});
    focus(env, el, true);
    el.typeText('125');
    blur(env, el);
    assert.equal(n.value(), '125.000');
});

test('클릭처럼 선택 없이 들어오면 지금처럼 끝에 캐럿 — 이어 치면 붙는다(브라우저 기본)', () => {
    const env = load();
    const el = fakeInput('1234.5');
    const n = env.hb.number(el, {scale: 3});
    focus(env, el, false);
    assert.deepEqual([el.selectionStart, el.selectionEnd], [6, 6]);
    el.typeText('1');
    blur(env, el);
    assert.equal(n.value(), '1234.510');
});

test('빈 칸·오류 칸은 포커스 때 글자를 바꾸지 않는다', () => {
    const env = load();
    const empty = fakeInput('');
    env.hb.number(empty, {scale: 0});
    focus(env, empty, true);
    assert.equal(empty.value, '');
    const bad = fakeInput('');
    const nb = env.hb.number(bad, {scale: 0});
    focus(env, bad, false);
    bad.typeText('1,5');
    blur(env, bad);
    assert.equal(nb.value(), null);
    focus(env, bad, true);
    assert.equal(bad.value, '1,5', '해석 실패면 글자를 그대로 둔다(조용히 고치지 않음)');
});
