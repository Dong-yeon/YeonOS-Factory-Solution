/*
 * 구매요청 (srm-pr) — SRM 1부 S2 (docs/31 D5).
 * 조회(기간 필수) → 요청 표 → 상세: 머리 폼 + 품목 행(한 벌 저장, 작성 중만 편집) + 상태 동작 버튼(허용된 다음 동작만).
 * 행 오류는 서버가 행 키(배열 순번)로 돌려준다 → 그 행 칸에 표시.
 */
(function () {
    'use strict';

    var API = '/api/srm/prs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = document.getElementById('msg');
    var form = document.getElementById('prForm');
    var search = document.getElementById('search');
    var lineBody = document.getElementById('lines');
    var formMode = document.getElementById('formMode');
    var current = null;      // PrDetail
    var lines = [];          // {key, prLineId, itemCd, qty, unitCd, needDate, prefCustCd, remark, itemNm}
    var keySeq = 0, sp, grid, statusNames = {};
    /** 행 칸 이름(접근성) — 키는 글자 그대로(I18nKeyGuardTest) */
    var LABELS = {itemCd: function () { return hb.t('js.srm.pr.col.itemCd'); }, qty: function () { return hb.t('js.srm.pr.col.qty'); },
        needDate: function () { return hb.t('js.srm.pr.col.needDate'); }, prefCustCd: function () { return hb.t('js.srm.pr.col.prefCustCd'); }};

    /** 상태별 허용 동작 — 서버 전이 표와 같다(판정은 서버) */
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var NEXT = {DRAFT: ['request', 'cancel'], REQUESTED: ['withdraw', 'approve', 'reject', 'cancel'],
        REJECTED: ['reopen'], APPROVED: ['close', 'cancel'], CLOSED: [], CANCELLED: []};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function editable() { return CAN('SAVE') && (!current || current.pr.status === 'DRAFT'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

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
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 만든·고친 것은 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.prId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var p = d ? d.pr : null;
        field('plantCd').value = p ? p.plantCd : (field('plantCd').options.length > 1 ? field('plantCd').options[1].value : '');
        field('deptNm').value = p ? p.deptNm || '' : '';
        field('needDate').value = p ? p.needDate || '' : '';
        field('reason').value = p ? p.reason || '' : '';
        lines = d ? d.lines.map(function (l) {
            return {key: 'k' + (++keySeq), prLineId: l.prLineId, itemCd: l.itemCd, itemNm: l.itemNm, qty: l.qty, unitCd: l.unitCd,
                needDate: l.needDate || '', prefCustCd: l.prefCustCd || '', remark: l.remark || '', status: l.lineStatus};
        }) : [];
        if (!d && CAN('SAVE')) { addLine(); }
        var edit = editable();
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = !edit; });
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !edit; }
        var addBtn = document.getElementById('addLineBtn');
        if (addBtn) { addBtn.hidden = !edit; }
        formMode.textContent = p ? hb.t('js.srm.pr.editing', p.prNo) : hb.t('js.srm.pr.creating');
        var stateLine = document.getElementById('stateLine');
        stateLine.textContent = '';
        if (p) {
            var state = hb.t('js.srm.pr.requestedBy', p.reqUserNm || p.reqLoginId, p.prDate);
            if (p.decidedByNm) { state += ' · ' + hb.t('js.srm.pr.decidedBy', p.decidedByNm); }
            if (p.rejectReason && p.status !== 'APPROVED') { state += ' · ' + hb.t('js.srm.pr.rejectReason', p.rejectReason); }
            if (p.cancelReason && p.status === 'CANCELLED') { state += ' · ' + hb.t('js.srm.pr.cancelReason', p.cancelReason); }
            stateLine.appendChild(srmUi.withBadge(null, srmUi.badge('pr', p.status, statusNames[p.status]), state));
        }
        var allowed = p ? NEXT[p.status] || [] : [];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            b.hidden = !(allowed.indexOf(b.getAttribute('data-act')) >= 0 && CAN(b.getAttribute('data-need')));
        });
        document.getElementById('dangerBar').hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        renderLines([]);
        hb.dirty.clear();
    }

    function addLine() {
        lines.push({key: 'k' + (++keySeq), prLineId: null, itemCd: '', qty: '', unitCd: '', needDate: '', prefCustCd: '', remark: ''});
    }

    /** 행 표 — 서버 오류(행 키 = 보낸 배열 순번)를 그 행 칸에 표시 */
    function renderLines(errors) {
        lineBody.textContent = '';
        var edit = editable();
        var byRow = {};
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined) { (byRow[e.rowKey] = byRow[e.rowKey] || []).push(e); }
        });
        var box = document.getElementById('linesError');
        var top = (errors || []).filter(function (e) { return e.field === 'lines'; });
        document.getElementById('linesErrorBox').hidden = !top.length;
        box.textContent = top.length ? top[0].message : '';
        if (!lines.length) { lineBody.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.pr.noLines'))); }
        // 상세 폭(기본 360px)에서 표는 칸이 너무 좁다 → 행마다 작은 폼(2열)
        lines.forEach(function (l, i) {
            var block = hb.el('div', 'hb-form' + (l.status && l.status !== 'OPEN' ? ' hb-tree-dim' : ''));
            block.setAttribute('data-line', String(i));
            var errs = byRow[String(i)] || [];
            var head = hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.pr.lineHead', i + 1) + (l.itemNm ? ' · ' + l.itemNm : ''));
            block.appendChild(head);
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
                return inp;
            }
            itemLookup(cell('itemCd', 'text', {list: 'itemList', maxlength: '40'}));
            cell('qty', 'text', {inputmode: 'decimal', maxlength: '24'});
            cell('needDate', 'date');
            cell('prefCustCd', 'text', {list: 'supList', maxlength: '30'});
            if (edit) {
                var bar = hb.el('div', 'hb-toolbar hb-wide');
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.pr.removeLine'));
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

    var addLineBtn = document.getElementById('addLineBtn');
    if (addLineBtn) {
        addLineBtn.addEventListener('click', function () {
            addLine();
            hb.dirty.mark();
            renderLines([]);
            var inputs = lineBody.querySelectorAll('[data-line]:last-child input');
            if (inputs.length) { inputs[0].focus(); }
        });
    }

    function newPr() {
        srmUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newPr); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var body = {plantCd: field('plantCd').value, deptNm: field('deptNm').value, needDate: field('needDate').value || null,
                reason: field('reason').value, rowVer: current ? current.pr.rowVer : null,
                lines: lines.map(function (l) {
                    return {prLineId: l.prLineId, itemCd: l.itemCd, qty: l.qty, needDate: l.needDate || null,
                        prefCustCd: l.prefCustCd, remark: l.remark};
                })};
            var editing = current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + editing.pr.prId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.pr.prId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, (err.errors || []).filter(function (e) { return e.rowKey === null || e.rowKey === undefined; }));
                renderLines(err.errors);
            });
        });
    }

    // ---------------------------------------------------------------- 상태 동작

    /** 사유를 받는 동작의 대화상자(제목·칸·버튼) — 취소(대화상자 닫기)는 아무 일 없음 */
    var REASON_DLG = {reject: ['js.srm.dlg.prReject', 'js.srm.pr.rejectLabel', 'js.srm.dlg.prRejectOk'],
        cancel: ['js.srm.dlg.prCancel', 'js.srm.pr.cancelLabel', 'js.srm.dlg.prCancelOk']};

    document.querySelectorAll(ACT_SEL).forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.pr.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            var dlg = b.getAttribute('data-reason') ? REASON_DLG[act] : null;
            (dlg ? srmUi.reason(dlg[0], dlg[1], dlg[2]) : Promise.resolve('')).then(function (reason) {
                if (reason !== null) { run(b, act, reason); }
            });
        });
    });

    function run(b, act, reason) {
        var id = current.pr.prId;
        hb.busy(b, function () {
            return hb.api(API + '/' + id + '/' + act, {method: 'POST', body: {rowVer: current.pr.rowVer, reason: reason || null}});
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
        return Promise.all([sp.ready, hb.api(API + '/plants'), hb.api(API + '/suppliers'), hb.codes('PR_STATUS')]);
    }).then(function (r) {
        var sel = field('plantCd');
        option(sel, '', hb.t('js.common.choose'));
        r[1].data.forEach(function (p) { option(sel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        var dl = document.getElementById('supList');
        r[2].data.forEach(function (s) {
            var o = hb.el('option', null, s.custNm);
            o.value = s.custCd;
            dl.appendChild(o);
        });
        statusNames = r[3];
        grid = hb.grid('#prGrid', {
            progCd: 'srm-pr', id: 'main', key: 'prId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {status: srmUi.TONES.pr},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.prId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
