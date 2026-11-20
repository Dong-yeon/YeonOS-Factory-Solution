// @ts-check
/*
 * hb.chart 확인 화면(local 전용, docs/61 11장) — 가짜 데이터로 4종을 그린다. 화면 차트 코드의 본보기:
 * 화면은 hb.chart.* 만 부르고 Highcharts 를 직접 부르지 않는다(ChartLibraryGuardTest).
 */
(function () {
    'use strict';

    /** @param {string} id */
    function $(id) {
        return /** @type {HTMLElement} */ (document.getElementById(id));
    }

    // CSP 위반 세기 — Highcharts 는 첫 그리기 때 실리므로 여기서 걸어도 잡힌다
    var violations = 0;
    document.addEventListener('securitypolicyviolation', function (e) {
        violations++;
        $('cspCount').textContent = String(violations);
        $('cspCount').classList.add('poc-bad');
        var li = document.createElement('li');
        li.textContent = e.violatedDirective + ' ← ' + (e.blockedURI || 'inline') + ' @ ' + e.sourceFile + ':' + e.lineNumber
            + (e.sample ? ' 「' + e.sample + '」' : '');
        $('cspList').appendChild(li);
    });

    // ── 가짜 데이터(고정) ──
    var defects = ['긁힘', '찍힘', '치수 불량', '이물', '도장 불량', '버(Burr)', '용접 불량', '<img src=x onerror=alert(1)>', '포장 파손'];
    var defectCnt = [42, 118, 37, 15, 64, 9, 4, 21, 0];   // 8번째 이름은 이스케이프 확인용(글자 그대로 보여야 한다)

    /** @type {string[]} */
    var days = [];
    /** @type {number[]} */
    var plan = [];
    /** @type {number[]} */
    var actual = [];
    /** @type {(number | null)[]} */
    var rate = [];
    for (var i = 0; i < 14; i++) {
        days.push((i + 1) + '일');
        var p = i % 7 === 5 || i % 7 === 6 ? 0 : 1200;
        var a = p ? Math.round(p * (0.82 + ((i * 37) % 23) / 100)) : 0;
        plan.push(p);
        actual.push(a);
        rate.push(p ? Math.round(a / p * 1000) / 10 : null);
    }

    /** @type {string[]} */
    var labels = [];
    /** @type {number[]} */
    var means = [];
    /** @type {number[]} */
    var ranges = [];
    /** @type {number[]} */
    var ucl = [];
    /** @type {number[]} */
    var lcl = [];
    /** @type {number[]} */
    var rUcl = [];
    /** @type {boolean[]} */
    var alarms = [];
    /** @type {string[]} */
    var tips = [];
    for (var k = 0; k < 30; k++) {
        labels.push(String(k + 1));
        var shift = k >= 20 ? 0.12 : 0;
        var m = 10 + shift + Math.sin(k * 1.7) * 0.06 + (k === 9 ? 0.25 : 0);
        means.push(Math.round(m * 1000) / 1000);
        ranges.push(Math.round((0.12 + Math.abs(Math.cos(k * 2.3)) * 0.1 + (k === 24 ? 0.2 : 0)) * 1000) / 1000);
        var tight = k >= 15;                       // 15번째부터 한계 재계산 → 계단선
        ucl.push(tight ? 10.16 : 10.2);
        lcl.push(tight ? 9.84 : 9.8);
        rUcl.push(tight ? 0.33 : 0.36);
        alarms.push(k === 9 || (k >= 20 && k <= 27));   // 규칙 1(한계 밖) + 규칙 2(같은 쪽 연속 — 서버 판정 흉내)
        tips.push('부분군 ' + (k + 1) + ' · LOT-2611' + String(k + 1).padStart(2, '0'));
    }

    /** @param {string} category @param {number} index */
    function drill(category, index) {
        $('clickOut').textContent = '드릴다운: ' + category + ' (원래 자리 ' + index + ')';
    }

    function drawAll() {
        return Promise.all([
            hb.chart.pareto($('paretoChart'), {categories: defects, values: defectCnt, top: 6, valueName: '불량 수', unit: '건',
                onClick: drill}),
            hb.chart.bar($('barChart'), {categories: days, unit: 'EA', rightUnit: '%', target: 1100,
                series: [{name: '계획', data: plan, tone: 'muted'}, {name: '실적', data: actual},
                    {name: '달성률', data: rate, kind: 'line', axis: 'right'}], onClick: drill}),
            hb.chart.gauge($('gaugeA'), {title: '가동률', value: 87.4, target: 85, unit: '%', decimals: 1}),
            hb.chart.gauge($('gaugeB'), {title: '품질률', value: 96.5, target: 98, unit: '%', decimals: 1, warnPct: 5}),
            hb.chart.gauge($('gaugeC'), {title: '불량 PPM', value: 1450, target: 1000, min: 0, max: 3000, higherIsBetter: false}),
            hb.chart.control($('xbarChart'), {title: 'X̄ 관리도 — 외경(mm)', labels: labels, values: means, ucl: ucl, cl: 10, lcl: lcl,
                alarms: alarms, marked: alarms.map(function (a, n) { return a && n === 9; }), tips: tips, decimals: 3, valueName: '평균'}),
            hb.chart.control($('rChart'), {title: 'R 관리도', labels: labels, values: ranges, ucl: rUcl, cl: 0.16, lcl: 0, decimals: 3,
                valueName: '범위', height: 220}),
            hb.chart.pareto($('emptyChart'), {categories: ['없음'], values: [0], title: '빈 데이터'}),
            hb.chart.bar($('stackChart'), {categories: ['1라인', '2라인', '3라인(긴 이름 라인 예시)'], stacked: true, horizontal: true, unit: '분',
                title: '라인별 비가동(누적)', height: 220,
                series: [{name: '고장', data: [42, 15, 30]}, {name: '셋업', data: [20, 35, 12]}, {name: '자재 대기', data: [8, 0, 25]}]})
        ]);
    }

    hb.chart.ready().then(function (ok) {
        $('libState').textContent = ok ? '라이브러리 실림' : '없음 — 안내 + 표만(scripts/fetch-highcharts.mjs)';
    });
    drawAll();

    $('redrawBtn').addEventListener('click', function () {
        // 같은 칸에 연달아 그리기 — 앞 호출은 버려지고 칸마다 차트 하나만 남아야 한다
        drawAll();
        drawAll().then(function (hs) {
            var svgs = document.querySelectorAll('.hb-chart svg.highcharts-root').length;
            $('clickOut').textContent = '다시 그림: 그린 차트 ' + hs.filter(function (h) { return h.drawn; }).length + '개, SVG ' + svgs + '개';
        });
    });
    $('printBtn').addEventListener('click', function () { window.print(); });
})();
