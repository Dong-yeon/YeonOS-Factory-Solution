// @ts-check
/* 근무조 관리 (mst-shift) — P2 1단계 (docs/25 4.2). 공장 선택 → 조 hb.grid(UI 단계 3 ①) + 상세 폼, 아래 "시각 → 작업일·근무조" 확인 */
/**
 * @typedef {{shiftId: number, shiftCd: string, shiftNm: string, startTime: string, endTime: string, dayOffset: number,
 *   breakMin: number, sortNo: number, useYn: string, remark?: string | null, rowVer: number, [field: string]: any}} ShiftRow
 * @typedef {{plantCd: string, plantNm: string, timeZone?: string | null}} ShiftPlant
 * @typedef {{localTime: string, zone: string, workDay: string, shiftCd?: string | null, shiftNm?: string | null}} ShiftWorkDay
 * @typedef {HTMLInputElement | HTMLSelectElement} ShiftField
 */
(function () {
    'use strict';

    var API = '/api/master/shifts';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('shiftForm'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var zoneInfo = /** @type {HTMLElement} */ (document.getElementById('zoneInfo'));
    var checkAt = /** @type {HTMLInputElement} */ (document.getElementById('checkAt'));
    var checkResult = /** @type {HTMLElement} */ (document.getElementById('checkResult'));
    /** 선택된 근무조 @type {ShiftRow | null} */
    var current = null;
    /** @type {HB.NumberControl} */
    var sortNo;
    /** @type {HB.NumberControl} */
    var breakMin;
    /** @type {ShiftPlant[]} */
    var plants = [];
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) { return /** @type {ShiftField} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** @param {unknown} t */
    function hhmm(t) { return t ? String(t).substring(0, 5) : ''; }

    function plant() { return plantSel.value; }
    var plantNow = '';

    function showZone() {
        var p = plants.filter(function (x) { return x.plantCd === plant(); })[0];
        zoneInfo.textContent = p ? hb.t('js.shift.zone', p.timeZone || hb.session().timeZone) : '';
    }

    /** @param {ShiftRow | null} s */
    function show(s) {
        current = s;
        hb.showFieldErrors(form, []);
        ['shiftCd', 'shiftNm', 'remark'].forEach(function (n) { field(n).value = s ? (s[n] || '') : ''; });
        field('startTime').value = s ? hhmm(s.startTime) : '';
        field('endTime').value = s ? hhmm(s.endTime) : '';
        field('dayOffset').value = s ? String(s.dayOffset) : '0';
        breakMin.setValue(s ? s.breakMin : 0);
        sortNo.setValue(s ? s.sortNo : 0);
        field('useYn').value = s ? s.useYn : 'Y';
        /** @type {HTMLInputElement} */ (field('shiftCd')).readOnly = !!s || ACTIONS.indexOf('SAVE') < 0;
        formMode.textContent = s ? hb.t('js.shift.editing', s.shiftCd) : hb.t('js.shift.creating', plant());
        hb.dirty.clear();
    }

    /** 조 목록 다시 읽기 — selectId 가 이 페이지에 있으면 다시 골라 onSelect 가 폼을 채운다. 공장을 바꿨으면 첫 쪽부터
     * @param {number} [selectId] */
    function load(selectId) {
        var changed = plantNow !== plant();
        plantNow = plant();
        if (!grid) { return Promise.resolve(); }
        if (changed) { show(null); }
        return grid.reload(changed, selectId);
    }

    function newShift() {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('shiftCd').focus();
        });
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }
    bind('newBtn', newShift);
    bind('newBtnList', newShift);

    // 이동을 취소하면 공장 선택도 되돌린다(전에는 선택만 바뀐 채 남았다) — plantNow 는 마지막으로 읽은 공장(load)
    plantSel.addEventListener('change', function () {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { plantSel.value = plantNow; return; }
            showZone();
            checkResult.textContent = '';
            load();
        });
    });

    bind('saveBtn', function (saveBtn) {
        var body = {plantCd: plant(), shiftCd: field('shiftCd').value, shiftNm: field('shiftNm').value,
            startTime: field('startTime').value, endTime: field('endTime').value,
            dayOffset: Number(field('dayOffset').value), breakMin: breakMin.numberValue(), sortNo: sortNo.numberValue(),
            useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null};
        var editId = current ? current.shiftId : null;   // 저장 중에 다른 행·신규를 골라도 이 조로 마무리
        hb.busy(saveBtn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });
    if (ACTIONS.indexOf('SAVE') < 0) {
        form.querySelectorAll('input, select').forEach(function (i) { /** @type {ShiftField} */ (i).disabled = true; });
    }

    // 시각 → 작업일·근무조
    var checkBox = /** @type {HTMLElement} */ (document.getElementById('checkBox'));
    bind('checkBtn', function (checkBtn) {
        var plantCd = plant();
        if (!plantCd) { return; }
        hb.showFieldErrors(checkBox, []);
        hb.busy(checkBtn, function () {
            return hb.latest('check', hb.api(API + '/work-day?plantCd=' + encodeURIComponent(plantCd)
                + (checkAt.value ? '&at=' + encodeURIComponent(checkAt.value) : '')));
        }).then(function (res) {
            if (!res || plant() !== plantCd) { return; }   // 기다리는 사이 공장을 바꿨으면 다른 공장 결과를 쓰지 않는다
            /** @type {ShiftWorkDay} */
            var d = res.data;
            checkResult.textContent = d.shiftCd
                ? hb.t('js.shift.checkResult', d.localTime, d.zone, d.workDay, d.shiftCd + ' · ' + d.shiftNm)
                : hb.t('js.shift.checkNoShift', d.localTime, d.zone, d.workDay);
        }, function (err) {
            if (plant() !== plantCd) { return; }
            checkResult.textContent = '';
            hb.showFieldErrors(checkBox, err.errors);
            if (!err.errors || !err.errors.length) { notify(err.message, true); }
        });
    });

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        breakMin = hb.number(/** @type {HTMLInputElement} */ (field('breakMin')));
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        plants = r[1].data;
        plants.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        if (!plants.length) { notify(hb.t('js.shift.noPlant'), true); return null; }   // 공장이 없으면 목록도 없다(공장 필수)
        plantNow = plant();
        showZone();
        show(null);
        grid = hb.grid('#shiftGrid', {
            progCd: PROG, id: 'main', key: 'shiftId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return {plantCd: plant()}; },
            format: {
                // 시작~끝 한 칸, 끝이 시작보다 이르면 다음 날
                startTime: function (v, row) {
                    return hhmm(v) + ' ~ ' + hhmm(row.endTime) + (hhmm(row.endTime) <= hhmm(v) ? ' ' + hb.t('js.shift.nextDay') : '');
                },
                dayOffset: function (v) { return v === -1 ? hb.t('js.shift.offsetPrev') : hb.t('js.shift.offsetStart'); }
            },
            beforeSelect: function () { return mesUi.leaveOk(); },
            status: {useYn: mesUi.TONES.use},                                              // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { show(/** @type {ShiftRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
