// @ts-check
/*
 * hb.chart — 화면 차트 감싸기 (docs/61 11장). 안쪽은 Highcharts(styled mode), 화면은 hb.chart 만 부른다.
 *   hb.chart.pareto(el, {categories, values})                     → 큰 순 막대 + 누적 % 선 + 80% 선
 *   hb.chart.bar(el, {categories, series:[{name, data, kind}]})   → 막대(+선), 목표선, 누적, 오른쪽 축
 *   hb.chart.gauge(el, {value, target, min, max})                 → 반원 게이지(목표 표시, 상태 색)
 *   hb.chart.control(el, {labels, values, ucl, cl, lcl})          → 관리도(한계는 점마다 다를 수 있어 계단선)
 * 모두 Promise<ChartHandle>. 같은 el 에 다시 부르면 앞의 것을 지우고 그린다(늦게 끝난 앞 호출은 버림).
 *
 * - Highcharts 는 저장소에 없다(상업 유료 — 파일 커밋 금지). scripts/fetch-highcharts.mjs 로 받은
 *   /vendor-local/highcharts/<HC_VERSION>/ 을 처음 그릴 때 한 번만 싣는다. 없으면 화면은 깨지지 않고
 *   안내 문구 + 수치 표만 보인다(drawn=false).
 * - CSP(style-src 'self'): styled mode 만 쓴다 — styled mode 가 아니면 Highcharts 가 <style> 팔레트를 SVG 에 넣어 막힌다.
 *   색은 hb.chart.css 가 디자인 토큰(--hb-chart-*)을 Highcharts CSS 변수에 잇는다. 옵션에 색·style 을 넣지 않는다.
 * - 글자(항목 이름·계열 이름)는 escapeText 로 &<> 를 바꿔 넘긴다 — Highcharts 는 라벨을 HTML 조각으로 해석한다.
 * - 접근성: 차트 아래 접는 "표로 보기"(textContent), Highcharts accessibility 모듈 + 요약 문장(aria).
 * - 인쇄: SVG viewBox 유지 + hb.chart.css 의 @media print 로 폭에 맞춘다.
 * - 화면 JS 가 Highcharts 전역을 직접 부르면 ChartLibraryGuardTest 가 실패한다(교체 가능성을 지키려고).
 * 순수 함수(paretoData·controlData·gaugeStatus·escapeText·optionsOf·tableOf)는 node 단위 시험(src/test/js/hb.chart.test.js).
 */
(function (root) {
    'use strict';

    /** scripts/fetch-highcharts.mjs 의 VERSION 과 같아야 한다(ChartLibraryGuardTest) */
    var HC_VERSION = '13.1.1';
    var HC_BASE = '/vendor-local/highcharts/' + HC_VERSION + '/';
    var HC_SCRIPTS = ['highcharts.js', 'highcharts-more.js', 'modules/solid-gauge.js', 'modules/accessibility.js'];
    var CSS = [HC_BASE + 'css/highcharts.css', '/css/hb.chart.css'];

    // ───────────────────────── 순수 함수 ─────────────────────────

    /** @param {unknown} v @returns {v is number} */
    function isNum(v) {
        return typeof v === 'number' && isFinite(v);
    }

    /** @param {number} v @param {number} d */
    function round(v, d) {
        var f = Math.pow(10, d);
        return Math.round(v * f) / f;
    }

    /**
     * 표·라벨용 숫자 글자 — 천 단위 쉼표, 소수 d 자리(로케일과 무관하게 같은 결과)
     * @param {number | null | undefined} v @param {number} [d]
     */
    function fmt(v, d) {
        if (!isNum(v)) { return ''; }
        var s = round(v, d || 0).toFixed(d || 0);
        var neg = s.charAt(0) === '-';
        var parts = (neg ? s.substring(1) : s).split('.');
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        return (neg ? '-' : '') + parts.join('.');
    }

    /** Highcharts 라벨은 HTML 조각으로 해석된다 → 글자 그대로 보이게 @param {unknown} s */
    function escapeText(s) {
        return String(s === null || s === undefined ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /**
     * 파레토: 0 초과 값만, 큰 순(같으면 원래 순서), 누적 %(소수 1자리, 마지막은 100).
     * top 을 주면 top 개 뒤는 othersLabel 한 막대로 묶는다(기타는 크기와 상관없이 맨 뒤).
     * cutCount = 누적 % 가 threshold 에 처음 닿는 항목까지의 개수(중요한 소수).
     * @param {string[]} categories @param {(number | null | undefined)[]} values
     * @param {{threshold?: number, top?: number, othersLabel?: string}} [opt]
     * @returns {HB.ChartParetoData}
     */
    function paretoData(categories, values, opt) {
        var o = opt || {};
        var threshold = isNum(o.threshold) ? o.threshold : 80;
        /** @type {{c: string, v: number, i: number}[]} */
        var rows = [];
        for (var i = 0; i < categories.length; i++) {
            var v = values[i];
            if (isNum(v) && v > 0) { rows.push({c: String(categories[i]), v: v, i: i}); }
        }
        rows.sort(function (a, b) { return b.v - a.v || a.i - b.i; });
        if (isNum(o.top) && o.top > 0 && rows.length > o.top + 1) {
            var rest = rows.slice(o.top).reduce(function (s, r) { return s + r.v; }, 0);
            rows = rows.slice(0, o.top).concat([{c: o.othersLabel || 'Others', v: rest, i: Infinity}]);
        }
        var total = rows.reduce(function (s, r) { return s + r.v; }, 0);
        var run = 0;
        var cutCount = 0;
        var cum = rows.map(function (r, k) {
            run += r.v;
            var p = k === rows.length - 1 ? 100 : round(run / total * 100, 1);
            if (!cutCount && p >= threshold) { cutCount = k + 1; }
            return p;
        });
        return {categories: rows.map(function (r) { return r.c; }), values: rows.map(function (r) { return r.v; }),
            cumPct: cum, total: total, cutCount: cutCount, threshold: threshold};
    }

    /**
     * 한계 값(하나 또는 점마다) → 길이 n 배열. 없으면 null 로 채운다
     * @param {number | null | undefined | (number | null | undefined)[]} v @param {number} n
     * @returns {(number | null)[]}
     */
    function limits(v, n) {
        var out = [];
        for (var i = 0; i < n; i++) {
            var x = Array.isArray(v) ? v[i] : v;
            out.push(isNum(x) ? x : null);
        }
        return out;
    }

    /**
     * 관리도: 한계를 점마다 맞추고, 위반 표시가 없으면 한계 밖 점을 위반으로 본다(규칙 1).
     * @param {HB.ChartControlOptions} o
     * @returns {HB.ChartControlData}
     */
    function controlData(o) {
        var n = o.labels.length;
        var values = limits(o.values, n);
        var ucl = limits(o.ucl, n);
        var cl = limits(o.cl, n);
        var lcl = limits(o.lcl, n);
        var alarms = [];
        var out = 0;
        for (var i = 0; i < n; i++) {
            var v = values[i];
            var u = ucl[i];
            var l = lcl[i];
            var beyond = isNum(v) && ((isNum(u) && v > u) || (isNum(l) && v < l));
            if (beyond) { out++; }
            alarms.push(o.alarms ? !!o.alarms[i] : beyond);
        }
        return {labels: o.labels.map(String), values: values, ucl: ucl, cl: cl, lcl: lcl, alarms: alarms,
            outCount: out, alarmCount: alarms.filter(Boolean).length};
    }

    /**
     * 게이지 상태: 목표 이상(작을수록 좋으면 이하) ok, 목표에서 warnPct% 안쪽 warn, 그 밖 danger, 값·목표 없으면 none
     * @param {number | null | undefined} value @param {number | null | undefined} target
     * @param {boolean} [higherIsBetter] 기본 true @param {number} [warnPct] 기본 10
     * @returns {'ok' | 'warn' | 'danger' | 'none'}
     */
    function gaugeStatus(value, target, higherIsBetter, warnPct) {
        if (!isNum(value) || !isNum(target)) { return 'none'; }
        var band = Math.abs(target) * (isNum(warnPct) ? warnPct : 10) / 100;
        if (higherIsBetter === false) {
            return value <= target ? 'ok' : value <= target + band ? 'warn' : 'danger';
        }
        return value >= target ? 'ok' : value >= target - band ? 'warn' : 'danger';
    }

    /**
     * 표로 보기 — 머리줄 + 행(글자). 화면 문구는 labels 로 받는다(순수 함수라 hb.t 를 부르지 않는다)
     * @param {HB.ChartKind} kind @param {any} o @param {HB.ChartLabels} L
     * @returns {{head: string[], rows: string[][]}}
     */
    function tableOf(kind, o, L) {
        var d = isNum(o.decimals) ? o.decimals : 0;
        if (kind === 'pareto') {
            var p = paretoData(o.categories, o.values, {threshold: o.threshold, top: o.top, othersLabel: L.others});
            return {head: [L.category, o.valueName || L.value, L.cumPct],
                rows: p.categories.map(function (c, i) { return [c, fmt(p.values[i], d), fmt(p.cumPct[i], 1) + '%']; })};
        }
        if (kind === 'bar') {
            /** @type {HB.ChartBarSeries[]} */
            var series = o.series;
            return {head: [L.category].concat(series.map(function (s) { return s.name; })),
                rows: o.categories.map(/** @param {string} c @param {number} i */ function (c, i) {
                    return [String(c)].concat(series.map(function (s) { return fmt(s.data[i], d); }));
                })};
        }
        if (kind === 'gauge') {
            return {head: [L.value, L.target, L.status], rows: [[fmt(o.value, d) + (o.unit || ''),
                isNum(o.target) ? fmt(o.target, d) + (o.unit || '') : '',
                L.statusText[gaugeStatus(o.value, o.target, o.higherIsBetter, o.warnPct)]]]};
        }
        var c = controlData(o);
        return {head: [L.no, o.valueName || L.value, 'UCL', 'CL', 'LCL', L.alarm],
            rows: c.labels.map(function (lb, i) {
                return [lb, fmt(c.values[i], d), fmt(c.ucl[i], d), fmt(c.cl[i], d), fmt(c.lcl[i], d), c.alarms[i] ? L.alarm : ''];
            })};
    }

    /**
     * 화면 낭독기용 요약 한 문장
     * @param {HB.ChartKind} kind @param {any} o @param {HB.ChartLabels} L
     */
    function summaryOf(kind, o, L) {
        if (o.summary) { return String(o.summary); }
        var d = isNum(o.decimals) ? o.decimals : 0;
        if (kind === 'pareto') {
            var p = paretoData(o.categories, o.values, {threshold: o.threshold, top: o.top, othersLabel: L.others});
            return L.sumPareto(p.categories.length, p.cutCount, p.threshold);
        }
        if (kind === 'bar') { return L.sumBar(o.categories.length, o.series.map(/** @param {HB.ChartBarSeries} s */ function (s) { return s.name; }).join(', ')); }
        if (kind === 'gauge') {
            return L.sumGauge(fmt(o.value, d) + (o.unit || ''), isNum(o.target) ? fmt(o.target, d) + (o.unit || '') : '-',
                L.statusText[gaugeStatus(o.value, o.target, o.higherIsBetter, o.warnPct)]);
        }
        var c = controlData(o);
        return L.sumControl(c.labels.length, c.alarmCount);
    }

    /**
     * Highcharts 옵션(순수 객체). 색·style 은 넣지 않는다 — styled mode + hb.chart.css
     * @param {HB.ChartKind} kind @param {any} o @param {HB.ChartLabels} L
     * @returns {Record<string, any>}
     */
    function optionsOf(kind, o, L) {
        var d = isNum(o.decimals) ? o.decimals : 0;
        var unit = o.unit ? ' ' + escapeText(o.unit) : '';
        /** @type {Record<string, any>} */
        var base = {
            chart: {styledMode: true, height: isNum(o.height) ? o.height : (kind === 'gauge' ? 220 : 300), animation: false},
            title: {text: o.title ? escapeText(o.title) : undefined},
            credits: {enabled: false},
            exporting: {enabled: false},
            accessibility: {enabled: true, description: escapeText(summaryOf(kind, o, L))},
            legend: {enabled: false},
            tooltip: {valueDecimals: d},
            plotOptions: {series: {animation: false}}
        };
        if (kind === 'pareto') {
            var p = paretoData(o.categories, o.values, {threshold: o.threshold, top: o.top, othersLabel: L.others});
            base.xAxis = {categories: p.categories.map(escapeText), crosshair: true};
            base.yAxis = [
                {title: {text: o.unit ? escapeText(o.unit) : null}, min: 0},
                {title: {text: escapeText(L.cumPct)}, min: 0, max: 100, tickInterval: 20, opposite: true,
                    labels: {format: '{value}%'},
                    plotLines: [{value: p.threshold, className: 'hb-chart-ref', zIndex: 3,
                        label: {text: escapeText(L.thresholdLine(p.threshold)), align: 'right', x: -4}}]}];
            base.tooltip = {shared: true, valueDecimals: d};
            base.legend = {enabled: true};
            base.series = [
                {type: 'column', name: escapeText(o.valueName || L.value), colorIndex: 0, tooltip: {valueSuffix: unit},
                    data: p.values.map(function (v, i) { return {y: v, className: i < p.cutCount ? 'hb-chart-vital' : 'hb-chart-trivial'}; })},
                {type: 'line', name: escapeText(L.cumPct), yAxis: 1, colorIndex: 1, className: 'hb-chart-cum',
                    tooltip: {valueSuffix: '%', valueDecimals: 1}, data: p.cumPct}];
            return base;
        }
        if (kind === 'bar') {
            /** @type {HB.ChartBarSeries[]} */
            var series = o.series;
            var right = series.some(function (s) { return s.axis === 'right'; });
            /** @type {Record<string, any>[]} */
            var yAxis = [{title: {text: o.unit ? escapeText(o.unit) : null},
                plotLines: isNum(o.target) ? [{value: o.target, className: 'hb-chart-ref', zIndex: 3,
                    label: {text: escapeText(L.targetLine(fmt(o.target, d))), align: 'right', x: -4}}] : []}];
            if (right) { yAxis.push({title: {text: o.rightUnit ? escapeText(o.rightUnit) : null}, opposite: true}); }
            base.chart.inverted = !!o.horizontal;
            base.xAxis = {categories: o.categories.map(escapeText), crosshair: true};
            base.yAxis = yAxis;
            base.tooltip = {shared: true, valueDecimals: d};
            base.legend = {enabled: series.length > 1};
            base.plotOptions.column = {stacking: o.stacked ? 'normal' : undefined, grouping: !o.stacked};
            var nth = 0;   // 옅은(muted) 계열은 색 순번을 쓰지 않는다 — 계획 + 실적이면 실적이 기본색(--hb-chart-1)
            base.series = series.map(function (s) {
                return {type: s.kind === 'line' ? 'line' : 'column', name: escapeText(s.name), data: s.data.slice(),
                    yAxis: right && s.axis === 'right' ? 1 : 0, colorIndex: s.tone === 'muted' ? 5 : nth++ % 6,
                    className: s.tone === 'muted' ? 'hb-chart-muted' : undefined};
            });
            return base;
        }
        if (kind === 'gauge') {
            var min = isNum(o.min) ? o.min : 0;
            var max = isNum(o.max) ? o.max : 100;
            var shown = isNum(o.value) ? Math.min(Math.max(o.value, min), max) : null;
            var status = gaugeStatus(o.value, o.target, o.higherIsBetter, o.warnPct);
            var eps = (max - min) * 0.006;
            base.chart.type = 'solidgauge';
            base.pane = {center: ['50%', '80%'], size: '100%', startAngle: -90, endAngle: 90,
                background: [{innerRadius: '60%', outerRadius: '100%', shape: 'arc', className: 'hb-chart-track'}]};
            base.tooltip = {enabled: false};
            base.yAxis = {min: min, max: max, tickPositions: [min, max], lineWidth: 0, minorTickInterval: null,
                labels: {y: 16, distance: -12, formatter: /** @this {any} */ function () { return fmt(this.value, d); }},
                plotBands: isNum(o.target) ? [{from: o.target - eps, to: o.target + eps, innerRadius: '56%', outerRadius: '104%',
                    className: 'hb-chart-target', zIndex: 5}] : []};
            base.plotOptions.solidgauge = {innerRadius: '60%', dataLabels: {y: -6, borderWidth: 0, useHTML: false}};
            base.series = [{name: escapeText(o.name || L.value),
                data: shown === null ? [] : [{y: shown, className: 'hb-chart-' + status}],
                dataLabels: {format: escapeText(fmt(o.value, d)) + unit, className: 'hb-chart-gauge-label'}}];
            return base;
        }
        var c = controlData(o);
        /** @param {(number | null)[]} arr @param {string} name @param {string} cls */
        function limitSeries(arr, name, cls) {
            var last = arr.length - 1;
            return {type: 'line', name: name, className: cls, step: 'center', enableMouseTracking: false,
                marker: {enabled: false}, states: {inactive: {enabled: false}},
                data: arr.map(function (v, i) {
                    return i === last && isNum(v) ? {y: v, dataLabels: {enabled: true, format: name + ' ' + fmt(v, d), align: 'left', x: 4,
                        className: cls + '-label'}} : v;
                })};
        }
        base.chart.type = 'line';
        base.xAxis = {categories: c.labels.map(escapeText), labels: {step: c.labels.length > 30 ? Math.ceil(c.labels.length / 15) : undefined}};
        base.yAxis = {title: {text: o.unit ? escapeText(o.unit) : null}};
        base.plotOptions.line = {marker: {enabled: true}};
        var tips = Array.isArray(o.tips) ? o.tips : [];
        base.series = [
            limitSeries(c.ucl, 'UCL', 'hb-chart-limit'),
            limitSeries(c.cl, 'CL', 'hb-chart-center'),
            limitSeries(c.lcl, 'LCL', 'hb-chart-limit'),
            {type: 'line', name: escapeText(o.valueName || L.value), colorIndex: 0, className: 'hb-chart-value', zIndex: 4,
                tooltip: {valueDecimals: d},
                data: c.values.map(function (v, i) {
                    var cls = (c.alarms[i] ? 'hb-chart-alarm' : '') + (o.marked && o.marked[i] ? ' hb-chart-marked' : '');
                    return {y: v, name: tips[i] ? escapeText(tips[i]) : undefined, className: cls.trim() || undefined,
                        marker: {radius: c.alarms[i] ? 5 : 3, symbol: c.alarms[i] ? 'diamond' : 'circle'}};
                })}];
        base.tooltip = {valueDecimals: d, pointFormat: '{point.name} <b>{point.y}</b>'};
        return base;
    }

    var pure = {paretoData: paretoData, controlData: controlData, gaugeStatus: gaugeStatus, escapeText: escapeText,
        fmt: fmt, limits: limits, tableOf: tableOf, summaryOf: summaryOf, optionsOf: optionsOf, HC_VERSION: HC_VERSION};
    if (typeof module === 'object' && module.exports) {
        module.exports = pure;
        return;
    }

    // ───────────────────────── 브라우저 ─────────────────────────

    /** @returns {any} */
    function lib() {
        return /** @type {any} */ (root).Highcharts;
    }

    /** @type {Promise<boolean> | null} */
    var loading = null;

    /** @param {string} href @returns {Promise<void>} 실패해도 진행(없으면 기본 모양) */
    function addCss(href) {
        return new Promise(function (resolve) {
            if (document.querySelector('link[data-hb-chart-css="' + href + '"]')) { resolve(); return; }
            var l = document.createElement('link');
            l.rel = 'stylesheet';
            l.href = href;
            l.setAttribute('data-hb-chart-css', href);
            l.onload = function () { resolve(); };
            l.onerror = function () { resolve(); };
            document.head.appendChild(l);
        });
    }

    /** @param {string} src @returns {Promise<void>} */
    function addScript(src) {
        return new Promise(function (resolve, reject) {
            var s = document.createElement('script');
            s.src = src;
            s.async = false;
            s.onload = function () { resolve(); };
            s.onerror = function () { reject(new Error('load ' + src)); };
            document.head.appendChild(s);
        });
    }

    /** Highcharts 를 한 번만 싣는다. 없으면 false(다시 시도하지 않음 — 화면마다 404 반복 방지) */
    function ready() {
        if (!loading) {
            var css = Promise.all(CSS.map(addCss));
            var js = lib() ? Promise.resolve() : HC_SCRIPTS.reduce(function (p, f) {
                return p.then(function () { return addScript(HC_BASE + f); });
            }, Promise.resolve());
            loading = Promise.all([css, js]).then(function () {
                var H = lib();
                if (!H || typeof H.chart !== 'function') { return false; }
                H.setOptions({lang: {thousandsSep: ',', decimalPoint: '.'}});
                return true;
            }, function () { return false; });
        }
        return loading;
    }

    function labels() {
        /** @type {HB.ChartLabels} */
        var L = {
            category: hb.t('js.chart.col.category'), value: hb.t('js.chart.col.value'), cumPct: hb.t('js.chart.cumPct'),
            target: hb.t('js.chart.col.target'), status: hb.t('js.chart.col.status'), no: hb.t('js.chart.col.no'),
            alarm: hb.t('js.chart.col.alarm'), others: hb.t('js.chart.others'),
            statusText: {ok: hb.t('js.chart.status.ok'), warn: hb.t('js.chart.status.warn'), danger: hb.t('js.chart.status.danger'), none: '-'},
            thresholdLine: function (v) { return hb.t('js.chart.thresholdLine', v); },
            targetLine: function (v) { return hb.t('js.chart.targetLine', v); },
            sumPareto: function (n, cut, th) { return hb.t('js.chart.sum.pareto', n, cut, th); },
            sumBar: function (n, names) { return hb.t('js.chart.sum.bar', n, names); },
            sumGauge: function (v, tg, st) { return hb.t('js.chart.sum.gauge', v, tg, st); },
            sumControl: function (n, a) { return hb.t('js.chart.sum.control', n, a); }
        };
        return L;
    }

    /** @type {WeakMap<HTMLElement, {seq: number, chart: any}>} */
    var states = new WeakMap();

    /** @param {HB.ChartKind} kind @param {any} o */
    function isEmpty(kind, o) {
        if (kind === 'pareto') { return paretoData(o.categories || [], o.values || []).categories.length === 0; }
        if (kind === 'bar') { return !o.categories || !o.categories.length || !o.series || !o.series.length; }
        if (kind === 'gauge') { return !isNum(o.value); }
        return !o.labels || !o.labels.length;
    }

    /** @param {HTMLElement} el */
    function clear(el) {
        var st = states.get(el);
        if (st && st.chart) {
            try { st.chart.destroy(); } catch (e) { /* 이미 지워짐 */ }
            st.chart = null;
        }
        el.textContent = '';
    }

    /** @param {{head: string[], rows: string[][]}} t @param {string} caption */
    function tableEl(t, caption) {
        var table = document.createElement('table');
        table.className = 'hb-chart-tbl';
        var cap = document.createElement('caption');
        cap.textContent = caption;
        table.appendChild(cap);
        var thead = document.createElement('thead');
        var hr = document.createElement('tr');
        t.head.forEach(function (h) {
            var th = document.createElement('th');
            th.scope = 'col';
            th.textContent = h;
            hr.appendChild(th);
        });
        thead.appendChild(hr);
        table.appendChild(thead);
        var tbody = document.createElement('tbody');
        t.rows.forEach(function (r) {
            var tr = document.createElement('tr');
            r.forEach(function (v, i) {
                var td = document.createElement(i === 0 ? 'th' : 'td');
                if (i === 0) { /** @type {HTMLTableCellElement} */ (td).scope = 'row'; }
                td.textContent = v;
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        return table;
    }

    /** SVG 에 viewBox 가 없으면 넣는다 — 인쇄 때 폭에 맞춰 줄어들게 @param {HTMLElement} plot */
    function ensureViewBox(plot) {
        var svg = plot.querySelector('svg.highcharts-root');
        if (svg && !svg.getAttribute('viewBox')) {
            svg.setAttribute('viewBox', '0 0 ' + svg.getAttribute('width') + ' ' + svg.getAttribute('height'));
        }
    }

    /**
     * @param {HB.ChartKind} kind @param {HTMLElement} el @param {any} o
     * @returns {Promise<HB.ChartHandle>}
     */
    function mount(kind, el, o) {
        var st = states.get(el) || {seq: 0, chart: null};
        states.set(el, st);
        var seq = ++st.seq;
        /** @param {boolean} drawn @returns {HB.ChartHandle} */
        function handle(drawn) {
            return {drawn: drawn, destroy: function () { if (st.seq === seq) { st.seq++; clear(el); } }};
        }
        return Promise.all([ready(), hb.loadMessages()]).then(function (r) {
            if (st.seq !== seq) { return handle(false); }   // 뒤 호출이 이미 그렸다
            var ok = r[0];
            var L = labels();
            clear(el);
            el.classList.add('hb-chart', 'highcharts-light');
            el.setAttribute('data-kind', kind);
            if (isEmpty(kind, o)) {
                var empty = document.createElement('p');
                empty.className = 'hb-chart-empty';
                empty.textContent = o.emptyText || hb.t('js.chart.empty');
                el.appendChild(empty);
                return handle(false);
            }
            var summary = summaryOf(kind, o, L);
            var plot = document.createElement('div');
            plot.className = 'hb-chart-plot';
            el.appendChild(plot);
            var details = document.createElement('details');
            details.className = 'hb-chart-data';
            var sm = document.createElement('summary');
            sm.textContent = hb.t('js.chart.showTable');
            details.appendChild(sm);
            details.appendChild(tableEl(tableOf(kind, o, L), o.title || summary));
            el.appendChild(details);
            if (!ok) {
                plot.className = 'hb-chart-missing';
                plot.setAttribute('role', 'status');
                plot.textContent = hb.t('js.chart.missing');
                details.open = true;
                return handle(false);
            }
            var opts = optionsOf(kind, o, L);
            var onClick = o.onClick;
            if (typeof onClick === 'function' && (kind === 'bar' || kind === 'pareto')) {
                var cats = kind === 'pareto' ? paretoData(o.categories, o.values, {threshold: o.threshold, top: o.top, othersLabel: L.others}).categories
                    : o.categories.map(String);
                el.classList.add('hb-chart-clickable');
                opts.plotOptions.series.cursor = 'pointer';
                opts.plotOptions.series.point = {events: {click: /** @this {any} */ function () {
                    var i = this.index;
                    onClick(cats[i], kind === 'pareto' ? o.categories.indexOf(cats[i]) : i);
                }}};
            }
            opts.chart.events = {render: function () { ensureViewBox(plot); }};
            st.chart = lib().chart(plot, opts);
            ensureViewBox(plot);
            return handle(true);
        });
    }

    /** @type {HB.ChartApi} */
    var api = {
        ready: ready,
        paretoData: paretoData,
        gaugeStatus: gaugeStatus,
        pareto: function (el, o) { return mount('pareto', el, o); },
        bar: function (el, o) { return mount('bar', el, o); },
        gauge: function (el, o) { return mount('gauge', el, o); },
        control: function (el, o) { return mount('control', el, o); }
    };
    /** @type {HB.Hb} */ (/** @type {any} */ (root).hb).chart = api;
})(typeof window !== 'undefined' ? window : globalThis);
