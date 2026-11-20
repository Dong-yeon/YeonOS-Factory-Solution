// hb.grid 상태 칸 톤·행 의미 클래스 (docs/40 단계 3 공통 보강). 실행: node --test src/test/js/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {toneOf, rowClasses, hashFields} = require('../../main/resources/static/js/common/hb.grid.js');

test('상태 톤: 값 → 톤 표, 표에 없는 값·빈 값은 배지 없음(null)', () => {
    const spec = {PLAN: 'neutral', RUN: 'info', DONE: 'success'};
    assert.equal(toneOf(spec, 'RUN', {}), 'info');
    assert.equal(toneOf(spec, 'HOLD', {}), null);
    assert.equal(toneOf(spec, '', {}), null);
    assert.equal(toneOf(spec, null, {}), null);
    assert.equal(toneOf(undefined, 'RUN', {}), null);
    assert.equal(toneOf({1: 'alarm-1'}, 1, {}), 'alarm-1');   // 숫자 값은 글자로 찾는다
});

test('상태 톤: 함수는 (값, 행)을 받고 null 이면 글자만', () => {
    const fn = (v, row) => (row.late ? 'danger' : v === 'Y' ? 'normal' : null);
    assert.equal(toneOf(fn, 'Y', {late: true}), 'danger');
    assert.equal(toneOf(fn, 'Y', {}), 'normal');
    assert.equal(toneOf(fn, 'N', {}), null);
});

test('행 클래스: hb-row-*·hb-tree-* 만, 여러 개·배열 가능, 그 밖 이름·빈 값은 버린다', () => {
    assert.deepEqual(rowClasses(undefined, {}), []);
    assert.deepEqual(rowClasses(() => null, {}), []);
    assert.deepEqual(rowClasses(() => 'hb-tree-dim', {}), ['hb-tree-dim']);
    assert.deepEqual(rowClasses(() => 'hb-tree-dim  hb-row-warn', {}), ['hb-tree-dim', 'hb-row-warn']);
    assert.deepEqual(rowClasses(() => ['hb-row-warn', 'red', 'hb-row-"x', 'HB-ROW-DIM'], {}), ['hb-row-warn']);
    assert.deepEqual(rowClasses(() => 'hbg-row hb-btn-danger', {}), []);   // 그리드 내부·다른 부품 클래스 금지
});

test('기존 hashFields 그대로', () => {
    assert.equal(hashFields([{field: 'a'}, {field: 'b'}]), hashFields([{field: 'a'}, {field: 'b'}]));
    assert.notEqual(hashFields([{field: 'a'}, {field: 'b'}]), hashFields([{field: 'b'}, {field: 'a'}]));
});

test('TEXT 칸 format: 한 인자 함수 그대로, 두 번째 인자로 행 전체, 빈 값은 부르지 않음', () => {
    const {textOf} = require('../../main/resources/static/js/common/hb.grid.js');
    assert.equal(textOf(undefined, 'A', {}), 'A');
    assert.equal(textOf(undefined, 0, {}), '0');
    assert.equal(textOf(v => v + '!', 'A', {}), 'A!');                                    // 기존 한 인자
    const row = {cycleN: 1, cycleUnit: '개월', fixedYn: 'Y'};
    assert.equal(textOf((v, r) => r.cycleN + r.cycleUnit + (r.fixedYn === 'Y' ? ' · 달력 고정' : ''), 1, row), '1개월 · 달력 고정');
    let called = 0;
    assert.equal(textOf(() => { called++; return 'x'; }, null, row), '');
    assert.equal(textOf(() => { called++; return 'x'; }, undefined, row), '');
    assert.equal(called, 0);
    assert.equal(textOf(() => null, 'A', row), '');                                       // 함수가 비우면 빈 글자
    assert.equal(textOf(() => 12, 'A', row), '12');                                        // 글자로
    assert.equal(textOf(() => '<b>x</b>', 'A', row), '<b>x</b>');                         // 글자 그대로(textContent 로 들어간다)
});

test('행 체크박스 머리 상태: 이 쪽 모두·일부·없음(빈 쪽은 없음), key 는 글자로 맞춘다', () => {
    const {pageCheckState} = require('../../main/resources/static/js/common/hb.grid.js');
    const rows = [{id: 1}, {id: 2}, {id: 3}];
    const set = keys => k => new Set(keys).has(k);
    assert.equal(pageCheckState(rows, 'id', set([])), 'none');
    assert.equal(pageCheckState(rows, 'id', set(['2'])), 'some');
    assert.equal(pageCheckState(rows, 'id', set(['1', '2', '3'])), 'all');
    assert.equal(pageCheckState(rows, 'id', set(['1', '2', '3', '99'])), 'all');   // 다른 쪽 체크는 이 쪽 상태에 안 섞임
    assert.equal(pageCheckState([], 'id', set(['1'])), 'none');
});

test('checkableRow: 못 고르는 행은 머리 칸 상태에서 빠진다, 고를 수 있는 행이 없으면 none', () => {
    const {pageCheckState, canCheck} = require('../../main/resources/static/js/common/hb.grid.js');
    const rows = [{id: 1, st: 'OPEN'}, {id: 2, st: 'DONE'}, {id: 3, st: 'OPEN'}];
    const open = r => r.st === 'OPEN';
    const set = keys => k => new Set(keys).has(k);
    assert.equal(pageCheckState(rows, 'id', set(['1', '3']), open), 'all');      // 고를 수 있는 행 모두 = 모두
    assert.equal(pageCheckState(rows, 'id', set(['1']), open), 'some');
    assert.equal(pageCheckState(rows, 'id', set([]), open), 'none');
    assert.equal(pageCheckState([{id: 2, st: 'DONE'}], 'id', set([]), open), 'none');
    assert.equal(pageCheckState(rows, 'id', set(['1', '3'])), 'some');           // checkableRow 없으면 모든 행을 센다
    assert.equal(canCheck(undefined, rows[1]), true);
    assert.equal(canCheck(open, rows[1]), false);
    assert.equal(canCheck(() => /** @type {any} */ (null), rows[0]), false);      // 거짓 같은 값 = 못 고름
});

test('emptyText: 칸 글자가 빈 글자일 때만, 필드가 있을 때만', () => {
    const {emptyOf, textOf} = require('../../main/resources/static/js/common/hb.grid.js');
    const map = {validMonths: '만료 없음', pinYn: '-'};
    assert.equal(emptyOf(map, 'validMonths', ''), '만료 없음');
    assert.equal(emptyOf(map, 'validMonths', '12'), null);                // 값이 있으면 그대로
    assert.equal(emptyOf(map, 'other', ''), null);                        // 그 필드가 없으면 빈 칸 그대로
    assert.equal(emptyOf(undefined, 'validMonths', ''), null);            // 옵션 없음 = 지금 동작
    assert.equal(emptyOf({toString: 'x'}, 'toString', ''), 'x');          // 자기 필드만 본다
    assert.equal(emptyOf({}, 'toString', ''), null);                      // 상속 이름(toString)은 필드가 아니다
    assert.equal(emptyOf({a: ''}, 'a', ''), null);
    // format 이 먼저: null 이면 format 을 부르지 않고 빈 글자 → emptyText, format 결과가 빈 글자여도 emptyText
    let called = 0;
    const fmt = v => { called++; return v === 'Y' ? '고정' : ''; };
    assert.equal(emptyOf(map, 'pinYn', textOf(fmt, null, {})), '-');
    assert.equal(called, 0);
    assert.equal(emptyOf(map, 'pinYn', textOf(fmt, 'N', {})), '-');
    assert.equal(emptyOf(map, 'pinYn', textOf(fmt, 'Y', {})), null);
});

