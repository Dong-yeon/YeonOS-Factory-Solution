// 붙여넣기 파서 단위 테스트 (docs/10 8장 3일차). 실행: node --test src/test/js/
// Gradle check 에는 넣지 않았다(Node 가 없는 빌드 환경이 있음) — 결과는 TESTING.md·docs/10 11.5 에 기록.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../../main/resources/static/js/dev/grid-poc/paste-parser.js');

const CODES = [{code: 'SALE', name: '매출'}, {code: 'PURC', name: '매입'}, {code: 'BOTH', name: '매출입'}];

test('TSV: 엑셀 기본 형식(CRLF, 끝 줄바꿈)', () => {
    assert.deepEqual(P.parseTsv('a\tb\r\nc\td\r\n'), [['a', 'b'], ['c', 'd']]);
});

test('TSV: 따옴표 셀 안의 탭·줄바꿈·"" 이스케이프', () => {
    assert.deepEqual(P.parseTsv('"줄1\n줄2"\t"탭\t포함"\t"말 ""인용"""\n'), [['줄1\n줄2', '탭\t포함', '말 "인용"']]);
});

test('TSV: 빈 셀·빈 줄 유지, 셀 중간 따옴표는 문자 그대로', () => {
    assert.deepEqual(P.parseTsv('a\t\tc\n\nx"y\n'), [['a', '', 'c'], [''], ['x"y']]);
});

test('TSV: 줄바꿈 종류 섞임(LF·CR)', () => {
    assert.deepEqual(P.parseTsv('a\nb\rc'), [['a'], ['b'], ['c']]);
});

test('텍스트: 전각 공백·NBSP·BOM 제거, 수식은 텍스트로', () => {
    assert.equal(P.parseCell('text', '　 한빛정밀 ').value, '한빛정밀');
    assert.equal(P.parseCell('text', '﻿ABC').value, 'ABC');
    assert.equal(P.parseCell('text', '=HYPERLINK("x")').value, '=HYPERLINK("x")');
    assert.equal(P.parseCell('text', 'abcdef', {maxLength: 5}).error, 'TOO_LONG');
    assert.equal(P.parseCell('text', '  ', {required: true}).error, 'REQUIRED');
});

test('숫자: 천 단위·소수·음수·회계 괄호·전각', () => {
    assert.equal(P.parseCell('number', '1,234,567').value, 1234567);
    assert.equal(P.parseCell('number', '1234.50').value, 1234.5);
    assert.equal(P.parseCell('number', '-12').value, -12);
    assert.equal(P.parseCell('number', '(1,234)').value, -1234);
    assert.equal(P.parseCell('number', '１２３').value, 123);
    assert.equal(P.parseCell('number', ' 42 ').value, 42);
    assert.equal(P.parseCell('number', '').value, null);
});

test('숫자: 오류는 원문을 그대로 남긴다', () => {
    for (const bad of ['12a', '1,23', '1.2.3', '1,2345', 'abc', '--1', '1e5', '₩1,000']) {
        const r = P.parseCell('number', bad);
        assert.equal(r.error, 'NUMBER', bad);
        assert.equal(r.value, bad, '원문 유지: ' + bad);
    }
    assert.equal(P.parseCell('number', '-1', {min: 0}).error, 'MIN');
    assert.equal(P.parseCell('number', '1.234', {scale: 2}).error, 'SCALE');
    assert.equal(P.parseCell('number', '1.23', {scale: 2}).value, 1.23);
});

test('날짜: 형식 4종 → yyyy-MM-dd', () => {
    for (const s of ['2026-10-15', '2026/10/15', '2026.10.15', '20261015', '2026-1-5', '2026. 1. 5.'.replace(/ /g, '')]) {
        assert.ok(['2026-10-15', '2026-01-05'].includes(P.parseCell('date', s).value), s);
    }
    assert.equal(P.parseCell('date', '2026-1-5').value, '2026-01-05');
});

test('날짜: 없는 날짜·엑셀 일련번호·다른 형식은 오류 + 원문 유지', () => {
    for (const bad of ['2026-02-30', '2026-13-01', '46000', '15/10/2026', '10월 15일', '2026-10', '0001-01-01']) {
        const r = P.parseCell('date', bad);
        assert.equal(r.error, 'DATE', bad);
        assert.equal(r.value, bad);
    }
    assert.equal(P.parseCell('date', '2028-02-29').value, '2028-02-29'); // 윤년
});

test('코드: 코드(대소문자 무시) 또는 이름 → 코드', () => {
    assert.equal(P.parseCell('code', 'sale', {options: CODES}).value, 'SALE');
    assert.equal(P.parseCell('code', '매입', {options: CODES}).value, 'PURC');
    assert.equal(P.parseCell('code', ' 매출입 ', {options: CODES}).value, 'BOTH');
    const bad = P.parseCell('code', '매출액', {options: CODES});
    assert.equal(bad.error, 'CODE');
    assert.equal(bad.value, '매출액');
    const dup = [{code: 'A', name: '같음'}, {code: 'B', name: '같음'}];
    assert.equal(P.parseCell('code', '같음', {options: dup}).error, 'CODE_AMBIGUOUS');
});

const COLS = [
    {field: 'custCd', type: 'text', editable: true, maxLength: 20},
    {field: 'custNm', type: 'text', editable: true, required: true},
    {field: 'personNm', type: 'text', editable: true},
    {field: 'qty', type: 'number', editable: true, min: 0, scale: 0},
    {field: 'unitPrice', type: 'number', editable: true, scale: 2},
    {field: 'tradeDt', type: 'date', editable: true},
    {field: 'tradeType', type: 'code', editable: true, options: CODES},
    {field: 'regAt', type: 'text', editable: false},
    {field: 'remark', type: 'text', editable: true}
];

function excel50x8() {
    const lines = [];
    for (let i = 0; i < 50; i++) {
        lines.push([`C${i}`, `거래처${i}`, '홍길동', `${i * 1000 > 999 ? (i * 1000).toLocaleString('en-US') : i}`,
            '12,345.67', `2026/10/${(i % 28) + 1}`, i % 2 ? '매출' : 'PURC', '무시됨'].join('\t'));
    }
    return lines.join('\r\n') + '\r\n';
}

test('계획: 엑셀 50행×8열 — 새 행 수, 편집 불가 열 버림', () => {
    const m = P.parseTsv(excel50x8());
    assert.equal(m.length, 50);
    assert.equal(m[0].length, 8);
    const p = P.plan(m, 10, 0, COLS, 30, {maxRows: 500});
    assert.equal(p.truncated, false);
    assert.equal(p.newRows, 30);        // 10행부터 50행 → 60행 필요, 지금 30행
    assert.equal(p.skippedReadonly, 50); // 8번째 열 = regAt(편집 불가)
    assert.equal(p.errors, 0);
    assert.equal(p.cells.length, 50 * 7);
    const qty49 = p.cells.find(c => c.row === 59 && c.field === 'qty');
    assert.equal(qty49.value, 49000);
});

test('계획: 오류 셀은 원문 유지 + 오류 코드, 넘친 열은 버림', () => {
    const m = P.parseTsv('X1\t\t-\t-5\tabc\t2026-02-30\t없는코드\tR\t비고\t넘침\n');
    const p = P.plan(m, 0, 0, COLS, 1);
    const by = Object.fromEntries(p.cells.map(c => [c.field, c]));
    assert.equal(by.custNm.error, 'REQUIRED');
    assert.equal(by.qty.error, 'MIN');
    assert.equal(by.qty.value, '-5');
    assert.equal(by.unitPrice.error, 'NUMBER');
    assert.equal(by.unitPrice.value, 'abc');
    assert.equal(by.tradeDt.value, '2026-02-30');
    assert.equal(by.tradeType.value, '없는코드');
    assert.equal(p.skippedReadonly, 1);
    assert.equal(p.skippedOverflow, 1);
    assert.equal(p.newRows, 0);
});

test('계획: 시작 열이 중간이면 거기서부터 매핑', () => {
    const p = P.plan([['10', '1.5']], 0, 3, COLS, 1);
    assert.deepEqual(p.cells.map(c => [c.field, c.value]), [['qty', 10], ['unitPrice', 1.5]]);
});

test('계획: 상한 초과는 아무것도 적용하지 않는다', () => {
    const m = Array.from({length: 501}, () => ['a']);
    const p = P.plan(m, 0, 0, COLS, 0, {maxRows: 500});
    assert.equal(p.truncated, true);
    assert.equal(p.cells.length, 0);
});
