/*
 * 발주 관리 (srm-po) — SRM 1부 S3 (docs/31 D1·D3·D6).
 * 조회(기간 필수) → 발주 표 → 상세: 머리 폼 + 품목 행(한 벌, 작성 중만, 행마다 2열 작은 폼) + 구매요청에서 가져오기
 * + 상태 동작(허용된 것만) + 결재 상신 / 결재선(내 차례면 승인·반려, 내가 상신했으면 회수 — 판정은 결재 API) + 발행 이력.
 */
(function () {
    'use strict';

    var API = '/api/srm/pos';
    var APR = '/api/platform/approvals';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = document.getElementById('msg');
    var form = document.getElementById('poForm');
    var search = document.getElementById('search');
    var lineBox = document.getElementById('lines');
    var formMode = document.getElementById('formMode');
    var current = null;      // PoDetail
    var lines = [];
    var keySeq = 0, sp, grid, statusNames = {}, paySel;
    /** 행 칸 이름 — 키는 글자 그대로(I18nKeyGuardTest) */
    var LABELS = {itemCd: function () { return hb.t('js.srm.po.col.itemCd'); }, qty: function () { return hb.t('js.srm.po.col.qty'); },
        unitPrice: function () { return hb.t('js.srm.po.col.unitPrice'); }, dueDate: function () { return hb.t('js.srm.po.col.dueDate'); }};
    /** 상태별 동작 — 서버 전이 표와 같다(판정은 서버). confirm 은 결재가 필요 없을 때만 보인다 */
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var NEXT = {DRAFT: ['confirm', 'cancel'], IN_APPROVAL: [], APPROVED: ['issue', 'revise', 'cancel'],
        ISSUED: ['revise', 'close', 'cancel'], ACKED: ['revise', 'close', 'cancel'],
        PARTIAL_RECEIVED: ['close'], RECEIVED: ['close'], CLOSED: [], CANCELLED: []};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function editable() { return CAN('SAVE') && (!current || current.po.status === 'DRAFT'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function money(v, cur) { return v === null || v === undefined ? '' : v + ' ' + (cur || ''); }

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
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
            if (selectId && (!sel || sel.poId !== selectId)) { return openRow(selectId); }
            if (!selectId) { return show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function loadWhs(plantCd, keep) {
        var sel = field('whCd');
        sel.textContent = '';
        option(sel, '', hb.t('js.srm.po.noWh'));
        if (!plantCd) { return Promise.resolve(); }
        return hb.latest('whs', hb.api(API + '/whs?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            res.data.forEach(function (w) { option(sel, w.nodeCd, w.nodeCd + ' · ' + w.nodeNm); });
            sel.value = keep || '';
        }, function () { /* 창고 목록 실패는 저장 때 서버가 다시 본다 */ });
    }

    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var p = d ? d.po : null;
        field('custCd').value = p ? p.custCd : '';
        field('custCd').readOnly = !!p;
        var plantSel = field('plantCd');
        plantSel.value = p ? p.plantCd : (plantSel.options.length > 1 ? plantSel.options[1].value : '');
        paySel.setValue(p ? p.payTermsCd || '' : '');
        field('remark').value = p ? p.remark || '' : '';
        lines = d ? d.lines.map(function (l) {
            return {key: 'k' + (++keySeq), poLineId: l.poLineId, itemCd: l.itemCd, itemNm: l.itemNm, qty: l.qty, unitCd: l.unitCd,
                unitPrice: l.unitPrice, amt: l.amt, dueDate: l.dueDate || '', prLineId: l.prLineId, prNo: l.prNo,
                remark: l.remark || '', flags: [l.leadShort ? hb.t('js.srm.po.leadShort') : null, l.moqShort ? hb.t('js.srm.po.moqShort') : null,
                    l.multOff ? hb.t('js.srm.po.multOff') : null].filter(Boolean)};
        }) : [];
        var edit = editable();
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = !edit; });
        if (!p && CAN('SAVE')) { field('custCd').disabled = false; }
        ['saveBtn', 'addLineBtn', 'fromPrBtn'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !edit; }
        });
        document.getElementById('prPick').hidden = true;
        formMode.textContent = p ? hb.t('js.srm.po.editing', p.poNo, p.rev) : hb.t('js.srm.po.creating');
        var stateLine = document.getElementById('stateLine');
        stateLine.textContent = '';
        if (p) {
            var state = ' · ' + hb.t('js.srm.po.total', money(p.totalAmt, p.currencyCd));
            if (p.source === 'ERP') { state += ' · ' + hb.t('js.srm.po.fromErp', p.erpPoNo || ''); }
            if (p.status === 'CANCELLED' && p.cancelReason) { state += ' · ' + hb.t('js.srm.po.cancelled', p.cancelReason); }
            stateLine.appendChild(srmUi.badge('po', p.status, statusNames[p.status]));
            stateLine.appendChild(document.createTextNode(state));
            // 거래 정지·보류 협력사 — 발행이 막히거나 확인이 필요한 상태(톤 = 협력사 거래 상태 표)
            if (p.tradeStatus === 'BLOCKED' || p.tradeStatus === 'HOLD') {
                stateLine.appendChild(document.createTextNode(' '));
                stateLine.appendChild(srmUi.badge('trade', p.tradeStatus, hb.t(p.tradeStatus === 'BLOCKED' ? 'js.srm.po.blocked' : 'js.srm.po.hold')));
            }
        }
        var allowed = p ? (NEXT[p.status] || []).filter(function (a) {
            return (a !== 'confirm' || !p.approvalRequired) && (a !== 'revise' || p.source !== 'ERP');     // ERP 발주는 ERP 에서 바꾼다(D1)
        }) : [];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            b.hidden = !(allowed.indexOf(b.getAttribute('data-act')) >= 0 && CAN(b.getAttribute('data-need')));
        });
        document.getElementById('dangerBar').hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        var reviseBtn = document.querySelector('#actionBar [data-act="revise"]');
        reviseBtn.textContent = p && p.status === 'APPROVED' ? hb.t('js.srm.po.redo') : hb.t('js.srm.po.revise');
        var docBar = document.getElementById('docBar');
        if (docBar) { docBar.hidden = !p; }
        document.getElementById('submitBox').hidden = !(p && p.status === 'DRAFT' && CAN('SAVE'));
        document.getElementById('approvalNeed').textContent = p && p.approvalRequired ? hb.t('js.srm.po.needApproval') : hb.t('js.srm.po.noNeedApproval');
        showApproval(p);
        var revBox = document.getElementById('revBox');
        var revList = document.getElementById('revList');
        revList.textContent = '';
        revBox.hidden = !(d && d.revs.length);
        (d ? d.revs : []).forEach(function (r) {
            revList.appendChild(hb.el('li', null, hb.t('js.srm.po.revLine', r.rev, hb.fmt.datetime(r.issuedAt), r.issuedBy)));
        });
        renderLines([]);
        hb.dirty.clear();
        return loadWhs(plantSel.value, p ? p.whCd : '');
    }

    function addLine(init) {
        lines.push(Object.assign({key: 'k' + (++keySeq), poLineId: null, itemCd: '', qty: '', unitPrice: '', dueDate: '', prLineId: null,
            remark: '', flags: []}, init || {}));
    }

    function renderLines(errors) {
        lineBox.textContent = '';
        var edit = editable();
        var byRow = {};
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined) { (byRow[e.rowKey] = byRow[e.rowKey] || []).push(e); }
        });
        var box = document.getElementById('linesError');
        var top = (errors || []).filter(function (e) { return e.field === 'lines'; });
        document.getElementById('linesErrorBox').hidden = !top.length;
        box.textContent = top.length ? top[0].message : '';
        if (!lines.length) { lineBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.po.noLines'))); }
        lines.forEach(function (l, i) {
            var block = hb.el('div', 'hb-form');
            block.setAttribute('data-line', String(i));
            var errs = byRow[String(i)] || [];
            var head = hb.t('js.srm.po.lineHead', i + 1) + (l.itemNm ? ' · ' + l.itemNm : '') + (l.prNo ? ' · ' + l.prNo : '')
                + (l.amt ? ' · ' + hb.t('js.srm.po.amt', l.amt) : '');
            block.appendChild(hb.el('p', 'hb-muted hb-wide', head));
            if (l.flags && l.flags.length) { block.appendChild(hb.el('p', 'hb-wide hb-field-error', l.flags.join(' · '))); }
            function cell(name, type, attrs) {
                var lab = hb.el('label');
                lab.appendChild(hb.el('span', null, LABELS[name]() + (name === 'qty' && l.unitCd ? ' (' + l.unitCd + ')' : '')));
                var inp = hb.el('input');
                inp.type = type || 'text';
                inp.value = l[name] || '';
                inp.disabled = !edit || (name === 'itemCd' && !!l.prLineId);
                Object.keys(attrs || {}).forEach(function (k) { inp.setAttribute(k, attrs[k]); });
                var err = errs.filter(function (e) { return e.field === name || (name === 'itemCd' && e.field === 'prLineId'); })[0];
                if (err) { inp.setAttribute('aria-invalid', 'true'); }
                inp.addEventListener('input', function () { l[name] = inp.value; hb.dirty.mark(); });
                lab.appendChild(inp);
                if (err) { lab.appendChild(hb.el('span', 'hb-field-error', err.message)); }
                block.appendChild(lab);
                return inp;
            }
            itemLookup(cell('itemCd', 'text', {list: 'itemList', maxlength: '40'}));
            cell('qty', 'text', {inputmode: 'decimal', maxlength: '24'});
            cell('unitPrice', 'text', {inputmode: 'decimal', maxlength: '24', placeholder: hb.t('js.srm.po.pricePlaceholder')});
            cell('dueDate', 'date');
            if (edit) {
                var bar = hb.el('div', 'hb-toolbar hb-wide');
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.po.removeLine'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    lines.splice(lines.indexOf(l), 1);
                    hb.dirty.mark();
                    renderLines([]);
                });
                bar.appendChild(del);
                block.appendChild(bar);
            }
            lineBox.appendChild(block);
        });
    }

    var itemTimer = null;
    function itemLookup(input) {
        input.addEventListener('input', function () {
            window.clearTimeout(itemTimer);
            itemTimer = window.setTimeout(function () {
                var k = input.value.trim();
                if (k.length < 2) { return; }
                hb.latest('items', hb.api(API + '/items?keyword=' + encodeURIComponent(k))).then(function (res) {
                    var dl = document.getElementById('itemList');
                    dl.textContent = '';
                    res.data.forEach(function (i) {
                        var o = hb.el('option', null, i.itemNm + ' (' + i.unitCd + ')');
                        o.value = i.itemCd;
                        dl.appendChild(o);
                    });
                }, function () { /* 찾기 실패는 입력을 막지 않는다 */ });
            }, 250);
        });
    }

    field('plantCd').addEventListener('change', function () { loadWhs(field('plantCd').value, ''); });

    var addLineBtn = document.getElementById('addLineBtn');
    if (addLineBtn) {
        addLineBtn.addEventListener('click', function () {
            addLine();
            hb.dirty.mark();
            renderLines([]);
            var inputs = lineBox.querySelectorAll('[data-line]:last-child input');
            if (inputs.length) { inputs[0].focus(); }
        });
    }

    // ---------------------------------------------------------------- 구매요청에서 가져오기

    var fromPrBtn = document.getElementById('fromPrBtn');
    if (fromPrBtn) {
        fromPrBtn.addEventListener('click', function () {
            var box = document.getElementById('prPick');
            box.hidden = !box.hidden;
            if (box.hidden) { return; }
            var body = document.querySelector('#prLines tbody');
            hb.latest('prLines', hb.api(API + '/pr-lines')).then(function (res) {
                body.textContent = '';
                var taken = lines.map(function (l) { return l.prLineId; });
                if (!res.data.length) {
                    var tr0 = hb.el('tr');
                    var td0 = hb.el('td', 'hb-muted', hb.t('js.srm.po.noPrLines'));
                    td0.colSpan = 5;
                    tr0.appendChild(td0);
                    body.appendChild(tr0);
                }
                res.data.forEach(function (r) {
                    var tr = hb.el('tr');
                    var td = hb.el('td');
                    var cb = hb.el('input');
                    cb.type = 'checkbox';
                    cb.disabled = taken.indexOf(r.prLineId) >= 0;
                    cb.setAttribute('aria-label', r.prNo + ' ' + r.itemCd);
                    cb.prLine = r;
                    td.appendChild(cb);
                    tr.appendChild(td);
                    [r.prNo, r.itemCd + ' · ' + r.itemNm, r.remainQty + ' ' + r.unitCd, r.needDate || '']
                        .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                    body.appendChild(tr);
                });
            }, function (err) { notify(err.message, true); });
        });
        document.getElementById('prAddBtn').addEventListener('click', function () {
            var picked = Array.prototype.filter.call(document.querySelectorAll('#prLines input[type=checkbox]'), function (c) {
                return c.checked && !c.disabled;
            });
            if (!picked.length) { notify(hb.t('js.srm.po.pickPr'), true); return; }
            picked.forEach(function (c) {
                var r = c.prLine;
                addLine({itemCd: r.itemCd, itemNm: r.itemNm, qty: r.remainQty, unitCd: r.unitCd, dueDate: r.needDate || '',
                    prLineId: r.prLineId, prNo: r.prNo});
                if (!field('custCd').value && r.prefCustCd) { field('custCd').value = r.prefCustCd; }
            });
            lines = lines.filter(function (l) { return l.itemCd || l.prLineId; });
            hb.dirty.mark();
            document.getElementById('prPick').hidden = true;
            renderLines([]);
        });
    }

    // ---------------------------------------------------------------- 저장

    function newPo() {
        srmUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            addLine();
            renderLines([]);
            hb.page.openDetail();
            field('custCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newPo); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = current;
            var body = {custCd: field('custCd').value, plantCd: field('plantCd').value, whCd: field('whCd').value,
                payTermsCd: paySel.value(), remark: field('remark').value, rowVer: editing ? editing.po.rowVer : null,
                lines: lines.map(function (l) {
                    return {poLineId: l.poLineId, itemCd: l.itemCd, qty: l.qty, unitPrice: l.unitPrice || null,
                        dueDate: l.dueDate || null, prLineId: l.prLineId, remark: l.remark};
                })};
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + editing.po.poId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.po.poId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, (err.errors || []).filter(function (e) { return e.rowKey === null || e.rowKey === undefined; }));
                renderLines(err.errors);
            });
        });
    }

    // ---------------------------------------------------------------- 발주서 (S4)

    var printBtn = document.getElementById('printBtn');
    if (printBtn) {
        printBtn.addEventListener('click', function () {
            if (current) { window.open('/page/srm-po-print?poId=' + current.po.poId, '_blank', 'noopener'); }
        });
        document.getElementById('excelBtn').addEventListener('click', function () {
            if (current) { window.location.href = API + '/' + current.po.poId + '/excel'; }
        });
    }

    // ---------------------------------------------------------------- 상태 동작 · 결재

    function refresh(id) {
        return load(id).then(function () { notify(hb.t('js.common.saved')); });
    }

    function failed(err) {
        notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
        if (err.errors) { renderLines(err.errors); }
    }

    /** 동작 전 묻기 — 취소는 사유(필수), 발행한 발주의 개정은 확인. 대화상자 취소 = null(아무 일 없음) */
    function before(act) {
        if (act === 'cancel') { return srmUi.reason('js.srm.dlg.poCancel', 'js.srm.po.cancelLabel', 'js.srm.dlg.poCancelOk'); }
        if (act === 'revise' && current.po.status !== 'APPROVED') {
            return srmUi.ask('js.srm.dlg.poRevise', hb.t('js.srm.po.confirmRevise', current.po.rev + 1), 'js.srm.dlg.poReviseOk', false)
                .then(function (yes) { return yes ? '' : null; });
        }
        return Promise.resolve('');
    }

    document.querySelectorAll(ACT_SEL).forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.po.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            before(act).then(function (reason) {
                if (reason === null || !current) { return; }
                var id = current.po.poId;
                hb.busy(b, function () {
                    return hb.api(API + '/' + id + '/' + act, {method: 'POST', body: {rowVer: current.po.rowVer, reason: reason || null}});
                }).then(function (res) { return res ? refresh(id) : null; }, failed);
            });
        });
    });

    var submitBtn = document.getElementById('submitBtn');
    submitBtn.addEventListener('click', function () {
        if (!current) { return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.srm.po.saveFirst'), true); return; }
        var approvers = document.getElementById('approvers').value.split(',').map(function (s) { return s.trim(); })
            .filter(function (s) { return s; });
        var id = current.po.poId;
        hb.busy(submitBtn, function () {
            return hb.api(API + '/' + id + '/submit', {method: 'POST', body: {rowVer: current.po.rowVer, approvers: approvers}});
        }).then(function (res) { return res ? refresh(id) : null; }, failed);
    });

    function showApproval(p) {
        var box = document.getElementById('aprBox');
        var list = document.getElementById('aprLines');
        list.textContent = '';
        var inApr = p && p.status === 'IN_APPROVAL' && p.aprRequestId;
        box.hidden = !inApr;
        document.getElementById('aprApproveBtn').hidden = !(inApr && p.myTurn);
        document.getElementById('aprRejectBtn').hidden = !(inApr && p.myTurn);
        document.getElementById('aprWithdrawBtn').hidden = !(inApr && p.myRequest);
        document.getElementById('aprComment').value = '';
        if (!inApr) { return; }
        hb.latest('apr', hb.api(APR + '/' + p.aprRequestId)).then(function (res) {
            res.data.lines.forEach(function (l) {
                list.appendChild(hb.el('li', l.status === 'CURRENT' ? null : 'hb-muted',
                    l.approverNm + ' · ' + l.status + (l.comment ? ' · ' + l.comment : '')));
            });
        }, function () { list.appendChild(hb.el('li', 'hb-muted', hb.t('js.srm.po.aprHidden'))); });
    }

    [['aprApproveBtn', 'approve'], ['aprRejectBtn', 'reject'], ['aprWithdrawBtn', 'withdraw']].forEach(function (x) {
        var b = document.getElementById(x[0]);
        b.addEventListener('click', function () {
            if (!current || !current.po.aprRequestId) { return; }
            var id = current.po.poId;
            var comment = document.getElementById('aprComment').value;
            hb.busy(b, function () {
                return hb.api(APR + '/' + current.po.aprRequestId + '/' + x[1], {method: 'POST', body: {comment: comment || null}});
            }).then(function (res) { return res ? refresh(id) : null; }, failed);
        });
    });

    // ---------------------------------------------------------------- ERP 발주 받기 (S3b — 정책 SRM_PO_SOURCE=ERP)

    var erpImportBtn = document.getElementById('erpImportBtn');
    if (erpImportBtn) {
        erpImportBtn.addEventListener('click', function () {
            hb.importDialog({templateCd: 'SRM_ERP_PO', onDone: function () { load(); }});
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        paySel = hb.select(field('payTermsCd'));
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, paySel.ready, hb.api(API + '/plants'), hb.api(API + '/suppliers'), hb.api(API + '/approvers'),
            hb.codes('PO_STATUS')]);
    }).then(function (r) {
        var sel = field('plantCd');
        option(sel, '', hb.t('js.common.choose'));
        r[2].data.forEach(function (p) { option(sel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        var dl = document.getElementById('supList');
        r[3].data.forEach(function (s) {
            var o = hb.el('option', null, s.custNm);
            o.value = s.custCd;
            dl.appendChild(o);
        });
        var al = document.getElementById('approverList');
        r[4].data.forEach(function (u) {
            var o = hb.el('option', null, u.userNm);
            o.value = u.loginId;
            al.appendChild(o);
        });
        statusNames = r[5];
        grid = hb.grid('#poGrid', {
            progCd: 'srm-po', id: 'main', key: 'poId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {status: srmUi.TONES.po},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.poId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
