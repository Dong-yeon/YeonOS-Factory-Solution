// @ts-check
/*
 * 내 공수 (pms-worklog) — PMS 4단계 (docs/32 PM4, D9).
 * 주 띠(월~일) → 날짜 → 그 날 작성 중 행 한 벌 편집·저장 / 미리 채우기 / 확정 / 다시 열기(사유).
 * 판정(합계 상한·미래 날짜·분류·남의 행)은 서버 — 화면은 합계만 미리 보여 준다.
 */
/**
 * @typedef {{wlId: number, workDay: string, projId: number, projNo: string, projNm: string, taskId?: number | null,
 *   wbsTaskNm?: string | null, categoryCd?: string | null, hours: number, entryMethod: string, status: string,
 *   reopenReason?: string | null, note?: string | null, rowVer: number}} PmsLog
 * @typedef {{day: string, total: string, status: string, future: boolean, rows: PmsLog[]}} PmsDay
 * @typedef {{projId: number, projNo: string, projNm: string, taskId: number, taskNm: string}} PmsSug
 * @typedef {{from: string, to: string, today: string, maxHours: number, days: PmsDay[], suggestions: PmsSug[],
 *   projects: {projId: number, projNo: string, projNm: string}[]}} PmsWeek
 */
(function () {
    'use strict';

    var API = '/api/pms/worklogs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var weekUl = /** @type {HTMLElement} */ (document.getElementById('week'));
    var body = /** @type {HTMLTableSectionElement} */ (document.querySelector('#logs tbody'));
    /** @type {PmsWeek | null} */
    var week = null;
    /** @type {string | null} */
    var selected = null;
    /** @type {HB.Code[]} */
    var cats = [];
    /** @type {Record<string, {taskId: number, wbsNo: string, taskNm: string}[]>} */
    var taskCache = {};
    var dirty = false;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {any} err */
    function fail(err) {
        if (err && err.code === 'STALE') { return; }
        notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
        var errors = (err && err.errors) || [];
        body.querySelectorAll('tr[data-row-key]').forEach(function (tr) {
            var k = tr.getAttribute('data-row-key');
            hb.showFieldErrors(tr, errors.filter(/** @param {any} e */ function (e) { return e.rowKey === k; }));
        });
    }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 'yyyy-MM-dd' + n 일 (UTC 일 번호) @param {string} base @param {number} n */
    function plus(base, n) {
        var p = base.split('-');
        var d = new Date(Date.UTC(+p[0], +p[1] - 1, +p[2]) + n * 86400000);
        return d.toISOString().substring(0, 10);
    }

    /** @param {string} day */
    function weekday(day) {
        var p = day.split('-');
        return new Date(Date.UTC(+p[0], +p[1] - 1, +p[2])).getUTCDay();
    }

    /** 요일 이름 — 키는 글자 그대로 @param {number} w */
    function dayName(w) {
        return [hb.t('js.pms.dow.0'), hb.t('js.pms.dow.1'), hb.t('js.pms.dow.2'), hb.t('js.pms.dow.3'), hb.t('js.pms.dow.4'),
            hb.t('js.pms.dow.5'), hb.t('js.pms.dow.6')][w];
    }

    /** @param {string} s */
    function stateName(s) {
        /** @type {Record<string, string>} */
        var m = {NONE: hb.t('js.pms.wl.NONE'), DRAFT: hb.t('js.pms.wl.DRAFT'), CONFIRMED: hb.t('js.pms.wl.CONFIRMED')};
        return m[s] || s;
    }

    // ------------------------------------------------------------------ 주

    /** @param {string | null} from @param {string | null} [pick] */
    function load(from, pick) {
        return pmsUi.leaveOk(dirty).then(function (ok) {
            if (!ok) { return; }
            dirty = false;
            return loadWeek(from, pick);
        });
    }

    /** @param {string | null} from @param {string | null} [pick] */
    function loadWeek(from, pick) {
        return hb.latest('week', hb.api(API + (from ? '?from=' + from : ''))).then(function (res) {
            week = res.data;
            var w = /** @type {PmsWeek} */ (week);
            /** @type {HTMLElement} */ (document.getElementById('weekLabel')).textContent = w.from + ' ~ ' + w.to;
            /** @type {HTMLElement} */ (document.getElementById('maxInfo')).textContent = hb.t('js.pms.maxHours', w.maxHours);
            var day = pick && pick >= w.from && pick <= w.to ? pick
                : (w.today >= w.from && w.today <= w.to ? w.today : w.from);
            drawWeek();
            showDay(day);
        }, fail);
    }

    function drawWeek() {
        var w = /** @type {PmsWeek} */ (week);
        weekUl.textContent = '';
        w.days.forEach(function (d) {
            var wd = weekday(d.day);
            // 지난 평일인데 확정 안 함 = 배지
            var missing = !d.future && d.status !== 'CONFIRMED' && wd !== 0 && wd !== 6 && d.day < w.today;
            var li = hb.el('li');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary pms-weekday pms-wl-' + d.status + (missing ? ' pms-wl-missing' : '')));
            b.type = 'button';
            b.disabled = d.future;
            b.setAttribute('aria-pressed', d.day === selected ? 'true' : 'false');
            b.appendChild(hb.el('span', 'pms-weekday-d', dayName(wd) + ' ' + d.day.substring(5)));
            b.appendChild(hb.el('span', 'pms-weekday-h', d.total + 'h'));
            b.appendChild(hb.el('span', 'pms-weekday-s', missing ? hb.t('js.pms.wl.missing') : stateName(d.status)));
            b.addEventListener('click', function () {
                pmsUi.leaveOk(dirty).then(function (ok) {
                    if (!ok) { return; }
                    dirty = false;
                    showDay(d.day);
                });
            });
            li.appendChild(b);
            weekUl.appendChild(li);
        });
    }

    // ------------------------------------------------------------------ 하루

    /** @param {string} day */
    function showDay(day) {
        var w = /** @type {PmsWeek} */ (week);
        selected = day;
        var d = w.days.filter(function (x) { return x.day === day; })[0];
        weekUl.querySelectorAll('button').forEach(function (b, i) {
            b.setAttribute('aria-pressed', w.days[i] && w.days[i].day === day ? 'true' : 'false');
        });
        /** @type {HTMLElement} */ (document.getElementById('dayBox')).hidden = false;
        /** @type {HTMLElement} */ (document.getElementById('dayTitle')).textContent = day + ' (' + dayName(weekday(day)) + ')';
        var st = /** @type {HTMLElement} */ (document.getElementById('dayState'));
        st.textContent = '';
        st.appendChild(pmsUi.badge('day', d.status, stateName(d.status)));
        var confirmed = d.status === 'CONFIRMED';
        var edit = CAN_SAVE && !confirmed && !d.future;
        /** @type {HTMLElement} */ (document.getElementById('dayTools')).hidden = !edit;
        /** @type {HTMLElement} */ (document.getElementById('reopenTools')).hidden = !(CAN_SAVE && confirmed);
        /** @type {HTMLInputElement} */ (document.getElementById('reopenReason')).value = '';
        body.textContent = '';
        d.rows.forEach(function (r) { addRow(r, edit); });
        if (!d.rows.length) {
            var tr = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.pms.noLogs'));
            td.colSpan = 6;
            tr.setAttribute('data-empty', 'Y');
            tr.appendChild(td);
            body.appendChild(tr);
        }
        var reopened = d.rows.filter(function (r) { return r.reopenReason; })[0];
        /** @type {HTMLElement} */ (document.getElementById('dayNote')).textContent = reopened
            ? hb.t('js.pms.reopenedNote', reopened.reopenReason) : (edit && w.suggestions.length
                ? hb.t('js.pms.suggestNote', w.suggestions.map(function (s) { return s.projNo + ' ' + s.taskNm; }).join(', ')) : '');
        total();
    }

    /** @param {PmsLog | null} r @param {boolean} edit */
    function addRow(r, edit) {
        var w = /** @type {PmsWeek} */ (week);
        var empty = body.querySelector('tr[data-empty]');
        if (empty) { empty.remove(); }
        var tr = hb.el('tr');
        tr.setAttribute('data-row-key', r ? String(r.wlId) : 'n' + body.querySelectorAll('tr').length);
        if (r) { tr.setAttribute('data-id', String(r.wlId)); tr.setAttribute('data-ver', String(r.rowVer)); }
        var proj = /** @type {HTMLSelectElement} */ (hb.el('select'));
        proj.setAttribute('data-field', 'projId');
        option(proj, '', hb.t('js.pms.chooseProject'));
        w.projects.forEach(function (p) { option(proj, String(p.projId), p.projNo + ' ' + p.projNm); });
        if (r && !w.projects.some(function (p) { return p.projId === r.projId; })) { option(proj, String(r.projId), r.projNo + ' ' + r.projNm); }
        proj.value = r ? String(r.projId) : '';
        proj.disabled = !edit || !!r;
        var task = /** @type {HTMLSelectElement} */ (hb.el('select'));
        task.setAttribute('data-field', 'taskId');
        var cat = /** @type {HTMLSelectElement} */ (hb.el('select'));
        cat.setAttribute('data-field', 'categoryCd');
        option(cat, '', hb.t('js.pms.chooseCategory'));
        cats.forEach(function (c) { option(cat, c.code, c.name); });
        cat.value = r && r.categoryCd ? r.categoryCd : '';
        var hours = /** @type {HTMLInputElement} */ (hb.el('input'));
        hours.setAttribute('data-field', 'hours');
        hours.inputMode = 'decimal';
        hours.className = 'pms-hours';
        hours.value = r ? String(r.hours) : '';
        var note = /** @type {HTMLInputElement} */ (hb.el('input'));
        note.setAttribute('data-field', 'note');
        note.maxLength = 500;
        note.value = r && r.note ? r.note : '';
        [task, cat, hours, note].forEach(function (i) { i.disabled = !edit; });
        [proj, task, cat, hours, note].forEach(function (i) {
            i.addEventListener('input', function () { dirty = true; total(); });
            i.addEventListener('change', function () { dirty = true; });
            var td = hb.el('td');
            td.appendChild(i);
            tr.appendChild(td);
        });
        var del = hb.el('td');
        if (edit) {
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.pms.removeRow')));
            b.type = 'button';
            b.addEventListener('click', function () { tr.remove(); dirty = true; total(); });
            del.appendChild(b);
        }
        if (r && r.entryMethod === 'PREFILL') { del.appendChild(hb.el('span', 'hb-muted', hb.t('js.pms.prefilled'))); }
        tr.appendChild(del);
        body.appendChild(tr);
        proj.addEventListener('change', function () { fillTasks(proj.value, task, null); });
        fillTasks(proj.value, task, r && r.taskId ? r.taskId : null);
    }

    /** 프로젝트 작업 선택지(프로젝트마다 한 번 읽어 둔다) @param {string} projId @param {HTMLSelectElement} sel @param {number | null} value */
    function fillTasks(projId, sel, value) {
        sel.textContent = '';
        option(sel, '', hb.t('js.pms.noTask'));
        if (!projId) { return; }
        var cached = taskCache[projId];
        var p = cached ? Promise.resolve(cached) : hb.api(API + '/projects/' + projId + '/tasks').then(function (res) {
            taskCache[projId] = res.data;
            return res.data;
        });
        p.then(/** @param {{taskId: number, wbsNo: string, taskNm: string}[]} list */ function (list) {
            list.forEach(function (t) { option(sel, String(t.taskId), t.wbsNo + ' ' + t.taskNm); });
            if (value) { sel.value = String(value); }
        }, fail);
    }

    /** @param {Element} tr @param {string} n */
    function cell(tr, n) { return /** @type {HTMLInputElement} */ (tr.querySelector('[data-field="' + n + '"]')).value; }

    function total() {
        var w = /** @type {PmsWeek} */ (week);
        var sum = 0;
        body.querySelectorAll('tr[data-row-key]').forEach(function (tr) {
            var v = Number(cell(tr, 'hours'));
            if (!isNaN(v)) { sum += v; }
        });
        var t = /** @type {HTMLElement} */ (document.getElementById('dayTotal'));
        t.textContent = hb.t('js.pms.dayTotal', Math.round(sum * 10) / 10, w.maxHours);
        t.classList.toggle('pms-late', sum > w.maxHours);
    }

    function rowsBody() {
        return Array.prototype.map.call(body.querySelectorAll('tr[data-row-key]'), /** @param {HTMLElement} tr */ function (tr) {
            var id = tr.getAttribute('data-id');
            return {wlId: id ? Number(id) : null, projId: cell(tr, 'projId') ? Number(cell(tr, 'projId')) : null,
                taskId: cell(tr, 'taskId') ? Number(cell(tr, 'taskId')) : null, categoryCd: cell(tr, 'categoryCd'),
                hours: cell(tr, 'hours'), note: cell(tr, 'note'), rowVer: id ? Number(tr.getAttribute('data-ver')) : null};
        });
    }

    /** 작성 중 내용을 먼저 저장 */
    function save() {
        return hb.api(API + '/day', {method: 'PUT', body: {day: selected, rows: rowsBody()}});
    }

    /**
     * 저장한 뒤 화면 행을 서버 값(새 행 id·rowVer)으로 다시 그린다 — 이어지는 확정이 실패해도
     * 화면이 낡은 rowVer 를 들고 있지 않게(다시 확정하면 409 가 나던 결함, 브라우저 확인에서 발견)
     */
    function saveAndRefresh() {
        var day = /** @type {string} */ (selected);
        var w = /** @type {PmsWeek} */ (week);
        return save().then(function () { return hb.api(API + '?from=' + w.from); }).then(function (res) {
            week = res.data;
            dirty = false;
            drawWeek();
            showDay(day);
        });
    }

    /** @param {string} id @param {() => Promise<any>} fn @param {(res: any) => string} done */
    function action(id, fn, done) {
        var btn = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (!btn) { return; }
        var b = btn;
        b.addEventListener('click', function () {
            var day = selected;
            var w = /** @type {PmsWeek} */ (week);
            hb.busy(b, fn).then(function (res) {
                if (!res) { return; }
                dirty = false;
                taskCache = {};
                return load(w.from, day).then(function () { notify(done(res)); });
            }, fail);
        });
    }

    action('saveDayBtn', save, function () { return hb.t('js.common.saved'); });
    action('prefillBtn', function () {
        return (dirty ? saveAndRefresh() : Promise.resolve()).then(function () {
            return hb.api(API + '/prefill', {method: 'POST', body: {day: selected}});
        });
    }, function (res) { return hb.t('js.pms.prefilledN', res.data); });
    action('confirmBtn', function () {
        return saveAndRefresh().then(function () { return hb.api(API + '/confirm', {method: 'POST', body: {day: selected}}); });
    }, function (res) { return hb.t('js.pms.confirmedN', res.data); });
    action('reopenBtn', function () {
        var reason = /** @type {HTMLInputElement} */ (document.getElementById('reopenReason')).value;
        return hb.api(API + '/reopen', {method: 'POST', body: {day: selected, reason: reason}});
    }, function () { return hb.t('js.pms.reopened'); });

    /** @type {HTMLElement} */ (document.getElementById('addRowBtn')).addEventListener('click', function () { addRow(null, true); dirty = true; });
    /** @type {HTMLElement} */ (document.getElementById('prevBtn')).addEventListener('click', function () {
        if (week) { load(plus(week.from, -7)); }
    });
    /** @type {HTMLElement} */ (document.getElementById('nextBtn')).addEventListener('click', function () {
        if (week) { load(plus(week.from, 7)); }
    });
    /** @type {HTMLElement} */ (document.getElementById('thisBtn')).addEventListener('click', function () { load(null); });
    window.addEventListener('beforeunload', function (e) { if (dirty) { e.preventDefault(); } });

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codeList('PMS_WORK_CAT')]);
    }).then(function (r) {
        cats = r[1];
        return load(null);
    }).then(null, function (/** @type {any} */ err) { notify(err && err.message, true); });
})();
