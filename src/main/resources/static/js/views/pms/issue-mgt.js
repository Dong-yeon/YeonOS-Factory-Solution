// @ts-check
/*
 * 이슈 (pms-issue) — PMS 4단계 (docs/32 PM4, D10).
 * 조회 조건 → 목록 → 오른쪽 폼(등록·수정) + 진행(전이). URL ?objType=ITEM&objCd=품번 이면 그 객체의 이슈로 연다(품목·설비 화면 링크).
 * 판정(행 권한·전이·같은 회사 객체)은 서버.
 */
/**
 * @typedef {{issueId: number, issueNo: string, projId: number, projNo: string, projNm: string, taskId?: number | null,
 *   taskNm?: string | null, title: string, detail?: string | null, severity: string, status: string, ownerLoginId?: string | null,
 *   ownerNm?: string | null, dueDate?: string | null, resolution?: string | null, resolvedOn?: string | null, source: string,
 *   issueType: string, closeKind?: string | null,
 *   gateReviewNo?: number | null, objType?: string | null, objCd?: string | null, objNm?: string | null, rowVer: number,
 *   createdBy: string, late: boolean, canEdit: boolean, canClose: boolean, capaId?: number | null, capaNo?: string | null,
 *   capaStatus?: string | null}} PmsIssue
 */
(function () {
    'use strict';

    var API = '/api/pms/issues';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('issueForm'));
    var statusForm = /** @type {HTMLFormElement} */ (document.getElementById('statusForm'));
    /** @type {PmsIssue | null} */
    var current = null;
    var creating = false;
    /** @type {Record<string, string>} */
    var sevNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** QMS 시정조치 단계 이름(공통 코드 CAPA_STATUS) @type {Record<string, string>} */
    var capaNames = {};
    /** 종료 방식 이름(공통 코드 PMS_ISSUE_CLOSE — 해결 뒤 종료·기각) @type {Record<string, string>} */
    var closeNames = {};
    /** 첨부 대상(sys_file.target_type) — id 는 "I" + 이슈 id */
    var FILE_TARGET = 'pms-issue';
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement | null} [f] */
    function fail(f) {
        /** @param {any} err */
        return function (err) {
            if (err && err.code === 'STALE') { return; }
            notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
            if (f) { hb.showFieldErrors(f, err && err.errors); }
        };
    }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {string} n */
    function field(n) { return /** @type {HTMLInputElement} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** 조회 조건 → grid 파라미터(빈 값 빼고, 내 담당은 true 일 때만) */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        for (var k in v) {
            var x = v[k];
            if (k === 'mine') { if (x) { q.mine = true; } continue; }
            if (x !== null && x !== undefined && x !== '') { q[k] = x; }
        }
        return q;
    }

    // ------------------------------------------------------------------ 목록 (hb.grid — UI 단계 3 ②)

    /** 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 열고(onSelect), 없으면(다른 쪽·조건 밖) 상세만 연다 @param {number | null} [selectId] */
    function loadList(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.issueId !== selectId)) { return open(selectId); }
        });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('issue', hb.api(API + '/' + id)).then(function (res) { show(res.data); }, fail());
    }

    // ------------------------------------------------------------------ 상세

    /** @param {PmsIssue | null} r */
    function show(r) {
        current = r;
        creating = !r;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(statusForm, []);
        var proj = /** @type {HTMLSelectElement} */ (document.getElementById('projSel'));
        if (r && !Array.prototype.some.call(proj.options, /** @param {HTMLOptionElement} o */ function (o) { return o.value === String(r.projId); })) {
            option(proj, String(r.projId), r.projNo + ' ' + r.projNm);
        }
        proj.value = r ? String(r.projId) : (proj.value || '');
        proj.disabled = !!r;
        ['title', 'detail', 'dueDate', 'objCd'].forEach(function (n) {
            /** @type {Record<string, any>} */
            var x = r || {};
            field(n).value = x[n] || '';
        });
        field('severity').value = r ? r.severity : 'MID';
        field('issueType').value = r ? r.issueType : '';
        field('ownerLoginId').value = r && r.ownerLoginId ? r.ownerLoginId : '';
        field('objType').value = r && r.objType ? r.objType : '';
        var edit = CAN_SAVE && (creating || !!(r && r.canEdit));
        form.querySelectorAll('input, select, textarea').forEach(function (i) {
            if (i !== proj) { /** @type {HTMLInputElement} */ (i).disabled = !edit; }
        });
        if (creating) { proj.disabled = !CAN_SAVE; }
        var save = document.getElementById('saveBtn');
        if (save) { save.hidden = !edit; }
        /** @type {HTMLElement} */ (document.getElementById('mode')).textContent = r ? r.issueNo + ' · ' + (statusNames[r.status] || r.status)
            + (r.closeKind ? ' · ' + (closeNames[r.closeKind] || r.closeKind) : '') : hb.t('js.pms.creatingIssue');
        var note = r ? hb.t('js.pms.issueBy', r.createdBy) + (r.source === 'GATE' && r.gateReviewNo ? ' · ' + hb.t('js.pms.fromGate', r.gateReviewNo) : '')
            + (r.objNm ? ' · ' + r.objNm : '')
            + (r.resolution ? ' · ' + (r.closeKind === 'REJECTED' ? hb.t('js.pms.rejectNote', r.resolution, r.resolvedOn || '')
                : hb.t('js.pms.resolutionNote', r.resolution, r.resolvedOn || '')) : '')
            + (r.capaId ? ' · ' + hb.t('js.pms.capaLinked', r.capaNo || String(r.capaId), r.capaStatus ? (capaNames[r.capaStatus] || r.capaStatus) : '') : '') : '';
        /** @type {HTMLElement} */ (document.getElementById('issueNote')).textContent = note;
        loadTasks(proj.value, r && r.taskId ? r.taskId : null);
        // 진행 버튼: 서버 전이 표와 같게 후보만
        /** @type {Record<string, string[]>} */
        var next = {OPEN: ['IN_PROGRESS', 'RESOLVED', 'REJECTED'], IN_PROGRESS: ['OPEN', 'RESOLVED', 'REJECTED'], RESOLVED: ['CLOSED', 'IN_PROGRESS']};
        var box = /** @type {HTMLElement} */ (document.getElementById('statusBox'));
        box.hidden = !(CAN_SAVE && r && (r.canEdit || r.canClose) && r.status !== 'CLOSED');
        document.querySelectorAll('[data-to]').forEach(function (b) {
            var to = b.getAttribute('data-to') || '';
            // 종료·기각 = PM·PMO(canClose), 나머지 = 고칠 수 있는 사람
            var ok = !!r && (next[r.status] || []).indexOf(to) >= 0 && (to === 'CLOSED' || to === 'REJECTED' ? r.canClose : r.canEdit);
            /** @type {HTMLButtonElement} */ (b).hidden = !ok;
        });
        // 시정조치로 올리기(PM6): 고칠 수 있는 열린 이슈, 아직 안 올린 것 — qms-capa 권한은 서버가 본다
        /** @type {HTMLButtonElement} */ (document.getElementById('escalateBtn')).hidden =
            !(CAN_SAVE && r && r.canEdit && !r.capaId && (r.status === 'OPEN' || r.status === 'IN_PROGRESS'));
        /** @type {HTMLTextAreaElement} */ (statusForm.querySelector('[data-field="resolution"]')).value = r && r.resolution ? r.resolution : '';
        hb.dirty.clear(form);
        loadFiles();
    }

    // ------------------------------------------------------------------ 첨부 (docs/32 4.15 — 플랫폼 첨부, 잠금은 서버 IssueFileLock)

    function loadFiles() {
        var box = /** @type {HTMLElement} */ (document.getElementById('fileBox'));
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        var r = current;
        box.hidden = !r;
        if (!r) { return; }
        var mine = r;
        var edit = CAN_SAVE && r.status !== 'CLOSED' && (r.canEdit || r.canClose);
        /** @type {HTMLElement} */ (document.getElementById('filePick')).hidden = !edit;
        hb.latest('files', hb.api('/api/platform/files?targetType=' + FILE_TARGET + '&targetId=I' + r.issueId)).then(function (res) {
            if (current !== mine) { return; }
            (res.list || []).forEach(/** @param {{fileId: number, name: string, size: number}} f */ function (f) {
                var li = hb.el('li');
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.name));
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (edit) {
                    var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        pmsUi.ask(hb.t('js.pms.dlg.fileDelete'), hb.t('js.pms.confirmFileDelete', f.name), hb.t('js.pms.dlg.fileDeleteOk')).then(function (ok) {
                            if (!ok) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}).then(function () { loadFiles(); }, fail());
                        });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
            if (!(res.list || []).length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.noFiles'))); }
        }, function (/** @type {any} */ err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var fileInput = /** @type {HTMLInputElement} */ (document.getElementById('fileInput'));
    fileInput.addEventListener('change', function () {
        var file = fileInput.files && fileInput.files[0];
        var r = current;
        if (!file || !r) { return; }
        var fd = new FormData();
        fd.append('targetType', FILE_TARGET);
        fd.append('targetId', 'I' + r.issueId);
        fd.append('file', file);
        hb.upload('/api/platform/files', fd).then(function () {
            fileInput.value = '';
            notify(hb.t('js.common.saved'));
            loadFiles();
        }, function (/** @type {any} */ err) {
            fileInput.value = '';
            notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
        });
    });

    /** @param {string} projId @param {number | null} value */
    function loadTasks(projId, value) {
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('taskSel'));
        sel.textContent = '';
        option(sel, '', hb.t('js.pms.noTask'));
        if (!projId) { return; }
        hb.latest('tasks', hb.api('/api/pms/issue-projects/' + projId + '/tasks')).then(function (res) {
            res.data.forEach(/** @param {any} t */ function (t) { option(sel, String(t.taskId), t.wbsNo + ' ' + t.taskNm); });
            if (value) { sel.value = String(value); }
        }, fail());
    }

    /** @type {HTMLSelectElement} */ (document.getElementById('projSel')).addEventListener('change', function () {
        loadTasks(/** @type {HTMLSelectElement} */ (document.getElementById('projSel')).value, null);
    });

    // ------------------------------------------------------------------ 버튼

    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newBtn'));
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            pmsUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                show(null);
                hb.page.openDetail();
                field('title').focus();
            });
        });
    }

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            var cur = current;
            /** @type {Record<string, unknown>} */
            var b = {};
            ['title', 'detail', 'severity', 'ownerLoginId', 'dueDate', 'objType', 'objCd', 'issueType'].forEach(function (n) { b[n] = field(n).value; });
            var task = field('taskId').value;
            b.taskId = task ? Number(task) : null;
            if (cur) { b.rowVer = cur.rowVer; } else { b.projId = field('projId').value ? Number(field('projId').value) : null; }
            hb.showFieldErrors(form, []);
            hb.busy(sb, function () {
                return cur ? hb.api(API + '/' + cur.issueId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear(form);
                return loadList(cur ? cur.issueId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(form));
        });
    }

    document.querySelectorAll('[data-to]').forEach(function (el) {
        var btn = /** @type {HTMLButtonElement} */ (el);
        btn.addEventListener('click', function () {
            var cur = /** @type {PmsIssue} */ (current);
            if (!cur) { return; }
            var to = btn.getAttribute('data-to');
            var asked = to === 'CLOSED'
                ? pmsUi.ask(hb.t('js.pms.dlg.closeIssue'), hb.t('js.pms.confirmCloseIssue'), hb.t('js.pms.dlg.closeIssueOk'))
                : to === 'REJECTED'
                    ? pmsUi.ask(hb.t('js.pms.dlg.rejectIssue'), hb.t('js.pms.confirmRejectIssue'), hb.t('js.pms.dlg.rejectIssueOk'))
                    : Promise.resolve(true);
            asked.then(function (ok) {
                if (!ok) { return; }
                var res0 = /** @type {HTMLTextAreaElement} */ (statusForm.querySelector('[data-field="resolution"]')).value;
                hb.busy(btn, function () {
                    return hb.api(API + '/' + cur.issueId + '/status', {method: 'POST', body: {status: to, resolution: res0, rowVer: cur.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return loadList(cur.issueId).then(function () { notify(hb.t('js.common.saved')); });
                }, fail(statusForm));
            });
        });
    });

    var escalateBtn = /** @type {HTMLButtonElement} */ (document.getElementById('escalateBtn'));
    escalateBtn.addEventListener('click', function () {
        var cur = /** @type {PmsIssue} */ (current);
        if (!cur) { return; }
        pmsUi.ask(hb.t('js.pms.dlg.escalate'), hb.t('js.pms.confirmEscalate', cur.issueNo), hb.t('js.pms.dlg.escalateOk')).then(function (ok) {
            if (!ok) { return; }
            hb.busy(escalateBtn, function () {
                return hb.api(API + '/' + cur.issueId + '/escalate', {method: 'POST', body: {rowVer: cur.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return loadList(cur.issueId).then(function () { notify(hb.t('js.pms.escalated')); });
            }, fail());
        });
    });

    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    if (excelBtn) {
        var eb = excelBtn;
        eb.addEventListener('click', function () {
            var v = sp.values();
            var body = {projId: v.projId ? Number(v.projId) : null, status: v.status || null, severity: v.severity || null, mine: !!v.mine,
                objType: v.objType || null, objCd: v.objCd || null, keyword: v.keyword || null, issueType: v.issueType || null};
            hb.busy(eb, function () { return hb.download(API + '/export', body); }).then(null, fail());
        });
    }

    // ------------------------------------------------------------------ 시작

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: function () { return loadList(); }});
        return Promise.all([hb.loadSession(), hb.codeList('PMS_ISSUE_SEVERITY'), hb.codeList('PMS_ISSUE_STATUS'),
            hb.api('/api/pms/issue-projects'), hb.api('/api/pms/issue-users'), sp.ready, hb.codes('CAPA_STATUS'),
            hb.codeList('PMS_ISSUE_TYPE'), hb.codes('PMS_ISSUE_CLOSE')]);
    }).then(function (r) {
        var sev = /** @type {HTMLSelectElement} */ (document.getElementById('sevSel'));
        r[1].forEach(function (c) { sevNames[c.code] = c.name; option(sev, c.code, c.name); });
        r[2].forEach(function (c) { statusNames[c.code] = c.name; });
        capaNames = r[6];
        var typeSel = /** @type {HTMLSelectElement} */ (document.getElementById('typeSel'));
        option(typeSel, '', hb.t('js.pms.chooseType'));
        r[7].forEach(function (/** @type {any} */ c) { option(typeSel, c.code, c.name); });
        closeNames = r[8];
        var projSearch = /** @type {HTMLSelectElement} */ (document.getElementById('projSearch'));
        var projSel = /** @type {HTMLSelectElement} */ (document.getElementById('projSel'));
        option(projSearch, '', hb.t('js.pms.allProjects'));
        option(projSel, '', hb.t('js.pms.chooseProject'));
        r[3].data.forEach(/** @param {any} p */ function (p) {
            option(projSearch, String(p.projId), p.projNo + ' ' + p.projNm);
            option(projSel, String(p.projId), p.projNo + ' ' + p.projNm);
        });
        var owner = /** @type {HTMLSelectElement} */ (document.getElementById('ownerSel'));
        option(owner, '', hb.t('js.pms.noOwner'));
        r[4].data.forEach(/** @param {any} u */ function (u) { option(owner, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        // 품목·설비 화면에서 온 링크: ?objType=ITEM&objCd=… (전체 상태로)
        var urlq = new URLSearchParams(window.location.search);
        if (urlq.get('objType') && urlq.get('objCd')) {
            sp.setValues({objType: urlq.get('objType'), objCd: urlq.get('objCd'), status: 'ALL'});
        }
        var pid = urlq.get('projId');
        if (pid) { sp.setValue('projId', pid); }
        show(null);
        grid = hb.grid('#issueGrid', {
            progCd: 'pms-issue', id: 'main', key: 'issueId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {severity: 'severity', projNo: 'keyword'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            // 다른 행·쪽으로 옮기기 전 미저장 확인(화면 안 대화상자, 취소 = 그대로)
            beforeSelect: function () { return pmsUi.leaveOk(); },
            status: {severity: pmsUi.TONES.severity, status: pmsUi.TONES.issue,
                dueDate: function (v, row) { return row.late ? 'danger' : null; }},   // 기한 지남 = 위험 배지
            rowClass: function (row) { return row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { open(Number(row.issueId)).then(function () { hb.page.openDetail(); }); },
            notify: notify
        });
        return grid.ready.then(function () { return loadList(); });
    }).then(null, function (/** @type {any} */ err) { notify(err && err.message, true); });
})();
