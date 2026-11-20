// @ts-check
/*
 * 사람 × 주 부하 보기 (pms-project APPROVE) — docs/32 4.26, 사용자 결정 P-14.
 * 목록 화면의 [부하 보기] → 표(사람 × 주, 계획/확정 MD, 가용 넘는 칸 표시) → 칸을 누르면 그 사람의 주별 그림(hb.chart.bar)과
 * 그 주 몫을 이루는 작업. 계산은 서버(GET /api/pms/load·/load/cell) — 화면은 시간 → MD 나누기만 한다. 서버 글은 textContent 로만.
 */
(function () {
    'use strict';

    var API = '/api/pms/load';
    var viewEl = document.getElementById('loadView');
    var btn = document.getElementById('loadBtn');
    if (!viewEl || !btn) {
        return;
    }
    var view = viewEl;
    var listView = /** @type {HTMLElement} */ (document.getElementById('listView'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('loadForm'));
    var table = /** @type {HTMLTableElement} */ (document.getElementById('loadTable'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('loadSummary'));
    var detail = /** @type {HTMLElement} */ (document.getElementById('loadDetail'));
    var detailTitle = /** @type {HTMLElement} */ (document.getElementById('loadDetailTitle'));
    var chartBox = /** @type {HTMLElement} */ (document.getElementById('loadChart'));
    var cellBody = /** @type {HTMLTableSectionElement} */ (/** @type {HTMLTableElement} */ (document.getElementById('loadCell')).tBodies[0]);
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));

    /**
     * @typedef {{weekStart: string, weekEnd: string, workdays: number, capHours: number}} PmsLoadWeek
     * @typedef {{userId: number|null, loginId: string|null, userNm: string|null, active: boolean, plan: number[], conf: number[],
     *   planTotal: number, confTotal: number, over: number}} PmsLoadRow
     * @typedef {{from: string, to: string, hoursPerDay: number, weeks: PmsLoadWeek[], rows: PmsLoadRow[], unassigned: PmsLoadRow|null,
     *   taskCnt: number}} PmsLoadView
     * @typedef {{taskId: number, projId: number, projNo: string, projNm: string, taskNm: string, status: string, planStart: string,
     *   planEnd: string, planHours: number, weekHours: number}} PmsLoadTask
     */
    /** @type {PmsLoadView|null} */
    var data = null;

    /** @param {string} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {any} err */
    function fail(err) {
        notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
        hb.showFieldErrors(form, err && err.errors);
    }

    /** 시간 → MD(회사 하루 시간), 소수 한 자리 */
    /** @param {number|null|undefined} hours */
    function md(hours) {
        var hpd = data ? data.hoursPerDay : 8;
        return hb.fmt.num((Number(hours) || 0) / hpd, 1);
    }

    /** @param {number|null|undefined} hours */
    function mdNum(hours) {
        var hpd = data ? data.hoursPerDay : 8;
        return Math.round(((Number(hours) || 0) / hpd) * 10) / 10;
    }

    /** @param {PmsLoadRow} r */
    function name(r) {
        if (r.userId == null) {
            return hb.t('js.pms.load.unassigned');
        }
        var n = (r.userNm || '') + (r.loginId ? ' (' + r.loginId + ')' : '');
        return r.active ? n : n + ' · ' + hb.t('js.pms.load.inactive');
    }

    function show() {
        listView.hidden = true;
        view.hidden = false;
        load();     // 시작 주가 비면 서버가 회사 시간대 이번 주로 채운다
    }

    function hide() {
        view.hidden = true;
        listView.hidden = false;
        notify('');
    }

    function load() {
        var from = /** @type {HTMLInputElement} */ (form.elements.namedItem('from')).value;
        var weeks = /** @type {HTMLSelectElement} */ (form.elements.namedItem('weeks')).value;
        hb.showFieldErrors(form, []);
        detail.hidden = true;
        return hb.latest('pmsLoad', hb.api(API + '?from=' + encodeURIComponent(from) + '&weeks=' + encodeURIComponent(weeks)))
            .then(function (res) {
                data = /** @type {PmsLoadView} */ (res.data);
                /** @type {HTMLInputElement} */ (form.elements.namedItem('from')).value = data.from;
                render();
                notify('');
            }, fail);
    }

    /** @param {number} plan @param {number} conf */
    function cellText(plan, conf) {
        return md(plan) + ' / ' + md(conf);
    }

    function render() {
        var d = /** @type {PmsLoadView} */ (data);
        var head = /** @type {HTMLTableSectionElement} */ (table.tHead).rows[0];
        head.textContent = '';
        head.appendChild(hb.el('th', null, hb.t('js.pms.load.person')));
        head.appendChild(hb.el('th', null, hb.t('js.pms.load.total')));
        d.weeks.forEach(function (w) {
            var th = hb.el('th', 'pms-load-week', w.weekStart.substring(5));
            th.title = w.weekStart + ' ~ ' + w.weekEnd + ' · ' + hb.t('js.pms.load.workdays', w.workdays);
            head.appendChild(th);
        });
        var body = table.tBodies[0];
        body.textContent = '';
        var rows = d.rows.slice();
        if (d.unassigned) {
            rows.push(d.unassigned);
        }
        if (!rows.length) {
            hb.emptyRow(body, hb.t('js.pms.load.empty'));
        }
        rows.forEach(function (r) {
            var tr = hb.el('tr', r.userId == null ? 'pms-load-none' : null);
            tr.appendChild(hb.el('th', null, name(r)));
            tr.appendChild(hb.el('td', 'pms-load-num', cellText(r.planTotal, r.confTotal)));
            d.weeks.forEach(function (w, i) {
                var over = r.userId != null && Number(r.plan[i]) > Number(w.capHours);
                var td = hb.el('td', 'pms-load-num pms-load-cell' + (over ? ' pms-load-over' : ''), cellText(r.plan[i], r.conf[i]));
                td.tabIndex = 0;
                td.title = w.weekStart + ' · ' + name(r) + (over ? ' · ' + hb.t('js.pms.load.overTip') : '');
                td.addEventListener('click', function () { openCell(r, i); });
                td.addEventListener('keydown', function (ev) {
                    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); openCell(r, i); }
                });
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
        var foot = /** @type {HTMLTableSectionElement} */ (table.tFoot);
        foot.textContent = '';
        var cap = hb.el('tr', 'pms-load-cap');
        cap.appendChild(hb.el('th', null, hb.t('js.pms.load.cap')));
        cap.appendChild(hb.el('td', null, ''));
        d.weeks.forEach(function (w) { cap.appendChild(hb.el('td', 'pms-load-num', md(w.capHours))); });
        foot.appendChild(cap);
        var overPeople = d.rows.filter(function (r) { return r.over > 0; }).length;
        summary.textContent = hb.t('js.pms.load.summary', d.from, d.to, d.rows.length, overPeople, d.taskCnt, d.hoursPerDay);
    }

    /** @param {PmsLoadRow} r @param {number} i */
    function openCell(r, i) {
        var d = /** @type {PmsLoadView} */ (data);
        var w = d.weeks[i];
        detail.hidden = false;
        detailTitle.textContent = hb.t('js.pms.load.detailTitle', name(r), w.weekStart);
        hb.chart.bar(chartBox, {
            title: name(r),
            categories: d.weeks.map(function (x) { return x.weekStart.substring(5); }),
            series: [
                {name: hb.t('js.pms.load.planMd'), data: r.plan.map(mdNum), tone: 'muted'},
                {name: hb.t('js.pms.load.confMd'), data: r.conf.map(mdNum)},
                {name: hb.t('js.pms.load.capMd'), data: d.weeks.map(function (x) { return mdNum(x.capHours); }), kind: 'line'}
            ],
            unit: 'MD', decimals: 1, height: 240,
            onClick: function (cat, idx) { if (idx >= 0) { openCell(r, idx); } }
        });
        var q = 'week=' + encodeURIComponent(w.weekStart) + (r.userId == null ? '&unassigned=true' : '&userId=' + r.userId);
        hb.latest('pmsLoadCell', hb.api(API + '/cell?' + q)).then(function (res) {
            cellBody.textContent = '';
            /** @type {PmsLoadTask[]} */
            var list = res.data || [];
            if (!list.length) {
                hb.emptyRow(cellBody, hb.t('js.pms.load.cellEmpty'));
            }
            list.forEach(function (t) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, t.projNo));
                tr.appendChild(hb.el('td', null, t.taskNm + ' · ' + t.projNm));
                tr.appendChild(hb.el('td', null, t.planStart + ' ~ ' + t.planEnd));
                tr.appendChild(hb.el('td', 'pms-load-num', md(t.planHours)));
                tr.appendChild(hb.el('td', 'pms-load-num', md(t.weekHours)));
                cellBody.appendChild(tr);
            });
        }, fail);
    }

    btn.addEventListener('click', show);
    /** @type {HTMLElement} */ (document.getElementById('loadBack')).addEventListener('click', hide);
    form.addEventListener('submit', function (ev) {
        ev.preventDefault();
        load();
    });
})();
