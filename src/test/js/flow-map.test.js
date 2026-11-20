// 업무 흐름 지도 계산(순수 함수) — 실행: node --test src/test/js/*.test.js (docs/46 12장)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {model, layout, fitScale, restore, move, shown, route, firstSentence} = require('../../main/resources/static/js/views/home/flow-map.layout.js');

const links = {
    'a-1': {prev: [], next: ['b-1', 'c-1']},
    'b-1': {prev: [], next: ['b-2']},
    'b-2': {prev: ['x-out'], next: ['a-1']},
    'c-1': {prev: ['b-2'], next: ['x-out']}
};
const flow = {steps: [['a-1'], ['b-1', 'b-2'], ['c-1']]};
const GRID = {w: 100, h: 40, gx: 50, gy: 20, gr: 30, pad: 10};

test('화살표 = next 또는 prev 어느 쪽에 있어도 하나, 흐름 밖 화면은 빠진다', () => {
    const m = model(flow, links);
    assert.deepEqual(m.cols, [['a-1'], ['b-1', 'b-2'], ['c-1']]);
    assert.deepEqual(m.edges.map((e) => e.from + '>' + e.to + ':' + e.kind), [
        'a-1>b-1:next', 'a-1>c-1:skip', 'b-1>b-2:down', 'b-2>a-1:back', 'b-2>c-1:next'
    ]);
    assert.deepEqual(m.next['a-1'], ['b-1', 'c-1']);
    assert.deepEqual(m.prev['a-1'], ['b-2']);
    assert.deepEqual(m.next['c-1'], [], 'x-out 은 흐름 밖');
});

test('같은 단계 아래 → 위는 되돌아감, 같은 화면 두 번·잘못된 코드·빈 단계는 무시', () => {
    const m = model({steps: [['p-1', 'p-2', 'p-1', 'BAD CODE', 3], [], ['p-1']]}, {'p-2': {next: ['p-1']}});
    assert.deepEqual(m.cols, [['p-1', 'p-2']]);
    assert.equal(m.edges[0].kind, 'back');
    assert.deepEqual(model(null, null).cols, []);
    assert.deepEqual(model({steps: 'x'}, {}).edges, []);
});

test('기본 보기: 주 흐름(다음·아래)만, 상자를 고르면 그 상자의 선은 종류와 상관없이 강조', () => {
    const m = model(flow, links);
    const main = {next: true, down: true, skip: false, back: false};
    assert.deepEqual(shown(m, main, null).map((e) => e.from + '>' + e.to), ['a-1>b-1', 'b-1>b-2', 'b-2>c-1']);
    const sel = shown(m, main, 'a-1');
    assert.deepEqual(sel.filter((e) => e.hot).map((e) => e.from + '>' + e.to), ['a-1>b-1', 'a-1>c-1', 'b-2>a-1'], '들어오고 나가는 것');
    assert.equal(sel.length, 5, '주 흐름 + a-1 의 건너뜀·되돌아감');
    assert.deepEqual(shown(m, {}, null), [], '모두 끄면 선 없음');
});

test('넓으면 한 줄: 단계 = 열, 같은 단계는 아래로', () => {
    const m = model(flow, links);
    const r = layout(m, GRID, {w: 1000, h: 1000});
    assert.equal(r.perRow, 3);
    assert.equal(r.scale, 1);
    assert.deepEqual(r.points['a-1'], {x: 10, y: 10});
    assert.deepEqual(r.points['b-2'], {x: 160, y: 70});
    assert.deepEqual(r.points['c-1'], {x: 310, y: 10});
    assert.deepEqual([r.w, r.h], [420, 120]);
});

test('좁으면 뱀(ㄹ자): 다음 줄은 오른쪽 → 왼쪽, 줄 끝 단계의 다음은 바로 아래', () => {
    const steps = [['s-1'], ['s-2'], ['s-3'], ['s-4'], ['s-5']];
    const m = model({steps}, {});
    // 폭 300 = 두 열(10 + 100 + 50 + 100 + 10 = 270)
    const r = layout(m, GRID, {w: 300, h: 1000});
    assert.equal(r.perRow, 2);
    assert.equal(r.scale, 1);
    assert.deepEqual(['s-1', 's-2', 's-3', 's-4', 's-5'].map((c) => r.points[c].x), [10, 160, 160, 10, 10]);
    assert.deepEqual(['s-1', 's-3', 's-5'].map((c) => r.points[c].y), [10, 80, 150], '줄 사이 = 상자 높이 + gr');
    // 어떤 폭에도 다 안 들어가면 가장 크게 보이는 배치 + 배율
    const tight = layout(m, GRID, {w: 150, h: 150});
    assert.ok(tight.scale < 1 && tight.scale > 0);
});

test('화면 맞춤 배율: 들어가는 가장 큰 값, 범위로 자름', () => {
    assert.equal(fitScale({w: 2000, h: 500}, {w: 1000, h: 1000}, 0.35, 1), 0.5);
    assert.equal(fitScale({w: 500, h: 500}, {w: 1000, h: 1000}, 0.35, 1), 1, '최대 100%');
    assert.equal(fitScale({w: 10000, h: 500}, {w: 1000, h: 1000}, 0.35, 1), 0.35, '최소');
    assert.equal(fitScale({w: 0, h: 0}, {w: 1000, h: 1000}, 0.35, 1), 1, '내용 없음');
});

test('저장 자리는 이 흐름 화면·범위 안 숫자만', () => {
    const m = model(flow, links);
    const base = layout(m, GRID, {w: 1000, h: 1000}).points;
    const r = restore(base, {'a-1': {x: 400.4, y: 5}, 'b-1': {x: -3, y: 1}, 'b-2': {x: 'x', y: 2}, 'c-1': {x: 1e9, y: 0}, 'z-9': {x: 1, y: 1}});
    assert.deepEqual(r['a-1'], {x: 400, y: 5});
    assert.deepEqual(r['b-1'], base['b-1'], '음수는 버림');
    assert.deepEqual(r['b-2'], base['b-2'], '숫자 아님');
    assert.deepEqual(r['c-1'], base['c-1'], '상한 넘음');
    assert.equal(r['z-9'], undefined, '흐름 밖 화면은 안 만든다');
    assert.deepEqual(restore(base, 'garbage'), base);
    assert.deepEqual(move({x: 5, y: 5}, -20, 16), {x: 0, y: 21}, '0 아래로는 못 간다');
});

test('직각 연결: 옆이면 좌우 면, 위아래로 겹치면 위·아래 면', () => {
    const a = {x: 10, y: 10, w: 100, h: 40};
    assert.equal(route(a, {x: 160, y: 70, w: 100, h: 40}), 'M110 30 H135 V90 H160', '오른쪽');
    assert.equal(route(a, {x: 160, y: 10, w: 100, h: 40}), 'M110 30 H135 V30 H160', '같은 높이 = 직선');
    assert.equal(route({x: 300, y: 10, w: 100, h: 40}, {x: 10, y: 10, w: 100, h: 40}), 'M300 30 H205 V30 H110', '왼쪽(끌어서 뒤바뀜)');
    assert.equal(route(a, {x: 30, y: 100, w: 100, h: 40}), 'M60 50 V75 H80 V100', '아래');
    assert.equal(route({x: 30, y: 100, w: 100, h: 40}, a), 'M80 100 V75 H60 V50', '위');
});

test('한 줄 설명은 첫 문장(괄호 안 마침표·소수점은 끝이 아님)', () => {
    assert.equal(firstSentence('작업지시를 만듭니다(공정. 파라미터). 둘째 문장.'), '작업지시를 만듭니다(공정. 파라미터).');
    assert.equal(firstSentence('비율 0.5 를 씁니다'), '비율 0.5 를 씁니다');
    assert.equal(firstSentence('줄\n둘째 줄'), '줄');
    assert.equal(firstSentence(null), '');
});

test('실제 파일: 흐름 9개 모두 모델이 만들어지고, 주 흐름만으로도 단계가 끊기지 않는다', () => {
    const dir = path.join(__dirname, '../../main/resources/static/js/common');
    const flows = JSON.parse(fs.readFileSync(path.join(dir, 'flow-map.json'), 'utf8'));
    const help = JSON.parse(fs.readFileSync(path.join(dir, 'help-flow.json'), 'utf8'));
    assert.deepEqual(Object.keys(flows), ['F0', 'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8']);
    for (const [id, f] of Object.entries(flows)) {
        const m = model(f, help);
        const codes = f.steps.flat();
        assert.equal(Object.keys(m.pos).length, codes.length, id + ' 흐름 안 중복 없음');
        codes.forEach((c) => assert.ok(help[c], id + ' ' + c + ' 는 help-flow.json 에 있어야'));
        // 기본 보기(주 흐름만)에서도 단계마다 다음 단계로 가는 선이 하나 이상 — 흐름이 끊겨 보이지 않게
        const main = shown(m, {next: true, down: true}, null);
        for (let c = 0; c < m.cols.length - 1; c++) {
            assert.ok(main.some((e) => m.pos[e.from].col === c && m.pos[e.to].col === c + 1), id + ' ' + (c + 1) + '단계 뒤가 끊김');
        }
        assert.ok(main.length < m.edges.length, id + ' 기본 보기는 화살표 전부보다 적다');
        // 한눈에: 1366×768 창의 그림판(약 1020×410)에 75% 이상으로, 1920 창(약 1600×800)에는 100% 로 들어간다
        const g = {w: 148, h: 48, gx: 40, gy: 12, gr: 40, pad: 16};
        assert.ok(layout(m, g, {w: 1020, h: 410}).scale >= 0.75, id + ' 1366 에서 너무 작음');
        assert.equal(layout(m, g, {w: 1600, h: 800}).scale, 1, id + ' 1920 에서 맞지 않음');
        assert.ok(layout(m, g, {w: 1600, h: 800}, 1.4).scale >= 1, id + ' 넉넉하면 키운다');
    }
});
