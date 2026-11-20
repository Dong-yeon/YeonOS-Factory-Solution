// @ts-check
/* 공장 휴일 달력 (mst-work-cal) — docs/25 4.15. 공장(또는 회사 공통) × 달 → 달력 + 그 달 예외 hb.grid(UI 단계 3 ①) + 오른쪽 폼(날짜·근무조·구분·사유) */
/**
 * @typedef {{shiftCd: string, shiftNm: string, start: string, end: string, dayOffset: number, breakMin: number}} WorkCalShift
 * @typedef {{day: string, workday: boolean, source: string, reason?: string | null, shifts: WorkCalShift[]}} WorkCalDay
 * @typedef {{calId: number, plantCd?: string | null, calDate: string, shiftCd?: string | null, shiftNm?: string | null,
 *   dayType: string, reason: string, rowVer: number, [field: string]: any}} WorkCalRow
 * @typedef {{plantCd?: string | null, month: string, weeklyOff: string, days: WorkCalDay[], rows: WorkCalRow[],
 *   shifts: WorkCalShift[]}} WorkCalMonth
 * @typedef {{plantCd: string, plantNm: string}} WorkCalPlant
 * @typedef {HTMLInputElement | HTMLSelectElement} WorkCalField
 */
(function () {
    'use strict';

    var API = '/api/master/work-cal';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var PROG = document.body.getAttribute('data-prog') || '';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('calForm'));
    var calBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#cal tbody'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var monthIn = /** @type {HTMLInputElement} */ (document.getElementById('monthIn'));
    var weeklyInfo = /** @type {HTMLElement} */ (document.getElementById('weeklyInfo'));
    /** 지금 보이는 달 @type {WorkCalMonth | null} */
    var view = null;
    /** 고른 예외 행 @type {WorkCalRow | null} */
    var current = null;
    /** 달력에서 고른 날짜(새 예외의 기본 날짜) @type {string} */
    var pickedDay = '';
    /** 이 달 예외 목록 @type {HB.Grid | null} */
    var grid = null;
    /** 마지막으로 읽은 공장·달 — 이동 취소 때 선택을 되돌린다 */
    var shown = {plant: '', month: ''};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) { return /** @type {WorkCalField} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** 주간 쉬는 날 정책 → 문구(키는 리터럴로 — 동적 조합 금지) @param {string} v */
    function weeklyText(v) {
        if (v === 'SUN') { return hb.t('js.workCal.weekly.SUN'); }
        if (v === 'SAT_SUN') { return hb.t('js.workCal.weekly.SAT_SUN'); }
        return hb.t('js.workCal.weekly.NONE');
    }

    /** @param {string} t */
    function typeText(t) { return t === 'WORK' ? hb.t('js.workCal.type.WORK') : hb.t('js.workCal.type.OFF'); }

    /** @param {WorkCalRow} r */
    function scopeText(r) { return r.plantCd ? r.plantCd : hb.t('js.workCal.company'); }

    function shiftOptions() {
        var sel = /** @type {HTMLSelectElement} */ (field('shiftCd'));
        sel.textContent = '';
        var all = hb.el('option', null, hb.t('js.workCal.wholeDay'));
        all.value = '';
        sel.appendChild(all);
        (view ? view.shifts : []).forEach(function (s) {
            var o = hb.el('option', null, s.shiftCd + ' · ' + s.shiftNm);
            o.value = s.shiftCd;
            sel.appendChild(o);
        });
        sel.disabled = !plantSel.value || !CAN_SAVE;
    }

    /** @param {WorkCalRow | null} r */
    function show(r) {
        current = r;
        hb.showFieldErrors(form, []);
        var date = /** @type {HTMLInputElement} */ (field('calDate'));
        var shift = /** @type {HTMLSelectElement} */ (field('shiftCd'));
        date.value = r ? r.calDate : pickedDay;
        shift.value = r && r.shiftCd ? r.shiftCd : '';
        // 이미 있는 예외의 대상(날짜·근무조)은 고정 — 바꾸려면 사용 중지하고 새로
        date.readOnly = !!r || !CAN_SAVE;
        shift.disabled = !!r || !plantSel.value || !CAN_SAVE;
        field('dayType').value = r ? r.dayType : 'OFF';
        field('reason').value = r ? r.reason : '';
        var disableBtn = document.getElementById('disableBtn');
        if (disableBtn) { /** @type {HTMLButtonElement} */ (disableBtn).disabled = !r; }
        formMode.textContent = r ? hb.t('js.workCal.editing', r.calDate, scopeText(r))
            : hb.t('js.workCal.creating', plantSel.value || hb.t('js.workCal.company'));
        calBody.querySelectorAll('td[data-day]').forEach(function (td) {
            td.setAttribute('aria-selected', td.getAttribute('data-day') === (r ? r.calDate : pickedDay) ? 'true' : 'false');
        });
        hb.dirty.clear(form);
    }

    /** 이 화면 범위(회사 공통이면 회사 행만, 공장이면 그 공장 행)의 그날 예외 @param {string} day */
    function rowsOf(day) {
        var p = plantSel.value || null;
        return (view ? view.rows : []).filter(function (r) { return r.calDate === day && (r.plantCd || null) === p; });
    }

    /** 목록 선택을 폼에 맞춘다 — 고른 행이 이 쪽에 있으면 그 행, 새로 쓰기면 선택을 지운다(다시 읽기) @param {WorkCalRow | null} r */
    function syncGrid(r) {
        if (!grid) { return; }
        var sel = grid.selected();
        if (r) {
            if (!sel || sel.calId !== r.calId) { grid.selectById(r.calId); }
        } else if (sel) {
            grid.reload(false);
        }
    }

    /** @param {string} day */
    function pickDay(day) {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            pickedDay = day;
            var mine = rowsOf(day);
            var r = mine.length === 1 ? mine[0] : null;
            show(r);
            syncGrid(r);
            hb.page.openDetail();
        });
    }

    function render() {
        if (!view) { return; }
        var v = view;
        weeklyInfo.textContent = weeklyText(v.weeklyOff);
        calBody.textContent = '';
        // 월요일 시작 — 첫날 앞 칸은 빈 칸
        var first = new Date(v.days[0].day + 'T00:00:00Z').getUTCDay();
        var lead = (first + 6) % 7;
        /** @type {HTMLTableRowElement} */
        var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr'));
        for (var i = 0; i < lead; i++) { tr.appendChild(hb.el('td', 'wcal-out')); }
        v.days.forEach(function (d) {
            if (tr.children.length === 7) {
                calBody.appendChild(tr);
                tr = /** @type {HTMLTableRowElement} */ (hb.el('tr'));
            }
            var dayRows = v.rows.filter(function (r) { return r.calDate === d.day; });
            var exc = dayRows.length > 0;
            var td = hb.el('td', (d.workday ? '' : 'wcal-off') + (exc ? ' wcal-exc' : ''));
            td.setAttribute('data-day', d.day);
            td.tabIndex = 0;
            td.appendChild(hb.el('span', 'wcal-day', String(Number(d.day.substring(8)))));
            // 하루 판정의 근거(주간 휴무·하루 예외 사유) + 근무조 예외 사유(특근 등)
            var notes = [d.source === 'WEEKLY' ? hb.t('js.workCal.weeklyOff') : d.reason || ''];
            dayRows.forEach(function (r) { if (r.shiftCd) { notes.push(r.shiftCd + ' ' + r.reason); } });
            var note = notes.filter(function (x) { return !!x; }).join(' · ');
            if (note) { td.appendChild(hb.el('span', 'wcal-note', note)); }
            if (v.plantCd && d.workday) {
                td.appendChild(hb.el('span', 'wcal-shifts', d.shifts.map(function (s) { return s.shiftCd; }).join(' ')));
            }
            var day = d.day;
            td.addEventListener('click', function () { pickDay(day); });
            td.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pickDay(day); } });
            calBody.appendChild(tr);
            tr.appendChild(td);
        });
        while (tr.children.length < 7) { tr.appendChild(hb.el('td', 'wcal-out')); }
    }

    /**
     * 달 다시 읽기(달력 + 예외 목록) — selectId 가 있으면 그 예외를 고른다. 저장 뒤에는 목록 쪽을 지키고, 범위·달을 바꾸면 첫 쪽으로
     * @param {number} [selectId] @param {boolean} [keepPage]
     */
    function load(selectId, keepPage) {
        var plant = plantSel.value;
        var month = monthIn.value;
        shown = {plant: plant, month: month};
        if (!month) { return Promise.resolve(); }
        return hb.latest('workCal', hb.api(API + '?month=' + encodeURIComponent(month) + (plant ? '&plantCd=' + encodeURIComponent(plant) : '')))
            .then(function (res) {
                view = res.data;
                shiftOptions();
                render();
                var found = (view ? view.rows : []).filter(function (r) { return r.calId === selectId; })[0];
                show(found || null);
                if (grid) { grid.reload(!keepPage, selectId); }
            }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {number} delta */
    function moveMonth(delta) {
        mesUi.leaveOk().then(function (ok) { if (ok) { stepMonth(delta); } });
    }

    /** @param {number} delta */
    function stepMonth(delta) {
        var p = (monthIn.value || hb.dates.today().substring(0, 7)).split('-');
        var y = Number(p[0]);
        var m = Number(p[1]) + delta;
        if (m < 1) { m = 12; y--; }
        if (m > 12) { m = 1; y++; }
        monthIn.value = y + '-' + (m < 10 ? '0' : '') + m;
        pickedDay = '';
        load();
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function newRow() {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            syncGrid(null);
            hb.page.openDetail();
            field('calDate').focus();
        });
    }
    bind('newBtn', newRow);
    bind('newBtnList', newRow);
    // 엑셀 올리기(MST_WORK_CAL) — 끝나면 지금 달을 다시 읽는다
    bind('importBtn', function () { hb.importDialog({templateCd: 'MST_WORK_CAL', onDone: function () { load(); }}); });
    bind('prevBtn', function () { moveMonth(-1); });
    bind('nextBtn', function () { moveMonth(1); });
    // 이동을 취소하면 공장·달 선택도 되돌린다(마지막으로 읽은 값 — shown)
    plantSel.addEventListener('change', function () {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { plantSel.value = shown.plant; return; }
            load();
        });
    });
    monthIn.addEventListener('change', function () {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { monthIn.value = shown.month; return; }
            pickedDay = '';
            load();
        });
    });

    bind('saveBtn', function (saveBtn) {
        var edit = current;   // 저장 중에 다른 날을 골라도 이 행으로 마무리
        var body = edit
            ? {dayType: field('dayType').value, reason: field('reason').value, rowVer: edit.rowVer}
            : {plantCd: plantSel.value || null, calDate: field('calDate').value, shiftCd: field('shiftCd').value || null,
                dayType: field('dayType').value, reason: field('reason').value};
        hb.busy(saveBtn, function () {
            return edit ? hb.api(API + '/' + edit.calId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(edit ? edit.calId : res.data, true).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    bind('disableBtn', function (btn) {
        if (!current) { return; }
        var edit = current;
        mesUi.ask('js.mes.dlg.calDisable', hb.t('js.workCal.confirmDisable', edit.calDate, edit.reason), 'js.mes.dlg.disableOk', true)
            .then(function (ok) {
                if (!ok) { return; }
                hb.busy(btn, function () {
                    return hb.api(API + '/' + edit.calId + '/disable', {method: 'POST', body: {rowVer: edit.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(undefined, true).then(function () { notify(hb.t('js.workCal.disabled')); });
                }, function (err) { notify(err.message, true); });
            });
    });
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { /** @type {WorkCalField} */ (i).disabled = true; });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        monthIn.value = hb.dates.today().substring(0, 7);
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        /** @type {WorkCalPlant[]} */
        var plants = r[1].data;
        plants.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        return load();
    }).then(function () {
        grid = hb.grid('#rowsGrid', {
            progCd: PROG, id: 'rows', key: 'calId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return {plantCd: shown.plant, month: shown.month}; },
            format: {
                shiftCd: function (v, row) { return v + ' · ' + (row.shiftNm || ''); },
                dayType: function (v) { return typeText(v); }
            },
            // 범위가 비면 회사 공통, 근무조가 비면 하루 전체(format 은 빈 값에 불리지 않는다)
            emptyText: {plantCd: hb.t('js.workCal.company'), shiftCd: hb.t('js.workCal.wholeDay')},
            beforeSelect: function () { return mesUi.leaveOk(); },
            onSelect: function (row) {
                var r = /** @type {WorkCalRow} */ (row);
                pickedDay = r.calDate;
                show(r);
                hb.page.openDetail();
            },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
