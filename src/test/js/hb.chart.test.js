// hb.chart 순수 함수 — 파레토 누적·관리도 한계·게이지 상태·글자 이스케이프·Highcharts 옵션 모양 (docs/61 11장). 실행: node --test src/test/js/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const c = require('../../main/resources/static/js/common/hb.chart.js');

/** @type {any} 화면 문구 대신 고정 글자 */
const L = {
    category: '항목', value: '값', cumPct: '누적 %', target: '목표', status: '상태', no: '번호', alarm: '위반', others: '기타',
    statusText: {ok: '정상', warn: '주의', danger: '이상', none: '-'},
    thresholdLine: (v) => v + '% 선', targetLine: (v) => '목표 ' + v,
    sumPareto: (n, cut, th) => `파레토 ${n}개, 상위 ${cut}개가 ${th}%`, sumBar: (n, names) => `막대 ${n}개: ${names}`,
    sumGauge: (v, tg, st) => `게이지 ${v} 목표 ${tg} ${st}`, sumControl: (n, a) => `관리도 ${n}점 위반 ${a}`,
};

test('파레토: 큰 순 정렬, 0·음수·비숫자 제외, 누적 % 마지막은 100, 80% 에 처음 닿는 개수', () => {
    const p = c.paretoData(['긁힘', '찍힘', '치수', '이물', '없음', '음수', '빈칸'], [12, 30, 8, 50, 0, -3, null]);
    assert.deepEqual(p.categories, ['이물', '찍힘', '긁힘', '치수']);
    assert.deepEqual(p.values, [50, 30, 12, 8]);
    assert.equal(p.total, 100);
    assert.deepEqual(p.cumPct, [50, 80, 92, 100]);
    assert.equal(p.cutCount, 2);   // 80.0 에 닿는 순간 포함
    assert.equal(p.threshold, 80);
});

test('파레토: 같은 값은 원래 순서, 반올림이 쌓여도 마지막은 정확히 100', () => {
    const p = c.paretoData(['A', 'B', 'C'], [1, 1, 1]);
    assert.deepEqual(p.categories, ['A', 'B', 'C']);
    assert.deepEqual(p.cumPct, [33.3, 66.7, 100]);
    assert.equal(p.cutCount, 3);
});

test('파레토: top 뒤는 기타로 묶어 맨 뒤(기타가 커도), 하나만 남으면 묶지 않는다', () => {
    const p = c.paretoData(['a', 'b', 'c', 'd', 'e'], [40, 30, 15, 10, 5], {top: 2, othersLabel: '기타'});
    assert.deepEqual(p.categories, ['a', 'b', '기타']);
    assert.deepEqual(p.values, [40, 30, 30]);
    assert.deepEqual(p.cumPct, [40, 70, 100]);
    const q = c.paretoData(['a', 'b', 'c'], [3, 2, 1], {top: 2, othersLabel: '기타'});
    assert.deepEqual(q.categories, ['a', 'b', 'c'], '기타 한 개는 원래 이름이 더 낫다');
});

test('파레토: 기준선을 바꿀 수 있고, 전부 0 이면 빈 결과(나누기 0 없음)', () => {
    assert.equal(c.paretoData(['a', 'b', 'c'], [50, 30, 20], {threshold: 50}).cutCount, 1);
    const e = c.paretoData(['a'], [0]);
    assert.deepEqual(e.categories, []);
    assert.deepEqual(e.cumPct, []);
    assert.equal(e.cutCount, 0);
});

test('관리도: 한계 하나·점마다 모두 길이 맞춤, 한계 밖 점 = 위반(서버 판정이 있으면 그것)', () => {
    const d = c.controlData({labels: ['1', '2', '3', '4'], values: [10, 13, 7, null], ucl: 12, cl: 10, lcl: [8, 8, 8, 6]});
    assert.deepEqual(d.ucl, [12, 12, 12, 12]);
    assert.deepEqual(d.lcl, [8, 8, 8, 6]);
    assert.deepEqual(d.values, [10, 13, 7, null]);
    assert.deepEqual(d.alarms, [false, true, true, false]);
    assert.equal(d.outCount, 2);
    const s = c.controlData({labels: ['1', '2', '3'], values: [10, 11, 10], ucl: 12, cl: 10, lcl: 8, alarms: [false, true, false]});
    assert.deepEqual(s.alarms, [false, true, false], '규칙 2~8(연속 등)은 서버가 판정해 넘긴다');
    assert.equal(s.outCount, 0);
    assert.equal(s.alarmCount, 1);
});

test('게이지 상태: 클수록 좋음·작을수록 좋음, 경고 띠, 값·목표 없음', () => {
    assert.equal(c.gaugeStatus(95, 90), 'ok');
    assert.equal(c.gaugeStatus(85, 90), 'warn');     // 90 의 10% = 9 → 81 이상
    assert.equal(c.gaugeStatus(80, 90), 'danger');
    assert.equal(c.gaugeStatus(1.0, 1.2, false), 'ok');
    assert.equal(c.gaugeStatus(1.3, 1.2, false), 'warn');
    assert.equal(c.gaugeStatus(2, 1.2, false), 'danger');
    assert.equal(c.gaugeStatus(50, null), 'none');
    assert.equal(c.gaugeStatus(null, 90), 'none');
    assert.equal(c.gaugeStatus(88, 90, true, 1), 'danger');
});

test('글자 이스케이프: Highcharts 가 HTML 로 해석하지 않게 &<> 를 바꾼다', () => {
    assert.equal(c.escapeText('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
    assert.equal(c.escapeText('A&B'), 'A&amp;B');
    assert.equal(c.escapeText(null), '');
});

test('숫자 글자: 천 단위 쉼표·소수 자리·음수, 비숫자는 빈 글자', () => {
    assert.equal(c.fmt(1234567.891, 2), '1,234,567.89');
    assert.equal(c.fmt(-1234, 0), '-1,234');
    assert.equal(c.fmt(0.5, 0), '1');
    assert.equal(c.fmt(NaN), '');
    assert.equal(c.fmt(null), '');
});

test('옵션: 네 종류 모두 styled mode(CSP — <style> 주입 없음), 크레딧·내보내기 끔, 색 지정 없음', () => {
    const all = [
        c.optionsOf('pareto', {categories: ['a', 'b'], values: [2, 1]}, L),
        c.optionsOf('bar', {categories: ['1일'], series: [{name: '계획', data: [10], tone: 'muted'}, {name: '실적', data: [9]}]}, L),
        c.optionsOf('gauge', {value: 87, target: 90}, L),
        c.optionsOf('control', {labels: ['1'], values: [1], ucl: 2, cl: 1, lcl: 0}, L),
    ];
    for (const o of all) {
        assert.equal(o.chart.styledMode, true);
        assert.equal(o.credits.enabled, false);
        assert.equal(o.exporting.enabled, false);
        assert.equal(o.accessibility.enabled, true);
        assert.doesNotMatch(JSON.stringify(o), /"(color|style|fillColor|lineColor)"\s*:/, '색·style 은 CSS(토큰)로만');
    }
});

test('옵션 파레토: 막대 + 오른쪽 축 누적 % 선, 80% 기준선, 중요한 소수 막대 표시', () => {
    const o = c.optionsOf('pareto', {categories: ['x', 'y', 'z'], values: [10, 70, 20], unit: '건'}, L);
    assert.deepEqual(o.xAxis.categories, ['y', 'z', 'x']);
    assert.equal(o.series[0].type, 'column');
    assert.equal(o.series[1].type, 'line');
    assert.equal(o.series[1].yAxis, 1);
    assert.deepEqual(o.series[1].data, [70, 90, 100]);
    assert.equal(o.yAxis[1].max, 100);
    assert.equal(o.yAxis[1].plotLines[0].value, 80);
    assert.deepEqual(o.series[0].data.map((p) => p.className), ['hb-chart-vital', 'hb-chart-vital', 'hb-chart-trivial']);
});

test('옵션 막대: 목표선, 선 계열, 오른쪽 축, 누적, 가로', () => {
    const o = c.optionsOf('bar', {categories: ['A', 'B'], target: 100, stacked: true, horizontal: true,
        series: [{name: '계획', data: [100, 100], tone: 'muted'}, {name: '실적', data: [90, null]}, {name: '달성률', data: [90, 0], kind: 'line', axis: 'right'}]}, L);
    assert.equal(o.yAxis.length, 2);
    assert.equal(o.yAxis[0].plotLines[0].value, 100);
    assert.deepEqual(o.series.map((s) => s.type), ['column', 'column', 'line']);
    assert.deepEqual(o.series.map((s) => s.yAxis), [0, 0, 1]);
    assert.equal(o.series[0].className, 'hb-chart-muted');
    assert.deepEqual(o.series.map((s) => s.colorIndex), [5, 0, 1], '옅은 계열 다음 실적이 기본색');
    assert.equal(o.plotOptions.column.stacking, 'normal');
    assert.equal(o.chart.inverted, true);
    assert.equal(o.legend.enabled, true);
});

test('옵션 게이지: 범위 밖 값은 바늘만 끝에, 라벨은 실제 값, 목표는 얇은 띠, 상태 class', () => {
    const o = c.optionsOf('gauge', {value: 123.4, min: 0, max: 100, target: 90, decimals: 1, unit: '%'}, L);
    assert.equal(o.chart.type, 'solidgauge');
    assert.equal(o.series[0].data[0].y, 100);
    assert.equal(o.series[0].data[0].className, 'hb-chart-ok');
    assert.equal(o.series[0].dataLabels.format, '123.4 %');
    assert.equal(o.yAxis.plotBands.length, 1);
    assert.ok(o.yAxis.plotBands[0].from < 90 && o.yAxis.plotBands[0].to > 90);
});

test('옵션 관리도: 한계는 계단선(step center), 마지막 점에 UCL·CL·LCL 라벨, 위반 점은 모양·크기도 다름', () => {
    const o = c.optionsOf('control', {labels: ['1', '2', '3'], values: [10, 13, 9], ucl: [12, 12, 12.5], cl: 10, lcl: 8, decimals: 1,
        marked: [false, true, false], tips: ['LOT-1', 'LOT-<2>', '']}, L);
    const [ucl, cl, lcl, val] = o.series;
    assert.deepEqual([ucl.name, cl.name, lcl.name], ['UCL', 'CL', 'LCL']);
    for (const s of [ucl, cl, lcl]) {
        assert.equal(s.step, 'center');
        assert.equal(s.enableMouseTracking, false);
    }
    assert.equal(ucl.data[2].dataLabels.format, 'UCL 12.5');
    assert.equal(val.data[1].className, 'hb-chart-alarm hb-chart-marked');
    assert.equal(val.data[1].marker.symbol, 'diamond');
    assert.equal(val.data[0].className, undefined);
    assert.equal(val.data[1].name, 'LOT-&lt;2&gt;');
});

test('항목 이름·제목·단위는 이스케이프되어 옵션에 들어간다(XSS·글자 사라짐 방지)', () => {
    const o = c.optionsOf('bar', {title: '<b>t</b>', unit: '<u>', categories: ['<img src=x>'], series: [{name: '<s>', data: [1]}]}, L);
    assert.equal(o.title.text, '&lt;b&gt;t&lt;/b&gt;');
    assert.equal(o.xAxis.categories[0], '&lt;img src=x&gt;');
    assert.equal(o.series[0].name, '&lt;s&gt;');
    assert.equal(o.yAxis[0].title.text, '&lt;u&gt;');
});

test('표로 보기: 종류별 머리줄과 글자 행(파레토는 정렬·누적 %)', () => {
    const p = c.tableOf('pareto', {categories: ['a', 'b'], values: [1, 3]}, L);
    assert.deepEqual(p.head, ['항목', '값', '누적 %']);
    assert.deepEqual(p.rows, [['b', '3', '75.0%'], ['a', '1', '100.0%']]);
    const b = c.tableOf('bar', {categories: ['1일', '2일'], series: [{name: '실적', data: [1200, null]}]}, L);
    assert.deepEqual(b.rows, [['1일', '1,200'], ['2일', '']]);
    const g = c.tableOf('gauge', {value: 85, target: 90, unit: '%'}, L);
    assert.deepEqual(g.rows, [['85%', '90%', '주의']]);
    const k = c.tableOf('control', {labels: ['1', '2'], values: [1, 5], ucl: 4, cl: 2, lcl: 0}, L);
    assert.deepEqual(k.head, ['번호', '값', 'UCL', 'CL', 'LCL', '위반']);
    assert.deepEqual(k.rows[1], ['2', '5', '4', '2', '0', '위반']);
});

test('요약 문장: 종류별 기본, summary 를 주면 그대로', () => {
    assert.equal(c.summaryOf('pareto', {categories: ['a', 'b', 'c'], values: [70, 20, 10]}, L), '파레토 3개, 상위 2개가 80%');
    assert.equal(c.summaryOf('control', {labels: ['1', '2'], values: [1, 9], ucl: 5, cl: 2, lcl: 0}, L), '관리도 2점 위반 1');
    assert.equal(c.summaryOf('gauge', {value: 87, target: 90, summary: '직접'}, L), '직접');
});
