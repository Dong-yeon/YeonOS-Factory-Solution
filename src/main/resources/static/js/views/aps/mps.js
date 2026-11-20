// @ts-check
/**
 * 기준생산계획 MPS (aps-mps) — APS 7단계, docs/38 16장.
 * 목록(표) → 상세(요약·확정·폐기) → 격자(품목마다 수주·예측·지시 공급·예상 재고·제안·계획 줄, 일/주 접기) → MPS 품목 표·폼.
 * 서버 수량은 글자 — 예상 재고는 화면에서 다시 계산할 때만 Number(표시용, 저장은 글자 그대로).
 *
 * @typedef {{mpsId: number, mpsNo: string, plantCd: string, plantNm: string, mpsNm: string | null, horizonStart: string,
 *   horizonDays: number, fenceDays: number, fenceEnd: string, baseMpsId: number | null, baseMpsNo: string | null, status: string,
 *   itemCnt: number, calculatedAt: string, calculatedBy: string, firmedAt: string | null, firmedBy: string | null,
 *   supersededAt: string | null, discardReason: string | null, rowVer: number}} MpsHead
 * @typedef {{cellId: number, date: string, demandOrder: string, demandFcst: string, pastDueQty: string, supplyWo: string,
 *   propQty: string, baseQty: string, fencedYn: string, planQty: string, chgReason: string | null, pab: string}} MpsCell
 * @typedef {{mpsRowId: number, itemId: number, itemCd: string, itemNm: string, unitCd: string, stockQty: string, safetyQty: string,
 *   lotRule: string, lotQty: string | null, lotMult: string | null, cells: MpsCell[]}} MpsItemRowView
 * @typedef {{mps: MpsHead, currentMpsId: number | null, rows: MpsItemRowView[]}} MpsDetailView
 * @typedef {{mpsItemId: number, plantCd: string, itemId: number, itemCd: string, itemNm: string, unitCd: string, safetyQty: string,
 *   lotRule: string, lotQty: string | null, lotMult: string | null, remark: string | null, useYn: string, rowVer: number}} MpsItemDef
 * @typedef {{planQty: string, reason: string | null}} MpsEdit
 * @typedef {(cs: MpsCell[], idx: number[]) => string} MpsLineFn
 */
(function () {
    'use strict';

    var API = '/api/aps/mps';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('mpsForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var listBody = /** @type {HTMLElement} */ (document.querySelector('#mpsList tbody'));
    var listCount = /** @type {HTMLElement} */ (document.getElementById('listCount'));
    var gridSection = /** @type {HTMLElement} */ (document.getElementById('gridSection'));
    var gridTitle = /** @type {HTMLElement} */ (document.getElementById('gridTitle'));
    var gridTable = /** @type {HTMLTableElement} */ (document.getElementById('mpsGrid'));
    var itemBody = /** @type {HTMLElement} */ (document.querySelector('#itemTable tbody'));
    var itemForm = /** @type {HTMLFormElement | null} */ (document.getElementById('itemForm'));
    var calcBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('calcBtn'));
    var firmBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('firmBtn'));
    var discardBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('discardBtn'));
    var saveCellsBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveCellsBtn'));
    var ST = {DRAFT: 'js.apsMps.st.DRAFT', FIRMED: 'js.apsMps.st.FIRMED', SUPERSEDED: 'js.apsMps.st.SUPERSEDED',
        DISCARDED: 'js.apsMps.st.DISCARDED'};
    /** @type {Record<string, HB.StatusTone>} */
    var TONE = {DRAFT: 'info', FIRMED: 'success', SUPERSEDED: 'neutral', DISCARDED: 'neutral'};
    var LOT = {EX: 'js.apsMps.lot.EX', FX: 'js.apsMps.lot.FX', MIN: 'js.apsMps.lot.MIN'};
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.NumberControl} */
    var daysCtl;
    /** @type {MpsDetailView | null} */
    var current = null;
    /** 고친 칸(칸 id → 값) — 저장 전 @type {Record<string, MpsEdit>} */
    var edits = {};
    /** @type {MpsItemDef[]} */
    var itemDefs = [];
    /** 고치는 MPS 품목(새 품목이면 null) @type {MpsItemDef | null} */
    var editingItem = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function fail(err) {
        if (err && err.stale) { return; }
        notify(err && err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ')
            : err && err.message, true);
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function field(f, n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} s */
    function stText(s) { return hb.t(/** @type {Record<string, string>} */ (ST)[s] || 'js.apsMps.st.DRAFT'); }

    /** @param {string} r */
    function lotText(r) { return hb.t(/** @type {Record<string, string>} */ (LOT)[r] || 'js.apsMps.lot.EX'); }

    /** 서버 글자 수량 → 천 단위 쉼표 @param {string | number | null | undefined} s */
    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var t = String(s);
        var neg = t.charAt(0) === '-';
        var p = (neg ? t.slice(1) : t).split('.');
        return (neg ? '-' : '') + p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** 표시용 합 — 자릿수 6 안에서 반올림 오차를 없앤다 @param {number} n */
    function numText(n) { return qtyText(String(Math.round(n * 1e6) / 1e6)); }

    /** @param {string | null} iso */
    function dt(iso) { return iso ? hb.fmt.datetime(iso) : ''; }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    function requestKey() {
        return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID()
            : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    }

    /** 고친 칸이 있으면 화면 안 대화상자로 묻는다 @returns {Promise<boolean>} */
    function leaveOk() {
        if (!Object.keys(edits).length && (current || !hb.dirty.isDirty())) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    // ------------------------------------------------------------------ 목록

    /** @param {number} [selectId] */
    function load(selectId) {
        var v = sp.values();
        /** @type {string[]} */
        var q = [];
        ['from', 'to', 'plantCd'].forEach(function (k) { if (v[k]) { q.push(k + '=' + encodeURIComponent(v[k])); } });
        return hb.latest('list', hb.api(API + '?' + q.join('&'))).then(function (res) {
            var data = /** @type {{rows: MpsHead[], more: boolean}} */ (res.data);
            renderList(data.rows);
            listCount.textContent = data.more ? hb.t('js.apsMps.more', data.rows.length) : hb.t('js.apsMps.count', data.rows.length);
            if (selectId) { return open(selectId); }
            show(null);
            return undefined;
        }, fail);
    }

    /** @param {MpsHead[]} rows */
    function renderList(rows) {
        listBody.textContent = '';
        if (!rows.length) {
            var tr = hb.el('tr');
            var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.apsMps.none')));
            td.colSpan = 7;
            tr.appendChild(td);
            listBody.appendChild(tr);
            return;
        }
        rows.forEach(function (m) {
            var tr = hb.el('tr', 'aps-mps-pick' + (current && current.mps.mpsId === m.mpsId ? ' aps-mps-picked' : ''));
            tr.tabIndex = 0;
            tr.setAttribute('data-id', String(m.mpsId));
            tr.appendChild(hb.el('td', null, m.mpsNo + (m.mpsNm ? ' · ' + m.mpsNm : '')));
            tr.appendChild(hb.el('td', null, m.plantCd));
            tr.appendChild(hb.el('td', null, m.horizonStart));
            tr.appendChild(hb.el('td', 'hb-num', m.horizonDays));
            tr.appendChild(hb.el('td', 'hb-num', m.itemCnt));
            var st = hb.el('td');
            st.appendChild(hb.status(TONE[m.status] || 'neutral', stText(m.status)));
            tr.appendChild(st);
            tr.appendChild(hb.el('td', null, dt(m.calculatedAt) + ' · ' + m.calculatedBy));
            var id = m.mpsId;
            var pick = function () {
                leaveOk().then(function (ok) { if (ok) { open(id).then(function () { hb.page.openDetail(); }); } });
            };
            tr.addEventListener('click', pick);
            tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { pick(); } });
            listBody.appendChild(tr);
        });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('mps', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {MpsDetailView} */ (res.data));
        }, fail);
    }

    // ------------------------------------------------------------------ 상세

    /** @param {MpsDetailView | null} d */
    function show(d) {
        current = d;
        edits = {};
        hb.dirty.clear();
        hb.showFieldErrors(form, []);
        var m = d ? d.mps : null;
        Array.prototype.forEach.call(listBody.querySelectorAll('tr[data-id]'), function (/** @type {HTMLElement} */ tr) {
            tr.classList.toggle('aps-mps-picked', !!m && tr.getAttribute('data-id') === String(m.mpsId));
        });
        if (m) {
            field(form, 'plantCd').value = m.plantCd;
            field(form, 'horizonStart').value = m.horizonStart;
            daysCtl.setValue(m.horizonDays);
            field(form, 'mpsNm').value = m.mpsNm || '';
        } else {
            field(form, 'horizonStart').value = hb.dates.today();
            daysCtl.setValue(28);
            field(form, 'mpsNm').value = '';
        }
        ['plantCd', 'horizonStart', 'horizonDays', 'mpsNm'].forEach(function (n) { field(form, n).disabled = !CAN_SAVE || !!m; });
        formMode.textContent = m ? hb.t('js.apsMps.viewing', m.mpsNo) : hb.t('js.apsMps.creating');
        if (calcBtn) { calcBtn.hidden = !!m; }
        var draft = !!m && m.status === 'DRAFT';
        if (firmBtn) { firmBtn.hidden = !draft; }
        if (discardBtn) { discardBtn.hidden = !draft; }
        renderSummary(d);
        gridSection.hidden = !d;
        if (d && m) {
            gridTitle.textContent = hb.t('js.apsMps.gridOf', m.mpsNo);
            renderGrid();
        }
    }

    /** @param {MpsDetailView | null} d */
    function renderSummary(d) {
        summary.textContent = '';
        summary.hidden = !d;
        if (!d) { return; }
        var m = d.mps;
        /** @type {[string, string][]} */
        var items = [
            [hb.t('js.apsMps.sum.status'), stText(m.status) + (d.currentMpsId === m.mpsId ? ' · ' + hb.t('js.apsMps.current') : '')
                + (m.discardReason ? ' — ' + m.discardReason : '')],
            [hb.t('js.apsMps.sum.calculated'), dt(m.calculatedAt) + ' · ' + m.calculatedBy],
            [hb.t('js.apsMps.sum.horizon'), hb.t('js.apsMps.horizon', m.horizonStart, m.horizonDays)],
            [hb.t('js.apsMps.sum.fence'), hb.t('js.apsMps.fence', m.fenceDays, m.fenceEnd)],
            [hb.t('js.apsMps.sum.base'), m.baseMpsNo || '-']];
        if (m.firmedAt) {
            items.push([hb.t('js.apsMps.sum.firmed'), dt(m.firmedAt) + ' · ' + (m.firmedBy || '')]);
        }
        if (m.supersededAt) {
            items.push([hb.t('js.apsMps.sum.superseded'), dt(m.supersededAt)]);
        }
        items.forEach(function (it) {
            summary.appendChild(hb.el('dt', null, it[0]));
            summary.appendChild(hb.el('dd', null, it[1]));
        });
    }

    // ------------------------------------------------------------------ 격자

    function bucket() {
        var r = /** @type {HTMLInputElement | null} */ (document.querySelector('#bucket input:checked'));
        return r ? r.value : 'DAY';
    }

    /** @param {MpsCell} c */
    function planOf(c) {
        var e = edits[String(c.cellId)];
        return e ? e.planQty : c.planQty;
    }

    /** 주 머리(월요일) @param {string} iso */
    function weekOf(iso) {
        var d = new Date(iso + 'T00:00:00Z');
        var dow = (d.getUTCDay() + 6) % 7;
        return hb.dates.add(iso, -dow);
    }

    /**
     * 열 묶음 — 일 보기 = 칸 하나씩, 주 보기 = 같은 주 칸들
     * @param {MpsCell[]} cells @returns {{label: string, idx: number[], fenced: boolean}[]}
     */
    function columns(cells) {
        /** @type {{label: string, idx: number[], fenced: boolean}[]} */
        var cols = [];
        var week = bucket() === 'WEEK';
        cells.forEach(function (c, i) {
            var key = week ? weekOf(c.date) : c.date;
            var last = cols[cols.length - 1];
            if (last && last.label === key) {
                last.idx.push(i);
                last.fenced = last.fenced && c.fencedYn === 'Y';
            } else {
                cols.push({label: key, idx: [i], fenced: c.fencedYn === 'Y'});
            }
        });
        return cols;
    }

    function renderGrid() {
        gridTable.textContent = '';
        if (!current) { return; }
        var d = current;
        var editable = CAN_SAVE && d.mps.status === 'DRAFT' && bucket() === 'DAY';
        if (saveCellsBtn) { saveCellsBtn.hidden = !(CAN_SAVE && d.mps.status === 'DRAFT'); }
        if (!d.rows.length) {
            gridTable.appendChild(hb.el('caption', 'hb-muted', hb.t('js.apsMps.noRows')));
            return;
        }
        var cols = columns(d.rows[0].cells);
        var thead = hb.el('thead');
        var hr = hb.el('tr');
        hr.appendChild(hb.el('th', null, hb.t('js.apsMps.col.item')));
        hr.appendChild(hb.el('th', null, hb.t('js.apsMps.col.line')));
        cols.forEach(function (col) {
            var th = hb.el('th', 'hb-num' + (col.fenced ? ' aps-mps-fenced' : ''),
                bucket() === 'WEEK' ? hb.t('js.apsMps.weekOf', col.label.slice(5)) : col.label.slice(5));
            th.title = col.label;
            hr.appendChild(th);
        });
        thead.appendChild(hr);
        gridTable.appendChild(thead);
        d.rows.forEach(function (row) { gridTable.appendChild(itemBlock(row, cols, editable)); });
    }

    /**
     * 품목 하나 = 6줄
     * @param {MpsItemRowView} row @param {{label: string, idx: number[], fenced: boolean}[]} cols @param {boolean} editable
     */
    function itemBlock(row, cols, editable) {
        var tb = hb.el('tbody', 'aps-mps-item');
        var safety = Number(row.safetyQty);
        // 예상 재고 — 고친 계획으로 다시 계산(서버와 같은 식)
        var pab = Number(row.stockQty);
        /** @type {number[]} */
        var pabs = row.cells.map(function (c) {
            pab = pab + Number(c.supplyWo) + Number(planOf(c)) - Number(c.demandOrder) - Number(c.demandFcst);
            return pab;
        });
        /** @type {[string, MpsLineFn][]} */
        var lines = [
            ['js.apsMps.line.order', function (cs) { return numText(sum(cs, 'demandOrder')); }],
            ['js.apsMps.line.fcst', function (cs) { return numText(sum(cs, 'demandFcst')); }],
            ['js.apsMps.line.supply', function (cs) { return numText(sum(cs, 'supplyWo')); }],
            ['js.apsMps.line.pab', function (cs, idx) { return numText(pabs[idx[idx.length - 1]]); }],
            ['js.apsMps.line.prop', function (cs) { return numText(sum(cs, 'propQty')); }],
            ['js.apsMps.line.plan', function (cs) {
                return numText(cs.reduce(function (a, c) { return a + Number(planOf(c)); }, 0));
            }]];
        lines.forEach(function (ln, li) {
            var tr = hb.el('tr', li === 5 ? 'aps-mps-planline' : null);
            if (li === 0) {
                var th = /** @type {HTMLTableCellElement} */ (hb.el('th', 'aps-mps-itemcell'));
                th.rowSpan = 6;
                th.appendChild(hb.el('strong', null, row.itemCd));
                th.appendChild(hb.el('div', 'hb-muted', row.itemNm));
                th.appendChild(hb.el('div', 'hb-muted', hb.t('js.apsMps.itemInfo', qtyText(row.stockQty), qtyText(row.safetyQty),
                    lotText(row.lotRule) + (row.lotQty ? ' ' + qtyText(row.lotQty) : '') + (row.lotMult ? ' · ' + hb.t('js.apsMps.multOf', qtyText(row.lotMult)) : ''), row.unitCd)));
                tr.appendChild(th);
            }
            tr.appendChild(hb.el('th', null, hb.t(ln[0])));
            cols.forEach(function (col) {
                var cs = col.idx.map(function (i) { return row.cells[i]; });
                var td = hb.el('td', 'hb-num' + (col.fenced ? ' aps-mps-fenced' : ''));
                if (li === 5 && editable) {
                    td.appendChild(planInput(cs[0], row));
                } else {
                    td.textContent = ln[1](cs, col.idx);
                }
                if (li === 0 && cs.some(function (c) { return Number(c.pastDueQty) > 0; })) {
                    td.classList.add('aps-mps-pastdue');
                    td.title = hb.t('js.apsMps.pastDue', qtyText(cs[0].pastDueQty));
                }
                if (li === 3) {
                    var p = pabs[col.idx[col.idx.length - 1]];
                    td.classList.toggle('aps-mps-neg', p < 0);
                    td.classList.toggle('aps-mps-low', p >= 0 && p < safety);
                }
                if (li === 5 && cs.some(function (c) { return !!edits[String(c.cellId)]; })) {
                    td.classList.add('aps-mps-edited');
                }
                if (li === 5 && !editable && cs.some(function (c) { return !!c.chgReason; })) {
                    td.title = cs.map(function (c) { return c.chgReason || ''; }).filter(Boolean).join(' / ');
                }
                tr.appendChild(td);
            });
            tb.appendChild(tr);
        });
        return tb;
    }

    /** @param {MpsCell[]} cs @param {'demandOrder' | 'demandFcst' | 'supplyWo' | 'propQty'} k */
    function sum(cs, k) {
        return cs.reduce(function (a, c) { return a + Number(c[k]); }, 0);
    }

    /** 계획 칸 입력 — 바꾸면 edits 에, 확정 구간 칸이 계산 값과 다르면 사유를 묻는다 @param {MpsCell} c @param {MpsItemRowView} row */
    function planInput(c, row) {
        var inp = /** @type {HTMLInputElement} */ (hb.el('input', 'aps-mps-input'));
        inp.inputMode = 'decimal';
        inp.maxLength = 40;
        inp.value = planOf(c);
        inp.setAttribute('aria-label', hb.t('js.apsMps.planAria', row.itemCd, c.date));
        inp.setAttribute('data-cell', String(c.cellId));
        inp.addEventListener('change', function () {
            var v = inp.value.trim().replace(/,/g, '');
            if (v === c.planQty && !c.chgReason) {
                delete edits[String(c.cellId)];
                renderGrid();
                return;
            }
            var needReason = c.fencedYn === 'Y' && v !== '' && !isNaN(Number(v)) && Number(v) !== Number(c.baseQty);
            var prev = edits[String(c.cellId)];
            var ask = needReason
                ? hb.dialog.prompt({title: hb.t('js.apsMps.reasonTitle'), message: hb.t('js.apsMps.reasonPrompt', row.itemCd, c.date),
                    label: hb.t('js.apsMps.reason'), ok: hb.t('js.apsMps.reasonOk'), required: true, maxLength: 500,
                    value: prev && prev.reason ? prev.reason : (c.chgReason || '')})
                : Promise.resolve(/** @type {string | null} */ (null));
            ask.then(function (reason) {
                if (needReason && reason === null) {   // 취소 = 원래 값
                    renderGrid();
                    return;
                }
                edits[String(c.cellId)] = {planQty: v, reason: needReason ? reason : null};
                renderGrid();
                var next = /** @type {HTMLInputElement | null} */ (gridTable.querySelector('input[data-cell="' + c.cellId + '"]'));
                if (next) { next.focus(); }
            });
        });
        return inp;
    }

    document.querySelectorAll('#bucket input').forEach(function (r) {
        r.addEventListener('change', function () { renderGrid(); });
    });

    if (saveCellsBtn) {
        var saveCells = saveCellsBtn;
        saveCells.addEventListener('click', function () {
            if (!current) { return; }
            var m = current.mps;
            var cells = Object.keys(edits).map(function (id) {
                return {cellId: Number(id), planQty: edits[id].planQty, reason: edits[id].reason};
            });
            if (!cells.length) { notify(hb.t('js.apsMps.noEdits')); return; }
            hb.busy(saveCells, function () {
                return hb.api(API + '/' + m.mpsId + '/cells', {method: 'PUT', body: {rowVer: m.rowVer, cells: cells}});
            }).then(function (res) {
                if (!res) { return; }
                return open(m.mpsId).then(function () { notify(hb.t('js.apsMps.saved', cells.length)); });
            }, function (err) {
                fail(err);
                (err.errors || []).forEach(function (/** @type {HB.FieldError} */ fe) {
                    var inp = fe.rowKey ? gridTable.querySelector('input[data-cell="' + fe.rowKey + '"]') : null;
                    if (inp) { inp.classList.add('hb-invalid'); inp.setAttribute('title', fe.message); }
                });
            });
        });
    }

    // ------------------------------------------------------------------ 새 MPS·확정·폐기

    function startNew() {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', startNew); }
    });

    if (calcBtn) {
        var calc = calcBtn;
        calc.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            if (!daysCtl.validate().ok) { return; }
            var days = daysCtl.numberValue();
            var body = {plantCd: field(form, 'plantCd').value, horizonStart: field(form, 'horizonStart').value || null,
                horizonDays: days === null ? null : days, mpsNm: field(form, 'mpsNm').value.trim()};
            var key = requestKey();
            notify(hb.t('js.apsMps.calculating'));
            hb.busy(calc, function () {
                return hb.api(API, {method: 'POST', body: body, headers: {'X-Idempotency-Key': key}, timeoutMs: 120000});
            }).then(function (res) {
                if (!res) { return; }
                return load(/** @type {number} */ (res.data)).then(function () { notify(hb.t('js.apsMps.calculated')); });
            }, function (err) {
                fail(err);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (firmBtn) {
        var firm = firmBtn;
        firm.addEventListener('click', function () {
            if (!current) { return; }
            var m = current.mps;
            if (Object.keys(edits).length) { notify(hb.t('js.apsMps.saveFirst'), true); return; }
            hb.dialog.confirm({title: hb.t('js.apsMps.firmTitle'), message: hb.t('js.apsMps.firmPrompt', m.mpsNo, m.plantCd),
                ok: hb.t('js.apsMps.firmOk')}).then(function (ok) {
                if (!ok) { return null; }
                var key = requestKey();
                return hb.busy(firm, function () {
                    return hb.api(API + '/' + m.mpsId + '/firm', {method: 'POST', body: {rowVer: m.rowVer}, headers: {'X-Idempotency-Key': key}});
                });
            }).then(function (res) {
                if (!res) { return; }
                return load(m.mpsId).then(function () { notify(hb.t('js.apsMps.firmed')); });
            }, fail);
        });
    }

    if (discardBtn) {
        var discard = discardBtn;
        discard.addEventListener('click', function () {
            if (!current) { return; }
            var m = current.mps;
            hb.dialog.prompt({title: hb.t('js.apsMps.discardTitle'), message: hb.t('js.apsMps.discardPrompt', m.mpsNo),
                label: hb.t('js.apsMps.discardReason'), ok: hb.t('js.apsMps.discardOk'), danger: true, required: true, maxLength: 500})
                .then(function (reason) {
                    if (reason === null) { return null; }
                    return hb.busy(discard, function () {
                        return hb.api(API + '/' + m.mpsId + '/discard', {method: 'POST', body: {reason: reason, rowVer: m.rowVer}});
                    });
                }).then(function (res) {
                if (!res) { return; }
                return load(m.mpsId).then(function () { notify(hb.t('js.apsMps.discarded')); });
            }, fail);
        });
    }

    // ------------------------------------------------------------------ MPS 품목

    function loadItems() {
        return hb.api(API + '/items').then(function (res) {
            itemDefs = /** @type {MpsItemDef[]} */ (res.data);
            renderItems();
        }, fail);
    }

    function renderItems() {
        itemBody.textContent = '';
        if (!itemDefs.length) {
            var tr = hb.el('tr');
            var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.apsMps.noItems')));
            td.colSpan = 6;
            tr.appendChild(td);
            itemBody.appendChild(tr);
            return;
        }
        itemDefs.forEach(function (it) {
            var tr = hb.el('tr', it.useYn === 'Y' ? 'aps-mps-pick' : 'aps-mps-pick hb-tree-dim');
            tr.tabIndex = 0;
            tr.appendChild(hb.el('td', null, it.plantCd));
            tr.appendChild(hb.el('td', null, it.itemCd + ' · ' + it.itemNm));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(it.safetyQty) + ' ' + it.unitCd));
            tr.appendChild(hb.el('td', null, lotText(it.lotRule)));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(it.lotQty) + (it.lotMult ? ' · ' + hb.t('js.apsMps.multOf', qtyText(it.lotMult)) : '')));
            tr.appendChild(hb.el('td', null, hb.t(it.useYn === 'Y' ? 'js.yn.Y' : 'js.yn.N')));
            if (itemForm) {
                tr.addEventListener('click', function () { editItem(it); });
                tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { editItem(it); } });
            }
            itemBody.appendChild(tr);
        });
    }

    /** @param {MpsItemDef | null} it */
    function editItem(it) {
        if (!itemForm) { return; }
        var f = itemForm;
        editingItem = it;
        f.hidden = false;
        hb.showFieldErrors(f, []);
        field(f, 'plantCd').value = it ? it.plantCd : field(form, 'plantCd').value;
        field(f, 'itemCd').value = it ? it.itemCd : '';
        field(f, 'plantCd').disabled = !!it;
        field(f, 'itemCd').disabled = !!it;
        field(f, 'useYn').value = it ? it.useYn : 'Y';
        field(f, 'remark').value = it && it.remark ? it.remark : '';
        field(f, it ? 'remark' : 'itemCd').focus();
    }

    if (itemForm) {
        var f = itemForm;
        var itemNew = document.getElementById('itemNewBtn');
        if (itemNew) { itemNew.addEventListener('click', function () { editItem(null); }); }
        var cancel = /** @type {HTMLButtonElement} */ (document.getElementById('itemCancelBtn'));
        cancel.addEventListener('click', function () { f.hidden = true; editingItem = null; });
        var saveItem = /** @type {HTMLButtonElement} */ (document.getElementById('itemSaveBtn'));
        saveItem.addEventListener('click', function () {
            hb.showFieldErrors(f, []);
            var it = editingItem;
            var body = {plantCd: field(f, 'plantCd').value, itemCd: field(f, 'itemCd').value.trim(),
                useYn: field(f, 'useYn').value,
                remark: field(f, 'remark').value.trim(), rowVer: it ? it.rowVer : null};
            hb.busy(saveItem, function () {
                return hb.api(API + '/items' + (it ? '/' + it.mpsItemId : ''), {method: it ? 'PUT' : 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                f.hidden = true;
                editingItem = null;
                return loadItems().then(function () { notify(hb.t('js.apsMps.itemSaved')); });
            }, function (err) {
                fail(err);
                hb.showFieldErrors(f, err.errors);
            });
        });
    }

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        daysCtl = hb.number(/** @type {HTMLInputElement} */ (field(form, 'horizonDays')));
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
        option(plantSel, '', hb.t('js.apsMps.allPlant'));
        var itemPlant = itemForm ? /** @type {HTMLSelectElement} */ (field(itemForm, 'plantCd')) : null;
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(/** @type {HTMLSelectElement} */ (field(form, 'plantCd')), p.plantCd, p.plantCd + ' · ' + p.plantNm);
            if (itemPlant) { option(itemPlant, p.plantCd, p.plantCd + ' · ' + p.plantNm); }
        });
        sp = hb.searchPanel(search, {onSearch: function () { return leaveOk().then(function (ok) { return ok ? load() : null; }); }});
        hb.dirty.watch(form);
        if (!CAN_APPROVE && firmBtn) { firmBtn.hidden = true; }
        return sp.ready;
    }).then(function () {
        show(null);
        return Promise.all([load(), loadItems()]);
    }).then(null, function (err) { fail(err); });
})();
