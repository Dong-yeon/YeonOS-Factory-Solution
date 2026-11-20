// @ts-check
/* 실적 조회 (prd-result) — docs/25 4.16. 작업일 기간 × 조건 × 보기 → hb.grid(쪽·정렬·건수, docs/40 §10.2 D1) + 불량 유형 합계 → [엑셀](마지막으로 조회한 조건 그대로).
   줄의 기준은 원래 실적 — 정정된 실적은 상세에서 흐리게, 묶음에서는 빠진다(순수량). 열은 보기마다 달라 보기를 바꿔 조회하면 그리드를 새로 만든다. */
/**
 * @typedef {{kind: string, defectCd: string, defectNm: string, qtyText?: string | null, unitCd: string}} ResultViewDefect
 */
(function () {
    'use strict';

    var API = '/api/production/results';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var periodHost = /** @type {HTMLElement} */ (document.getElementById('period'));
    var gridHost = /** @type {HTMLElement} */ (document.getElementById('resultGrid'));
    var defectsHost = /** @type {HTMLElement} */ (document.getElementById('defects'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var shiftSel = /** @type {HTMLSelectElement} */ (document.getElementById('shiftSel'));
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));   // EXCEL 권한 없으면 없음
    var KEYS = ['view', 'from', 'to', 'plantCd', 'shiftCd', 'keyword', 'itemCd', 'procCd', 'workerCd'];
    /** 마지막으로 조회한 조건(엑셀) @type {Record<string, string> | null} */
    var last = null;
    /** 그리드가 쪽·정렬을 넘길 때 쓰는 조건 — 조회 때만 바뀐다(입력 중인 칸은 안 따라간다) @type {Record<string, string>} */
    var gridCond = {};
    /** @type {HB.Grid | null} */
    var grid = null;
    /** 지금 그리드의 보기 @type {string} */
    var gridView = '';

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {unknown} v */
    function q(v) { return v == null || v === '' ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {string} k */
    function kindText(k) {
        if (k === 'SCRAP') { return hb.t('js.prdResult.kind.SCRAP'); }
        if (k === 'REWORK') { return hb.t('js.prdResult.kind.REWORK'); }
        return hb.t('js.prdResult.kind.REPAIR');
    }

    /** 불량 유형 합계(작은 요약 표 — 그리드 아님) @param {ResultViewDefect[]} list */
    function drawDefects(list) {
        defectsHost.textContent = '';
        if (!list.length) {
            defectsHost.appendChild(hb.el('p', 'hb-muted', hb.t('js.prdResult.noDefects')));
            return;
        }
        var t = hb.el('table', 'hb-cmp-table');
        var thead = hb.el('thead');
        var head = hb.el('tr');
        [[hb.t('js.prdResult.col.kind'), null], [hb.t('js.prdResult.col.defect'), null], [hb.t('js.prdResult.col.qty'), 'hb-num'],
            [hb.t('js.prdResult.col.unitCd'), null]].forEach(function (c) { head.appendChild(hb.el('th', c[1], c[0] || '')); });
        thead.appendChild(head);
        t.appendChild(thead);
        var body = hb.el('tbody');
        list.forEach(function (d) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, kindText(d.kind)));
            tr.appendChild(hb.el('td', null, d.defectCd + ' ' + d.defectNm));
            tr.appendChild(hb.el('td', 'hb-num', q(d.qtyText)));
            tr.appendChild(hb.el('td', null, d.unitCd));
            body.appendChild(tr);
        });
        t.appendChild(body);
        defectsHost.appendChild(t);
    }

    /** 보기에 맞는 그리드 — 같은 보기면 첫 쪽부터 다시 읽고, 보기가 바뀌면 열이 달라 새로 만든다(설정 저장 키도 보기마다) @param {string} view */
    function showGrid(view) {
        if (grid && gridView === view) { return grid.reload(true); }
        if (grid) { grid.destroy(); }
        gridView = view;
        grid = hb.grid(gridHost, {
            progCd: PROG, id: view.toLowerCase(), key: 'resultId',
            url: API + '/grid', columnsUrl: API + '/columns?view=' + encodeURIComponent(view),
            actions: ACTIONS, params: function () { return gridCond; },
            // 묶음 보기의 빈 작업자·근무조 = 작업자 없음·근무조 밖(상세도 같은 뜻)
            emptyText: {workerCd: hb.t('js.prdResult.noWorker'), shiftCd: hb.t('js.prdResult.noShift')},
            rowClass: function (row) { return row.corrected ? 'hb-tree-dim' : null; },   // 정정된 실적은 흐리게
            notify: notify
        });
        return grid.ready;
    }

    /** 조회 패널이 검사를 마친 값으로 부른다 — 불량 합계(조건 검사 오류를 칸에 표시) → 그리드 @param {HB.SearchValues} v */
    function search(v) {
        /** @type {Record<string, string>} */
        var cond = {};
        KEYS.forEach(function (k) { if (v[k]) { cond[k] = String(v[k]); } });
        var view = cond.view || 'DETAIL';
        last = null;
        if (excelBtn) { excelBtn.disabled = true; }
        var qs = Object.keys(cond).map(function (k) { return k + '=' + encodeURIComponent(cond[k]); }).join('&');
        return hb.latest('defects', hb.api(API + '/defects?' + qs)).then(function (res) {
            drawDefects(/** @type {ResultViewDefect[]} */ (res.data));
            notify('');
            gridCond = cond;
            return showGrid(view).then(function () {
                last = cond;
                if (excelBtn) { excelBtn.disabled = false; }
            });
        }, /** @param {HB.ApiError} err */ function (err) {
            if (err.code === 'STALE') { return; }
            /** @type {HB.FieldError | undefined} */
            var fe = (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return e.field === 'from' || e.field === 'to'; })[0];
            var period = periodHost.hbDateRange;
            if (fe && fe.field && period) {
                period.showServerError(fe.field, fe.message);
            } else {
                notify(err.message, true);
            }
        });
    }

    /** 공장을 바꾸면 그 공장 근무조로 @param {string} plantCd */
    function loadShifts(plantCd) {
        shiftSel.textContent = '';
        var all = hb.el('option', null, hb.t('js.prdResult.allShift'));
        all.value = '';
        shiftSel.appendChild(all);
        if (!plantCd) { return Promise.resolve(); }
        return hb.latest('shifts', hb.api(API + '/shifts?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            /** @type {{shiftCd: string, shiftNm: string, useYn: string}[]} */
            var list = res.data;
            list.forEach(function (s) {
                var o = hb.el('option', null, s.shiftCd + ' · ' + s.shiftNm + (s.useYn === 'Y' ? '' : ' ' + hb.t('js.prdResult.inactive')));
                o.value = s.shiftCd;
                shiftSel.appendChild(o);
            });
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    if (excelBtn) {
        var exBtn = excelBtn;
        exBtn.addEventListener('click', function () {
            var c = last;
            if (!c) { notify(hb.t('js.prdResult.searchFirst'), true); return; }
            hb.busy(exBtn, function () {
                return hb.download(API + '/excel', c);
            }).then(function (r) {
                if (r === null) { return; }
                notify(hb.t('js.grid.exported'));
            }, /** @param {HB.ApiError} err */ function (err) { notify(err.message, true); });
        });
    }
    // 조건을 바꾸면 엑셀은 다시 조회한 뒤에(표와 다른 파일 방지)
    form.addEventListener('input', function () {
        last = null;
        if (excelBtn) { excelBtn.disabled = true; }
    });
    plantSel.addEventListener('change', function () { loadShifts(plantSel.value); });

    /** @type {HB.SearchPanel} */
    var sp;
    form.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return Promise.all([hb.loadSession(), hb.api(API + '/plants')]); }).then(function (r) {
        hb.page.init();
        var all = hb.el('option', null, hb.t('js.prdResult.allPlant'));
        all.value = '';
        plantSel.appendChild(all);
        /** @type {{plantCd: string, plantNm: string}[]} */
        var plants = r[1].data;
        plants.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        return loadShifts('');
    }).then(function () {
        sp = hb.searchPanel(form, {onSearch: search});
        return sp.ready;
    }).then(function () {
        // 링크 조건(q.plantCd)으로 공장이 정해졌으면 그 공장 근무조를 채운 뒤 조회
        return plantSel.value ? loadShifts(plantSel.value) : null;
    }).then(function () { return sp.search(); }).then(null, function (err) { notify(err && err.message, true); });
})();
