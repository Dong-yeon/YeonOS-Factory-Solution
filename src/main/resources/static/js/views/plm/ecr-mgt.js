/* 설계 변경 요청 (plm-ecr) — PLM 1단계 (docs/39). 조회 → 요청 표 → 상세(폼 + 검토 + 첨부).
   요청자만 열린 요청을 고치거나 취소, 검토 담당(APPROVE)은 접수·반려. 판정은 서버. */
(function () {
    'use strict';

    var API = '/api/plm/ecrs';
    var PROG = 'plm-ecr';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var search = document.getElementById('search');
    var form = document.getElementById('ecrForm');
    var decideForm = document.getElementById('decideForm');
    var decideSection = document.getElementById('decideSection');
    var formMode = document.getElementById('formMode');
    var saveBtn = document.getElementById('saveBtn');
    var cancelBtn = document.getElementById('cancelBtn');
    var current = null, sp = null, grid = null, statusNames = {}, reasonSel = null;
    var notify = plm.notify;

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }
    function dfield(n) { return decideForm ? decideForm.querySelector('[data-field="' + n + '"]') : null; }


    var fileBox = plm.files(PROG, function () { return current ? current.ecrId : null; },
        function () { return !!current && current.status === 'OPEN' && isMine(); });

    function isMine() { return !!current && current.mine; }

    // ------------------------------------------------------------------ 목록

    function params() {
        var v = sp.values();
        var q = {};
        ['status', 'itemCd', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기(UI 단계 3 hb.grid, docs/39 21.2) */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.ecrId !== selectId)) { return open(selectId); }
        });
    }

    function open(id) {
        return hb.latest('ecr', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { notify(err.message, true); });
    }

    // ------------------------------------------------------------------ 상세

    function show(r) {
        current = r;
        hb.showFieldErrors(form, []);
        var editable = CAN_SAVE && (!r || (r.status === 'OPEN' && isMine()));
        field('title').value = r ? r.title : '';
        reasonSel.setValue(r ? r.reasonCd : '');
        field('itemCd').value = r && r.itemCd ? r.itemCd : '';
        field('description').value = r ? (r.description || '') : '';
        ['title', 'reasonCd', 'itemCd', 'description'].forEach(function (n) { field(n).disabled = !editable; });
        plm.setBadge(document.getElementById('ecrStatus'), r ? plm.badge('ecr', r.status, statusNames[r.status] || r.status) : null,
            r ? r.requesterNm : '');
        document.getElementById('ecrEco').textContent = r && r.ecoNo ? r.ecoNo : '';
        document.getElementById('ecrDecision').textContent = r && r.decisionComment
            ? r.decisionComment + ' (' + r.decidedBy + ', ' + hb.fmt.datetime(r.decidedAt) + ')' : '';
        formMode.textContent = r ? hb.t('js.plm.editing', r.ecrNo) : hb.t('js.plm.creatingEcr');
        if (saveBtn) { saveBtn.hidden = !editable; }
        if (cancelBtn) { cancelBtn.hidden = !r || r.status !== 'OPEN' || !isMine(); }
        if (decideSection) {
            decideSection.hidden = !r || r.status !== 'OPEN';
            if (!decideSection.hidden) { loadDraftEcos(); }
            dfield('comment').value = '';
            dfield('effectiveDate').value = hb.dates.today();
        }
        fileBox.load();
        hb.dirty.clear();
    }

    function loadDraftEcos() {
        var sel = dfield('ecoId');
        return hb.api(API + '/draft-ecos').then(function (res) {
            sel.textContent = '';
            plm.option(sel, '', hb.t('js.plmEcr.newEco'));
            res.data.rows.forEach(function (e) { plm.option(sel, e.ecoId, e.ecoNo + ' · ' + e.title); });
        }, function (err) { notify(err.message, true); });
    }

    // ------------------------------------------------------------------ 저장·취소·접수·반려

    function body() {
        return {title: field('title').value.trim(), reasonCd: reasonSel.value(), itemCd: field('itemCd').value.trim(),
            description: field('description').value};
    }

    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            var b = body();
            var editing = !!current;
            if (editing) { b.rowVer = current.rowVer; }
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.ecrId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? current.ecrId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    function act(btn, path, payload, done) {
        hb.busy(btn, function () {
            return hb.api(API + '/' + current.ecrId + '/' + path, {method: 'POST', body: payload});
        }).then(function (res) {
            if (!res) { return; }
            return load(current.ecrId).then(function () { notify(done); });
        }, function (err) {
            notify(plm.errorText(err), true);
            if (decideForm) { hb.showFieldErrors(decideForm, err.errors); }
        });
    }

    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            plm.reason('js.plmEcr.dlg.cancel', 'js.plmEcr.dlg.cancelReason', 'js.plmEcr.dlg.cancelOk').then(function (reason) {
                if (reason === null) { return; }
                act(cancelBtn, 'cancel', {rowVer: current.rowVer, comment: reason}, hb.t('js.common.saved'));
            });
        });
    }

    var acceptBtn = document.getElementById('acceptBtn');
    if (acceptBtn) {
        acceptBtn.addEventListener('click', function () {
            plm.ask('js.plmEcr.dlg.accept', hb.t('js.plmEcr.acceptConfirm', current.ecrNo), 'js.plmEcr.dlg.acceptOk').then(function (ok) {
                if (!ok) { return; }
                var eco = dfield('ecoId').value;
                act(acceptBtn, 'accept', {rowVer: current.rowVer, ecoId: eco ? Number(eco) : null,
                    effectiveDate: dfield('effectiveDate').value, comment: dfield('comment').value}, hb.t('js.plmEcr.accepted'));
            });
        });
    }
    var rejectBtn = document.getElementById('rejectBtn');
    if (rejectBtn) {
        rejectBtn.addEventListener('click', function () {
            if (!dfield('comment').value.trim()) {
                hb.showFieldErrors(decideForm, [{field: 'comment', message: hb.t('js.plmEcr.commentRequired')}]);
                return;
            }
            act(rejectBtn, 'reject', {rowVer: current.rowVer, comment: dfield('comment').value}, hb.t('js.common.saved'));
        });
    }

    function newEcr() {
        plm.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('title').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newEcr); }
    });

    plm.itemAssist(API + '/items', field('itemCd'), document.getElementById('itemList'));
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        reasonSel = hb.select(field('reasonCd'));
        return Promise.all([hb.loadSession(), hb.codes('PLM_ECR_STATUS'), reasonSel.ready]);
    }).then(function (r) {
        statusNames = r[1];
        sp = hb.searchPanel(search, {onSearch: function () {
            return plm.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return sp.ready;
    }).then(function () {
        show(null);
        // 주 목록 = hb.grid(UI 단계 3) — 상태 배지(plm.TONES)·닫힌 요청 흐림·미저장 이동 확인
        grid = hb.grid('#ecrGrid', {
            progCd: 'plm-ecr', id: 'main', key: 'ecrId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'plm_ecr', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return plm.leaveOk(); },
            status: {status: /** @type {any} */ (plm.TONES.ecr)},
            rowClass: function (row) { return row.status === 'OPEN' ? null : 'hb-tree-dim'; },
            onSelect: function (row) { open(row.ecrId).then(function () { hb.page.openDetail(); }); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
