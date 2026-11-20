// hb.dialog 입력 검증(순수 함수) — 실행: node --test src/test/js/*.test.js (docs/40 단계 1)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {check, submitValue} = require('../../main/resources/static/js/common/hb.dialog.js');
const numbers = require('../../main/resources/static/js/common/hb.numbers.js');

test('글자: 빈 값·필수·최대 길이·앞뒤 공백', () => {
    assert.deepEqual(check('', {}, null), {ok: true, value: null});
    assert.deepEqual(check('  ', {required: true}, null), {ok: false, code: 'REQUIRED'});
    assert.deepEqual(check(' 사유 ', {required: true}, null), {ok: true, value: '사유'});
    assert.deepEqual(check('abcdef', {maxLength: 5}, null), {ok: false, code: 'TOO_LONG'});
});

test('숫자: 천 단위 쉼표·자릿수 고정·범위 — 쉼표 사고(1,5 → 15) 막기', () => {
    const n = {scale: 3, min: '0.001', max: '1200'};
    assert.deepEqual(check('1,000.5', {number: n}, numbers), {ok: true, value: '1000.500'});
    assert.deepEqual(check('1,5', {number: n}, numbers), {ok: false, code: 'NUMBER'});
    assert.deepEqual(check('0.0001', {number: n}, numbers), {ok: false, code: 'SCALE'});
    assert.deepEqual(check('0', {number: n}, numbers), {ok: false, code: 'MIN'});
    assert.deepEqual(check('1,201', {number: n}, numbers), {ok: false, code: 'MAX'});
    assert.deepEqual(check('-1', {number: {allowNegative: false}}, numbers), {ok: false, code: 'NEGATIVE'});
    assert.deepEqual(check('', {number: n, required: true}, numbers), {ok: false, code: 'REQUIRED'});
    assert.deepEqual(check('７', {number: {scale: 0}}, numbers), {ok: true, value: '7'});   // 전각 숫자
});

test('숫자 검증에 hb.numbers 가 없으면 프로그래밍 오류', () => {
    assert.throws(() => check('1', {number: {}}, null));
});

test('최대 길이: 붙여넣은 긴 글은 잘리지 않고 TOO_LONG — 경계 500/501 (QA O-1)', () => {
    assert.deepEqual(check('가'.repeat(500), {maxLength: 500, required: true}, null), {ok: true, value: '가'.repeat(500)});
    assert.deepEqual(check('가'.repeat(501), {maxLength: 500, required: true}, null), {ok: false, code: 'TOO_LONG'});
    assert.deepEqual(check('  ' + '가'.repeat(500) + '  ', {maxLength: 500}, null), {ok: true, value: '가'.repeat(500)});   // 앞뒤 공백은 다듬은 뒤 센다
});

test('prompt 확정 결과: 비운 선택 입력 = \'\' (취소 = null 과 구분 — docs/60 D1), 필수는 그대로 오류', () => {
    assert.deepEqual(submitValue('', {}, null), {ok: true, value: ''});            // "비우면 자동" 새 리비전 — 예전엔 null(취소와 같음)
    assert.deepEqual(submitValue('   ', {required: false}, null), {ok: true, value: ''});
    assert.deepEqual(submitValue(' A1 ', {required: false}, null), {ok: true, value: 'A1'});
    assert.deepEqual(submitValue('', {required: true}, null), {ok: false, code: 'REQUIRED'});   // 필수는 닫히지 않는다 — '' 가 나가지 않음
    assert.deepEqual(submitValue('', {number: {scale: 3}}, numbers), {ok: true, value: ''});
    assert.deepEqual(submitValue('1,5', {number: {scale: 3}}, numbers), {ok: false, code: 'NUMBER'});
    assert.equal(check('', {}, null).value, null);                                   // check 자체는 그대로(다른 곳이 씀)
});
