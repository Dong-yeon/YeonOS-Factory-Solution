// hb.dates 단위 테스트 — 실행: node --test src/test/js/*.test.js   (Gradle check 의 testJs, docs/16 9장 0단계)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const d = require('../../main/resources/static/js/common/hb.dates.js');

test('회사 시간대 오늘: UTC 15시 이후는 한국 날짜가 하루 뒤', () => {
    const utc2330 = new Date(Date.UTC(2026, 9, 15, 23, 30));
    assert.equal(d.todayIn('Asia/Seoul', utc2330), '2026-10-16');
    assert.equal(d.todayIn('UTC', utc2330), '2026-10-15');
    assert.equal(d.todayIn('America/Los_Angeles', utc2330), '2026-10-15');
});

test('직접 입력 형식', () => {
    const ok = (t, v, o) => assert.deepEqual(d.parse(t, o), {ok: true, value: v}, t);
    ok('2026-10-01', '2026-10-01');
    ok('2026.10.01', '2026-10-01');
    ok('2026/10/1', '2026-10-01');
    ok('20261001', '2026-10-01');
    ok(' 2026. 10. 01. ', '2026-10-01');
    ok('26-10-01', '2026-10-01');
    ok('261001', '2026-10-01');
    ok('1015', '2026-10-15', {year: 2026});
    ok('10-15', '2025-10-15', {year: 2025});
    ok('2024-02-29', '2024-02-29');
});

test('없는 날짜·이상한 입력은 오류 (조용히 고치지 않음)', () => {
    for (const t of ['2026-02-29', '2026-13-01', '2026-00-10', '2026-04-31', 'abc', '2026-10', '2026101', '1899-12-31',
        '3000-01-01', '<script>', '2026-10-01T00:00', '１２３４']) {
        assert.equal(d.parse(t).ok, false, t);
    }
    assert.deepEqual(d.parse('   '), {ok: false, code: 'EMPTY'});
    assert.deepEqual(d.parse(null), {ok: false, code: 'EMPTY'});
});

test('붙여넣기 범위 — 구분자 여러 가지, 거꾸로면 정렬 + 표시', () => {
    assert.deepEqual(d.parseRange('2026-10-01 ~ 2026-10-15'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: false});
    assert.deepEqual(d.parseRange('2026-10-01\t2026-10-15'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: false});
    assert.deepEqual(d.parseRange('2026.10.01-2026.10.15'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: false});
    assert.deepEqual(d.parseRange('20261001-20261015'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: false});
    assert.deepEqual(d.parseRange('2026-10-15 ~ 2026-10-01'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: true});
    assert.deepEqual(d.parseRange('2026-10-01 ~ 1015'), {ok: true, from: '2026-10-01', to: '2026-10-15', swapped: false});
    assert.equal(d.parseRange('2026-10-01'), null, '날짜 하나는 범위가 아님');
    assert.equal(d.parseRange('2026-10-01 ~ 2026-10-15 ~ 2026-11-01'), null);
    assert.equal(d.parseRange('2026-10-01 ~ 2026-02-30').ok, false);
});

test('일수(종료일 포함)·월말·윤년', () => {
    assert.equal(d.diffDays('2026-10-01', '2026-10-01'), 1);
    assert.equal(d.diffDays('2026-10-01', '2026-10-15'), 15);
    assert.equal(d.diffDays('2024-02-28', '2024-03-01'), 3);
    assert.equal(d.add('2026-10-31', 1), '2026-11-01');
    assert.equal(d.add('2026-03-01', -1), '2026-02-28');
    assert.equal(d.add('2026-12-31', 1), '2027-01-01');
    assert.equal(d.daysInMonth(2024, 2), 29);
});

test('프리셋 — 기준일, max(어제) 보정, 월 경계, 주 시작', () => {
    const t = '2026-10-16'; // 금요일
    assert.deepEqual(d.preset('last7', {today: t}), {from: '2026-10-10', to: '2026-10-16', days: 7});
    assert.deepEqual(d.preset('last7', {today: t, max: '2026-10-15'}), {from: '2026-10-09', to: '2026-10-15', days: 7});
    assert.equal(d.preset('today', {today: t, max: '2026-10-15'}), null, '오늘은 max(어제)를 넘음');
    assert.deepEqual(d.preset('yesterday', {today: t, max: '2026-10-15'}), {from: '2026-10-15', to: '2026-10-15', days: 1});
    assert.deepEqual(d.preset('thisWeek', {today: t}), {from: '2026-10-12', to: '2026-10-16', days: 5});
    assert.deepEqual(d.preset('thisWeek', {today: t, weekStart: 0}), {from: '2026-10-11', to: '2026-10-16', days: 6});
    assert.deepEqual(d.preset('thisMonth', {today: '2026-10-01', max: '2026-09-30'}), {from: '2026-09-01', to: '2026-09-30', days: 30});
    assert.deepEqual(d.preset('lastMonth', {today: '2026-01-10'}), {from: '2025-12-01', to: '2025-12-31', days: 31});
    assert.deepEqual(d.preset('lastMonth', {today: '2024-03-05'}), {from: '2024-02-01', to: '2024-02-29', days: 29});
    assert.equal(d.preset('last30', {today: t, maxDays: 7}), null, '상한을 넘는 프리셋은 숨김');
    assert.equal(d.preset('nope', {today: t}), null);
});

test('resolve: today·yesterday·날짜만', () => {
    assert.equal(d.resolve('yesterday', '2026-10-16'), '2026-10-15');
    assert.equal(d.resolve('2026-10-01', '2026-10-16'), '2026-10-01');
    assert.equal(d.resolve('2026-02-30', '2026-10-16'), null);
    assert.equal(d.resolve('drop table', '2026-10-16'), null);
});
