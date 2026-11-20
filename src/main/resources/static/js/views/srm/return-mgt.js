/*
 * 반품 지시 (srm-return) — SRM 2부 R4c (docs/31 4.12.4).
 * 조회(만든 날 기간 필수) → 반품 표 → 상세: 협력사·공장 + 근거(반품 처리 줄 하나) + 수량·교체 요구·발주 행 + 상태 동작.
 * 근거는 "반품 대상 가져오기"(QMS 계약 — 닫힌 NCR 의 반품 처리 줄)에서만 고르고, 만든 뒤엔 바꾸지 않는다.
 * 반품 대상 목록은 보기용(합하지 않음) — 잘렸으면(truncated) 기간을 줄이라고 알린다.
 */
(function () {
    'use strict';

    var API = '/api/srm/returns';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = document.getElementById('msg');
    var form = document.getElementById('retForm');
    var qtyForm = document.getElementById('qtyForm');
    var search = document.getElementById('search');
    var formMode = document.getElementById('formMode');
    var pickList = document.getElementById('pickList');
    var current = null;      // ReturnRow
    var basis = null;        // {ncrId, ncrNo, lineNo, lotNo, itemId, itemCd, qty, unitCd, defectNm}
    var candidates = [];
    var sp, grid, statusNames = {}, claimStatusNames = {};

    /** 상태별 허용 동작 — 서버 전이 표와 같다(판정은 서버). 출고는 WMS 반품 출고가 바꾼다 */
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var NEXT = {DRAFT: ['confirm', 'cancel'], CONFIRMED: ['cancel'], SHIPPED: [], CANCELLED: []};
    var ACT_NEED = {confirm: 'APPROVE', cancel: 'SAVE'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 — 문서 전체로 찾으면 grid 머리 칸(같은 data-field)이 잡힌다 */
    function field(n) { return form.querySelector('[data-field="' + n + '"]') || qtyForm.querySelector('[data-field="' + n + '"]'); }

    function editable() { return CAN('SAVE') && (!current || current.status === 'DRAFT'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function today() { return hb.dates.todayIn(hb.session().timeZone); }

    // ---------------------------------------------------------------- 목록

    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            var shown = show(r.data);
            hb.page.openDetail();
            return shown;
        }, function (err) { notify(err.message, true); });
    }

    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.returnId !== selectId)) { return openRow(selectId); }
            if (!selectId) { return show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function show(r) {
        current = r;
        basis = r ? {ncrId: r.ncrId, ncrNo: r.ncrNo, lineNo: r.ncrLineNo, lotNo: r.lotNo, itemId: r.itemId, itemCd: r.itemCd, unitCd: r.unitCd,
            qty: null, defectNm: null} : null;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(qtyForm, []);
        basisError(null);
        candidates = [];
        pickList.textContent = '';
        document.getElementById('pickTrunc').hidden = true;
        field('custCd').value = r ? r.custCd : '';
        field('plantCd').value = r ? r.plantCd : (field('plantCd').options.length > 1 ? field('plantCd').options[1].value : '');
        field('qty').value = r ? r.qty : '';
        field('replaceYn').checked = !!r && r.replaceYn === 'Y';
        field('remark').value = r ? r.remark || '' : '';
        var edit = editable();
        [form, qtyForm].forEach(function (f) { f.querySelectorAll('input, select').forEach(function (i) { i.disabled = !edit; }); });
        field('custCd').disabled = !edit || !!r;              // 협력사·근거는 만들 때만
        document.getElementById('pickHead').hidden = !edit || !!r;
        if (!r) {
            document.getElementById('pickFrom').value = hb.dates.add(today(), -90);
            document.getElementById('pickTo').value = today();
        }
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !edit; }
        formMode.textContent = r ? hb.t('js.srm.return.editing', r.returnNo) : hb.t('js.srm.return.creating');
        var stateLine = document.getElementById('stateLine');
        stateLine.textContent = '';
        if (r) {
            var state = '';
            if (r.confirmedAt) { state += ' · ' + hb.t('js.srm.return.confirmedBy', r.confirmedBy, r.confirmedAt.slice(0, 10)); }
            if (r.shippedAt) { state += ' · ' + hb.t('js.srm.return.shippedRef', r.shippedAt.slice(0, 10), r.shipRef || '-'); }
            // 교체 요구로 출고됨 — 입고를 되돌렸으니 그 발주 행이 교체품을 기다린다(열린 수량)
            if (r.status === 'SHIPPED' && r.replaceYn === 'Y' && r.poNo) {
                state += ' · ' + hb.t('js.srm.return.awaitReplace', r.poNo, r.poLineNo, r.poOpenQty, r.poUnitCd);
            }
            if (r.cancelReason && r.status === 'CANCELLED') { state += ' · ' + hb.t('js.srm.return.cancelReason', r.cancelReason); }
            stateLine.appendChild(srmUi.badge('ret', r.status, statusNames[r.status]));
            stateLine.appendChild(document.createTextNode(state));
        }
        var allowed = r ? NEXT[r.status] || [] : [];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            var act = b.getAttribute('data-act');
            b.hidden = !(allowed.indexOf(act) >= 0 && CAN(ACT_NEED[act]));
        });
        document.getElementById('dangerBar').hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        renderBasis();
        renderLinks(r ? r.claims : []);
        hb.dirty.clear();
        return loadPoLines(r ? r.poLineId : null);
    }

    /** 같은 근거 NCR 의 클레임 — 번호를 누르면 클레임 화면을 그 번호·만든 날로 연다 */
    function renderLinks(rows) {
        var list = document.getElementById('linkList');
        list.textContent = '';
        document.getElementById('linkBox').hidden = !rows || !rows.length;
        (rows || []).forEach(function (c) {
            var li = hb.el('li');
            var btn = hb.el('button', 'hb-link-btn', c.no);
            btn.type = 'button';
            btn.addEventListener('click', function () {
                srmUi.openProg('srm-claim', hb.t('js.srm.return.claimTab'), {keyword: c.no, from: c.createdOn, to: c.createdOn});
            });
            li.appendChild(btn);
            li.appendChild(document.createTextNode(' · ' + c.ncrNo + ' · '));
            li.appendChild(srmUi.badge('claim', c.status, claimStatusNames[c.status] || c.status));
            list.appendChild(li);
        });
    }

    function renderBasis() {
        var line = document.getElementById('basisLine');
        if (!basis) {
            line.textContent = hb.t('js.srm.return.noBasis');
        } else if (basis.qty === null) {          // 저장된 반품 — 처리 줄 수량은 계약에서 다시 읽을 때만(확정·수정이 본다)
            line.textContent = hb.t('js.srm.return.basisSaved', basis.ncrNo, basis.lineNo, basis.lotNo, basis.itemCd);
        } else {
            line.textContent = hb.t('js.srm.return.basisLine', basis.ncrNo, basis.lineNo, basis.lotNo, basis.itemCd, basis.qty, basis.unitCd);
        }
        document.getElementById('qtyLabel').textContent = basis ? hb.t('js.srm.return.qtyLabel', basis.unitCd) : hb.t('js.srm.return.qtyPlain');
    }

    function basisError(text) {
        document.getElementById('basisErrorBox').hidden = !text;
        document.getElementById('basisError').textContent = text || '';
    }

    /** 교체품 받을 발주 행 — 협력사·품목이 정해져야 고른다(받은 것이 있는 SRM 발주 행) */
    function loadPoLines(selected) {
        var sel = field('poLineId');
        sel.textContent = '';
        option(sel, '', hb.t('js.srm.return.noPoLine'));
        var cust = field('custCd').value;
        if (!cust || !basis) { return Promise.resolve(); }
        return hb.api(API + '/po-lines?custCd=' + encodeURIComponent(cust) + '&itemId=' + basis.itemId).then(function (res) {
            res.data.forEach(function (o) {
                option(sel, String(o.poLineId), hb.t('js.srm.return.poLineOpt', o.poNo, o.lineNo, o.poDate, o.recvQty, o.unitCd));
            });
            sel.value = selected ? String(selected) : '';
        }, function (err) { notify(err.message, true); });
    }

    // ---------------------------------------------------------------- 반품 대상 가져오기

    var pickBtn = document.getElementById('pickBtn');
    pickBtn.addEventListener('click', function () {
        var cust = field('custCd').value;
        if (!cust) { notify(hb.t('js.srm.return.pickNeedSupplier'), true); return; }
        var q = 'custCd=' + encodeURIComponent(cust) + '&from=' + encodeURIComponent(document.getElementById('pickFrom').value)
            + '&to=' + encodeURIComponent(document.getElementById('pickTo').value);
        hb.busy(pickBtn, function () { return hb.api(API + '/candidates?' + q); }).then(function (res) {
            if (!res) { return; }
            candidates = res.data.rows;
            document.getElementById('pickTrunc').hidden = !res.data.truncated;
            pickList.textContent = '';
            if (!candidates.length) { pickList.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.return.pickNone'))); }
            candidates.forEach(function (c, i) {
                var lab = hb.el('label', 'hb-check hb-wide');
                var rb = hb.el('input');
                rb.type = 'radio';
                rb.name = 'pick';
                rb.value = String(i);
                rb.disabled = !!c.returnNo;          // 이 처리 줄엔 살아 있는 반품이 이미 있음
                lab.appendChild(rb);
                lab.appendChild(hb.el('span', null, hb.t('js.srm.return.pickHead', c.ncrNo, c.lineNo, c.qty, c.unitCd)));
                // 자세한 글은 줄 바꿈되는 문단으로(좁은 화면에서 체크 칸 글자는 잘린다)
                var detail = hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.return.pickDetail', c.lotNo, c.supplierLotNo || '-', c.itemCd,
                    c.defectNm || c.defectCd || '-', (c.closedAt || '').slice(0, 10))
                    + (c.returnNo ? ' · ' + hb.t('js.srm.return.pickTaken', c.returnNo) : ''));
                rb.addEventListener('change', function () {
                    basis = {ncrId: c.ncrId, ncrNo: c.ncrNo, lineNo: c.lineNo, lotNo: c.lotNo, itemId: c.itemId, itemCd: c.itemCd,
                        unitCd: c.unitCd, qty: c.qty, defectNm: c.defectNm};
                    field('qty').value = c.qty;
                    basisError(null);
                    hb.dirty.mark();
                    renderBasis();
                    loadPoLines(null);
                });
                pickList.appendChild(lab);
                pickList.appendChild(detail);
            });
        }, function (err) { notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true); });
    });

    field('custCd').addEventListener('change', function () {
        basis = null;                                 // 다른 협력사 — 근거를 다시 고른다
        candidates = [];
        pickList.textContent = '';
        document.getElementById('pickTrunc').hidden = true;
        renderBasis();
        loadPoLines(null);
    });

    // ---------------------------------------------------------------- 저장 · 동작

    function newReturn() {
        srmUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newReturn); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var po = field('poLineId').value;
            var body = {custCd: field('custCd').value, plantCd: field('plantCd').value, ncrId: basis ? basis.ncrId : null,
                lineNo: basis ? basis.lineNo : null, qty: field('qty').value, replaceYn: field('replaceYn').checked ? 'Y' : 'N',
                poLineId: po ? Number(po) : null, remark: field('remark').value, rowVer: current ? current.rowVer : null};
            var editing = current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + editing.returnId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.returnId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                var errs = err.errors || [];
                hb.showFieldErrors(form, errs);
                hb.showFieldErrors(qtyForm, errs);
                var b = errs.filter(function (e) { return e.field === 'ncrId' || e.field === 'lineNo'; })[0];
                basisError(b ? b.message : null);
            });
        });
    }

    document.querySelectorAll(ACT_SEL).forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.return.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            (b.getAttribute('data-reason') ? srmUi.reason('js.srm.dlg.returnCancel', 'js.srm.return.cancelLabel', 'js.srm.dlg.returnCancelOk')
                : srmUi.ask('js.srm.dlg.returnConfirm', null, 'js.srm.dlg.returnConfirmOk').then(function (ok) { return ok ? '' : null; }))
                .then(function (reason) {
                    if (reason !== null) { run(b, act, reason); }
                });
        });
    });

    function run(b, act, reason) {
        var id = current.returnId;
        hb.busy(b, function () {
            return hb.api(API + '/' + id + '/' + act, {method: 'POST', body: {rowVer: current.rowVer, reason: reason || null}});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    hb.dirty.watch(qtyForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, hb.api(API + '/plants'), hb.api(API + '/suppliers'), hb.codes('RETURN_STATUS'),
            hb.codes('CLAIM_STATUS')]);
    }).then(function (r) {
        var sel = field('plantCd');
        option(sel, '', hb.t('js.common.choose'));
        r[1].data.forEach(function (p) { option(sel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        var cs = field('custCd');
        option(cs, '', hb.t('js.common.choose'));
        r[2].data.forEach(function (s) { option(cs, s.custCd, s.custCd + ' · ' + s.custNm); });
        statusNames = r[3];
        claimStatusNames = r[4];
        grid = hb.grid('#retGrid', {
            progCd: 'srm-return', id: 'main', key: 'returnId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {status: srmUi.TONES.ret},
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.returnId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
