// hb.numbers 단위 테스트 — 실행: node --test src/test/js/*.test.js   (Gradle check 의 testJs, docs/16 9장 3단계)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const n = require('../../main/resources/static/js/common/hb.numbers.js');

const ok = (raw, value, opts) => assert.deepEqual(n.parse(raw, opts), {ok: true, value}, String(raw));
const bad = (raw, code, opts) => assert.deepEqual(n.parse(raw, opts), {ok: false, code}, String(raw));

test('받는 형식: 쉼표·소수·부호·회계 괄호·전각·공백 → 정규화된 10진 문자열', () => {
    ok('1,234,567', '1234567');
    ok('12345.50', '12345.5');
    ok('-12', '-12');
    ok('+7', '7');
    ok('(1,234)', '-1234');
    ok('１２３．５', '123.5');
    ok('－３', '-3');
    ok('　42 ', '42');
    ok('007', '7');
    ok('0.000', '0');
    ok('-0', '0');
    ok('', null);
    ok('   ', null);
    ok(null, null);
});

test('거부: 지수·통화·쉼표 위치·점 위치 — 조용히 고치지 않는다', () => {
    for (const s of ['1e5', '₩1,000', '$5', '1,23', '1,2345', '1.2.3', '.5', '5.', '12a', '--1', '(-1)', '1 234', 'abc', ',', '-']) {
        bad(s, 'NUMBER');
    }
});

test('자릿수: 반올림하지 않고 SCALE, 끝의 0 은 세지 않음, 0 = 정수만', () => {
    bad('1.234', 'SCALE', {scale: 2});
    ok('1.23', '1.23', {scale: 2});
    ok('1.230', '1.23', {scale: 2});
    bad('1.5', 'SCALE', {scale: 0});
    ok('1.0', '1', {scale: 0});
});

test('음수 금지·min·max (경계 포함)', () => {
    bad('-1', 'NEGATIVE', {allowNegative: false});
    bad('(5)', 'NEGATIVE', {allowNegative: false});
    ok('0', '0', {allowNegative: false});
    bad('9', 'MIN', {min: 10});
    ok('10', '10', {min: 10});
    bad('10000000', 'MAX', {max: '9999999'});
    ok('9,999,999', '9999999', {max: 9999999});
    bad('9999999.001', 'MAX', {max: '9999999'});
    ok('-5', '-5', {min: -5});
    ok('1', '1', {min: 'oops'}, 'min 선언 오타는 무시');
});

test('15자리 넘는 값도 정확하게 (부동소수로 바꾸지 않음)', () => {
    ok('12,345,678,901,234,567.891', '12345678901234567.891');
    bad('12345678901234567.892', 'MAX', {max: '12345678901234567.891'});
    assert.equal(n.compare('0.1', '0.10'), 0);
    assert.equal(n.compare('-2', '-10'), 1);
    assert.equal(n.compare('-0.5', '0'), -1);
    assert.equal(n.compare('99', '100'), -1);
});

test('fixed·format: 자릿수 고정과 천 단위 표시', () => {
    assert.equal(n.fixed('1234.5', 3), '1234.500');
    assert.equal(n.fixed('7', 0), '7');
    assert.equal(n.fixed('7', undefined), '7');
    assert.equal(n.fixed(null, 2), null);
    assert.equal(n.format('12345.5', 2), '12,345.50');
    assert.equal(n.format('-1234567', 0), '-1,234,567');
    assert.equal(n.format('999', 0), '999');
    assert.equal(n.format('0.5'), '0.5');
    assert.equal(n.format(null, 2), '');
    assert.equal(n.format('12345678901234567.891', 3), '12,345,678,901,234,567.891');
});

test('format → parse 왕복이 같은 값', () => {
    for (const v of ['0', '1', '-1', '1000', '1234567.25', '-0.5', '100000000000000000001']) {
        assert.equal(n.parse(n.format(v, 2), {scale: 2}).value, n.parse(v).value, v);
    }
});

test('형식 밖 값: 표시는 글자 그대로(예외로 멈추지 않음), 비교는 명시적 TypeError (2026-10-20 타입 검사)', () => {
    assert.equal(n.format('1e-7', 2), '1e-7');
    assert.equal(n.fixed('abc', 2), 'abc');
    assert.equal(n.format(null, 2), '');
    assert.throws(() => n.compare('abc', 1), TypeError);
});

// QA N-a(docs/qa/2026-11-02_number-copq-trace.md 부록 A) — 소수점이 쉼표인 표기(0,123 = 0.123)를 붙여넣으면 천 단위로 읽혀 1000배로 저장되던 것
test('N-a: 첫 묶음이 0 인 쉼표 숫자는 천 단위가 아니다 — 0,123 을 123 으로 받지 않는다', () => {
    bad('0,123', 'NUMBER');
    bad('00,123', 'NUMBER');
    bad('0,123.5', 'NUMBER');
    bad('0,5', 'NUMBER');
    bad('-0,123', 'NUMBER');
    bad('(0,123)', 'NUMBER');
    bad('０，１２３', 'NUMBER');      // 전각도 같은 규칙(NFKC 뒤)
    ok('1,500', '1500');              // 첫 묶음 1~999 는 그대로 천 단위
    ok('100,000', '100000');
    ok('1,234,567.5', '1234567.5');
    ok('-1,500', '-1500');
    ok('(1,234)', '-1234');
    ok('0.123', '0.123');
    ok('0123', '123');                // 쉼표 없는 앞 0 은 지금처럼 정수로
});
