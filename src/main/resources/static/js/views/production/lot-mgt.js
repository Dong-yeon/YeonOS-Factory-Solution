/* LOT 추적 (prd-lot) — P2 4단계 (docs/25 4.5). LOT 표 → 상세(정보·분할·라벨 바꿈·보류 근거별 해제 — 4.19) + 정·역추적 들여쓰기 표.
   병합은 표에서 같은 품목 LOT 을 두 개 이상 고른 뒤. 수량은 서버가 글자로 보낸다(Number 로 바꾸지 않는다). */
(function () {
    'use strict';

    var API = '/api/production/lots';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var traceBody = document.querySelector('#trace tbody');
    var lotInfo = document.getElementById('lotInfo');
    var lotFacts = document.getElementById('lotFacts');
    var traceSection = document.getElementById('traceSection');
    var traceInfo = document.getElementById('traceInfo');
    var receiveForm = document.getElementById('receiveForm');
    var splitBtn = document.getElementById('splitBtn');
    var relabelBtn = document.getElementById('relabelBtn');
    var reworkBtn = document.getElementById('reworkBtn');
    var repairBtn = document.getElementById('repairBtn');
    var reportLink = document.getElementById('reportLink');
    var holdBtn = document.getElementById('holdBtn');
    var mergeBtn = document.getElementById('mergeBtn');
    var current = null, sp = null, grid = null;
    var holdNames = {};   // 보류 사유 코드 이름(사용 중지 포함, docs/68 Q1)
    var ORIGIN = {RECEIVED: 'js.prdLot.o.RECEIVED', PRODUCED: 'js.prdLot.o.PRODUCED', SPLIT: 'js.prdLot.o.SPLIT',
        MERGE: 'js.prdLot.o.MERGE', RELABEL: 'js.prdLot.o.RELABEL', REWORK: 'js.prdLot.o.REWORK', REPAIR: 'js.prdLot.o.REPAIR', UNIT: 'js.prdLot.o.UNIT'};
    var REL = {CONSUME: 'js.prdLot.r.CONSUME', SPLIT: 'js.prdLot.r.SPLIT', MERGE: 'js.prdLot.r.MERGE', RELABEL: 'js.prdLot.r.RELABEL',
        REWORK: 'js.prdLot.r.REWORK', REPAIR: 'js.prdLot.r.REPAIR', SORT: 'js.prdLot.r.SORT'};
    /** 보류 근거 출처(docs/25 4.19) — 화면은 MANUAL·MES_CORRECT 만 풀 수 있다(QMS 근거는 QMS 처분으로) */
    var HOLD_SRC = {MANUAL: 'js.prdLot.src.MANUAL', MES_CORRECT: 'js.prdLot.src.MES_CORRECT', QMS_NCR: 'js.prdLot.src.QMS_NCR',
        QMS_CONTAIN: 'js.prdLot.src.QMS_CONTAIN', QMS_SPC: 'js.prdLot.src.QMS_SPC'};
    var SCREEN_SRC = ['MANUAL', 'MES_CORRECT'];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** 상태 — 보류는 배지(hold:Y, 확인 필요), 그 밖은 글자 */
    function state(l) {
        if (l.holdYn === 'Y') { return mesUi.badge('hold', 'Y', hb.t('js.prdLot.held')); }
        return l.balance === '0' ? hb.t('js.prdLot.empty') : hb.t('js.prdLot.active');
    }

    /** 칸 하나 — 값이 요소(배지)면 그대로, 아니면 글자 */
    function cell(tag, cls, x) {
        var td = hb.el(tag, cls);
        if (x instanceof Node) { td.appendChild(x); } else { td.textContent = x === null || x === undefined ? '' : String(x); }
        return td;
    }

    /** 사유 입력(안내 문구에 값이 들어가 라벨로) — 취소 = null */
    function askReason(titleKey, label, okKey, required) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: label, ok: hb.t(okKey), required: required !== false, maxLength: 500});
    }

    // ------------------------------------------------------------------ 목록

    /** 조회 조건(검색 패널) — 빈 값은 보내지 않는다 */
    function params() {
        var v = sp.values(), q = {};
        ['from', 'to', 'itemCd', 'keyword', 'origin', 'holdOnly'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기(페이지·체크 그대로) + 그 LOT 상세 — 지금 페이지에 없으면(새 LOT 등) 상세만 다시 연다 */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        return g.reload(false, selectId).then(function () {
            var sel = g.selected();
            if (selectId && (!sel || sel.lotId !== selectId)) { return open(selectId); }
        });
    }

    /** 병합 버튼 = 체크한 LOT(grid checkable, 쪽을 넘겨도 남음). 보류·잔량 0 LOT 은 체크칸이 비활성(checkableRow, D64) — 막는 것은 서버 */
    function syncMerge(rows) {
        if (!mergeBtn) { return; }
        mergeBtn.disabled = rows.length < 2;
        mergeBtn.textContent = rows.length ? hb.t('js.prdLot.mergeN', rows.length) : hb.t('js.prdLot.merge');
    }

    // ------------------------------------------------------------------ 상세·추적

    function open(id) {
        return hb.latest('lot', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { notify(err.message, true); });
    }

    function fact(dt, dd) {
        lotFacts.appendChild(hb.el('dt', null, dt));
        lotFacts.appendChild(cell('dd', null, dd || '-'));
    }

    function show(d) {
        current = d;
        if (receiveForm) { receiveForm.hidden = true; }
        var l = d.lot;
        lotInfo.textContent = l.lotNo;
        lotFacts.textContent = '';
        lotFacts.hidden = false;
        fact(hb.t('js.prdLot.f.item'), l.itemCd + ' · ' + l.itemNm);
        fact(hb.t('js.prdLot.f.origin'), hb.t(ORIGIN[l.origin] || 'js.prdLot.o.RECEIVED'));
        fact(hb.t('js.prdLot.f.qty'), qtyText(l.balance) + ' / ' + qtyText(l.qtyCreated) + ' ' + l.unitCd);
        fact(hb.t('js.prdLot.f.source'), l.woNo ? l.woNo : (l.supplierCd ? l.supplierCd + ' · ' + (l.supplierLotNo || '') : ''));
        fact(hb.t('js.prdLot.f.at'), hb.fmt.datetime(l.occurredAt) + ' · ' + l.createdBy);
        fact(hb.t('js.prdLot.f.state'), state(l));
        (d.holds || []).forEach(holdFact);
        var usable = l.holdYn !== 'Y' && l.balance !== '0';
        if (splitBtn) { splitBtn.hidden = !usable; }
        if (relabelBtn) { relabelBtn.hidden = !usable; }
        // 재작업·수리는 보류 LOT 도 된다(보류의 출구, 7b D18)
        [reworkBtn, repairBtn].forEach(function (b) { if (b) { b.hidden = l.balance === '0'; } });
        reportLink.hidden = false;
        reportLink.href = '/page/prd-lot-report?lotNo=' + encodeURIComponent(l.lotNo);
        // [보류] = 수동 근거 추가 — 이미 있으면 숨김(다른 근거가 있어도 수동 보류는 따로 건다)
        if (holdBtn) {
            holdBtn.hidden = (d.holds || []).some(function (h) { return h.source === 'MANUAL'; });
            /** @type {HTMLElement} */ (holdBtn.parentElement).hidden = holdBtn.hidden; // 떨어진 줄도 같이(빈 줄 여백 방지)
        }
        traceSection.hidden = false;
        loadTrace();
    }

    /** 보류 사유 글자 — 코드가 있으면 "코드 이름: 메모"(메모가 코드 이름 그대로면 이름만), 없으면 글자(미분류·자동 근거) */
    function holdText(h) {
        if (!h.reasonCd) { return h.reason; }
        var name = holdNames[h.reasonCd] || h.reasonCd;
        return h.reason && h.reason !== name ? name + ': ' + h.reason : name;
    }

    /** 살아 있는 보류 근거 한 줄 — 출처·사유·시각·사람 + 화면에서 풀 수 있으면 [해제] */
    function holdFact(h) {
        var dd = hb.el('dd', 'hb-wrap', hb.t(HOLD_SRC[h.source] || 'js.prdLot.src.MANUAL') + ' — ' + holdText(h) + ' · '
            + hb.fmt.datetime(h.heldAt) + ' · ' + h.heldBy + ' ');
        if (CAN_SAVE && SCREEN_SRC.indexOf(h.source) >= 0) {
            var b = hb.el('button', 'hb-btn-small', hb.t('js.prdLot.unhold'));
            b.type = 'button';
            b.addEventListener('click', function () { releaseHold(b, h); });
            dd.appendChild(b);
        } else if (SCREEN_SRC.indexOf(h.source) < 0) {
            dd.appendChild(hb.el('span', 'hb-hint', hb.t('js.prdLot.qmsOnly')));
        }
        lotFacts.appendChild(hb.el('dt', null, hb.t('js.prdLot.f.hold')));
        lotFacts.appendChild(dd);
    }

    function releaseHold(btn, h) {
        askReason('js.mes.dlg.unhold', hb.t('js.prdLot.unholdPrompt', current.lot.lotNo, h.reason), 'js.mes.dlg.unholdOk')
            .then(function (reason) {
                if (reason === null) { return; }
                act(btn, API + '/' + current.lot.lotId + '/holds/' + h.holdId + '/release', 'POST', {reason: reason, rowVer: current.lot.rowVer});
            });
    }

    function dir() { return document.querySelector('#dirSeg input:checked').value; }

    function loadTrace() {
        if (!current) { return; }
        hb.latest('trace', hb.api(API + '/' + current.lot.lotId + '/trace?dir=' + dir())).then(function (res) {
            var t = res.data;
            traceBody.textContent = '';
            traceInfo.textContent = t.rows.length
                ? hb.t(t.dir === 'BACK' ? 'js.prdLot.backInfo' : 'js.prdLot.fwdInfo', t.rows.length)
                    + (t.truncated ? ' ' + hb.t('js.prdLot.truncated') : '')
                : hb.t(t.dir === 'BACK' ? 'js.prdLot.noBack' : 'js.prdLot.noFwd');
            t.rows.forEach(function (r) {
                var tr = hb.el('tr', r.holdYn === 'Y' ? 'hb-tree-dim' : null);
                var name = hb.el('td', 'hb-wrap', (t.dir === 'BACK' ? '← ' : '→ ') + r.lotNo + (r.cycle ? ' ' + hb.t('js.prdLot.cycle') : ''));
                name.style.paddingLeft = (r.lvl - 1) * 16 + 8 + 'px';      // CSSOM — CSP 인라인 스타일 아님
                tr.appendChild(name);
                tr.appendChild(hb.el('td', 'hb-wrap', r.itemCd + ' · ' + r.itemNm));
                tr.appendChild(hb.el('td', null, hb.t(REL[r.relType] || 'js.prdLot.r.CONSUME')));
                tr.appendChild(hb.el('td', 'hb-num', qtyText(r.qty) + ' ' + (r.unitCd || '')));
                tr.appendChild(hb.el('td', 'hb-wrap', r.woNo || r.supplierLotNo || ''));
                tr.addEventListener('click', function () { open(r.lotId); });
                traceBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }
    document.querySelectorAll('#dirSeg input').forEach(function (i) { i.addEventListener('change', loadTrace); });

    // ------------------------------------------------------------------ 동작

    function act(btn, url, method, body, done) {
        hb.busy(btn, function () { return hb.api(url, {method: method, body: body}); }).then(function (res) {
            if (!res) { return; }
            return load(done ? done(res) : current.lot.lotId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) { notify(errText(err), true); });
    }

    if (splitBtn) {
        splitBtn.addEventListener('click', function () {
            // 여러 수(쉼표)라 숫자 칸이 아니라 글자로 — 수량 검증은 서버(칸 오류)
            hb.dialog.prompt({title: hb.t('js.mes.dlg.split'), label: hb.t('js.prdLot.splitPrompt', current.lot.balance, current.lot.unitCd),
                ok: hb.t('js.mes.dlg.splitOk'), required: true, maxLength: 500}).then(function (raw) {
                if (raw === null) { return; }
                var qtys = raw.split(/[\s,]+/).filter(function (x) { return x; });
                act(splitBtn, API + '/' + current.lot.lotId + '/split', 'POST', {qtys: qtys});
            });
        });
    }
    /** LOT 단위 재작업·수리 → 새 LOT(출처 = 종류). 수량을 비우면 잔량 전부 */
    function reworkLot(btn, kind) {
        var lot = current.lot;
        var titleKey = kind === 'REWORK' ? 'js.mes.dlg.rework' : 'js.mes.dlg.repair';
        // 수량은 단위 자릿수를 화면이 모르므로 글자로(비우면 잔량 전부 — 서버가 자릿수·잔량 검증)
        hb.dialog.prompt({title: hb.t(titleKey), label: kind === 'REWORK' ? hb.t('js.prdLot.reworkQty', lot.lotNo, lot.balance, lot.unitCd)
            : hb.t('js.prdLot.repairQty', lot.lotNo, lot.balance, lot.unitCd), value: lot.balance, ok: hb.t('js.mes.dlg.next'),
            required: false, maxLength: 30}).then(function (qty) {
            if (qty === null) { return; }
            return askReason(titleKey, hb.t('js.prdLot.reworkReason'), titleKey).then(function (reason) {
                if (reason === null) { return; }
                act(btn, API + '/' + lot.lotId + '/rework', 'POST', {kind: kind, qty: qty.trim(), reason: reason}, function (res) { return res.data; });
            });
        });
    }
    if (reworkBtn) { reworkBtn.addEventListener('click', function () { reworkLot(reworkBtn, 'REWORK'); }); }
    if (repairBtn) { repairBtn.addEventListener('click', function () { reworkLot(repairBtn, 'REPAIR'); }); }

    if (relabelBtn) {
        relabelBtn.addEventListener('click', function () {
            askReason('js.mes.dlg.relabel', hb.t('js.prdLot.relabelPrompt', current.lot.lotNo), 'js.mes.dlg.relabel').then(function (reason) {
                if (reason === null) { return; }
                act(relabelBtn, API + '/' + current.lot.lotId + '/relabel', 'POST', {reason: reason}, function (res) { return res.data; });
            });
        });
    }
    if (holdBtn) {
        holdBtn.addEventListener('click', function () {
            // 사유 코드(필수, 집계용) + 메모(선택) — docs/68 Q1
            hb.dialog.reasonCode({title: hb.t('js.mes.dlg.hold'), message: hb.t('js.prdLot.holdPrompt', current.lot.lotNo),
                code: 'LOT_HOLD_REASON', codeLabel: hb.t('js.prdLot.holdReasonCd'), label: hb.t('js.common.memo'),
                ok: hb.t('js.mes.dlg.holdOk'), danger: true, maxLength: 500}).then(function (r) {
                if (r === null) { return; }
                act(holdBtn, API + '/' + current.lot.lotId + '/hold', 'PUT',
                    {holdYn: 'Y', reasonCd: r.code, reason: r.memo || null, rowVer: current.lot.rowVer});
            });
        });
    }
    if (mergeBtn) {
        mergeBtn.addEventListener('click', function () {
            if (!grid) { return; }
            var g = grid;
            var lots = g.getChecked();
            askReason('js.mes.dlg.merge', hb.t('js.prdLot.mergePrompt', lots.map(function (l) { return l.lotNo; }).join(', ')),
                'js.mes.dlg.merge', false).then(function (reason) {
                if (reason === null) { return; }
                act(mergeBtn, API + '/merge', 'POST', {parts: lots.map(function (l) { return {lotId: l.lotId}; }), reason: reason},
                    function (res) { g.clearChecked(); return res.data; });   // 병합한 LOT 은 잔량 0 — 체크를 비운다
            });
        });
    }

    var receiveBtn = document.getElementById('receiveBtn');
    if (receiveBtn) {
        receiveBtn.addEventListener('click', function () {
            current = null;
            lotInfo.textContent = hb.t('js.prdLot.receiving');
            lotFacts.hidden = true;
            traceSection.hidden = true;
            [splitBtn, relabelBtn, holdBtn].forEach(function (b) { if (b) { b.hidden = true; } });
            receiveForm.hidden = false;
            hb.showFieldErrors(receiveForm, []);
            receiveForm.querySelectorAll('input').forEach(function (i) { i.value = ''; });
            hb.dirty.clear(receiveForm);
            hb.page.openDetail();
            receiveForm.querySelector('[data-field="itemCd"]').focus();
        });
        document.getElementById('receiveSaveBtn').addEventListener('click', function () {
            var b = {};
            ['itemCd', 'qty', 'supplierCd', 'supplierLotNo', 'remark'].forEach(function (n) {
                b[n] = receiveForm.querySelector('[data-field="' + n + '"]').value.trim();
            });
            hb.showFieldErrors(receiveForm, []);
            var btn = document.getElementById('receiveSaveBtn');
            hb.busy(btn, function () { return hb.api(API + '/receive', {method: 'POST', body: b}); }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear(receiveForm);
                return load(res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(receiveForm, err.errors);
            });
        });
        hb.dirty.watch(receiveForm);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codeNames('LOT_HOLD_REASON').then(function (m) { holdNames = m; })]);
    }).then(function () {
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            var g = grid;
            return mesUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });   // 새 조회 = 체크 비움(grid)
        }});
        return sp.ready;
    }).then(function () {
        // 주 목록 = hb.grid(UI 단계 3 ①, D60 갱신) — 병합 고르기 = 행 체크박스(checkable), 보류는 배지, 보류·잔량 0 행 흐리게
        grid = hb.grid('#lotGrid', {
            progCd: PROG, id: 'main', key: 'lotId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            checkable: CAN_SAVE && !!mergeBtn,
            checkableRow: function (row) { return row.state === 'ACTIVE'; },   // 보류·잔량 0 은 병합 못 함(서버도 LOT_HOLD·EMPTY_LOT)
            onCheck: syncMerge,
            format: {state: function (v) { return v === 'HELD' ? hb.t('js.prdLot.held') : v === 'EMPTY' ? hb.t('js.prdLot.empty') : hb.t('js.prdLot.active'); }},
            status: {state: function (v) { return v === 'HELD' ? 'warning' : null; }},   // 보류만 배지(확인 필요), 그 밖은 글자(4.29)
            rowClass: function (row) { return row.state !== 'ACTIVE' ? 'hb-tree-dim' : null; },
            beforeSelect: function () { return mesUi.leaveOk(); },
            onSelect: function (row) { open(row.lotId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
