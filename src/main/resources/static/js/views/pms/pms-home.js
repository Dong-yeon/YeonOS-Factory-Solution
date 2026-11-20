// @ts-check
/*
 * PMS 홈·포트폴리오 (pms-home) — PMS 5단계 (docs/32 PM5).
 * 내 할 일 4묶음 + 포트폴리오 표 → 행을 고르면 인쇄용 보고서. 홈 카드 링크(keyword=프로젝트 번호)로 열리면 그 조건으로 조회.
 */
/**
 * @typedef {{projId: number, projNo: string, projNm: string, projType: string, custNm?: string | null, status: string,
 *   sopDate?: string | null, sopDays?: number | null, phaseNm?: string | null, nextGateNm?: string | null, nextGateDate?: string | null,
 *   slipDays?: number | null, lateCnt: number, reqCnt: number, readyCnt: number, readyPct?: number | null, openIssueCnt: number,
 *   highIssueCnt: number, progressPct: number}} PmsPort
 */
(function () {
    'use strict';

    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var report = /** @type {HTMLElement} */ (document.getElementById('reportBox'));
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;
    /** @type {Record<string, string>} */
    var sevNames = {};
    /** @type {Record<string, string>} */
    var catNames = {};
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** 보고서를 열기 전 화면 위치 — 닫으면 목록으로 돌아간다 @type {number | null} */
    var returnY = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {any} err */
    function fail(err) {
        if (err && err.code === 'STALE') { return; }
        notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
    }

    /** @param {string} id @param {string[]} lines @param {boolean[]} [late] */
    function list(id, lines, late) {
        var ul = /** @type {HTMLElement} */ (document.getElementById(id));
        ul.textContent = '';
        if (!lines.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.todoNone'))); }
        lines.forEach(function (t, i) { ul.appendChild(hb.el('li', late && late[i] ? 'pms-late' : null, t)); });
    }

    /** 목록 제목 옆 건수 — 다 보이면 전체, 잘렸으면 "보이는 / 전체" @param {string} id @param {number} shown @param {number} total */
    function countMark(id, shown, total) {
        var h = /** @type {HTMLElement} */ (/** @type {HTMLElement} */ (document.getElementById(id)).parentElement).querySelector('h3');
        if (!h) { return; }
        var old = h.querySelector('.pms-todo-cnt');
        if (old) { old.remove(); }
        h.appendChild(hb.el('span', 'pms-todo-cnt' + (shown < total ? ' pms-late' : ''),
            shown < total ? hb.t('js.pms.todoCntCut', shown, total) : hb.t('js.pms.todoCnt', total)));
    }

    /** @param {HTMLElement} body @param {unknown[][]} rows @param {number} cols */
    function table(body, rows, cols) {
        body.textContent = '';
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.pms.todoNone'));
            td.colSpan = cols;
            tr0.appendChild(td);
            body.appendChild(tr0);
        }
        rows.forEach(function (r) {
            var tr = hb.el('tr');
            r.forEach(function (v) { tr.appendChild(hb.el('td', null, v === null || v === undefined ? '' : String(v))); });
            body.appendChild(tr);
        });
    }

    /** @param {number | null | undefined} n */
    function days(n) {
        if (n === null || n === undefined) { return ''; }
        return n > 0 ? '+' + n : String(n);
    }

    /** SOP 까지 — 문구 키는 글자 그대로 @param {number | null | undefined} n */
    function sopText(n) {
        if (n === null || n === undefined) { return ''; }
        return n >= 0 ? hb.t('js.pms.dMinus', n) : hb.t('js.pms.dPlus', -n);
    }

    // ------------------------------------------------------------------ 내 할 일

    function loadTodo() {
        return hb.latest('todo', hb.api('/api/pms/my-todo')).then(function (res) {
            var d = res.data;
            taskList(d.tasks);
            // 목록은 상한까지만 — 제목에 전체 건수(잘렸으면 "보이는 / 전체", QA H-a)
            countMark('todoTasks', d.tasks.length, d.taskCnt);
            countMark('todoDelivs', d.delivs.length, d.delivCnt);
            countMark('todoIssues', d.issues.length, d.issueCnt);
            list('todoDelivs', d.delivs.map(/** @param {any} x */ function (x) {
                return x.projNo + ' · ' + x.delivNm + ' · ' + x.due + (x.late ? ' ' + hb.t('js.pms.lateMark') : '');
            }), d.delivs.map(/** @param {any} x */ function (x) { return x.late; }));
            list('todoIssues', d.issues.map(/** @param {any} i */ function (i) {
                return i.issueNo + ' · ' + i.title + ' · ' + (sevNames[i.severity] || i.severity) + (i.dueDate ? ' · ' + i.dueDate : '');
            }), d.issues.map(/** @param {any} i */ function (i) { return i.late; }));
            list('todoDays', d.unconfirmedDays.map(/** @param {string} x */ function (x) { return x; }));
        }, fail);
    }

    /**
     * 내 작업 — 줄마다 [실적] 으로 실적 시작·종료·진척만 바로 저장(docs/32 4.19, P-10). 계획일은 여기서 못 바꾼다(일정변경 요청).
     * 게이트 완료는 게이트 심사로만이라 줄 편집을 주지 않는다. 저장 = POST /api/pms/tasks/{id}/actual(row_ver 다르면 409)
     * @param {any[]} tasks
     */
    function taskList(tasks) {
        var ul = /** @type {HTMLElement} */ (document.getElementById('todoTasks'));
        ul.textContent = '';
        if (!tasks.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.todoNone'))); }
        tasks.forEach(function (t) {
            var li = hb.el('li', t.late ? 'pms-late' : null);
            li.appendChild(hb.el('span', null, t.projNo + ' · ' + t.taskNm + ' · ' + t.planEnd + ' · ' + t.progressPct + '%'
                + (t.late ? ' ' + hb.t('js.pms.lateMark') : '')));
            if (t.taskType === 'GATE') {
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.t('js.pms.actual.gate')));
                ul.appendChild(li);
                return;
            }
            var open = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.pms.actual.open')));
            open.type = 'button';
            open.setAttribute('aria-expanded', 'false');
            li.appendChild(open);
            var form = /** @type {HTMLFormElement} */ (hb.el('form', 'hb-form pms-actual-form'));
            form.hidden = true;
            form.autocomplete = 'off';
            /** @param {string} field @param {string} label @param {string} type @param {string} value */
            function input(field, label, type, value) {
                var lab = hb.el('label');
                lab.appendChild(hb.el('span', null, label));
                var i = /** @type {HTMLInputElement} */ (hb.el('input'));
                i.type = type;
                i.setAttribute('data-field', field);
                i.value = value;
                if (type === 'number') { i.min = '0'; i.max = '100'; i.step = '1'; i.inputMode = 'numeric'; }
                lab.appendChild(i);
                form.appendChild(lab);
                return i;
            }
            var as = input('actStart', hb.t('js.pms.actual.start'), 'date', t.actStart || '');
            var ae = input('actEnd', hb.t('js.pms.actual.end'), 'date', t.actEnd || '');
            var pct = input('progressPct', hb.t('js.pms.actual.pct'), 'number', String(t.progressPct));
            var bar = hb.el('div', 'hb-toolbar');
            var save = /** @type {HTMLButtonElement} */ (hb.el('button', null, hb.t('js.pms.actual.save')));
            save.type = 'button';
            bar.appendChild(save);
            form.appendChild(bar);
            li.appendChild(form);
            open.addEventListener('click', function () {
                form.hidden = !form.hidden;
                open.setAttribute('aria-expanded', String(!form.hidden));
                if (!form.hidden) { as.focus(); }
            });
            save.addEventListener('click', function () {
                hb.showFieldErrors(form, []);
                var body = {actStart: as.value || null, actEnd: ae.value || null, progressPct: pct.value === '' ? null : Number(pct.value),
                    rowVer: t.rowVer};
                hb.busy(save, function () { return hb.api('/api/pms/tasks/' + t.taskId + '/actual', {method: 'POST', body: body}); })
                    .then(function (res) {
                        if (!res) { return; }
                        /** @type {string[]} */
                        var warn = res.data || [];
                        return loadTodo().then(function () {
                            notify(warn.length ? hb.t('js.pms.actual.savedWarn', warn.join(', ')) : hb.t('js.common.saved'));
                        });
                    }, /** @param {any} err */ function (err) {
                        if (err && err.errors) { hb.showFieldErrors(form, err.errors); }
                        fail(err);
                    });
            });
            ul.appendChild(li);
        });
    }

    // ------------------------------------------------------------------ 포트폴리오

    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['keyword', 'projType', 'status'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        if (v.mine) { q.mine = true; }
        return q;
    }

    /** 포트폴리오 = hb.grid(UI 단계 3 ②). 다시 읽으면 선택이 풀려 같은 행을 다시 눌러도 보고서가 열린다 */
    function loadPortfolio() {
        loadChart();
        loadTrend();
        return grid.reload(true);
    }

    /** 상태 이름 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function projStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.status.DRAFT'), ACTIVE: hb.t('js.pms.status.ACTIVE'), ON_HOLD: hb.t('js.pms.status.ON_HOLD'),
            CLOSED: hb.t('js.pms.status.CLOSED'), CANCELLED: hb.t('js.pms.status.CANCELLED')};
        return m[s] || s;
    }

    /**
     * 막대 한 줄 — 위 막대(전체)·아래 막대(부분), 길이 = 최댓값 기준(CSSOM 너비라 CSP 안). 상태별·연도별·유형별이 같이 쓴다
     * @param {string} label @param {number} a @param {number} b @param {number} max @param {string} bClass @param {string} text
     */
    function barRow(label, a, b, max, bClass, text) {
        var li = hb.el('li', 'pms-chart-row');
        li.appendChild(hb.el('span', 'pms-chart-label', label));
        var track = hb.el('span', 'pms-chart-track');
        var top = hb.el('span', 'pms-chart-bar');
        top.style.width = (a * 100 / max) + '%';
        var sub = hb.el('span', 'pms-chart-bar ' + bClass);
        sub.style.width = (b * 100 / max) + '%';
        track.appendChild(top);
        track.appendChild(sub);
        li.appendChild(track);
        li.appendChild(hb.el('span', 'pms-chart-text', text));
        return li;
    }

    /**
     * 연도·유형 막대(docs/32 4.20, P-11) — 상태별 막대와 같은 조건. 연도 = 계획 시작 해(착수)·종료일 해(종료), 유형 = 건수·그중 진행.
     * 작업이 없어 계획 시작이 없는 프로젝트는 연도 막대에서 빠지고 숫자로만 알린다
     */
    function loadTrend() {
        var q = params();
        var qs = Object.keys(q).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(String(q[k])); }).join('&');
        return hb.latest('trend', hb.api('/api/pms/portfolio/trend' + (qs ? '?' + qs : ''))).then(function (res) {
            var d = res.data;
            var years = /** @type {HTMLElement} */ (document.getElementById('portYearRows'));
            years.textContent = '';
            /** @type {any[]} */
            var ys = d.years;
            if (!ys.length) { years.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.todoNone'))); }
            var ymax = Math.max(1, Math.max.apply(null, ys.map(function (r) { return Math.max(r.startCnt, r.closeCnt); })));
            ys.forEach(function (r) {
                years.appendChild(barRow(String(r.year), r.startCnt, r.closeCnt, ymax, 'pms-chart-done',
                    hb.t('js.pms.trend.year', r.startCnt, r.closeCnt)));
            });
            /** @type {HTMLElement} */ (document.getElementById('portYearNote')).textContent =
                d.noPlanCnt ? hb.t('js.pms.trend.noPlan', d.noPlanCnt) : '';
            var types = /** @type {HTMLElement} */ (document.getElementById('portTypeRows'));
            types.textContent = '';
            /** @type {any[]} */
            var ts = d.types;
            if (!ts.length) { types.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.todoNone'))); }
            var tmax = Math.max(1, Math.max.apply(null, ts.map(function (r) { return r.projCnt; })));
            ts.forEach(function (r) {
                types.appendChild(barRow(typeNames[r.projType] || r.projType, r.projCnt, r.activeCnt, tmax, 'pms-chart-done',
                    hb.t('js.pms.trend.type', r.projCnt, r.activeCnt)));
            });
        }, function (/** @type {any} */ err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** 상태별 막대(docs/32 4.15) — 쪽 나눔 없이 조건 전체. 막대 길이 = 가장 많은 상태 기준 */
    function loadChart() {
        var q = params();
        var qs = Object.keys(q).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(String(q[k])); }).join('&');
        return hb.latest('chart', hb.api('/api/pms/portfolio/chart' + (qs ? '?' + qs : ''))).then(function (res) {
            var ul = /** @type {HTMLElement} */ (document.getElementById('portChartRows'));
            ul.textContent = '';
            /** @type {any[]} */
            var rows = res.data;
            var max = Math.max(1, Math.max.apply(null, rows.map(function (r) { return r.projCnt; })));
            rows.forEach(function (r) {
                var li = hb.el('li', 'pms-chart-row');
                li.appendChild(hb.el('span', 'pms-chart-label', projStatusName(r.status)));
                var track = hb.el('span', 'pms-chart-track');
                var all = hb.el('span', 'pms-chart-bar');
                all.style.width = (r.projCnt * 100 / max) + '%';
                var late = hb.el('span', 'pms-chart-bar pms-chart-late');
                late.style.width = (r.lateCnt * 100 / max) + '%';
                track.appendChild(all);
                track.appendChild(late);
                li.appendChild(track);
                li.appendChild(hb.el('span', 'pms-chart-text', hb.t('js.pms.chart.row', r.projCnt, r.lateCnt, r.slipCnt)));
                ul.appendChild(li);
            });
        }, function (/** @type {any} */ err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** 보고서는 클릭·Enter 만(↑↓ 는 고르기만 — 보고서가 아래에 열리며 화면이 움직인다, docs/32 4.11 G1) */
    function openSelected() {
        var row = grid.selected();
        if (row) { openReport(Number(row.projId)); }
    }

    // ------------------------------------------------------------------ 보고서

    /** @param {number} id */
    function openReport(id) {
        return hb.latest('report', hb.api('/api/pms/projects/' + id + '/report')).then(function (res) {
            var r = res.data;
            var p = r.project;
            var s = r.summary;
            if (report.hidden) { returnY = window.scrollY; }
            report.hidden = false;
            /** @type {HTMLElement} */ (document.getElementById('repTitle')).textContent = p.projNo + ' · ' + p.projNm;
            /** @type {HTMLElement} */ (document.getElementById('repSummary')).textContent = hb.t('js.pms.repSummary',
                p.custNm || '-', p.pmUserNm, p.sopDate || '-', sopText(s.sopDays) || '-', p.planStart || '-', p.planEnd || '-',
                p.progressPct, days(s.slipDays) || '-');
            // 공수(docs/48 R-2) — 실적 = 확정만, 미확정은 따로
            /** @type {HTMLElement} */ (document.getElementById('repEffort')).textContent = hb.t('js.pms.repEffort',
                pmsUi.hours(s.planHours, r.hoursPerDay) || '-', pmsUi.hours(s.confHours, r.hoursPerDay),
                pmsUi.hours(s.draftHours, r.hoursPerDay), s.burnPct === null ? '-' : s.burnPct + '%',
                pmsUi.hours(s.remainHours, r.hoursPerDay) || '-');
            var ph = /** @type {HTMLElement} */ (document.getElementById('repPhases'));
            ph.textContent = '';
            r.phases.forEach(/** @param {any} x */ function (x) {
                var li = hb.el('li', 'pms-phase' + (x.reqCnt && x.readyCnt === x.reqCnt ? ' pms-phase-ok' : ''));
                li.appendChild(hb.el('span', 'pms-phase-nm', x.phaseNm));
                li.appendChild(hb.el('span', 'pms-phase-pct', x.reqCnt ? x.pct + '% (' + x.readyCnt + '/' + x.reqCnt + ')' : '—'));
                ph.appendChild(li);
            });
            table(/** @type {HTMLElement} */ (document.querySelector('#repGates tbody')), r.gates.map(/** @param {any} g */ function (g) {
                return [g.wbsNo, g.taskNm, g.planStart, g.readyCnt + ' / ' + g.reqCnt,
                    g.reviewNo ? '#' + g.reviewNo + ' ' + (g.decision ? decisionName(g.decision) : g.reviewStatus) : ''];
            }), 5);
            table(/** @type {HTMLElement} */ (document.querySelector('#repLate tbody')), r.lateTasks.map(/** @param {any} t */ function (t) {
                return [t.wbsNo, t.taskNm, t.planEnd, taskStatusName(t.status)];
            }), 4);
            table(/** @type {HTMLElement} */ (document.querySelector('#repSlip tbody')), r.slipTasks.map(/** @param {any} t */ function (t) {
                return [t.wbsNo, t.taskNm, t.baseEnd, t.planEnd, days(t.slipDays)];
            }), 5);
            table(/** @type {HTMLElement} */ (document.querySelector('#repIssues tbody')), r.openIssues.map(/** @param {any} i */ function (i) {
                return [i.issueNo, i.title, sevNames[i.severity] || i.severity, i.ownerNm, i.dueDate];
            }), 5);
            table(/** @type {HTMLElement} */ (document.querySelector('#repHours tbody')), r.hours.map(/** @param {any} h */ function (h) {
                return [h.categoryCd ? (catNames[h.categoryCd] || h.categoryCd) : '-', h.confirmedHours, h.draftHours];
            }), 3);
            /** @type {HTMLElement} */ (document.getElementById('repFoot')).textContent = hb.t('js.pms.repFoot', r.today);
            report.scrollIntoView({block: 'start'});
        }, fail);
    }

    /** 작업 상태 — 키는 글자 그대로 @param {string} s */
    function taskStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {NOT_STARTED: hb.t('js.pms.task.NOT_STARTED'), IN_PROGRESS: hb.t('js.pms.task.IN_PROGRESS'),
            DONE: hb.t('js.pms.task.DONE'), CANCELLED: hb.t('js.pms.task.CANCELLED')};
        return m[s] || s;
    }

    /** @param {string} d */
    function decisionName(d) {
        /** @type {Record<string, string>} */
        var m = {GO: hb.t('js.pms.decision.GO'), CONDITIONAL: hb.t('js.pms.decision.CONDITIONAL'), NO_GO: hb.t('js.pms.decision.NO_GO')};
        return m[d] || d;
    }

    /** @type {HTMLElement} */ (document.getElementById('printBtn')).addEventListener('click', function () { window.print(); });
    // 닫기 = 숨기기만 하면 페이지가 줄며 끝에서 툭 끊긴다 → 열기 전 위치로 먼저 올린 뒤 숨기고 목록에 포커스(키보드 사용자)
    /** @type {HTMLElement} */ (document.getElementById('closeReportBtn')).addEventListener('click', function () {
        var top = returnY === null ? 0 : returnY;
        returnY = null;
        var done = false;
        function hide() {
            if (done) { return; }
            done = true;
            report.hidden = true;
            var scroller = /** @type {HTMLElement | null} */ (document.querySelector('#portGrid .hbg-scroll'));
            if (scroller) { scroller.focus({preventScroll: true}); }
        }
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches || Math.abs(window.scrollY - top) < 2) {
            window.scrollTo(0, top);
            hide();
            return;
        }
        window.addEventListener('scrollend', hide, {once: true});
        window.setTimeout(hide, 800); // scrollend 미지원 브라우저
        window.scrollTo({top: top, behavior: 'smooth'});
    });

    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    if (excelBtn) {
        var eb = excelBtn;
        eb.addEventListener('click', function () {
            var v = sp.values();
            var body = {keyword: v.keyword || null, projType: v.projType || null, status: v.status || null, mine: !!v.mine};
            hb.busy(eb, function () { return hb.download('/api/pms/portfolio/export', body); }).then(null, fail);
        });
    }

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: function () { return loadPortfolio(); }});
        return Promise.all([hb.loadSession(), hb.codeList('PMS_ISSUE_SEVERITY'), hb.codeList('PMS_WORK_CAT'), sp.ready,
            hb.api('/api/pms/portfolio/effort-unit'), hb.codes('PMS_PROJ_TYPE')]);
    }).then(function (r) {
        typeNames = r[5];
        r[1].forEach(function (c) { sevNames[c.code] = c.name; });
        r[2].forEach(function (c) { catNames[c.code] = c.name; });
        var hpd = r[4].data.hoursPerDay;
        /** @param {any} v */
        var hrs = function (v) { return pmsUi.hours(v, hpd); };
        // 홈 카드 항목 링크: ?keyword=프로젝트 번호
        var kw = new URLSearchParams(window.location.search).get('keyword');
        if (kw) { sp.setValue('keyword', kw); }
        grid = hb.grid('#portGrid', {
            progCd: 'pms-home', id: 'main', key: 'projId',
            url: '/api/pms/portfolio/grid', columnsUrl: '/api/pms/portfolio/columns', exportUrl: '/api/pms/portfolio/export', params: params,
            actions: (document.body.getAttribute('data-actions') || '').split(','),
            format: {
                sopDays: sopText,
                slipDays: days,
                readyPct: function (v, row) { return v + '% (' + row.readyCnt + '/' + row.reqCnt + ')'; },
                nextGateNm: function (v, row) { return v + (row.nextGateDate ? ' · ' + row.nextGateDate : ''); },
                // 공수(docs/48 R-2) — 시간 + MD(회사 하루 시간), 실적 = 확정만
                planHours: hrs, confHours: hrs, draftHours: hrs, remainHours: hrs,
                burnPct: function (v) { return v + '%'; }
            },
            // 늦어짐 = 위험 배지(기준선 대비 +, 지연 작업 있음, 높음 이슈가 열려 있음). SOP 지남은 흐리게만(행 표시)
            status: {
                slipDays: function (v) { return v > 0 ? 'danger' : null; },
                lateCnt: function (v) { return v > 0 ? 'danger' : null; },
                openIssueCnt: function (v, row) { return row.highIssueCnt > 0 ? 'danger' : null; },
                // 확정 공수가 계획을 넘음 = 주의(남은 공수 음수와 같은 뜻)
                burnPct: function (v) { return v > 100 ? 'warning' : null; },
                remainHours: function (v) { return Number(v) < 0 ? 'warning' : null; }
            },
            notify: notify
        });
        var host = /** @type {HTMLElement} */ (document.getElementById('portGrid'));
        host.addEventListener('click', function (e) {
            if (e.target instanceof Element && e.target.closest('tbody tr')) { openSelected(); }
        });
        host.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && e.target instanceof Element && e.target.closest('.hbg-scroll')) { openSelected(); }
        });
        return Promise.all([loadTodo(), grid.ready.then(function () { return loadPortfolio(); })]);
    }).then(null, fail);
})();
