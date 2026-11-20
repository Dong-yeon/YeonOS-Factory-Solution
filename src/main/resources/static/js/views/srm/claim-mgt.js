/*
 * 협력사 클레임 (srm-claim) — SRM 2부 R4d (docs/31 4.12.4, R4-6).
 * 조회(만든 날 기간 필수) → 클레임 표 → 상세: 머리(요구 조치·금액) + 근거 NCR(한 벌 저장, 작성 중만) + 상태 동작 + 협력사 회신(보낸 뒤).
 * 근거 NCR 은 "근거 NCR 가져오기"(QMS 계약 — 반품 처리가 있는 닫힌 NCR)에서 고른다. 다른 클레임에 든 NCR 은 고를 수 없다.
 */
(function () {
    'use strict';

    var API = '/api/srm/claims';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = document.getElementById('msg');
    var form = document.getElementById('clmForm');
    var ansForm = document.getElementById('ansForm');
    var search = document.getElementById('search');
    var formMode = document.getElementById('formMode');
    var ncrBody = document.getElementById('ncrs');
    var pickList = document.getElementById('pickList');
    var current = null;      // ClaimDetail
    var ncrs = [];           // {ncrId, ncrNo, itemCd, ncQty, unitCd}
    var candidates = [];
    var sp, grid, statusNames = {}, retStatusNames = {}, actionSel, currencySel;

    /** 상태별 허용 동작 — 서버 전이 표와 같다(판정은 서버) */
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var NEXT = {DRAFT: ['send', 'cancel'], SENT: ['cancel'], ANSWERED: ['reject', 'close'], CLOSED: [], CANCELLED: []};
    var ACT_NEED = {send: 'SAVE', reject: 'SAVE', close: 'APPROVE', cancel: 'SAVE'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 — 문서 전체로 찾으면 grid 머리 칸(같은 data-field)이 잡힌다 */
    function field(n) { return form.querySelector('[data-field="' + n + '"]') || ansForm.querySelector('[data-field="' + n + '"]'); }

    function editable() { return CAN('SAVE') && (!current || current.claim.status === 'DRAFT'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function today() { return hb.dates.todayIn(hb.session().timeZone); }

    /** QMS NCR 상태(계약 값) → 문구. 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function ncrStatus(s) {
        return {OPEN: hb.t('js.srm.claim.ncr.OPEN'), PENDING: hb.t('js.srm.claim.ncr.PENDING'), CLOSED: hb.t('js.srm.claim.ncr.CLOSED')}[s] || s;
    }

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
            if (selectId && (!sel || sel.claimId !== selectId)) { return openRow(selectId); }
            if (!selectId) { return show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function show(d) {
        current = d;
        var c = d ? d.claim : null;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(ansForm, []);
        candidates = [];
        pickList.textContent = '';
        document.getElementById('pickTrunc').hidden = true;
        document.getElementById('pickAddBtn').hidden = true;
        field('custCd').value = c ? c.custCd : '';
        actionSel.setValue(c ? c.actionCd : '');
        currencySel.setValue(c ? c.currencyCd || '' : '');
        ['title', 'requestText', 'replyDue', 'chargeAmt'].forEach(function (n) { field(n).value = c ? c[n] || '' : ''; });
        ['answerCause', 'answerAction'].forEach(function (n) { field(n).value = c ? c[n] || '' : ''; });
        field('answeredOn').value = c ? c.answeredOn || today() : '';
        ncrs = d ? d.ncrs.map(function (n) {
            return {ncrId: n.ncrId, ncrNo: n.ncrNo, itemCd: n.itemCd, ncQty: n.ncQty, unitCd: n.unitCd};
        }) : [];
        var edit = editable();
        form.querySelectorAll('input, select, textarea').forEach(function (i) { i.disabled = !edit; });
        field('custCd').disabled = !edit || !!c;              // 협력사는 만들 때만(근거 NCR 이 그 협력사 것)
        document.getElementById('pickHead').hidden = !edit;
        if (edit) {
            document.getElementById('pickFrom').value = hb.dates.add(today(), -90);
            document.getElementById('pickTo').value = today();
        }
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !edit; }
        // 회신: 보낸 뒤 보이고, 보냄·회신 받음에서만 입력
        var answering = !!c && (c.status === 'SENT' || c.status === 'ANSWERED') && CAN('SAVE');
        document.getElementById('answerBox').hidden = !c || c.status === 'DRAFT' || (c.status === 'CANCELLED' && !c.answerCause);
        ansForm.querySelectorAll('input, textarea').forEach(function (i) { i.disabled = !answering; });
        document.getElementById('answerBtn').hidden = !answering;
        // 회신 경로 — 협력사가 포털에서 직접 / 우리 쪽이 대신 입력(칸이 생기기 전 회신은 경로 없음)
        var src = document.getElementById('answerSource');
        src.hidden = !c || !c.answerCause;
        src.textContent = c && c.answerCause ? hb.t(c.answerSource === 'PORTAL' ? 'js.srm.claim.byPortal' : 'js.srm.claim.byProxy',
            c.answeredBy || '-', c.answeredOn || '') : '';
        formMode.textContent = c ? hb.t('js.srm.claim.editing', c.claimNo) : hb.t('js.srm.claim.creating');
        var stateLine = document.getElementById('stateLine');
        stateLine.textContent = '';
        if (c) {
            var state = '';
            if (c.sentAt) { state += ' · ' + hb.t('js.srm.claim.sentBy', c.sentBy, c.sentAt.slice(0, 10)); }
            if (c.closedAt) { state += ' · ' + hb.t('js.srm.claim.closedBy', c.closedBy, c.closedAt.slice(0, 10)); }
            if (c.cancelReason && c.status === 'CANCELLED') { state += ' · ' + hb.t('js.srm.claim.cancelReason', c.cancelReason); }
            stateLine.appendChild(srmUi.badge('claim', c.status, statusNames[c.status]));
            stateLine.appendChild(document.createTextNode(state));
        }
        var allowed = c ? NEXT[c.status] || [] : [];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            var act = b.getAttribute('data-act');
            b.hidden = !(allowed.indexOf(act) >= 0 && CAN(ACT_NEED[act]));
        });
        document.getElementById('dangerBar').hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        renderNcrs([]);
        renderLinks(d ? d.returns : []);
        hb.dirty.clear();
    }

    /** 같은 근거 NCR 의 반품 지시 — 번호를 누르면 반품 화면을 그 번호·만든 날로 연다 */
    function renderLinks(rows) {
        var list = document.getElementById('linkList');
        list.textContent = '';
        document.getElementById('linkBox').hidden = !rows || !rows.length;
        (rows || []).forEach(function (r) {
            var li = hb.el('li');
            var btn = hb.el('button', 'hb-link-btn', r.no);
            btn.type = 'button';
            btn.addEventListener('click', function () {
                srmUi.openProg('srm-return', hb.t('js.srm.claim.returnTab'), {keyword: r.no, from: r.createdOn, to: r.createdOn});
            });
            li.appendChild(btn);
            li.appendChild(document.createTextNode(' · ' + r.ncrNo + ' · '));
            li.appendChild(srmUi.badge('ret', r.status, retStatusNames[r.status] || r.status));
            list.appendChild(li);
        });
    }

    /** 근거 NCR 줄 — 서버 오류(행 키 = 보낸 배열 순번)를 그 줄에 */
    function renderNcrs(errors) {
        ncrBody.textContent = '';
        var edit = editable();
        var byRow = {};
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined) { (byRow[e.rowKey] = byRow[e.rowKey] || []).push(e); }
        });
        var top = (errors || []).filter(function (e) { return e.field === 'ncrIds'; });
        document.getElementById('ncrsErrorBox').hidden = !top.length;
        document.getElementById('ncrsError').textContent = top.length ? top[0].message : '';
        if (!ncrs.length) { ncrBody.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.claim.noNcrs'))); }
        ncrs.forEach(function (n, i) {
            var row = hb.el('div', 'hb-toolbar');
            row.appendChild(hb.el('span', null, n.ncQty === null ? hb.t('js.srm.claim.ncrLineNew', n.ncrNo, n.itemCd)
                : hb.t('js.srm.claim.ncrLine', n.ncrNo, n.itemCd, n.ncQty, n.unitCd)));
            (byRow[String(i)] || []).forEach(function (e) { row.appendChild(hb.el('span', 'hb-field-error', e.message)); });
            if (edit) {
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.claim.removeNcr'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    ncrs.splice(ncrs.indexOf(n), 1);
                    hb.dirty.mark();
                    renderNcrs([]);
                });
                row.appendChild(del);
            }
            ncrBody.appendChild(row);
        });
    }

    // ---------------------------------------------------------------- 근거 NCR 가져오기

    var pickBtn = document.getElementById('pickBtn');
    pickBtn.addEventListener('click', function () {
        var cust = field('custCd').value;
        if (!cust) { notify(hb.t('js.srm.return.pickNeedSupplier'), true); return; }
        var q = 'custCd=' + encodeURIComponent(cust) + '&from=' + encodeURIComponent(document.getElementById('pickFrom').value)
            + '&to=' + encodeURIComponent(document.getElementById('pickTo').value);
        hb.busy(pickBtn, function () { return hb.api(API + '/ncr-candidates?' + q); }).then(function (res) {
            if (!res) { return; }
            candidates = res.data.rows;
            document.getElementById('pickTrunc').hidden = !res.data.truncated;
            pickList.textContent = '';
            if (!candidates.length) { pickList.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.claim.pickNone'))); }
            var mine = current ? current.claim.claimNo : null;
            candidates.forEach(function (c, i) {
                var lab = hb.el('label', 'hb-check hb-wide');
                var cb = hb.el('input');
                cb.type = 'checkbox';
                cb.value = String(i);
                var chosen = ncrs.some(function (n) { return n.ncrId === c.ncrId; });
                cb.disabled = chosen || (!!c.claimNo && c.claimNo !== mine);     // 다른 살아 있는 클레임에 든 NCR
                lab.appendChild(cb);
                lab.appendChild(hb.el('span', null, hb.t('js.srm.claim.pickHead', c.ncrNo, c.itemCd)));
                pickList.appendChild(lab);
                pickList.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.claim.pickDetail', c.lotNo, c.ncQty, c.unitCd,
                    c.defectNm || '-', (c.occurredAt || '').slice(0, 10), ncrStatus(c.status))
                    + (c.claimNo && c.claimNo !== mine ? ' · ' + hb.t('js.srm.claim.pickTaken', c.claimNo) : '')));
            });
            document.getElementById('pickAddBtn').hidden = !candidates.length;
        }, function (err) { notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true); });
    });

    document.getElementById('pickAddBtn').addEventListener('click', function () {
        pickList.querySelectorAll('input[type=checkbox]:checked').forEach(function (cb) {
            var c = candidates[Number(cb.value)];
            if (!ncrs.some(function (n) { return n.ncrId === c.ncrId; })) {
                ncrs.push({ncrId: c.ncrId, ncrNo: c.ncrNo, itemCd: c.itemCd, ncQty: null, unitCd: c.unitCd});   // 수량은 저장 때 계약에서
            }
        });
        pickList.textContent = '';
        document.getElementById('pickAddBtn').hidden = true;
        hb.dirty.mark();
        renderNcrs([]);
    });

    field('custCd').addEventListener('change', function () {
        ncrs = [];                                    // 다른 협력사 — 근거를 다시 고른다
        pickList.textContent = '';
        document.getElementById('pickAddBtn').hidden = true;
        renderNcrs([]);
    });

    // ---------------------------------------------------------------- 저장 · 동작

    function newClaim() {
        srmUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newClaim); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var body = {custCd: field('custCd').value, actionCd: actionSel.value(), title: field('title').value,
                requestText: field('requestText').value, replyDue: field('replyDue').value || null, chargeAmt: field('chargeAmt').value,
                currencyCd: currencySel.value() || null, ncrIds: ncrs.map(function (n) { return n.ncrId; }),
                rowVer: current ? current.claim.rowVer : null};
            var editing = current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + editing.claim.claimId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.claim.claimId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, (err.errors || []).filter(function (e) { return e.rowKey === null || e.rowKey === undefined; }));
                renderNcrs(err.errors);
            });
        });
    }

    var answerBtn = document.getElementById('answerBtn');
    answerBtn.addEventListener('click', function () {
        if (!current) { return; }
        var id = current.claim.claimId;
        var body = {rowVer: current.claim.rowVer, answerCause: field('answerCause').value, answerAction: field('answerAction').value,
            answeredOn: field('answeredOn').value || null};
        hb.busy(answerBtn, function () { return hb.api(API + '/' + id + '/answer', {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(ansForm, err.errors || []);
        });
    });

    document.querySelectorAll(ACT_SEL).forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.claim.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            var ask = b.getAttribute('data-reason')
                ? srmUi.reason('js.srm.dlg.claimCancel', 'js.srm.claim.cancelLabel', 'js.srm.dlg.claimCancelOk')
                : act === 'send' ? srmUi.ask('js.srm.dlg.claimSend', null, 'js.srm.dlg.claimSendOk').then(function (ok) { return ok ? '' : null; })
                    : Promise.resolve('');
            ask.then(function (reason) {
                if (reason !== null) { run(b, act, reason); }
            });
        });
    });

    function run(b, act, reason) {
        var id = current.claim.claimId;
        hb.busy(b, function () {
            return hb.api(API + '/' + id + '/' + act, {method: 'POST', body: {rowVer: current.claim.rowVer, reason: reason || null}});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            if (err.errors) { renderNcrs(err.errors); }
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    hb.dirty.watch(ansForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        actionSel = hb.select(field('actionCd'));
        currencySel = hb.select(field('currencyCd'));
        return Promise.all([sp.ready, actionSel.ready, currencySel.ready, hb.api(API + '/suppliers'), hb.codes('CLAIM_STATUS'),
            hb.codes('RETURN_STATUS')]);
    }).then(function (r) {
        var cs = field('custCd');
        option(cs, '', hb.t('js.common.choose'));
        r[3].data.forEach(function (s) { option(cs, s.custCd, s.custCd + ' · ' + s.custNm); });
        statusNames = r[4];
        retStatusNames = r[5];
        grid = hb.grid('#clmGrid', {
            progCd: 'srm-claim', id: 'main', key: 'claimId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {status: srmUi.TONES.claim},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.claimId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
