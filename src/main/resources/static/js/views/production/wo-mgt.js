/* 작업지시 관리 (prd-wo) — P2 3단계 (docs/25 4.4). 조회 → 지시 표 → 상세(폼 + 발행 뒤 공정·자재 소요 스냅샷).
   품목·공장·계획 시작일이 바뀌면 그 기준의 확정 라우팅·BOM·진행 중 수주 행을 다시 받아 선택지로 쓴다.
   7a(docs/25 4.9): 진행 뒤 공정별 완료판정(보존식 미리보기 → 확인)·재작업/수리 처리, 지시 완료·마감. */
(function () {
    'use strict';

    var API = '/api/production/wos';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var form = document.getElementById('woForm');
    var procBody = document.querySelector('#procs tbody');
    var bomBody = document.querySelector('#boms tbody');
    var formMode = document.getElementById('formMode');
    var woStatus = document.getElementById('woStatus');
    var unitInfo = document.getElementById('unitInfo');
    var snapSection = document.getElementById('snapSection');
    var releaseBtn = document.getElementById('releaseBtn');
    var cancelBtn = document.getElementById('cancelBtn');
    var completeBtn = document.getElementById('completeBtn');
    var closeWoBtn = document.getElementById('closeWoBtn');
    var doneSection = document.getElementById('doneSection');
    var checkBody = document.querySelector('#closeChecks tbody');
    var reworkForm = document.getElementById('reworkForm');
    var current = null, sp = null, grid = null, equips = {}, optTimer = null, completion = null, reworkProc = null;
    var PST = {WAIT: 'js.pop.st.WAIT', READY: 'js.pop.st.READY', RUN: 'js.pop.st.RUN', PAUSE: 'js.pop.st.PAUSE', DONE: 'js.prdWo.pst.DONE'};
    var ST = {PLANNED: 'js.prdWo.st.PLANNED', RELEASED: 'js.prdWo.st.RELEASED', IN_PROGRESS: 'js.prdWo.st.IN_PROGRESS',
        DONE: 'js.prdWo.st.DONE', CLOSED: 'js.prdWo.st.CLOSED', CANCELLED: 'js.prdWo.st.CANCELLED'};
    var LT = {NORMAL: 'js.prdWo.lt.NORMAL', BYPRODUCT: 'js.prdWo.lt.BYPRODUCT'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function stText(s) { return hb.t(ST[s] || 'js.prdWo.st.PLANNED'); }

    /** 서버가 글자로 보낸 수량에 천 단위 쉼표만(Number 로 바꾸지 않는다) */
    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** 저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다 → Promise<boolean> */
    function confirmLeave() {
        return mesUi.leaveOk();
    }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value === null || value === undefined ? '' : String(value);
        sel.appendChild(o);
    }

    // ------------------------------------------------------------------ 목록

    /** 조회 조건(검색 패널) — 빈 값은 보내지 않는다 */
    function params() {
        var v = sp.values(), q = {};
        ['from', 'to', 'plantCd', 'status', 'itemCd', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기(페이지 그대로) + 그 지시 상세 — 지금 페이지에 없으면(새 지시 등) 상세만 다시 연다 */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        return grid.reload(false, selectId).then(function () {
            var sel = grid ? grid.selected() : null;
            if (selectId && (!sel || sel.woId !== selectId)) { return open(selectId); }
        });
    }

    function open(id) {
        return hb.latest('wo', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { notify(err.message, true); });
    }

    // ------------------------------------------------------------------ 상세

    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var w = d ? d.wo : null;
        var planned = !w || w.status === 'PLANNED';
        var open = planned || w.status === 'RELEASED' || w.status === 'IN_PROGRESS';
        field('plantCd').value = w ? w.plantCd : (field('plantCd').value || '');
        field('itemCd').value = w ? w.itemCd : '';
        field('planQty').value = w ? w.planQty : '';
        field('planStart').value = w ? w.planStart : hb.dates.today();
        field('planEnd').value = w ? w.planEnd : '';
        field('priority').value = w ? String(w.priority) : '5';
        field('remark').value = w ? (w.remark || '') : '';
        // 발행 뒤에는 공장·품목·수량·기준정보 고정, 끝난 지시는 전부 읽기 전용
        ['plantCd', 'itemCd', 'planQty', 'routingId', 'bomId', 'orderLineId'].forEach(function (n) {
            field(n).disabled = !CAN_SAVE || !planned || (!!w && (n === 'plantCd' || n === 'itemCd'));
        });
        ['planStart', 'planEnd', 'priority', 'remark'].forEach(function (n) { field(n).disabled = !CAN_SAVE || !open; });
        woStatus.textContent = '';
        if (w) {
            woStatus.appendChild(mesUi.badge('wo', w.status, stText(w.status)));
            if (w.cancelReason) { woStatus.appendChild(document.createTextNode(' ' + w.cancelReason)); }
        }
        formMode.textContent = w ? hb.t('js.prdWo.editing', w.woNo) : hb.t('js.prdWo.creating');
        if (releaseBtn) { releaseBtn.hidden = !w || !planned; }
        if (cancelBtn) {
            cancelBtn.hidden = !w || (w.status !== 'PLANNED' && w.status !== 'RELEASED');
            /** @type {HTMLElement} */ (cancelBtn.parentElement).hidden = cancelBtn.hidden; // 떨어진 줄도 같이(빈 줄 여백 방지)
        }
        if (completeBtn) { completeBtn.hidden = !w || w.status !== 'IN_PROGRESS'; }
        if (closeWoBtn) { closeWoBtn.hidden = !w || w.status !== 'DONE'; }
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !open; }
        fillRefs(w);
        renderSnapshot(d);
        loadCompletion(w);
        hb.dirty.clear();
    }

    // ------------------------------------------------------------------ 완료판정·재작업 처리 (7a)

    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    function loadCompletion(w) {
        completion = null;
        checkBody.textContent = '';
        if (reworkForm) { reworkForm.hidden = true; }
        var shown = !!w && (w.status === 'IN_PROGRESS' || w.status === 'DONE' || w.status === 'CLOSED');
        doneSection.hidden = !shown;
        if (!shown) { return Promise.resolve(); }
        var woId = w.woId;
        return hb.latest('completion', hb.api(API + '/' + woId + '/completion')).then(function (res) {
            if (!current || current.wo.woId !== woId) { return; }
            completion = res.data;
            renderCompletion(res.data);
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function renderCompletion(c) {
        document.getElementById('doneInfo').textContent = hb.t('js.prdWo.receiptInfo', qtyText(c.lastGoodQty), qtyText(c.receivedQty),
            qtyText(c.unreceivedQty), c.unitCd);
        var canAct = CAN_SAVE && c.status === 'IN_PROGRESS';
        c.procs.forEach(function (p) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, String(p.opSeq)));
            tr.appendChild(hb.el('td', 'hb-wrap', p.procCd + ' · ' + p.procNm));
            tr.appendChild(hb.el('td', null, hb.t(PST[p.status] || 'js.pop.st.WAIT')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(p.inputQty) + (p.firstOp ? ' *' : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(p.goodQty) + (p.dispGoodQty !== '0' ? ' (+' + qtyText(p.dispGoodQty) + ')' : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(p.scrapQty)));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(p.reworkQty) + (p.reworkWait !== '0' ? ' (' + qtyText(p.reworkWait) + ')' : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(p.repairQty) + (p.repairWait !== '0' ? ' (' + qtyText(p.repairWait) + ')' : '')));
            tr.appendChild(hb.el('td', 'hb-num', p.firstOp ? '—' : qtyText(p.diffQty) + ' (±' + qtyText(p.tolQty) + ')'));
            var act = hb.el('td');
            if (p.status === 'DONE') {
                act.textContent = hb.t('js.prdWo.closed');
            } else {
                act.appendChild(hb.el('span', p.problems.length ? 'hb-muted' : null, p.problems.length
                    ? p.problems.map(problemText).join(', ') : hb.t('js.prdWo.canClose')));
                // 다른 모듈 경고(CloseNotice, docs/25 4.46) — 막지 않는다, 보여만
                if (p.notices && p.notices.length) {
                    act.appendChild(hb.el('div', 'hb-warn', noticeText(p.notices)));
                }
                if (canAct && (p.reworkWait !== '0' || p.repairWait !== '0')) {
                    var rb = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.prdWo.dispose'));
                    rb.type = 'button';
                    rb.addEventListener('click', function () { openRework(p); });
                    act.appendChild(rb);
                }
                if (canAct && !p.problems.length) {
                    var cb = hb.el('button', 'hb-btn-small', hb.t('js.prdWo.closeProc'));
                    cb.type = 'button';
                    cb.addEventListener('click', function () { closeProc(p, cb); });
                    act.appendChild(cb);
                }
            }
            tr.appendChild(act);
            checkBody.appendChild(tr);
        });
    }

    /** 막는 이유 코드 → 문구 (키는 리터럴 — I18nKeyGuardTest) */
    function problemText(code) {
        switch (code) {
            case 'STATE': return hb.t('js.prdWo.problem.STATE');
            case 'PREV_OPEN': return hb.t('js.prdWo.problem.PREV_OPEN');
            case 'PENDING': return hb.t('js.prdWo.problem.PENDING');
            case 'NO_RESULT': return hb.t('js.prdWo.problem.NO_RESULT');
            case 'BALANCE': return hb.t('js.prdWo.problem.BALANCE');
            case 'LOT_HOLD': return hb.t('js.prdWo.problem.LOT_HOLD');
            default: return code;
        }
    }

    function noticeText(list) {
        return hb.t('js.prdWo.notice', list.map(function (n) { return n.message; }).join(' / '));
    }

    /** 요청 id — 같은 처리를 다시 보내도 한 번만(서버 client_req_id 멱등) */
    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    function closeProc(p, btn) {
        var woId = current.wo.woId;
        var text = p.firstOp ? hb.t('js.prdWo.closeConfirmFirst', p.opSeq, p.procNm, qtyText(p.outputQty), p.unitCd)
            : hb.t('js.prdWo.closeConfirm', p.opSeq, p.procNm, qtyText(p.inputQty), qtyText(p.outputQty), qtyText(p.diffQty), p.tolPct);
        mesUi.ask('js.mes.dlg.procClose', text, 'js.mes.dlg.procCloseOk').then(function (ok) {
            if (!ok) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/procs/' + p.woProcId + '/close', {method: 'POST', body: {rowVer: p.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                var n = res.data && res.data.notices && res.data.notices.length ? ' — ' + noticeText(res.data.notices) : '';
                return open(woId).then(function () { notify(hb.t('js.prdWo.procClosed', p.opSeq) + n); });
            }, function (err) { notify(errText(err), true); });
        });
    }

    function rfield(n) { return reworkForm.querySelector('[data-field="' + n + '"]'); }

    function openRework(p) {
        if (!reworkForm) { return; }
        reworkProc = p;
        hb.showFieldErrors(reworkForm, []);
        document.getElementById('reworkTitle').textContent = hb.t('js.prdWo.reworkFor', p.opSeq, p.procNm,
            qtyText(p.reworkWait), qtyText(p.repairWait), p.unitCd);
        rfield('kind').value = p.reworkWait !== '0' ? 'REWORK' : 'REPAIR';
        rfield('goodQty').value = '';
        rfield('scrapQty').value = '';
        rfield('reason').value = '';
        reworkForm.hidden = false;
        rfield('goodQty').focus();
    }

    if (reworkForm) {
        var reworkSaveBtn = document.getElementById('reworkSaveBtn');
        document.getElementById('reworkCancelBtn').addEventListener('click', function () { reworkForm.hidden = true; });
        reworkSaveBtn.addEventListener('click', function () {
            var p = reworkProc;
            if (!p || !current) { return; }
            var woId = current.wo.woId;
            hb.showFieldErrors(reworkForm, []);
            var body = {clientReqId: reqId(),
                kind: rfield('kind').value, goodQty: rfield('goodQty').value.trim(), scrapQty: rfield('scrapQty').value.trim(),
                reason: rfield('reason').value};
            hb.busy(reworkSaveBtn, function () {
                return hb.api(API + '/procs/' + p.woProcId + '/rework', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                reworkForm.hidden = true;
                return open(woId).then(function () {
                    notify(res.data.lotNo ? hb.t('js.prdWo.reworkDoneLot', res.data.lotNo) : hb.t('js.prdWo.reworkDone'));
                });
            }, function (err) {
                notify(errText(err), true);
                hb.showFieldErrors(reworkForm, err.errors);
            });
        });
    }

    if (completeBtn) {
        completeBtn.addEventListener('click', function () {
            if (!current) { return; }
            mesUi.ask('js.mes.dlg.woComplete', hb.t('js.prdWo.completeConfirm', current.wo.woNo), 'js.mes.dlg.woCompleteOk').then(function (ok) {
                if (!ok || !current) { return; }
                var woId = current.wo.woId;
                hb.busy(completeBtn, function () {
                    return hb.api(API + '/' + woId + '/complete', {method: 'POST', body: {rowVer: current.wo.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(woId).then(function () { notify(hb.t('js.prdWo.completed')); });
                }, function (err) { notify(errText(err), true); });
            });
        });
    }

    if (closeWoBtn) {
        closeWoBtn.addEventListener('click', function () {
            if (!current) { return; }
            var un = completion && completion.unreceivedQty !== '0'
                ? hb.t('js.prdWo.closeUnreceived', qtyText(completion.unreceivedQty), completion.unitCd) : '';
            // .properties 값 앞 공백은 잘린다 — 이어 붙일 때 공백은 여기서
            mesUi.ask('js.mes.dlg.woClose', hb.t('js.prdWo.closeWoConfirm', current.wo.woNo) + (un ? ' ' + un : ''), 'js.mes.dlg.woCloseOk')
                .then(function (ok) {
                    if (!ok || !current) { return; }
                    var woId = current.wo.woId;
                    hb.busy(closeWoBtn, function () {
                        return hb.api(API + '/' + woId + '/close', {method: 'POST', body: {rowVer: current.wo.rowVer}});
                    }).then(function (res) {
                        if (!res) { return; }
                        return load(woId).then(function () { notify(hb.t('js.prdWo.closedWo')); });
                    }, function (err) { notify(errText(err), true); });
                });
        });
    }

    /** 라우팅·BOM·수주 선택지 — 계획 상태면 서버에서 다시 받고, 발행 뒤에는 지시에 저장된 값만 */
    function fillRefs(w) {
        var rs = field('routingId'), bs = field('bomId'), os = field('orderLineId');
        if (w && w.status !== 'PLANNED') {
            [rs, bs, os].forEach(function (s) { s.textContent = ''; });
            option(rs, w.routingId, w.routingRev);
            option(bs, w.bomId, w.bomRev || hb.t('js.prdWo.noBom'));
            option(os, w.orderLineId, w.orderNo ? w.orderNo + '-' + w.orderLineNo + ' · ' + w.custNm : hb.t('js.prdWo.noOrder'));
            unitInfo.textContent = hb.t('js.prdWo.unit', w.unitCd);
            return Promise.resolve();
        }
        return loadOptions(w);
    }

    function loadOptions(w) {
        var item = field('itemCd').value.trim();
        var rs = field('routingId'), bs = field('bomId'), os = field('orderLineId');
        if (!item) {
            [rs, bs, os].forEach(function (s) { s.textContent = ''; });
            unitInfo.textContent = '';
            return Promise.resolve();
        }
        var q = 'itemCd=' + encodeURIComponent(item) + '&plantCd=' + encodeURIComponent(field('plantCd').value)
            + (field('planStart').value ? '&date=' + field('planStart').value : '');
        return hb.latest('options', hb.api(API + '/options?' + q)).then(function (res) {
            var o = res.data;
            var keep = {routingId: w ? w.routingId : rs.value, bomId: w ? w.bomId : bs.value, orderLineId: w ? w.orderLineId : os.value};
            [rs, bs, os].forEach(function (s) { s.textContent = ''; });
            if (!o.routings.length) { option(rs, '', hb.t('js.prdWo.noRouting')); }
            o.routings.forEach(function (r) { option(rs, r.id, r.rev + (r.altNo > 1 ? ' (' + hb.t('js.prdWo.alt', r.altNo) + ')' : '')); });
            option(bs, '', o.boms.length ? hb.t('js.prdWo.autoBom') : hb.t('js.prdWo.noBom'));
            o.boms.forEach(function (b) { option(bs, b.id, b.rev + (b.altNo > 1 ? ' (' + hb.t('js.prdWo.alt', b.altNo) + ')' : '')); });
            option(os, '', hb.t('js.prdWo.noOrder'));
            o.orderLines.forEach(function (l) {
                option(os, l.lineId, l.orderNo + '-' + l.lineNo + ' · ' + l.custNm + ' · ' + qtyText(l.qty) + ' · ' + hb.fmt.date(l.dueDate));
            });
            ['routingId', 'bomId', 'orderLineId'].forEach(function (n) {
                var v = keep[n] === null || keep[n] === undefined ? '' : String(keep[n]);
                if (field(n).querySelector('option[value="' + v + '"]')) { field(n).value = v; }
            });
            unitInfo.textContent = hb.t('js.prdWo.unit', o.unitCd) + ' · ' + o.itemNm;
        }, function (err) {
            [rs, bs, os].forEach(function (s) { s.textContent = ''; });
            unitInfo.textContent = '';
            hb.showFieldErrors(form, err.errors || []);
        });
    }

    function scheduleOptions() {
        if (current && current.wo.status !== 'PLANNED') { return; }
        clearTimeout(optTimer);
        optTimer = setTimeout(function () { loadOptions(null); }, 300);
    }

    function renderSnapshot(d) {
        procBody.textContent = '';
        bomBody.textContent = '';
        snapSection.hidden = !d || !d.procs.length;
        if (!d) { return; }
        var assignable = CAN_SAVE && (d.wo.status === 'RELEASED' || d.wo.status === 'IN_PROGRESS');
        d.procs.forEach(function (p) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, String(p.opSeq)));
            tr.appendChild(hb.el('td', null, p.procCd + ' · ' + p.procNm));
            tr.appendChild(hb.el('td', null, p.nodeCd || ''));
            tr.appendChild(hb.el('td', 'hb-num', p.stdCtSec ? qtyText(p.stdCtSec) + (p.unitsPerCycle > 1 ? ' / ' + p.unitsPerCycle : '') : ''));
            var eqTd = hb.el('td');
            if (assignable) {
                var sel = hb.el('select');
                sel.setAttribute('aria-label', hb.t('js.prdWo.equipFor', p.opSeq));
                option(sel, '', hb.t('js.prdWo.noEquip'));
                (equips[d.wo.plantCd] || []).forEach(function (e) { option(sel, e.equipCd, e.equipCd + ' · ' + e.equipNm); });
                sel.value = p.equipCd || '';
                sel.addEventListener('change', function () { assign(p, sel); });
                eqTd.appendChild(sel);
            } else {
                eqTd.textContent = p.equipCd ? p.equipCd + ' · ' + p.equipNm : '';
            }
            tr.appendChild(eqTd);
            tr.appendChild(hb.el('td', 'hb-wrap', p.params.map(function (x) {
                return x.paramNm + ' ' + (x.targetVal || '') + (x.lsl || x.usl ? ' (' + (x.lsl || '') + '~' + (x.usl || '') + ')' : '')
                    + (x.unitNm ? ' ' + x.unitNm : '');
            }).join(', ')));
            procBody.appendChild(tr);
        });
        if (!d.boms.length) {
            var tr = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.prdWo.noBoms'));
            td.colSpan = 5;
            tr.appendChild(td);
            bomBody.appendChild(tr);
        }
        d.boms.forEach(function (b) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-wrap', b.itemCd + ' · ' + b.itemNm));
            tr.appendChild(hb.el('td', null, hb.t(LT[b.lineType] || 'js.prdWo.lt.NORMAL')
                + (b.altGrp ? ' · ' + hb.t('js.prdWo.altGrp', b.altGrp, b.altPct) : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(b.reqQty) + ' ' + b.unitCd));
            tr.appendChild(hb.el('td', null, qtyText(b.srcQty) + ' ' + b.srcUnitCd + (b.scrapPct !== '0' ? ' +' + b.scrapPct + '%' : '')));
            tr.appendChild(hb.el('td', null, b.phantomPath || ''));
            bomBody.appendChild(tr);
        });
    }

    function assign(p, sel) {
        hb.api(API + '/procs/' + p.woProcId + '/equip', {method: 'PUT', body: {equipCd: sel.value, rowVer: p.rowVer}})
            .then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return open(current.wo.woId);
            }, function (err) {
                notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
                sel.value = p.equipCd || '';
            });
    }

    // ------------------------------------------------------------------ 저장·발행·취소

    function body() {
        var b = {plantCd: field('plantCd').value, itemCd: field('itemCd').value.trim(), planQty: field('planQty').value.trim(),
            planStart: field('planStart').value, planEnd: field('planEnd').value, priority: Number(field('priority').value),
            remark: field('remark').value};
        ['routingId', 'bomId', 'orderLineId'].forEach(function (n) { b[n] = field(n).value ? Number(field(n).value) : null; });
        return b;
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            var b = body();
            var editing = !!current;
            if (editing) { b.rowVer = current.wo.rowVer; }
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.wo.woId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? current.wo.woId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (releaseBtn) {
        releaseBtn.addEventListener('click', function () {
            if (hb.dirty.isDirty()) { notify(hb.t('js.prdWo.saveFirst'), true); return; }
            mesUi.ask('js.mes.dlg.woRelease', hb.t('js.prdWo.releaseConfirm', current.wo.woNo), 'js.mes.dlg.woReleaseOk').then(function (ok) {
                if (!ok) { return; }
                hb.busy(releaseBtn, function () {
                    return hb.api(API + '/' + current.wo.woId + '/release', {method: 'POST', body: {rowVer: current.wo.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(current.wo.woId).then(function () { notify(hb.t('js.prdWo.released')); });
                }, function (err) {
                    notify(err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message, true);
                    hb.showFieldErrors(form, err.errors);
                });
            });
        });
    }

    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            if (!current) { return; }
            // 되돌릴 수 없는 취소 — 사유 필수(서버도 REQUIRED), 위험 톤
            hb.dialog.prompt({title: hb.t('js.mes.dlg.woCancel'), message: hb.t('js.prdWo.cancelPrompt', current.wo.woNo),
                label: hb.t('js.mes.dlg.reason'), ok: hb.t('js.mes.dlg.woCancelOk'), required: true, maxLength: 500, danger: true})
                .then(function (reason) {
                    if (reason === null || !current) { return; }
                    hb.busy(cancelBtn, function () {
                        return hb.api(API + '/' + current.wo.woId + '/cancel', {method: 'POST', body: {reason: reason, rowVer: current.wo.rowVer}});
                    }).then(function (res) {
                        if (!res) { return; }
                        return load(current.wo.woId).then(function () { notify(hb.t('js.common.saved')); });
                    }, function (err) {
                        notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
                    });
                });
        });
    }

    function newWo() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    }
    // 엑셀 올리기(PRD_WO) — 끝나면 목록을 다시 읽는다
    var importBtn = document.getElementById('importBtn');
    if (importBtn) {
        importBtn.addEventListener('click', function () { hb.importDialog({templateCd: 'PRD_WO', onDone: function () { if (grid) { grid.reload(true); } }}); });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newWo); }
    });

    // 품목 찾기 보조 + 기준 바뀌면 선택지 다시
    var itemTimer = null;
    field('itemCd').addEventListener('input', function () {
        clearTimeout(itemTimer);
        var q = field('itemCd').value.trim();
        scheduleOptions();
        if (q.length < 2) { return; }
        itemTimer = setTimeout(function () {
            hb.latest('items', hb.api(API + '/items?q=' + encodeURIComponent(q))).then(function (res) {
                var dl = document.getElementById('itemList');
                dl.textContent = '';
                res.data.forEach(function (i) { option(dl, i.itemCd, i.itemNm + ' (' + i.unitCd + ')'); });
            }, function () { /* 입력 보조 — 실패해도 입력은 된다 */ });
        }, 250);
    });
    ['plantCd', 'planStart'].forEach(function (n) { field(n).addEventListener('change', scheduleOptions); });

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        var plantSel = document.getElementById('plantSel');
        option(plantSel, '', hb.t('js.prdWo.allPlant'));
        r[1].data.forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(field('plantCd'), p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        return Promise.all(r[1].data.map(function (p) {
            return hb.api(API + '/equips?plantCd=' + encodeURIComponent(p.plantCd)).then(function (e) { equips[p.plantCd] = e.data; });
        }));
    }).then(function () {
        // 지시는 앞으로의 계획 — 기본 기간은 지난 7일 ~ 앞으로 30일(기간 프리셋은 모두 오늘까지라 직접 넣는다).
        // 기간 컴포넌트의 [초기화]·링크 되돌림은 data-default 프리셋(last30)으로 가므로 onReset 에서 이 기간으로 다시 맞춘다(L-c) —
        // 링크(sp.link)는 되돌린 뒤 링크 값을 채우므로 링크 기간이 그대로 이긴다. 오늘은 그때마다 다시 계산(다음 날 열어 둔 탭도 맞게)
        function woPeriod() {
            var today = hb.dates.today();
            return {from: hb.dates.add(today, -7), to: hb.dates.add(today, 30)};
        }
        sp = hb.searchPanel(search, {
            onSearch: function () {
                return grid ? confirmLeave().then(function (ok) { return ok ? grid.reload(true) : null; }) : null;
            },
            onReset: function () { if (sp) { sp.setValues(woPeriod()); } }
        });
        // ready(주소의 링크 조건 q.이름 적용) 전에 넣어야 홈 링크 조건이 이긴다 — ready 뒤에 넣으면 링크 기간을 덮는다
        sp.setValues(woPeriod());
        return sp.ready;
    }).then(function () {
        show(null);
        // 주 목록 = hb.grid(UI 단계 3 ①) — 서버 페이징·정렬, 상태 칸 배지, 취소·마감 행 흐리게, 미저장이면 옮기기 전에 묻는다
        grid = hb.grid('#woGrid', {
            progCd: PROG, id: 'main', key: 'woId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            beforeSelect: function () { return confirmLeave(); },
            status: {status: mesUi.TONES.wo},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { open(row.woId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
