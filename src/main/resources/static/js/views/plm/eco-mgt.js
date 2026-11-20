/* 설계 변경 지시 (plm-eco) — PLM 1단계 (docs/39). 조회 → 지시 표 → 상세(폼 · 영향 대상 · 결재 · 고객 승인 · 연결 요청 · 첨부).
   작성 중에만 내용·대상을 바꾸고, 결재는 플랫폼 결재 API(/api/platform/approvals) 로 처리한다. 판정은 모두 서버.
   3b(docs/39 13장): 변경 종류·표시 구간, 설비·공정 대상, 적용된 지시의 "표시된 LOT". */
(function () {
    'use strict';

    var API = '/api/plm/ecos';
    var APR = '/api/platform/approvals';
    var PROG = 'plm-eco';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var search = document.getElementById('search');
    var form = document.getElementById('ecoForm');
    var custForm = document.getElementById('custForm');
    var targetBody = document.querySelector('#targets tbody');
    var lineBody = document.querySelector('#aprLines tbody');
    var markBody = document.querySelector('#marks tbody');
    var formMode = document.getElementById('formMode');
    var saveBtn = document.getElementById('saveBtn');
    var releaseBtn = document.getElementById('releaseBtn');
    var cancelBtn = document.getElementById('cancelBtn');
    var copyBtn = document.getElementById('copyBtn');
    var noticeBtn = document.getElementById('noticeBtn');
    var recordBtn = document.getElementById('recordBtn');
    var current = null, sp = null, grid = null, reasonSel = null, kindSel = null, condSel = null, names = {}, targets = [], lines = [], seq = 0;
    var notify = plm.notify;

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }
    function cfield(n) { return custForm.querySelector('[data-field="' + n + '"]'); }
    function draft() { return !current || current.eco.status === 'DRAFT'; }
    function show_(el, on) { if (el) { el.hidden = !on; } }


    var fileBox = plm.files(PROG, function () { return current ? current.eco.ecoId : null; },
        function () { return draft(); },
        function (f) { return !!current && !!current.eco.submittedAt && f.createdAt > current.eco.submittedAt; });

    function nm(grp, code) { return (names[grp] && names[grp][code]) || code || ''; }

    // ------------------------------------------------------------------ 목록

    /** 목록 조회 조건(grid params) */
    function params() {
        var v = sp.values();
        var q = {};
        ['status', 'itemCd', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기(UI 단계 3 hb.grid, docs/39 21.2) — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.ecoId !== selectId)) { return open(selectId); }
        });
    }

    function open(id) {
        return hb.latest('eco', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { notify(err.message, true); });
    }

    // ------------------------------------------------------------------ 상세

    function show(d) {
        current = d;
        var e = d ? d.eco : null;
        hb.showFieldErrors(form, []);
        var editable = CAN_SAVE && draft();
        field('title').value = e ? e.title : '';
        reasonSel.setValue(e ? e.reasonCd : '');
        field('effectiveDate').value = e ? e.effectiveDate : hb.dates.today();
        field('ownerId').value = e ? String(e.ownerId) : '';
        field('custApprovalReqYn').value = e ? e.custApprovalReqYn : 'N';
        field('custCd').value = e && e.custCd ? e.custCd : '';
        field('description').value = e ? (e.description || '') : '';
        kindSel.setValue(e ? e.changeKind : 'DESIGN');
        field('markLots').value = e && e.markLots != null ? String(e.markLots) : '';
        field('markUntil').value = e && e.markUntil ? e.markUntil : '';
        condSel.setValue(e ? e.applyCond : 'DATE');
        field('stockItemCd').value = e && e.stockItemCd ? e.stockItemCd : '';
        field('stockPlantCd').value = e && e.stockPlantCd ? e.stockPlantCd : '';
        ['title', 'reasonCd', 'effectiveDate', 'ownerId', 'custApprovalReqYn', 'custCd', 'description', 'changeKind', 'markLots',
            'markUntil', 'applyCond', 'stockItemCd', 'stockPlantCd'].forEach(function (n) { field(n).disabled = !editable; });
        syncCust();
        syncMark();
        syncStock();
        // 재고 소진(18장): 적용 전이면 조회 시점의 가용 재고 — 0 이 되어야 적용된다(판정은 서버)
        var stockOut = document.getElementById('stockStatus');
        show_(/** @type {HTMLElement} */ (stockOut.closest('label')), !!e && e.applyCond === 'STOCK_OUT');
        stockOut.textContent = e && e.applyCond === 'STOCK_OUT'
            ? (e.stockItemCd || '') + ' · ' + (e.stockPlantCd || '')
              + (e.stockAvailable != null ? ' · ' + hb.t('js.plmEco.stockLeft', hb.fmt.num(e.stockAvailable)) : '') : '';
        plm.setBadge(document.getElementById('ecoStatus'), e ? plm.badge('eco', e.status, nm('PLM_ECO_STATUS', e.status)) : null,
            e ? ((e.cancelReason ? '— ' + e.cancelReason : '') + (e.releasedAt ? ' · ' + hb.fmt.datetime(e.releasedAt) : '')).trim() : '');
        var cust = !!e && e.custApprovalReqYn === 'Y';
        plm.setBadge(document.getElementById('custStatus'),
            cust ? plm.badge('cust', e.custApprovalStatus, nm('PLM_CUST_APR', e.custApprovalStatus)) : null,
            cust ? ((e.custApprovalRef || '') + (e.custApprovedOn ? ' · ' + hb.fmt.date(e.custApprovedOn) : '')) : '');
        formMode.textContent = e ? hb.t('js.plm.editing', e.ecoNo) : hb.t('js.plm.creatingEco');
        show_(saveBtn, editable);
        show_(releaseBtn, !!e && e.status === 'APPROVED');
        show_(cancelBtn, !!e && (e.status === 'DRAFT' || e.status === 'APPROVED'));
        show_(copyBtn, !!e);
        show_(noticeBtn, !!e);
        show_(recordBtn, !!e);
        targets = d ? d.targets.map(function (t) { return Object.assign({rowKey: 't' + t.targetId}, t); }) : [];
        renderTargets();
        renderApproval();
        renderCust();
        renderEcrs();
        renderQuality();
        loadMarks();
        plm.ecoExtras.show(d, function () { return load(d.eco.ecoId); });
        // D7(2단계): 상신하면 첨부 고정 — 올리기 칸을 숨긴다(판정은 서버 FileLockRule)
        var fileInput = document.getElementById('fileInput');
        if (fileInput) { fileInput.hidden = !draft(); }
        fileBox.load();
        hb.dirty.clear();
    }

    function syncCust() {
        field('custCd').disabled = field('custApprovalReqYn').disabled || field('custApprovalReqYn').value !== 'Y';
    }

    /** 표시 끝은 4M·임시만(설계 변경은 첫 N LOT) — 판정은 서버 */
    function syncMark() {
        var until = field('markUntil');
        until.disabled = field('changeKind').disabled || kindSel.value() === 'DESIGN';
        if (until.disabled && kindSel.value() === 'DESIGN') { until.value = ''; }
    }

    /** 소진 품목·공장은 적용 조건 STOCK_OUT 일 때만 */
    function syncStock() {
        var off = field('applyCond').disabled || condSel.value() !== 'STOCK_OUT';
        ['stockItemCd', 'stockPlantCd'].forEach(function (n) {
            field(n).disabled = off;
            if (off && condSel.value() !== 'STOCK_OUT') { field(n).value = ''; }
        });
    }

    // ---------- 영향 대상

    function renderTargets() {
        var section = document.getElementById('targetSection');
        show_(section, !!current);
        show_(document.getElementById('targetTools'), !!current && draft());
        targetBody.textContent = '';
        if (!current) { return; }
        if (!targets.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.plmEco.noTargets'));
            td0.colSpan = 6;
            tr0.appendChild(td0);
            targetBody.appendChild(tr0);
        }
        targets.forEach(function (t, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row-key', t.rowKey);
            tr.appendChild(hb.el('td', null, nm('PLM_TARGET_TYPE', t.targetType)));
            tr.appendChild(hb.el('td', 'hb-wrap', t.itemCd ? t.itemCd + ' · ' + (t.itemNm || '') : '-'));
            var refText = t.targetType === 'EQUIP' ? (t.equipCd || t.refCd || '') + (t.equipNm ? ' · ' + t.equipNm : '')
                : t.targetType === 'PROC' ? (t.procCd || t.refCd || '') + (t.procNm ? ' · ' + t.procNm : '')
                : t.targetType === 'DOC' ? (t.docNo || t.refCd || '') + (t.rev ? ' ' + t.rev : '') + (t.docNm ? ' · ' + t.docNm : '')
                : t.targetType === 'ITEM' ? '' : t.rev + (t.plantCd ? ' · ' + t.plantCd : '');
            tr.appendChild(hb.el('td', null, refText));
            var revType = t.targetType === 'BOM' || t.targetType === 'ROUTING' || t.targetType === 'DOC';
            // 리비전 상태는 공통코드 문구로(BOM·라우팅 = BOM_STATUS 와 같은 값, 도면·문서 = DOC_REV_STATUS)
            var st = !revType ? '' : nm(t.targetType === 'DOC' ? 'DOC_REV_STATUS' : 'BOM_STATUS', t.refStatus)
                + (t.refChangeNo ? ' · ' + t.refChangeNo : '');
            var stTd = hb.el('td', null, st);
            if (t.docTargetWarn) { stTd.appendChild(hb.el('span', 'hb-warn', ' ' + hb.t('js.plmEco.docTargetWarn'))); }
            if (t.changed) { stTd.appendChild(hb.el('span', 'hb-warn', ' ' + hb.t('js.plmEco.changed'))); }
            tr.appendChild(stTd);
            var descTd = hb.el('td');
            if (CAN_SAVE && draft()) {
                var inp = hb.el('input');
                inp.maxLength = 1000;
                inp.value = t.changeDesc || '';
                inp.setAttribute('aria-label', hb.t('js.plmEco.changeDescFor', t.itemCd));
                inp.addEventListener('input', function () { t.changeDesc = inp.value; hb.dirty.mark(); });
                descTd.appendChild(inp);
            } else {
                descTd.textContent = t.changeDesc || '';
            }
            tr.appendChild(descTd);
            var actTd = hb.el('td');
            if (CAN_SAVE && draft()) {
                var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                del.type = 'button';
                del.addEventListener('click', function () { targets.splice(i, 1); hb.dirty.mark(); renderTargets(); });
                actTd.appendChild(del);
            }
            tr.appendChild(actTd);
            targetBody.appendChild(tr);
        });
    }

    var candItem = document.getElementById('candItem');
    var candSel = document.getElementById('candSel');
    var candidates = [];
    function loadCandidates() {
        if (!candSel) { return; }
        var q = candItem.value.trim();
        hb.latest('cand', hb.api(API + '/candidates' + (q ? '?itemCd=' + encodeURIComponent(q) : ''))).then(function (res) {
            candidates = res.data;
            candSel.textContent = '';
            if (q) {
                plm.option(candSel, 'ITEM', hb.t('js.plmEco.itemOnly', q.toUpperCase()));
                plm.option(candSel, 'EQUIP', hb.t('js.plmEco.equipTarget', q.toUpperCase()));
                plm.option(candSel, 'PROC', hb.t('js.plmEco.procTarget', q.toUpperCase()));
                plm.option(candSel, 'DOC', hb.t('js.plmEco.docTarget', q.toUpperCase()));
            } else {
                plm.option(candSel, 'DOC', hb.t('js.plmEco.docOnly'));
            }
            candidates.forEach(function (c, i) {
                plm.option(candSel, String(i), nm('PLM_TARGET_TYPE', c.targetType) + ' ' + c.itemCd + ' ' + c.rev
                    + (c.plantCd ? ' · ' + c.plantCd : '') + (c.openEcoNo ? ' (' + c.openEcoNo + ')' : ''));
            });
        }, function (err) { notify(err.message, true); });
    }
    if (candItem) {
        plm.itemAssist(API + '/items', candItem, document.getElementById('itemList'));
        var candTimer = null;
        candItem.addEventListener('input', function () { clearTimeout(candTimer); candTimer = setTimeout(loadCandidates, 300); });
        document.getElementById('addTargetBtn').addEventListener('click', function () {
            var v = candSel.value;
            seq += 1;
            if (v === 'ITEM') {
                targets.push({rowKey: 'n' + seq, targetType: 'ITEM', itemCd: candItem.value.trim().toUpperCase(), itemNm: ''});
            } else if (v === 'EQUIP' || v === 'PROC' || v === 'DOC') {
                // DOC 는 품목 선택(16.1) — 품목 칸이 비어도 문서 번호만으로
                var ref = /** @type {HTMLInputElement} */ (document.getElementById('candRef')).value.trim().toUpperCase();
                if (!ref) { notify(hb.t('js.plmEco.refCdNeeded'), true); return; }
                targets.push({rowKey: 'n' + seq, targetType: v, itemCd: candItem.value.trim().toUpperCase(), itemNm: '', refCd: ref});
            } else if (v !== '') {
                var c = candidates[Number(v)];
                targets.push({rowKey: 'n' + seq, targetType: c.targetType, refId: c.refId, itemCd: c.itemCd, itemNm: c.itemNm,
                    rev: c.rev, plantCd: c.plantCd, refStatus: 'DRAFT'});
            } else {
                return;
            }
            hb.dirty.mark();
            renderTargets();
        });
        var saveTargetsBtn = document.getElementById('saveTargetsBtn');
        saveTargetsBtn.addEventListener('click', function () {
            var payload = {rowVer: current.eco.rowVer, targets: targets.map(function (t) {
                return {rowKey: t.rowKey, targetId: t.targetId || null, targetType: t.targetType, itemCd: t.itemCd,
                    refId: t.refId || t.bomId || t.routingId || null, refCd: t.refCd || null, changeDesc: t.changeDesc || ''};
            })};
            hb.busy(saveTargetsBtn, function () {
                return hb.api(API + '/' + current.eco.ecoId + '/targets', {method: 'PUT', body: payload});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(current.eco.ecoId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) { markRows(err); });
        });
    }

    // ---------- 영향 대상 후보 (docs/39 20.7) — 보기만, [대상에 넣기]는 화면 대상 목록에 더할 뿐(저장 때 서버가 판정)

    var impactBox = document.getElementById('impactBox');
    var impactBody = document.querySelector('#impactRows tbody');

    /** 후보 상태 문구 — 키를 이어 붙이지 않는다 */
    function impactState(r) {
        switch (r.state) {
            case 'IN_THIS': return hb.t('js.plmEco.impact.IN_THIS');
            case 'OPEN_ECO': return hb.t('js.plmEco.impact.OPEN_ECO', r.openEcoNo || '');
            case 'NO_DRAFT': return hb.t('js.plmEco.impact.NO_DRAFT');
            case 'ADDABLE': return hb.t('js.plmEco.impact.ADDABLE');
            case 'ITEM': return hb.t('js.plmEco.impact.ITEM');
            default: return r.state || '';
        }
    }

    function loadImpact() {
        if (!current || !candItem) { return; }
        // I1: 품목 코드 칸이 비면 ECO 의 첫 ITEM 대상 품목
        var first = targets.filter(function (t) { return t.targetType === 'ITEM' && t.itemCd; })[0];
        var code = candItem.value.trim() || (first ? first.itemCd : '');
        if (!code) { notify(hb.t('js.plmEco.impactNeedItem'), true); return; }
        var id = current.eco.ecoId;
        var all = /** @type {HTMLInputElement} */ (document.getElementById('impactAll')).checked;
        hb.latest('impact', hb.api(API + '/' + id + '/impact?itemCd=' + encodeURIComponent(code) + '&scope=' + (all ? 'ALL' : 'PARENT')))
            .then(function (res) {
                if (!current || current.eco.ecoId !== id) { return; }
                var d = res.data;
                impactBox.hidden = false;
                document.getElementById('impactInfo').textContent = hb.t('js.plmEco.impactInfo', d.itemCd + ' · ' + (d.itemNm || ''),
                    hb.fmt.date(d.on), d.rows.length);
                impactBody.textContent = '';
                d.rows.forEach(function (r) {
                    var tr = hb.el('tr');
                    tr.appendChild(hb.el('td', 'hb-num', String(r.level)));
                    tr.appendChild(hb.el('td', 'hb-wrap', r.parentCd + ' · ' + (r.parentNm || '')));
                    tr.appendChild(hb.el('td', null, r.rev || ''));
                    tr.appendChild(hb.el('td', null, r.draftRev || '-'));
                    tr.appendChild(hb.el('td', r.state === 'OPEN_ECO' || r.state === 'NO_DRAFT' ? 'hb-muted' : null, impactState(r)));
                    var act = hb.el('td');
                    var canAdd = draft() && (r.state === 'ADDABLE' || r.state === 'ITEM');
                    var dup = targets.some(function (t) {
                        return r.state === 'ADDABLE' ? (t.refId || t.bomId) === r.draftBomId : t.targetType === 'ITEM' && t.itemCd === r.parentCd;
                    });
                    if (canAdd && !dup) {
                        var b = hb.el('button', 'hb-btn-secondary hb-btn-small',
                            hb.t(r.state === 'ADDABLE' ? 'js.plmEco.impactAddBom' : 'js.plmEco.impactAddItem'));
                        b.type = 'button';
                        b.addEventListener('click', function () {
                            seq += 1;
                            if (r.state === 'ADDABLE') {
                                targets.push({rowKey: 'n' + seq, targetType: 'BOM', refId: r.draftBomId, itemCd: r.parentCd, itemNm: r.parentNm,
                                    rev: r.draftRev, refStatus: 'DRAFT'});
                            } else {
                                targets.push({rowKey: 'n' + seq, targetType: 'ITEM', itemCd: r.parentCd, itemNm: r.parentNm});
                            }
                            hb.dirty.mark();
                            renderTargets();
                            b.remove();
                        });
                        act.appendChild(b);
                    }
                    tr.appendChild(act);
                    impactBody.appendChild(tr);
                });
                if (!d.rows.length) {
                    var tr0 = hb.el('tr');
                    var td0 = hb.el('td', 'hb-muted', hb.t('js.plmEco.impactNone'));
                    td0.colSpan = 6;
                    tr0.appendChild(td0);
                    impactBody.appendChild(tr0);
                }
            }, function (err) { notify(plm.errorText(err), true); });
    }
    var impactBtn = document.getElementById('impactBtn');
    if (impactBtn) {
        impactBtn.addEventListener('click', loadImpact);
        document.getElementById('impactAll').addEventListener('change', function () { if (!impactBox.hidden) { loadImpact(); } });
    }

    /** 칸 오류의 rowKey 로 대상 행을 표시 — 적용 실패(t{대상}:…)도 앞부분으로 찾는다 */
    function markRows(err) {
        notify(plm.errorText(err), true);
        targetBody.querySelectorAll('.hb-field-error').forEach(function (x) { x.remove(); });
        (err.errors || []).forEach(function (x) {
            if (!x.rowKey) { return; }
            var key = String(x.rowKey).split(':')[0];
            var tr = targetBody.querySelector('tr[data-row-key="' + key + '"]');
            if (tr && tr.lastChild) { tr.lastChild.appendChild(hb.el('span', 'hb-field-error', x.message)); }
        });
    }

    // ---------- 결재

    /** 결재 줄 상태 문구 — 플랫폼 결재 화면과 같은 말(키를 이어 붙이지 않는다: 문구 키 검사) */
    function lineStatus(st) {
        switch (st) {
            case 'WAIT': return hb.t('js.sysApproval.st.WAIT');
            case 'CURRENT': return hb.t('js.sysApproval.st.CURRENT');
            case 'APPROVED': return hb.t('js.sysApproval.st.APPROVED');
            case 'REJECTED': return hb.t('js.sysApproval.st.REJECTED');
            case 'SKIPPED': return hb.t('js.sysApproval.st.SKIPPED');
            case 'REF': return hb.t('js.sysApproval.st.REF');
            default: return st || '';
        }
    }

    function renderApproval() {
        var section = document.getElementById('aprSection');
        show_(section, !!current);
        lineBody.textContent = '';
        if (!current) { return; }
        var a = current.approval;
        var building = draft() && CAN_SAVE;
        show_(document.getElementById('lineTools'), building);
        show_(document.getElementById('actTools'), a.myTurn || a.canWithdraw);
        show_(document.getElementById('approveBtn'), a.myTurn);
        show_(document.getElementById('rejectBtn'), a.myTurn);
        show_(document.getElementById('withdrawBtn'), a.canWithdraw);
        plm.signs(document.getElementById('ecoSigns'), current.signs);
        var rows = building ? lines : a.lines;
        rows.forEach(function (l, i) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, String(building ? i + 1 : l.seq)));
            tr.appendChild(hb.el('td', null, l.approverNm));
            tr.appendChild(hb.el('td', null, hb.t(l.lineType === 'AGREE' ? 'js.plmEco.line.AGREE'
                : l.lineType === 'REF' ? 'js.plmEco.line.REF' : 'js.plmEco.line.APPROVE')));
            var stTd = hb.el('td');
            if (!building) { plm.setBadge(stTd, plm.badge('aprLine', l.status, lineStatus(l.status)), l.actedAt ? hb.fmt.datetime(l.actedAt) : ''); }
            tr.appendChild(stTd);
            tr.appendChild(hb.el('td', 'hb-wrap', building ? '' : (l.comment || '')));
            var act = hb.el('td');
            if (building) {
                var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                del.type = 'button';
                del.addEventListener('click', function () { lines.splice(i, 1); renderApproval(); });
                act.appendChild(del);
            }
            tr.appendChild(act);
            lineBody.appendChild(tr);
        });
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t(building ? 'js.plmEco.noLines' : 'js.plmEco.notSubmitted'));
            td0.colSpan = 6;
            tr0.appendChild(td0);
            lineBody.appendChild(tr0);
        }
    }

    var addLineBtn = document.getElementById('addLineBtn');
    if (addLineBtn) {
        addLineBtn.addEventListener('click', function () {
            var u = document.getElementById('lineUser');
            if (!u.value) { return; }
            lines.push({userId: Number(u.value), approverNm: u.options[u.selectedIndex].text,
                lineType: document.getElementById('lineType').value});
            renderApproval();
        });
        var submitBtn = document.getElementById('submitBtn');
        submitBtn.addEventListener('click', function () {
            if (hb.dirty.isDirty()) { notify(hb.t('js.plmEco.saveFirst'), true); return; }
            plm.ask('js.plmEco.dlg.submit', hb.t('js.plmEco.submitConfirm', current.eco.ecoNo), 'js.plmEco.dlg.submitOk').then(function (ok) {
                if (!ok) { return; }
                hb.busy(submitBtn, function () {
                    return hb.api(API + '/' + current.eco.ecoId + '/submit', {method: 'POST', body: {rowVer: current.eco.rowVer,
                        lines: lines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
                }).then(function (res) {
                    if (!res) { return; }
                    lines = [];
                    return load(current.eco.ecoId).then(function () { notify(hb.t('js.plmEco.submitted')); });
                }, function (err) {
                    markRows(err);
                    hb.showFieldErrors(form, err.errors);
                });
            });
        });
    }

    function aprAct(btnId, path, needComment) {
        var btn = document.getElementById(btnId);
        btn.addEventListener('click', function () {
            var comment = document.getElementById('aprComment').value;
            if (needComment && !comment.trim()) { notify(hb.t('js.plmEco.commentRequired'), true); return; }
            hb.busy(btn, function () {
                return hb.api(APR + '/' + current.approval.aprId + '/' + path, {method: 'POST', body: {comment: comment},
                    headers: {'X-Idempotency-Key': String(Date.now()) + Math.random().toString(36).slice(2)}});
            }).then(function (res) {
                if (!res) { return; }
                document.getElementById('aprComment').value = '';
                return load(current.eco.ecoId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) { notify(plm.errorText(err), true); });
        });
    }
    // 승인 = 전자서명(19장): 서명 대화상자 → 토큰 → PLM 승인 API(같은 트랜잭션에서 서명 + 결재). 취소하면 아무것도 안 함
    (function () {
        var btn = document.getElementById('approveBtn');
        if (!btn) { return; }
        btn.addEventListener('click', function () {
            var e = current.eco;
            var agree = current.approval.lines.some(function (l) { return l.status === 'CURRENT' && l.lineType === 'AGREE'; });
            hb.esign({meaning: agree ? 'REVIEW' : 'APPROVE', targetType: 'plm_eco', targetId: e.ecoId, title: e.ecoNo + ' · ' + e.title})
                .then(function (token) {
                    if (!token) { return null; }
                    return hb.busy(btn, function () {
                        return hb.api(API + '/' + e.ecoId + '/approve', {method: 'POST',
                            body: {comment: document.getElementById('aprComment').value, esignToken: token},
                            headers: {'X-Idempotency-Key': String(Date.now()) + Math.random().toString(36).slice(2)}});
                    });
                })
                .then(function (res) {
                    if (!res) { return; }
                    document.getElementById('aprComment').value = '';
                    return load(e.ecoId).then(function () { notify(hb.t('js.common.saved')); });
                }, function (err) { notify(plm.errorText(err), true); });
        });
    })();
    aprAct('rejectBtn', 'reject', true);
    aprAct('withdrawBtn', 'withdraw', false);

    // ---------- 고객 승인 · 연결 요청

    function renderCust() {
        var e = current ? current.eco : null;
        var on = !!e && e.custApprovalReqYn === 'Y';
        show_(document.getElementById('custSection'), on);
        if (!on) { return; }
        var open = e.status === 'DRAFT' || e.status === 'PENDING' || e.status === 'APPROVED';
        cfield('custApprovalStatus').value = e.custApprovalStatus === 'NA' ? 'PENDING' : e.custApprovalStatus;
        cfield('custApprovalRef').value = e.custApprovalRef || '';
        cfield('custApprovedOn').value = e.custApprovedOn || '';
        ['custApprovalStatus', 'custApprovalRef', 'custApprovedOn'].forEach(function (n) { cfield(n).disabled = !CAN_SAVE || !open; });
        show_(document.getElementById('custBtn'), CAN_SAVE && open);
        hb.showFieldErrors(custForm, []);
    }

    var custBtn = document.getElementById('custBtn');
    if (custBtn) {
        custBtn.addEventListener('click', function () {
            hb.busy(custBtn, function () {
                return hb.api(API + '/' + current.eco.ecoId + '/cust-approval', {method: 'POST', body: {rowVer: current.eco.rowVer,
                    status: cfield('custApprovalStatus').value, ref: cfield('custApprovalRef').value,
                    approvedOn: cfield('custApprovedOn').value}});
            }).then(function (res) {
                if (!res) { return; }
                return load(current.eco.ecoId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(custForm, err.errors);
            });
        });
    }

    // ---------- 품질 요약(QMS 4M, 계약 EcoQualityView — QMS 없으면 null → 숨김)

    function renderQuality() {
        var q = current ? current.quality : null;
        show_(document.getElementById('qualitySection'), !!q);
        if (!q) { return; }
        // 설계 변경(DESIGN)은 4M 세부가 필수가 아니다 — 없으면 4M 문구를 빼고 초품 수만
        var parts = q.hasChange ? [hb.t('js.plmEco.qualityHas', (q.fourMTypes || []).join(', ') || '-', q.charCount)]
            : current.eco.changeKind === 'DESIGN' ? [] : [hb.t('js.plmEco.qualityNone')];
        if (q.firstOk !== null && q.firstOk !== undefined) {
            parts.push(hb.t('js.plmEco.qualityFirst', q.firstOk, q.firstNg, q.firstPending));
        }
        document.getElementById('qualityInfo').textContent = parts.join(' · ');
        /** @type {HTMLAnchorElement} */ (document.getElementById('qualityLink')).href = '/page/qms-change?ecoId=' + current.eco.ecoId;
        // QMS Q7: 첫 LOT 표시가 있으면(초품 대상) 초품 판정 화면 링크
        var firstCnt = (q.firstOk || 0) + (q.firstNg || 0) + (q.firstPending || 0);
        var setup = /** @type {HTMLAnchorElement} */ (document.getElementById('setupLink'));
        setup.hidden = firstCnt === 0;
        setup.href = '/page/qms-setup?ecoId=' + current.eco.ecoId;
    }

    // ---------- 표시된 LOT (적용된 지시만 — 생산 실적이 남긴 것)

    function loadMarks() {
        var e = current ? current.eco : null;
        var on = !!e && e.status === 'RELEASED';
        show_(document.getElementById('markSection'), on);
        markBody.textContent = '';
        if (!on) { return; }
        var id = e.ecoId;
        var rule = e.markUntil ? hb.t('js.plmEco.markWindow', hb.fmt.date(e.effectiveDate), hb.fmt.date(e.markUntil))
            : e.markLots != null ? hb.t('js.plmEco.markFirst', e.markLots) : hb.t('js.plmEco.markFirstPolicy');
        document.getElementById('markInfo').textContent = rule;
        hb.latest('marks', hb.api(API + '/' + id + '/lots')).then(function (res) {
            if (!current || current.eco.ecoId !== id) { return; }
            var rows = res.data.rows;
            if (!rows.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.plmEco.noMarks'));
                td0.colSpan = 7;
                tr0.appendChild(td0);
                markBody.appendChild(tr0);
            }
            rows.forEach(function (m) {
                var tr = hb.el('tr');
                [String(m.seqNo), nm('PLM_MARK_REASON', m.markReason), m.lotNo, m.itemCd + ' · ' + (m.itemNm || ''), m.woNo || '',
                    m.equipCd || '', hb.fmt.datetime(m.occurredAt)]
                    .forEach(function (x, i) { tr.appendChild(hb.el('td', i === 0 ? 'hb-num' : i === 3 ? 'hb-wrap' : null, x)); });
                markBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    function renderEcrs() {
        var ul = document.getElementById('ecrList');
        ul.textContent = '';
        var list = current ? current.ecrs : [];
        show_(document.getElementById('ecrSection'), list.length > 0);
        list.forEach(function (r) { ul.appendChild(hb.el('li', null, r.ecrNo + ' · ' + r.title + ' · ' + r.requesterNm)); });
    }

    // ------------------------------------------------------------------ 저장·적용·취소

    function body() {
        return {title: field('title').value.trim(), reasonCd: reasonSel.value(), effectiveDate: field('effectiveDate').value,
            ownerId: field('ownerId').value ? Number(field('ownerId').value) : null, description: field('description').value,
            custApprovalReqYn: field('custApprovalReqYn').value, custCd: field('custCd').value, changeKind: kindSel.value(),
            markLots: field('markLots').value ? Number(field('markLots').value) : null, markUntil: field('markUntil').value,
            applyCond: condSel.value(), stockItemCd: field('stockItemCd').value.trim(), stockPlantCd: field('stockPlantCd').value.trim()};
    }

    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            var b = body();
            var editing = !!current;
            if (editing) { b.rowVer = current.eco.rowVer; }
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.eco.ecoId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? current.eco.ecoId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (releaseBtn) {
        releaseBtn.addEventListener('click', function () {
            plm.ask('js.plmEco.dlg.release', hb.t('js.plmEco.releaseConfirm', current.eco.ecoNo, hb.fmt.date(current.eco.effectiveDate)),
                'js.plmEco.dlg.releaseOk').then(function (ok) {
                if (!ok) { return; }
                hb.busy(releaseBtn, function () {
                    return hb.api(API + '/' + current.eco.ecoId + '/release', {method: 'POST', body: {rowVer: current.eco.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(current.eco.ecoId).then(function () { notify(hb.t('js.plmEco.released')); });
                }, function (err) { markRows(err); });
            });
        });
    }

    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            plm.reason('js.plmEco.dlg.cancel', 'js.plmEco.dlg.cancelReason', 'js.plmEco.dlg.cancelOk').then(function (reason) {
                if (reason === null) { return; }
                hb.busy(cancelBtn, function () {
                    return hb.api(API + '/' + current.eco.ecoId + '/cancel', {method: 'POST', body: {rowVer: current.eco.rowVer, reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(current.eco.ecoId).then(function () { notify(hb.t('js.common.saved')); });
                }, function (err) { notify(plm.errorText(err), true); });
            });
        });
    }

    // 복사(docs/39 27장) — 새 작성 중 지시, 리비전 대상(BOM·라우팅·문서)은 빼고 건수로 알림
    if (copyBtn) {
        copyBtn.addEventListener('click', function () {
            var src = current.eco;
            plm.leaveOk().then(function (ok) {
                return ok ? plm.ask('js.plmEco.dlg.copy', hb.t('js.plmEco.copyConfirm', src.ecoNo), 'js.plmEco.dlg.copyOk') : false;
            }).then(function (ok) {
                if (!ok) { return; }
                hb.busy(copyBtn, function () {
                    return hb.api(API + '/' + src.ecoId + '/copy', {method: 'POST'});
                }).then(function (res) {
                    if (!res) { return; }
                    var r = res.data;
                    hb.dirty.clear();
                    return load(r.ecoId).then(function () {
                        notify(r.skipped ? hb.t('js.plmEco.copiedSkipped', src.ecoNo, r.copied, r.skipped) : hb.t('js.plmEco.copied', src.ecoNo, r.copied));
                    });
                }, function (err) { notify(plm.errorText(err), true); });
            });
        });
    }

    // 변경 통지서 엑셀(docs/39 29장) — 고객 제출용 서식 한 장(저장된 내용 기준)
    if (noticeBtn) {
        noticeBtn.addEventListener('click', function () {
            var id = current.eco.ecoId;
            hb.busy(noticeBtn, function () { return hb.download(API + '/' + id + '/notice'); })
                .then(function () { notify(hb.t('js.plmEco.noticeDone')); }, function (err) { notify(plm.errorText(err), true); });
        });
    }
    // 사내 기록서(docs/39 34장) — 감사·품질 보관용, 보는 사람 언어
    if (recordBtn) {
        recordBtn.addEventListener('click', function () {
            var id = current.eco.ecoId;
            hb.busy(recordBtn, function () { return hb.download(API + '/' + id + '/record'); })
                .then(function () { notify(hb.t('js.plmEco.recordDone')); }, function (err) { notify(plm.errorText(err), true); });
        });
    }

    function newEco() {
        plm.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('title').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newEco); }
    });
    field('custApprovalReqYn').addEventListener('change', syncCust);
    field('changeKind').addEventListener('change', syncMark);
    field('applyCond').addEventListener('change', syncStock);

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        reasonSel = hb.select(field('reasonCd'));
        kindSel = hb.select(field('changeKind'));
        condSel = hb.select(field('applyCond'));
        return Promise.all([hb.codes('PLM_ECO_STATUS'), hb.codes('PLM_TARGET_TYPE'), hb.codes('PLM_CUST_APR'),
            hb.api(API + '/users'), hb.api(API + '/custs'), reasonSel.ready, kindSel.ready, hb.codes('PLM_MARK_REASON'), condSel.ready,
            hb.codes('BOM_STATUS'), hb.codes('DOC_REV_STATUS')]);
    }).then(function (r) {
        names = {PLM_ECO_STATUS: r[0], PLM_TARGET_TYPE: r[1], PLM_CUST_APR: r[2], PLM_MARK_REASON: r[7], BOM_STATUS: r[9],
            DOC_REV_STATUS: r[10]};
        var owner = field('ownerId'), lineUser = document.getElementById('lineUser');
        plm.option(owner, '', hb.t('js.plmEco.ownerMe'));
        r[3].data.forEach(function (u) {
            plm.option(owner, u.userId, u.userNm);
            if (lineUser) { plm.option(lineUser, u.userId, u.userNm); }
        });
        var cust = field('custCd');
        plm.option(cust, '', hb.t('js.common.choose'));
        r[4].data.forEach(function (c) { plm.option(cust, c.custCd, c.custCd + ' · ' + c.custNm); });
        sp = hb.searchPanel(search, {onSearch: function () {
            return plm.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return sp.ready;
    }).then(function () {
        show(null);
        loadCandidates();
        // 주 목록 = hb.grid(UI 단계 3) — 상태 배지(plm.TONES)·취소·적용된 지시 흐림·미저장 이동 확인
        grid = hb.grid('#ecoGrid', {
            progCd: 'plm-eco', id: 'main', key: 'ecoId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'plm_eco', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return plm.leaveOk(); },
            status: {status: /** @type {any} */ (plm.TONES.eco)},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'RELEASED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { open(row.ecoId).then(function () { hb.page.openDetail(); }); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
