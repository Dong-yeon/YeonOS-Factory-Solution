/*
 * 사전 납품 통지 (srm-asn) — SRM 2부 R3a (docs/31 4.11).
 * 조회(도착 예정일 기간 필수) → ASN 표 → 상세: 머리 폼 + 납품 행(한 벌 저장, 작성 중만 편집) + 상태 동작(허용된 다음 동작만).
 * 행은 "발주 행 가져오기" 로만 더한다(그 협력사·공장의 열린 발주 행). 행 오류는 서버가 행 키(배열 순번)로 돌려준다.
 */
(function () {
    'use strict';

    var API = '/api/srm/asns';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = document.getElementById('msg');
    var form = document.getElementById('asnForm');
    var search = document.getElementById('search');
    var lineBody = document.getElementById('lines');
    var formMode = document.getElementById('formMode');
    var pickBox = document.getElementById('pickBox');
    var pickList = document.getElementById('pickList');
    var current = null;      // AsnDetail
    var lines = [];          // {key, poLineId, poNo, poLineNo, itemCd, itemNm, unitCd, qty, supplierLotNo, mfgDate, expiryDate, packCnt, remark, recvQty, remainQty}
    var options = [];        // 발주 행 선택지
    var keySeq = 0, sp, grid, statusNames = {};
    /** 행 칸 이름 — 키는 글자 그대로(I18nKeyGuardTest) */
    var LABELS = {qty: function () { return hb.t('js.srm.asn.col.qty'); }, supplierLotNo: function () { return hb.t('js.srm.asn.col.lot'); },
        mfgDate: function () { return hb.t('js.srm.asn.col.mfgDate'); }, expiryDate: function () { return hb.t('js.srm.asn.col.expiryDate'); },
        packCnt: function () { return hb.t('js.srm.asn.col.packCnt'); }};

    /** 상태별 허용 동작 — 서버 전이 표와 같다(판정은 서버). 입고 중·다 받음은 WMS 입고가 바꾼다 */
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var NEXT = {DRAFT: ['send', 'cancel'], SENT: ['close', 'cancel'], PARTIAL_RECEIVED: ['close'], RECEIVED: ['close'],
        CLOSED: [], CANCELLED: []};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function editable() { return CAN('SAVE') && (!current || current.asn.status === 'DRAFT'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 상태 배지 + 늦음(도착 예정일 지남, danger) */
    function statusCell(a) {
        var f = document.createDocumentFragment();
        f.appendChild(srmUi.badge('asn', a.status, statusNames[a.status]));
        if (a.late) { f.appendChild(document.createTextNode(' ')); f.appendChild(srmUi.flag('danger', hb.t('js.srm.asn.late'))); }
        return f;
    }

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'keyword', 'late'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            var shown = show(r.data);
            hb.page.openDetail();
            return shown;
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 만든·고친 것은 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.asnId !== selectId)) { return openRow(selectId); }
            if (!selectId) { return show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        pickBox.hidden = true;
        var a = d ? d.asn : null;
        field('custCd').value = a ? a.custCd : '';
        field('plantCd').value = a ? a.plantCd : (field('plantCd').options.length > 1 ? field('plantCd').options[1].value : '');
        ['shipDate', 'etaDate', 'supplierRef', 'carrierMemo', 'remark'].forEach(function (n) { field(n).value = a ? a[n] || '' : ''; });
        lines = d ? d.lines.map(function (l) {
            return {key: 'k' + (++keySeq), poLineId: l.poLineId, poNo: l.poNo, poLineNo: l.poLineNo, itemCd: l.itemCd, itemNm: l.itemNm,
                unitCd: l.unitCd, qty: l.qty, supplierLotNo: l.supplierLotNo || '', mfgDate: l.mfgDate || '', expiryDate: l.expiryDate || '',
                packCnt: l.packCnt === null || l.packCnt === undefined ? '' : String(l.packCnt), remark: l.remark || '',
                recvQty: l.recvQty, remainQty: l.remainQty};
        }) : [];
        var edit = editable();
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = !edit; });
        field('custCd').disabled = !edit || !!a;              // 협력사는 만들 때만(행이 그 협력사 발주에 묶임)
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !edit; }
        var pickBtn = document.getElementById('pickBtn');
        if (pickBtn) { pickBtn.hidden = !edit; }
        document.getElementById('printBtn').hidden = !a || a.status === 'DRAFT' || a.status === 'CANCELLED';
        formMode.textContent = a ? hb.t('js.srm.asn.editing', a.asnNo) : hb.t('js.srm.asn.creating');
        var stateLine = document.getElementById('stateLine');
        stateLine.textContent = '';
        if (a) {
            var state = '';
            if (a.sentAt) { state += ' · ' + hb.t('js.srm.asn.sentBy', a.sentBy, a.sentAt.slice(0, 10)); }
            if (a.cancelReason && a.status === 'CANCELLED') { state += ' · ' + hb.t('js.srm.asn.cancelReason', a.cancelReason); }
            stateLine.appendChild(statusCell(a));
            stateLine.appendChild(document.createTextNode(state));
        }
        var allowed = a ? NEXT[a.status] || [] : [];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            b.hidden = !(allowed.indexOf(b.getAttribute('data-act')) >= 0 && CAN('SAVE'));
        });
        document.getElementById('dangerBar').hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        renderLines([]);
        hb.dirty.clear();
    }

    /** 행 — 상세 폭이 좁아 행마다 작은 폼(2열). 서버 오류(행 키 = 보낸 배열 순번)를 그 행 칸에 */
    function renderLines(errors) {
        lineBody.textContent = '';
        var edit = editable();
        var byRow = {};
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined) { (byRow[e.rowKey] = byRow[e.rowKey] || []).push(e); }
        });
        var top = (errors || []).filter(function (e) { return e.field === 'lines'; });
        document.getElementById('linesErrorBox').hidden = !top.length;
        document.getElementById('linesError').textContent = top.length ? top[0].message : '';
        if (!lines.length) { lineBody.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.asn.noLines'))); }
        lines.forEach(function (l, i) {
            var block = hb.el('div', 'hb-form');
            block.setAttribute('data-line', String(i));
            var errs = byRow[String(i)] || [];
            block.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.asn.lineHead', i + 1, l.poNo, l.poLineNo, l.itemCd, l.itemNm || '')));
            if (current && current.asn.status !== 'DRAFT') {
                block.appendChild(hb.el('p', 'hb-wide', hb.t('js.srm.asn.received', l.recvQty, l.remainQty, l.unitCd)));
            }
            var poErr = errs.filter(function (e) { return e.field === 'poLineId'; })[0];
            if (poErr) { block.appendChild(hb.el('p', 'hb-field-error hb-wide', poErr.message)); }
            function cell(name, type, attrs) {
                var lab = hb.el('label');
                lab.appendChild(hb.el('span', null, LABELS[name]() + (name === 'qty' && l.unitCd ? ' (' + l.unitCd + ')' : '')));
                var inp = hb.el('input');
                inp.type = type || 'text';
                inp.value = l[name] || '';
                inp.disabled = !edit;
                Object.keys(attrs || {}).forEach(function (k) { inp.setAttribute(k, attrs[k]); });
                var err = errs.filter(function (e) { return e.field === name; })[0];
                if (err) { inp.setAttribute('aria-invalid', 'true'); }
                inp.addEventListener('input', function () { l[name] = inp.value; hb.dirty.mark(); });
                lab.appendChild(inp);
                if (err) { lab.appendChild(hb.el('span', 'hb-field-error', err.message)); }
                block.appendChild(lab);
            }
            cell('qty', 'text', {inputmode: 'decimal', maxlength: '24'});
            cell('supplierLotNo', 'text', {maxlength: '60'});
            cell('mfgDate', 'date');
            cell('expiryDate', 'date');
            cell('packCnt', 'text', {inputmode: 'numeric', maxlength: '5'});
            if (edit) {
                var bar = hb.el('div', 'hb-toolbar hb-wide');
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.asn.removeLine'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    lines.splice(lines.indexOf(l), 1);
                    hb.dirty.mark();
                    renderLines([]);
                });
                bar.appendChild(del);
                block.appendChild(bar);
            }
            lineBody.appendChild(block);
        });
    }

    // ---------------------------------------------------------------- 발주 행 가져오기

    var pickBtn = document.getElementById('pickBtn');
    if (pickBtn) {
        pickBtn.addEventListener('click', function () {
            var cust = field('custCd').value, plant = field('plantCd').value;
            if (!cust || !plant) { notify(hb.t('js.srm.asn.pickNeedHead'), true); return; }
            var q = 'custCd=' + encodeURIComponent(cust) + '&plantCd=' + encodeURIComponent(plant)
                + (current ? '&asnId=' + current.asn.asnId : '');
            hb.busy(pickBtn, function () { return hb.api(API + '/po-lines?' + q); }).then(function (res) {
                if (!res) { return; }
                options = res.data;
                pickList.textContent = '';
                if (!options.length) { pickList.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.asn.pickNone'))); }
                options.forEach(function (o, i) {
                    var lab = hb.el('label', 'hb-check hb-wide');
                    var cb = hb.el('input');
                    cb.type = 'checkbox';
                    cb.value = String(i);
                    cb.disabled = Number(o.notifiableQty) <= 0;
                    lab.appendChild(cb);
                    lab.appendChild(hb.el('span', null, hb.t('js.srm.asn.pickLine', o.poNo, o.lineNo, o.itemCd, o.itemNm, o.openQty,
                        o.notifiedQty, o.notifiableQty, o.unitCd, o.dueDate)));
                    pickList.appendChild(lab);
                });
                pickBox.hidden = false;
            }, function (err) { notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true); });
        });
        document.getElementById('pickAddBtn').addEventListener('click', function () {
            pickList.querySelectorAll('input[type=checkbox]:checked').forEach(function (cb) {
                var o = options[Number(cb.value)];
                lines.push({key: 'k' + (++keySeq), poLineId: o.poLineId, poNo: o.poNo, poLineNo: o.lineNo, itemCd: o.itemCd, itemNm: o.itemNm,
                    unitCd: o.unitCd, qty: o.notifiableQty, supplierLotNo: '', mfgDate: '', expiryDate: '', packCnt: '', remark: ''});
            });
            pickBox.hidden = true;
            hb.dirty.mark();
            renderLines([]);
        });
    }

    // ---------------------------------------------------------------- 저장 · 동작

    function newAsn() {
        srmUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newAsn); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var body = {custCd: field('custCd').value, plantCd: field('plantCd').value, shipDate: field('shipDate').value || null,
                etaDate: field('etaDate').value || null, supplierRef: field('supplierRef').value, carrierMemo: field('carrierMemo').value,
                remark: field('remark').value, rowVer: current ? current.asn.rowVer : null,
                lines: lines.map(function (l) {
                    return {poLineId: l.poLineId, qty: l.qty, supplierLotNo: l.supplierLotNo, mfgDate: l.mfgDate || null,
                        expiryDate: l.expiryDate || null, packCnt: l.packCnt, remark: l.remark};
                })};
            var editing = current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + editing.asn.asnId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.asn.asnId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, (err.errors || []).filter(function (e) { return e.rowKey === null || e.rowKey === undefined; }));
                renderLines(err.errors);
            });
        });
    }

    var importBtn = document.getElementById('importBtn');
    if (importBtn) {
        importBtn.addEventListener('click', function () {
            hb.importDialog({templateCd: 'SRM_ASN', onDone: function () { load(); }});
        });
    }

    document.getElementById('printBtn').addEventListener('click', function () {
        if (current) { window.open('/page/srm-asn-print?asnId=' + current.asn.asnId, '_blank', 'noopener'); }
    });

    document.querySelectorAll(ACT_SEL).forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.asn.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            // 취소는 사유 필수(대화상자) — 대화상자 취소는 아무 일 없음
            (b.getAttribute('data-reason') ? srmUi.reason('js.srm.dlg.asnCancel', 'js.srm.asn.cancelLabel', 'js.srm.dlg.asnCancelOk')
                : Promise.resolve('')).then(function (reason) {
                if (reason !== null) { run(b, act, reason); }
            });
        });
    });

    function run(b, act, reason) {
        var id = current.asn.asnId;
        hb.busy(b, function () {
            return hb.api(API + '/' + id + '/' + act, {method: 'POST', body: {rowVer: current.asn.rowVer, reason: reason || null}});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            if (err.errors) { renderLines(err.errors); }
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, hb.api(API + '/plants'), hb.api(API + '/suppliers'), hb.codes('ASN_STATUS')]);
    }).then(function (r) {
        var sel = field('plantCd');
        option(sel, '', hb.t('js.common.choose'));
        r[1].data.forEach(function (p) { option(sel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        var cs = field('custCd');
        option(cs, '', hb.t('js.common.choose'));
        r[2].data.forEach(function (s) { option(cs, s.custCd, s.custCd + ' · ' + s.custNm); });
        statusNames = r[3];
        grid = hb.grid('#asnGrid', {
            progCd: 'srm-asn', id: 'main', key: 'asnId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {
                status: srmUi.TONES.asn,
                etaDate: function (v, row) { return row.late ? 'danger' : null; }   // 늦음 = 도착 예정일 칸 위험 배지(글자 = 날짜)
            },
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.asnId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
