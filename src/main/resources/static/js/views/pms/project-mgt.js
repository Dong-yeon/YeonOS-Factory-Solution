/*
 * 프로젝트 관리 (pms-project) — PMS 1단계 (docs/32 PM1).
 * 목록 → 상세(머리 폼 + WBS·간트 + 작업 폼). 고치는 것은 서버가 행 권한(PM·APPROVE·담당자)으로 다시 판정한다 — 화면은 버튼만 숨긴다.
 * 요약 작업의 날짜·진척·상태는 읽기 전용(하위에서 계산), 저장할 때마다 작업 목록을 다시 읽어 파생 값을 보여 준다.
 */
(function () {
    'use strict';

    var API = '/api/pms/projects';
    var TASK_API = '/api/pms/tasks';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var listView = document.getElementById('listView');
    var detailView = document.getElementById('detailView');
    var projForm = document.getElementById('projForm');
    var taskForm = document.getElementById('taskForm');
    var taskMode = document.getElementById('taskMode');
    var taskNote = document.getElementById('taskNote');
    var headDetails = document.getElementById('headDetails');
    var project = null;        // 상세의 프로젝트(신규 작성 중이면 null)
    /** @type {any} */
    var copySrc = null;        // WBS 복사 원본(신규 작성이 복사일 때, docs/32 4.17)
    var tasks = [];            // 서버 작업(WBS 순서)
    var current = null;        // 폼의 작업(null + newSlot 이면 추가 중)
    var newSlot = null;        // {parentId, afterId}
    var today = null;
    var typeNames = {};
    var deps = [];             // PM2 의존 [{depId, predTaskId, succTaskId, lagDays, violated}]
    var baseline = null;       // 최신 기준선(없으면 null)
    /** 공수 요약(docs/48 R-2) — 계획·확정·미확정·소진율·하루 시간 @type {any} */
    var effort = null;
    var pushPlan = null;       // 밀기 미리보기(확정 때 그대로 보낸다)
    /** 일정변경 요청(docs/32 4.13) — 프로젝트의 요청 목록·작업별 열린 요청·결재자 후보 */
    var chgReqs = [];
    var users = [];
    var chg = null;            // 대화상자에 연 요청 상세
    /** 착수·종료 결재선(docs/32 4.16) — 상신자가 고른다 @type {{userId: number, name: string, lineType: string}[]} */
    var projAprLines = [];
    var sp, gantt, grid;

    /** 서버 전이 표와 같게 — 버튼 후보만 줄이고 판정은 서버 */
    var NEXT = {DRAFT: ['ACTIVE', 'CANCELLED'], ACTIVE: ['ON_HOLD', 'CLOSED', 'CANCELLED'], ON_HOLD: ['ACTIVE', 'CANCELLED']};

    /** 상태 문구 — 키는 글자 그대로 써 둔다(I18nKeyGuardTest) */
    function taskStatusName(s) {
        var m = {NOT_STARTED: hb.t('js.pms.task.NOT_STARTED'), IN_PROGRESS: hb.t('js.pms.task.IN_PROGRESS'),
            DONE: hb.t('js.pms.task.DONE'), CANCELLED: hb.t('js.pms.task.CANCELLED')};
        return m[s] || s;
    }
    function projStatusName(s) {
        var m = {DRAFT: hb.t('js.pms.status.DRAFT'), ACTIVE: hb.t('js.pms.status.ACTIVE'), ON_HOLD: hb.t('js.pms.status.ON_HOLD'),
            CLOSED: hb.t('js.pms.status.CLOSED'), CANCELLED: hb.t('js.pms.status.CANCELLED')};
        return m[s] || s;
    }

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function fail(form) {
        return function (err) {
            notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
            if (form) { hb.showFieldErrors(form, err && err.errors); }
        };
    }

    function pf(n) { return projForm.querySelector('[data-field="' + n + '"]'); }
    function tf(n) { return taskForm.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    // ------------------------------------------------------------------ 목록

    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'projType', 'status'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        if (v.mine) { q.mine = true; }
        return q;
    }

    /** 일정변경 요청 상태 문구 — 키는 글자 그대로(I18nKeyGuardTest) */
    function chgStatusName(s) {
        var m = {REQUESTED: hb.t('js.pms.chg.status.REQUESTED'), IN_APPROVAL: hb.t('js.pms.chg.status.IN_APPROVAL'),
            APPROVED: hb.t('js.pms.chg.status.APPROVED'), REJECTED: hb.t('js.pms.chg.status.REJECTED'),
            WITHDRAWN: hb.t('js.pms.chg.status.WITHDRAWN')};
        return m[s] || s;
    }

    /** 주 목록 = hb.grid(UI 단계 3 ②, 서버 페이징·정렬). 다시 읽으면 선택이 풀려 같은 행을 다시 눌러도 열린다 */
    function loadList() {
        return grid.reload(true);
    }

    /** 목록 행 열기 — 클릭·Enter 만(↑↓ 는 고르기만: 상세는 목록을 덮는 화면이라 키보드 이동마다 열지 않는다) */
    function openSelected() {
        var row = grid.selected();
        if (row) { openProject(row.projId); }
    }

    function showList() {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            detailView.hidden = true;
            listView.hidden = false;
            project = null;
            loadList();
        });
    }

    // ------------------------------------------------------------------ 상세

    function openProject(id, selectTaskId) {
        return pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            return loadProject(id, selectTaskId);
        });
    }

    function loadProject(id, selectTaskId) {
        copySrc = null;
        return hb.latest('project', hb.api(API + '/' + id)).then(function (res) {
            listView.hidden = true;
            detailView.hidden = false;
            showProject(res.data);
            return loadTasks(selectTaskId);
        }, fail());
    }

    function showProject(p) {
        project = p;
        hb.showFieldErrors(projForm, []);
        document.getElementById('projTitle').textContent = p ? p.projNo + ' · ' + p.projNm
            : copySrc ? hb.t('js.pms.copy.title', copySrc.projNo + ' · ' + copySrc.projNm) : hb.t('js.pms.creating');
        var copyBtn = document.getElementById('copyBtn');
        if (copyBtn) { copyBtn.hidden = !p; }
        var toTmplBtn = document.getElementById('toTmplBtn');
        if (toTmplBtn) { toTmplBtn.hidden = !p; }
        /** @type {HTMLElement} */ (document.getElementById('toTmplBox')).hidden = true;
        var st = document.getElementById('projStatus');
        st.textContent = '';
        if (p) { st.appendChild(pmsUi.badge('project', p.status, projStatusName(p.status))); }
        st.hidden = !p;
        document.getElementById('projSummary').textContent = p
            ? hb.t('js.pms.summary', p.planStart || '-', p.planEnd || '-', p.progressPct, p.lateCnt) : '';
        ['projNm', 'custCd', 'remark', 'sopDate'].forEach(function (n) { pf(n).value = p ? (p[n] || '') : ''; });
        pf('projType').value = p ? p.projType : 'APQP';
        pf('plantCd').value = p ? (p.plantCd || '') : '';
        var pmSel = pf('pmLoginId');
        pmSel.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        pmSel.value = p ? p.pmLoginId : '';
        if (p && pmSel.value !== p.pmLoginId) {
            option(pmSel, p.pmLoginId, p.pmUserNm + ' (' + p.pmLoginId + ')');     // 사용 중지된 PM — 비우면 PM 이 바뀌지 않게 그대로 보여 준다
            pmSel.lastChild.setAttribute('data-stale', '');
            pmSel.value = p.pmLoginId;
        }
        pf('itemCds').value = p && p.items ? p.items.map(function (i) { return i.itemCd; }).join(', ') : '';
        pf('tmplCd').value = '';
        pf('startDate').value = '';
        projForm.querySelectorAll('.pms-create-only').forEach(function (e) { e.hidden = !!p; });
        /** @type {HTMLElement} */ (document.getElementById('tmplLabel')).hidden = !!p || !!copySrc;   // 복사는 템플릿 없이
        var open = !p || ['DRAFT', 'ACTIVE', 'ON_HOLD'].indexOf(p.status) >= 0;
        // 착수·종료 결재 중이면 머리·상태를 고치지 않는다(서버도 막는다)
        var manage = CAN_SAVE && open && (!p || p.canManage) && !(p && p.aprTo);
        projForm.querySelectorAll('input, select').forEach(function (i) { i.disabled = !manage; });
        var saveBtn = document.getElementById('saveProjBtn');
        if (saveBtn) { saveBtn.hidden = !manage; }
        headDetails.open = !p;
        var box = document.getElementById('statusBox');
        var sel = document.getElementById('statusTo');
        sel.textContent = '';
        // 정책 켬이면 착수·종료는 결재 상자로(바로 바꾸는 목록에서 뺀다)
        /** @param {string} s */
        var viaApr = function (s) { return !!(p && p.approvalOn && aprTarget(p.status, s)); };
        (p && manage ? NEXT[p.status] || [] : []).forEach(function (s) { if (!viaApr(s)) { option(sel, s, projStatusName(s)); } });
        box.hidden = !sel.options.length;
        renderProjApr(p, manage ? (NEXT[p ? p.status : ''] || []).filter(viaApr) : []);
        document.getElementById('statusReason').value = '';
        document.getElementById('wbsTools').hidden = !p;
        // PLM ECO 연결(PM7) — 저장된 프로젝트만, 연결·해제는 관리자(plm-eco 권한은 서버가 본다)
        var ecoForm = document.getElementById('ecoForm');
        ecoForm.hidden = !p;
        hb.showFieldErrors(ecoForm, []);
        ecoForm.querySelector('[data-field="ecoNo"]').value = p && p.ecoNo ? p.ecoNo : '';
        document.getElementById('ecoInfo').textContent = p && p.ecoId
            ? hb.t('js.pms.ecoInfo', p.ecoNo || String(p.ecoId), p.ecoTitle || '', ecoStatusNames[p.ecoStatus] || p.ecoStatus || '',
                p.ecoCustApproval ? (custAprNames[p.ecoCustApproval] || p.ecoCustApproval) : '-') : hb.t('js.pms.ecoNone');
        ecoForm.querySelector('[data-field="ecoNo"]').disabled = !manage;
        document.getElementById('ecoLinkBtn').hidden = !manage;
        document.getElementById('ecoUnlinkBtn').hidden = !(manage && p && p.ecoId);
        hb.dirty.clear();
    }

    /** 결재 대상 전이 — 착수(작성 중 → 진행)·종료(진행 → 종료). 서버 ProjectService.approvalTarget 과 같다 @param {string} from @param {string} to */
    function aprTarget(from, to) {
        return (from === 'DRAFT' && to === 'ACTIVE') || (from === 'ACTIVE' && to === 'CLOSED');
    }

    /** 착수·종료 결재(docs/32 4.16) — 결재 중 알림, 상신 상자(목표·결재선) @param {any} p @param {string[]} targets */
    function renderProjApr(p, targets) {
        var note = /** @type {HTMLElement} */ (document.getElementById('projAprNotice'));
        note.hidden = !(p && p.aprTo);
        note.textContent = p && p.aprTo ? hb.t('js.pms.projApr.pending', projStatusName(p.aprTo)) : '';
        var box = /** @type {HTMLElement} */ (document.getElementById('projAprBox'));
        box.hidden = !targets.length;
        if (!targets.length) { return; }
        var to = /** @type {HTMLSelectElement} */ (document.getElementById('projAprTo'));
        to.textContent = '';
        targets.forEach(function (s) { option(to, s, projStatusName(s)); });
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('projAprUser'));
        sel.textContent = '';
        var me = String(hb.session().loginId || '').toLowerCase();
        users.filter(function (u) { return u.loginId.toLowerCase() !== me; })
            .forEach(function (u) { option(sel, String(u.userId), u.userNm + ' (' + u.loginId + ')'); });
        // 기본 결재선 = 내 부서장(서버 deptHeadOf, P-3·D-1). 없으면 비워 두고 직접 고르게 — 상신 전에 바꾸거나 지울 수 있다
        projAprLines = p.aprDefaultUserId ? [{userId: p.aprDefaultUserId, lineType: 'APPROVE',
            name: (p.aprDefaultUserNm || '') + ' (' + (p.aprDefaultLoginId || '') + ')'}] : [];
        /** @type {HTMLElement} */ (document.getElementById('projAprHint')).textContent =
            hb.t(p.aprDefaultUserId ? 'js.pms.projApr.headDefault' : 'js.pms.projApr.noHead');
        renderProjAprLines();
    }

    function renderProjAprLines() {
        var ol = /** @type {HTMLElement} */ (document.getElementById('projAprLines'));
        ol.textContent = '';
        /** @type {Record<string, string>} */
        var typeNames = {APPROVE: hb.t('js.pms.projApr.type.APPROVE'), AGREE: hb.t('js.pms.projApr.type.AGREE'),
            REF: hb.t('js.pms.projApr.type.REF')};
        projAprLines.forEach(function (l, i) {
            var li = hb.el('li', null, l.name + ' · ' + (typeNames[l.lineType] || l.lineType) + ' ');
            var x = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', '×'));
            x.type = 'button';
            x.setAttribute('aria-label', hb.t('js.common.remove'));
            x.addEventListener('click', function () { projAprLines.splice(i, 1); renderProjAprLines(); });
            li.appendChild(x);
            ol.appendChild(li);
        });
        /** @type {HTMLButtonElement} */ (document.getElementById('projAprSubmit')).disabled = !projAprLines.length;
    }

    /** @type {HTMLElement} */ (document.getElementById('projAprAdd')).addEventListener('click', function () {
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('projAprUser'));
        var type = /** @type {HTMLSelectElement} */ (document.getElementById('projAprType')).value;
        if (!sel.value || projAprLines.some(function (l) { return String(l.userId) === sel.value; })) { return; }
        projAprLines.push({userId: Number(sel.value), name: sel.options[sel.selectedIndex].text, lineType: type});
        renderProjAprLines();
    });

    var projAprSubmit = /** @type {HTMLButtonElement} */ (document.getElementById('projAprSubmit'));
    projAprSubmit.addEventListener('click', function () {
        if (!project || !projAprLines.length) { return; }
        var p = project;
        var to = /** @type {HTMLSelectElement} */ (document.getElementById('projAprTo')).value;
        pmsUi.ask(hb.t('js.pms.projApr.dlg'), hb.t('js.pms.projApr.confirm', projStatusName(to)), hb.t('js.pms.projApr.dlgOk')).then(function (ok) {
            if (!ok) { return; }
            hb.busy(projAprSubmit, function () {
                return hb.api(API + '/' + p.projId + '/approval', {method: 'POST', body: {to: to, rowVer: p.rowVer,
                    lines: projAprLines.map(function (l) { return {approverId: l.userId, lineType: l.lineType}; })}});
            }).then(function (res) {
                if (!res) { return; }
                return openProject(p.projId).then(function () { notify(hb.t('js.pms.projApr.submitted')); });
            }, fail());
        });
    });

    /** PLM 공통 코드 이름(ECO 상태·고객 승인) */
    var ecoStatusNames = {};
    var custAprNames = {};

    /** @param {string | null} ecoNo */
    function linkEco(ecoNo) {
        if (!project) { return; }
        var f = document.getElementById('ecoForm');
        var p = project;
        hb.showFieldErrors(f, []);
        hb.api(API + '/' + p.projId + '/eco', {method: 'PUT', body: {ecoNo: ecoNo, rowVer: p.rowVer}}).then(function () {
            return openProject(p.projId).then(function () { notify(hb.t('js.common.saved')); });
        }, fail(f));
    }

    document.getElementById('ecoLinkBtn').addEventListener('click', function () {
        linkEco(document.getElementById('ecoForm').querySelector('[data-field="ecoNo"]').value);
    });
    document.getElementById('ecoUnlinkBtn').addEventListener('click', function () {
        pmsUi.ask(hb.t('js.pms.dlg.ecoUnlink'), hb.t('js.pms.confirmEcoUnlink'), hb.t('js.pms.dlg.ecoUnlinkOk')).then(function (ok) {
            if (ok) { linkEco(null); }
        });
    });

    function loadTasks(selectId) {
        if (!project) {
            tasks = [];
            gantt.setRows([], {today: today});
            showTask(null);
            return Promise.resolve();
        }
        return hb.latest('tasks', hb.api(API + '/' + project.projId + '/tasks')).then(function (res) {
            var v = res.data;
            today = v.today;
            project.canManage = v.project.canManage;
            tasks = v.tasks;
            deps = v.deps || [];
            baseline = v.baseline;
            effort = v.effort;
            gantt.setRows(tasks.map(ganttRow), {today: today, links: deps.map(function (d) {
                return {from: d.predTaskId, to: d.succTaskId, bad: d.violated, type: d.depType};
            })});
            document.getElementById('projSummary').textContent =
                hb.t('js.pms.summary', project.planStart || '-', project.planEnd || '-', project.progressPct, project.lateCnt)
                + (baseline ? ' · ' + hb.t('js.pms.baselineInfo', baseline.baselineNo, slipText(v.projectSlipDays)) : '')
                + (deps.some(function (d) { return d.violated; }) ? ' · ' + hb.t('js.pms.depBad') : '')
                + effortText();
            var pick = selectId ? tasks.filter(function (t) { return t.taskId === selectId; })[0] : null;
            if (pick) { gantt.select(pick.taskId); }
            showTask(pick || null);
            updateTools();
            return loadChgReqs();
        }, fail());
    }

    /** 요청 목록(열린 것 먼저) — 있을 때만 접힌 표로 */
    function loadChgReqs() {
        if (!project) { return Promise.resolve(); }
        return hb.latest('chgReqs', hb.api(API + '/' + project.projId + '/chg-reqs')).then(function (res) {
            chgReqs = res.data;
            var body = document.querySelector('#chgTable tbody');
            body.textContent = '';
            chgReqs.forEach(function (r) {
                var tr = hb.el('tr');
                tr.tabIndex = 0;
                [r.wbsNo || '-', r.taskNm || '-', r.beforeStart + ' ~ ' + r.beforeEnd, r.newStart + ' ~ ' + r.newEnd, r.requesterNm]
                    .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                var st = hb.el('td');
                st.appendChild(pmsUi.badge('chg', r.status, chgStatusName(r.status)));
                tr.appendChild(st);
                tr.addEventListener('click', function () { openChg(r.reqId); });
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { openChg(r.reqId); } });
                body.appendChild(tr);
            });
            var open = chgReqs.filter(function (r) { return r.status === 'REQUESTED' || r.status === 'IN_APPROVAL'; }).length;
            var det = document.getElementById('chgDetails');
            det.hidden = !chgReqs.length;
            document.getElementById('chgSummary').textContent = hb.t('js.pms.chg.listSummary', open, chgReqs.length);
            showChgBox(current);
        }, fail());
    }

    function openChgOf(taskId) {
        return chgReqs.filter(function (r) {
            return r.taskId === taskId && (r.status === 'REQUESTED' || r.status === 'IN_APPROVAL');
        })[0] || null;
    }

    /**
     * 작업 폼의 요청 칸 — 잎 작업의 담당자이고 관리 권한이 없을 때 [일정변경 요청], 열린 요청이 있으면 [요청 보기].
     * 판정(담당자·관리자·중복·row_ver)은 서버가 다시 한다
     */
    function showChgBox(t) {
        var box = document.getElementById('chgBox');
        var open = t ? openChgOf(t.taskId) : null;
        var me = String(hb.session().loginId || '').toLowerCase();
        var owner = !!t && String(t.ownerLoginId || '').toLowerCase() === me;
        var canReq = CAN_SAVE && isOpen() && !!t && t.isSummary !== 'Y' && owner && !(project && project.canManage);
        box.hidden = !(canReq || open);
        document.getElementById('chgReqBtn').hidden = !canReq || !!open;
        document.getElementById('chgOpenBtn').hidden = !open;
        document.getElementById('chgInfo').textContent = open
            ? hb.t('js.pms.chg.openInfo', open.newStart + ' ~ ' + open.newEnd, chgStatusName(open.status), open.requesterNm)
            : hb.t('js.pms.chg.howTo');
    }

    function ganttRow(t) {
        var summary = t.isSummary === 'Y';
        return {id: t.taskId, lvl: t.lvl, summary: summary, type: t.taskType, start: t.planStart, end: t.planEnd,
            progress: t.progressPct, late: t.late, dim: t.status === 'CANCELLED',
            baseStart: t.baseStart, baseEnd: t.baseEnd, depBad: t.depViolation,
            title: t.wbsNo + ' ' + t.taskNm + ' · ' + t.planStart + ' ~ ' + t.planEnd + ' · ' + t.progressPct + '%',
            cells: {wbs: t.wbsNo, name: t.taskNm, owner: t.ownerNm || '', start: t.planStart, end: t.planEnd,
                pct: t.progressPct + '%', status: taskStatusName(t.status), slip: slipText(t.slipDays), hours: hoursCell(t)}};
    }

    /** 프로젝트 공수(docs/48 R-2) — 계획이 있거나 실적이 있을 때만. 실적 = 확정, 미확정은 따로 */
    function effortText() {
        if (!effort || (effort.planHours === null && !Number(effort.confHours) && !Number(effort.draftHours))) { return ''; }
        var h = effort.hoursPerDay;
        return ' · ' + hb.t('js.pms.effortInfo', pmsUi.hours(effort.planHours, h) || '-', pmsUi.hours(effort.confHours, h),
            pmsUi.hours(effort.draftHours, h), effort.burnPct === null ? '-' : effort.burnPct + '%');
    }

    /** WBS 공수 칸 — "확정/계획"(시간), 둘 다 없으면 빈칸 */
    function hoursCell(t) {
        if (t.planHours === null && !t.confHours) { return ''; }
        return hb.fmt.num(t.confHours || 0, 1, 0) + '/' + (t.planHours === null ? '-' : hb.fmt.num(t.planHours, 1, 0));
    }

    /** 기준선 대비 지연 일수 글자: +3 / -2 / 0 / (기준선 없음 = 빈칸) */
    function slipText(n) {
        if (n === null || n === undefined) { return ''; }
        return n > 0 ? '+' + n : String(n);
    }

    /** 계획 변경 이력 출처 — 키는 글자 그대로 */
    function sourceName(s) {
        var m = {PUSH: hb.t('js.pms.source.PUSH'), EDIT: hb.t('js.pms.source.EDIT'), REQ: hb.t('js.pms.source.REQ')};
        return m[s] || s;
    }

    function findTask(id) { return tasks.filter(function (t) { return t.taskId === id; })[0] || null; }

    /** 작업 폼: t = 고른 작업, null + newSlot = 추가 중, 둘 다 없으면 빈 폼(잠김) */
    function showTask(t) {
        current = t;
        hb.showFieldErrors(taskForm, []);
        var adding = !t && !!newSlot;
        ['taskNm', 'planStart', 'planEnd', 'actStart', 'actEnd', 'remark'].forEach(function (n) { tf(n).value = t ? (t[n] || '') : ''; });
        tf('taskType').value = t ? t.taskType : 'TASK';
        tf('ownerLoginId').value = t ? (t.ownerLoginId || '') : '';
        tf('progressPct').value = t ? String(t.progressPct) : '0';
        tf('planHours').value = t && t.planHours !== null ? String(t.planHours) : '';
        // 상태는 실적일로 정해지게 비워 둔다(취소만 그대로) — 실적을 넣고 상태를 안 바꿔도 모순 오류가 나지 않게
        tf('status').value = t && t.status === 'CANCELLED' ? 'CANCELLED' : '';
        var summary = t && t.isSummary === 'Y';
        var manage = CAN_SAVE && project && project.canManage && isOpen();
        var edit = CAN_SAVE && isOpen() && (adding ? manage : t && t.canEdit);
        // 담당자(관리 권한 없음)는 진행 칸만, 요약은 날짜·진척·상태가 자동
        var structural = ['taskNm', 'taskType', 'ownerLoginId', 'planStart', 'planEnd', 'planHours'];
        var progress = ['actStart', 'actEnd', 'progressPct', 'status'];
        taskForm.querySelectorAll('input, select').forEach(function (i) {
            var n = i.getAttribute('data-field');
            var on = edit && (manage || structural.indexOf(n) < 0);
            if (summary && (progress.indexOf(n) >= 0 || n === 'planStart' || n === 'planEnd' || n === 'planHours')) { on = false; }
            i.disabled = !on;
        });
        if (adding) {
            var par = newSlot.parentId ? findTask(newSlot.parentId) : null;
            taskMode.textContent = par ? hb.t('js.pms.addingUnder', par.wbsNo + ' ' + par.taskNm) : hb.t('js.pms.addingRoot');
            tf('planStart').value = par ? par.planStart : (today || '');
        } else {
            taskMode.textContent = t ? hb.t('js.pms.editingTask', t.wbsNo, t.taskNm) : hb.t('js.pms.selectTask');
        }
        taskNote.textContent = summary ? hb.t('js.pms.summaryNote') : (t && !t.canEdit ? hb.t('js.pms.readOnlyNote') : '');
        if (t && t.gateWarn) { taskNote.textContent += (taskNote.textContent ? ' ' : '') + hb.t('js.pms.gateWarn', t.gateWarn); }
        if (t && t.taskType === 'GATE') { taskNote.textContent += (taskNote.textContent ? ' ' : '') + hb.t('js.pms.gateByReview'); }
        // 실적 공수(확정·미확정 따로, 요약은 아래 작업 합)
        if (t && (Number(t.confHours) || Number(t.draftHours))) {
            var hpd = effort ? effort.hoursPerDay : 8;
            taskNote.textContent += (taskNote.textContent ? ' ' : '') + hb.t('js.pms.taskHours', pmsUi.hours(t.confHours || 0, hpd),
                pmsUi.hours(t.draftHours || 0, hpd));
        }
        // 기준선 뒤 잎 작업의 계획일을 고칠 수 있는 사람에게만 사유 칸
        tf('planReason').value = '';
        document.getElementById('planReasonBox').hidden = !(baseline && t && !summary && manage && edit);
        // 근무일 보조 — 계획 종료를 고칠 수 있는 잎 작업(TASK)만
        wdIn.disabled = tf('planEnd').disabled || tf('taskType').value !== 'TASK';
        wdBtn.disabled = wdIn.disabled;
        showWorkdays();
        showDeps(t, manage && edit);
        showChgBox(t);
        var saveBtn = document.getElementById('saveTaskBtn');
        if (saveBtn) { saveBtn.hidden = !edit; }
        hb.dirty.clear(taskForm);
    }

    function isOpen() { return project && ['DRAFT', 'ACTIVE', 'ON_HOLD'].indexOf(project.status) >= 0; }

    // ------------------------------------------------------------------ 근무일 입력 보조(docs/32 4.25, P-13)

    var wdIn = /** @type {HTMLInputElement} */ (document.getElementById('workdaysIn'));
    var wdBtn = /** @type {HTMLButtonElement} */ (document.getElementById('workdaysFromHours'));

    /** 계산만 서버(회사 휴일 달력) — 저장은 날짜, 다른 계산은 달력일 그대로 @param {Record<string, string>} q */
    function workdayCalc(q) {
        return hb.latest('workdays', hb.api('/api/pms/workdays?' + new URLSearchParams(q).toString()));
    }

    /** @param {any} err */
    function workdayFail(err) {
        if (err && err.code === 'STALE') { return; }
        fail(null)(err);
    }

    /** 계획 시작~종료의 근무일 수를 보조 칸에 */
    function showWorkdays() {
        var s = tf('planStart').value;
        var e = tf('planEnd').value;
        if (!s || !e || e < s || wdIn.disabled) { wdIn.value = ''; return; }
        workdayCalc({start: s, end: e}).then(function (res) { wdIn.value = String(res.data.workdays); }, workdayFail);
    }

    /** 계획 종료를 채우고 폼을 고친 것으로 @param {string} end */
    function setPlanEnd(end) {
        var el = tf('planEnd');
        el.value = end;
        el.dispatchEvent(new Event('input', {bubbles: true}));
    }

    wdIn.addEventListener('change', function () {
        var n = Number(wdIn.value);
        if (!tf('planStart').value || !(n >= 1) || Math.floor(n) !== n) { return; }
        workdayCalc({start: tf('planStart').value, days: String(n)}).then(function (res) { setPlanEnd(res.data.end); }, workdayFail);
    });
    wdBtn.addEventListener('click', function () {
        var h = tf('planHours').value;
        if (!tf('planStart').value || !h || !(Number(h) > 0)) { notify(hb.t('js.pms.workdaysNeedHours'), true); return; }
        workdayCalc({start: tf('planStart').value, hours: h}).then(function (res) {
            setPlanEnd(res.data.end);
            wdIn.value = String(res.data.workdays);
            notify(hb.t('js.pms.workdaysFromHoursDone', res.data.workdays, res.data.hoursPerDay));
        }, workdayFail);
    });
    ['planStart', 'planEnd'].forEach(function (n) { tf(n).addEventListener('change', showWorkdays); });
    tf('taskType').addEventListener('change', function () {
        wdIn.disabled = tf('planEnd').disabled || tf('taskType').value !== 'TASK';
        wdBtn.disabled = wdIn.disabled;
        showWorkdays();
    });

    /** 선행 작업 목록 + 추가 폼(잎 작업만, 자기 자신 빼고) — 의존은 잎끼리만(docs/32 4.4) */
    function showDeps(t, manage) {
        var box = document.getElementById('depBox');
        var list = document.getElementById('predList');
        document.getElementById('historyList').textContent = '';
        box.hidden = !t || t.isSummary === 'Y';
        list.textContent = '';
        if (box.hidden) { return; }
        var preds = deps.filter(function (d) { return d.succTaskId === t.taskId; });
        if (!preds.length) { list.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.noPreds'))); }
        preds.forEach(function (d) {
            var p = findTask(d.predTaskId);
            var li = hb.el('li', d.violated ? 'pms-late' : null,
                (p ? p.wbsNo + ' ' + p.taskNm : '?') + pmsUi.depTypeTag(d.depType) + (d.lagDays ? ' (' + hb.t('js.pms.lag', slipText(d.lagDays)) + ')' : '')
                + (d.violated ? ' — ' + hb.t('js.pms.violated') : ''));
            if (manage) {
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.pms.removeDep'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    hb.busy(del, function () { return hb.api('/api/pms/deps/' + d.depId, {method: 'DELETE'}); })
                        .then(function (res) { if (res) { return refreshProject(t.taskId); } }, fail());
                });
                li.appendChild(del);
            }
            list.appendChild(li);
        });
        var form = document.getElementById('depForm');
        form.hidden = !manage;
        var sel = document.getElementById('predSel');
        sel.textContent = '';
        tasks.filter(function (x) { return x.isSummary !== 'Y' && x.taskId !== t.taskId; }).forEach(function (x) {
            option(sel, String(x.taskId), x.wbsNo + ' ' + x.taskNm);
        });
        form.querySelector('[data-field="lagDays"]').value = '0';
        form.querySelector('[data-field="depType"]').value = 'FS';
        hb.showFieldErrors(form, []);
    }

    function updateTools() {
        var manage = CAN_SAVE && project && project.canManage && isOpen();
        var sel = current;
        document.getElementById('baselineBtn').hidden = !manage;
        document.getElementById('pushBtn').hidden = !manage;
        document.getElementById('addChildBtn').hidden = !manage;
        document.getElementById('addSiblingBtn').hidden = !manage;
        document.getElementById('deleteTaskBtn').hidden = !manage;
        document.querySelectorAll('[data-move]').forEach(function (b) { b.hidden = !manage; b.disabled = !sel; });
        document.getElementById('deleteTaskBtn').disabled = !sel;
        document.getElementById('addSiblingBtn').disabled = !sel;
    }

    function startAdd(parentId, afterId) {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            newSlot = {parentId: parentId, afterId: afterId};
            showTask(null);
            hb.page.openDetail();
            tf('taskNm').focus();
        });
    }

    function taskBody() {
        var b = {};
        ['taskNm', 'taskType', 'ownerLoginId', 'planStart', 'planEnd', 'actStart', 'actEnd', 'status', 'remark']
            .forEach(function (n) { b[n] = tf(n).value; });
        var pct = tf('progressPct').value;
        b.progressPct = pct === '' ? null : Number(pct);
        var ph = tf('planHours').value;
        b.planHours = ph === '' ? null : Number(ph);
        b.planReason = tf('planReason').value;
        return b;
    }

    // ------------------------------------------------------------------ 버튼

    document.getElementById('backBtn').addEventListener('click', showList);

    var newBtn = document.getElementById('newBtn');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            copySrc = null;
            listView.hidden = true;
            detailView.hidden = false;
            newSlot = null;
            showProject(null);
            loadTasks();
            pf('projNm').focus();
        });
    }

    // 템플릿으로 저장(docs/32 4.18) — 작업·상대 일정·표준 공수·선후행·산출물 칸·점검 문구. 이름 비우면 프로젝트명
    var toTmplForm = /** @type {HTMLFormElement} */ (document.getElementById('toTmplForm'));
    var toTmplBtn = document.getElementById('toTmplBtn');
    if (toTmplBtn) {
        toTmplBtn.addEventListener('click', function () {
            if (!project) { return; }
            var box = /** @type {HTMLElement} */ (document.getElementById('toTmplBox'));
            box.hidden = !box.hidden;
            hb.showFieldErrors(toTmplForm, []);
            /** @type {HTMLInputElement} */ (toTmplForm.querySelector('[data-field="tmplCd"]')).value = '';
            /** @type {HTMLInputElement} */ (toTmplForm.querySelector('[data-field="tmplNm"]')).value = project.projNm;
            if (!box.hidden) { /** @type {HTMLInputElement} */ (toTmplForm.querySelector('[data-field="tmplCd"]')).focus(); }
        });
        /** @type {HTMLElement} */ (document.getElementById('toTmplCancel')).addEventListener('click', function () {
            /** @type {HTMLElement} */ (document.getElementById('toTmplBox')).hidden = true;
        });
        var toTmplSave = /** @type {HTMLButtonElement} */ (document.getElementById('toTmplSave'));
        toTmplSave.addEventListener('click', function () {
            if (!project) { return; }
            var p = project;
            hb.showFieldErrors(toTmplForm, []);
            var body = {tmplCd: /** @type {HTMLInputElement} */ (toTmplForm.querySelector('[data-field="tmplCd"]')).value,
                tmplNm: /** @type {HTMLInputElement} */ (toTmplForm.querySelector('[data-field="tmplNm"]')).value};
            hb.busy(toTmplSave, function () {
                return hb.api('/api/pms/templates/from-project/' + p.projId, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                /** @type {HTMLElement} */ (document.getElementById('toTmplBox')).hidden = true;
                notify(hb.t('js.pms.toTmpl.done', String(body.tmplCd).toUpperCase()));
            }, fail(toTmplForm));
        });
    }

    var copyBtn = document.getElementById('copyBtn');
    if (copyBtn) {
        // 이 프로젝트의 WBS(작업·선후행·산출물 정의)로 새 프로젝트 — 머리는 새로 쓰고 시작일로 일정을 옮긴다(서버가 복사)
        copyBtn.addEventListener('click', function () {
            if (!project) { return; }
            var src = project;
            pmsUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                hb.dirty.clear();
                copySrc = src;
                newSlot = null;
                showProject(null);
                pf('projNm').value = hb.t('js.pms.copy.name', src.projNm);
                pf('projType').value = src.projType;
                pf('custCd').value = src.custCd || '';
                pf('plantCd').value = src.plantCd || '';
                loadTasks();
                pf('startDate').focus();
            });
        });
    }

    var saveProjBtn = document.getElementById('saveProjBtn');
    if (saveProjBtn) {
        saveProjBtn.addEventListener('click', function () {
            hb.showFieldErrors(projForm, []);
            var b = {};
            ['projNm', 'projType', 'custCd', 'plantCd', 'pmLoginId', 'sopDate', 'remark', 'tmplCd', 'startDate']
                .forEach(function (n) { b[n] = pf(n).value; });
            b.itemCds = pf('itemCds').value.split(/[\s,]+/).filter(function (x) { return x; });
            var editing = !!project;
            if (editing) { b.rowVer = project.rowVer; delete b.tmplCd; delete b.startDate; }
            var src = editing ? null : copySrc;
            if (src) { delete b.tmplCd; }
            hb.busy(saveProjBtn, function () {
                return editing ? hb.api(API + '/' + project.projId, {method: 'PUT', body: b})
                    : src ? hb.api(API + '/' + src.projId + '/copy', {method: 'POST', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return openProject(editing ? project.projId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(projForm));
        });
    }

    document.getElementById('statusBtn').addEventListener('click', function () {
        var btn = this;
        var to = document.getElementById('statusTo').value;
        // 취소는 되돌릴 수 없어(종료 → 재개는 됨) 위험 단추로 묻는다
        pmsUi.ask(hb.t('js.pms.dlg.status'), hb.t('js.pms.confirmStatus', projStatusName(to)), hb.t('js.pms.dlg.statusOk'), to === 'CANCELLED').then(function (ok) {
            if (!ok) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + project.projId + '/status', {method: 'POST',
                    body: {status: to, reason: document.getElementById('statusReason').value, rowVer: project.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return openProject(project.projId).then(function () { notify(hb.t('js.common.saved')); });
            }, fail());
        });
    });

    var saveTaskBtn = document.getElementById('saveTaskBtn');
    if (saveTaskBtn) {
        saveTaskBtn.addEventListener('click', function () {
            hb.showFieldErrors(taskForm, []);
            var b = taskBody();
            var adding = !current && newSlot;
            if (!current && !adding) { return; }
            if (adding) { b.parentId = newSlot.parentId; b.afterId = newSlot.afterId; } else { b.rowVer = current.rowVer; }
            var t = current;
            /** @param {string | null} depReason */
            function save(depReason) {
                if (depReason) { b.depReason = depReason; }
                return hb.busy(saveTaskBtn, function () {
                    return adding ? hb.api(API + '/' + project.projId + '/tasks', {method: 'POST', body: b})
                        : hb.api(TASK_API + '/' + t.taskId, {method: 'PUT', body: b});
                }).then(function (res) {
                    if (!res) { return; }
                    var id = adding ? res.data : t.taskId;
                    // PM3: 정책 WARN — 앞 게이트 통과 전 착수(저장은 됐다)
                    var warn = !adding && Array.isArray(res.data) && res.data.length ? hb.t('js.pms.gateWarnSaved', res.data.join(', ')) : '';
                    newSlot = null;
                    hb.dirty.clear();
                    return refreshProject(id).then(function () { notify(warn || hb.t('js.common.saved'), !!warn); });
                }, function (err) {
                    // 선행 미완 실적(정책 PMS_DEP_ENFORCE=BLOCK, docs/32 4.21) — PM 은 사유를 적으면 통과(기록됨). 취소 = 저장 안 함
                    var dep = err && err.errors && err.errors.filter(function (x) { return x.field === 'depReason'; })[0];
                    if (dep && !depReason) {
                        return pmsUi.reason(hb.t('js.pms.depWaive.dlg'), dep.message, hb.t('js.pms.depWaive.ok')).then(function (r) {
                            if (r) { return save(r); }
                        });
                    }
                    fail(taskForm)(err);
                });
            }
            save(null);
        });
    }

    /** 머리 요약(기간·진척)도 바뀌므로 둘 다 다시 */
    function refreshProject(selectId) {
        return hb.api(API + '/' + project.projId).then(function (res) {
            var keepOpen = headDetails.open;
            showProject(res.data);
            headDetails.open = keepOpen;
            return loadTasks(selectId);
        });
    }

    document.getElementById('addChildBtn').addEventListener('click', function () {
        startAdd(current ? current.taskId : null, null);
    });
    document.getElementById('addSiblingBtn').addEventListener('click', function () {
        if (current) { startAdd(current.parentId, current.taskId); }
    });

    document.querySelectorAll('[data-move]').forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            var id = current.taskId;
            hb.busy(b, function () {
                return hb.api(TASK_API + '/' + id + '/move', {method: 'POST', body: {op: b.getAttribute('data-move'), rowVer: current.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return refreshProject(id).then(function () { gantt.focus(); });
            }, fail());
        });
    });

    document.getElementById('deleteTaskBtn').addEventListener('click', function () {
        if (!current) { return; }
        var btn = this;
        var t = current;
        pmsUi.ask(hb.t('js.pms.dlg.deleteTask'), hb.t('js.pms.confirmDelete', t.wbsNo + ' ' + t.taskNm), hb.t('js.pms.dlg.delete'), true).then(function (ok) {
            if (!ok) { return; }
            hb.busy(btn, function () { return hb.api(TASK_API + '/' + t.taskId, {method: 'DELETE'}); })
                .then(function (res) {
                    if (!res) { return; }
                    return refreshProject(t.parentId).then(function () { notify(hb.t('js.pms.deleted')); });
                }, fail());
        });
    });

    document.querySelectorAll('#scaleSeg input').forEach(function (r) {
        r.addEventListener('change', function () { if (r.checked) { gantt.setScale(r.value); } });
    });
    document.getElementById('todayBtn').addEventListener('click', function () { gantt.scrollToToday(); });
    document.getElementById('collapseBtn').addEventListener('click', function () { gantt.collapseTo(1); });
    document.getElementById('expandBtn').addEventListener('click', function () { gantt.expandAll(); });

    // ------------------------------------------------------------------ PM2 의존·기준선·밀기

    document.getElementById('addDepBtn').addEventListener('click', function () {
        var btn = this;
        var form = document.getElementById('depForm');
        hb.showFieldErrors(form, []);
        var lag = form.querySelector('[data-field="lagDays"]').value;
        var body = {predTaskId: Number(document.getElementById('predSel').value), succTaskId: current.taskId,
            lagDays: lag === '' ? 0 : Number(lag), depType: form.querySelector('[data-field="depType"]').value};
        var id = current.taskId;
        /** @param {string | null} depReason */
        function add(depReason) {
            if (depReason) { body.depReason = depReason; }
            return hb.busy(btn, function () { return hb.api(API + '/' + project.projId + '/deps', {method: 'POST', body: body}); })
                .then(function (res) {
                    if (!res) { return; }
                    return refreshProject(id).then(function () { notify(hb.t('js.common.saved')); });
                }, function (err) {
                    // 이미 시작한 작업에 미완 선행(정책 BLOCK, docs/32 4.21 E7) — 실적 시작과 같게 사유를 물어 다시 보낸다. 취소 = 추가 안 함
                    var dep = err && err.errors && err.errors.filter(function (x) { return x.field === 'depReason'; })[0];
                    if (dep && !depReason) {
                        return pmsUi.reason(hb.t('js.pms.depWaive.addDlg'), dep.message, hb.t('js.pms.depWaive.addOk')).then(function (r) {
                            if (r) { return add(r); }
                        });
                    }
                    fail(form)(err);     // 서버 칸 이름(predTaskId·lagDays)이 폼 칸과 같다
                });
        }
        add(null);
    });

    document.getElementById('historyBtn').addEventListener('click', function () {
        var list = document.getElementById('historyList');
        var btn = this;
        // 계획 변경 이력 + 선행 미완 통과 기록(docs/32 4.21)
        hb.busy(btn, function () {
            return hb.latest('history', Promise.all([hb.api(TASK_API + '/' + current.taskId + '/plan-changes'),
                hb.api(TASK_API + '/' + current.taskId + '/dep-waivers')]));
        }).then(function (r) {
                if (!r) { return; }
                var res = r[0];
                /** @type {any[]} */
                var waives = r[1].data;
                list.textContent = '';
                if (!res.data.length && !waives.length) { list.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.noHistory'))); }
                res.data.forEach(function (c) {
                    list.appendChild(hb.el('li', null, hb.t('js.pms.historyRow', c.beforeStart + ' ~ ' + c.beforeEnd,
                        c.afterStart + ' ~ ' + c.afterEnd, sourceName(c.source),
                        c.reason, c.createdBy, hb.fmt.datetime(c.createdAt))));
                });
                waives.forEach(function (w) {
                    list.appendChild(hb.el('li', null, hb.t('js.pms.depWaive.row', w.openPreds, w.reason, w.createdBy, hb.fmt.datetime(w.createdAt))));
                });
            }, fail());
    });

    document.getElementById('baselineBtn').addEventListener('click', function () {
        var btn = this;
        // 사유 필수(취소 = 아무 일 없음) — 기준선은 계획 변경 이력의 기준이라 왜 다시 잡았는지 남긴다
        pmsUi.reason(hb.t('js.pms.dlg.baseline', baseline ? baseline.baselineNo + 1 : 1), hb.t('js.pms.dlg.baselineReason'),
            hb.t('js.pms.dlg.baselineOk')).then(function (reason) {
            if (reason === null) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + project.projId + '/baselines', {method: 'POST', body: {reason: reason}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return refreshProject(current ? current.taskId : null).then(function () {
                        notify(hb.t('js.pms.baselineSet', res.data));
                    });
                }, fail());
        });
    });

    var pushDialog = document.getElementById('pushDialog');
    document.getElementById('pushBtn').addEventListener('click', function () {
        var btn = this;
        hb.busy(btn, function () { return hb.latest('push', hb.api(API + '/' + project.projId + '/push-preview')); })
            .then(function (res) {
                if (!res) { return; }
                pushPlan = res.data;
                var body = document.querySelector('#pushTable tbody');
                body.textContent = '';
                pushPlan.moves.forEach(function (m) {
                    var tr = hb.el('tr');
                    [m.wbsNo, m.taskNm, m.oldStart + ' ~ ' + m.oldEnd, m.newStart + ' ~ ' + m.newEnd, '+' + m.shiftDays]
                        .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                    body.appendChild(tr);
                });
                document.getElementById('pushTable').hidden = !pushPlan.moves.length;
                document.getElementById('pushInfo').textContent = pushPlan.moves.length
                    ? hb.t('js.pms.pushInfo', pushPlan.moves.length) : hb.t('js.pms.pushNothing');
                document.getElementById('pushFixed').textContent = pushPlan.fixed.length
                    ? hb.t('js.pms.pushFixed', pushPlan.fixed.map(function (m) { return m.wbsNo + ' ' + m.taskNm; }).join(', ')) : '';
                document.getElementById('pushReasonBox').hidden = !pushPlan.needsReason || !pushPlan.moves.length;
                document.getElementById('pushReason').value = '';
                document.getElementById('pushApply').hidden = !pushPlan.moves.length;
                pushDialog.showModal();
            }, fail());
    });
    document.getElementById('pushCancel').addEventListener('click', function () { pushDialog.close(); });
    document.getElementById('pushApply').addEventListener('click', function () {
        var btn = this;
        var body = {reason: document.getElementById('pushReason').value,
            expected: pushPlan.moves.map(function (m) { return {taskId: m.taskId, newStart: m.newStart}; })};
        hb.busy(btn, function () { return hb.api(API + '/' + project.projId + '/push', {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                pushDialog.close();
                return refreshProject(current ? current.taskId : null).then(function () { notify(hb.t('js.pms.pushed', res.data)); });
            }, function (err) {
                notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
                // 409 = 그 사이 계획이 바뀜 → 닫고 다시 미리보기 하게
                if (err && err.status === 409) { pushDialog.close(); }
            });
    });


    // ------------------------------------------------------------------ 일정변경 요청 (docs/32 4.13)

    var chgReqDialog = document.getElementById('chgReqDialog');
    var chgReqForm = document.getElementById('chgReqForm');
    function cf(n) { return chgReqForm.querySelector('[data-field="' + n + '"]'); }

    document.getElementById('chgReqBtn').addEventListener('click', function () {
        var btn = this;
        var t = current;
        if (!t) { return; }
        hb.busy(btn, function () { return hb.api(TASK_API + '/' + t.taskId + '/chg-info'); }).then(function (res) {
            if (!res) { return; }
            var info = res.data;
            hb.showFieldErrors(chgReqForm, []);
            document.getElementById('chgReqTask').textContent = hb.t('js.pms.chg.reqTask', t.wbsNo + ' ' + t.taskNm,
                t.planStart + ' ~ ' + t.planEnd);
            cf('newStart').value = t.planStart;
            cf('newEnd').value = t.planEnd;
            cf('reason').value = '';
            var sel = document.getElementById('chgApproverSel');
            sel.textContent = '';
            var me = String(hb.session().loginId || '').toLowerCase();
            users.filter(function (u) { return u.loginId.toLowerCase() !== me; }).forEach(function (u) {
                option(sel, String(u.userId), u.userNm + ' (' + u.loginId + ')' + (u.userId === info.pmUserId ? ' — PM' : ''));
            });
            sel.value = String(info.pmUserId);
            document.getElementById('chgApproverBox').hidden = !info.approvalRequired;
            document.getElementById('chgReqNote').textContent = info.approvalRequired ? hb.t('js.pms.chg.viaApproval')
                : hb.t('js.pms.chg.viaPm', info.pmUserNm);
            chgReqDialog.dataset.approval = info.approvalRequired ? 'Y' : 'N';
            chgReqDialog.showModal();
            cf('newStart').focus();
        }, fail());
    });
    document.getElementById('chgReqCancel').addEventListener('click', function () { chgReqDialog.close(); });
    document.getElementById('chgReqSubmit').addEventListener('click', function () {
        var btn = this;
        var t = current;
        hb.showFieldErrors(chgReqForm, []);
        var body = {newStart: cf('newStart').value, newEnd: cf('newEnd').value, reason: cf('reason').value, rowVer: t.rowVer};
        var approver = document.getElementById('chgApproverSel').value;
        if (chgReqDialog.dataset.approval === 'Y' && approver) { body.approverId = Number(approver); }
        hb.busy(btn, function () { return hb.api(TASK_API + '/' + t.taskId + '/chg-reqs', {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                chgReqDialog.close();
                return refreshProject(t.taskId).then(function () { notify(hb.t('js.pms.chg.requested')); });
            }, function (err) {
                fail(chgReqForm)(err);
                // 409 = 그사이 작업이 바뀌었거나 이미 요청 중 → 닫고 다시 읽게
                if (err && err.status === 409) { chgReqDialog.close(); refreshProject(t.taskId); }
            });
    });
    document.getElementById('chgOpenBtn').addEventListener('click', function () {
        var open = current ? openChgOf(current.taskId) : null;
        if (open) { openChg(open.reqId); }
    });

    var chgDialog = document.getElementById('chgDialog');

    /** 요청 상세 — 프로젝트가 안 열렸거나 다른 프로젝트면 먼저 연다(결재함 "문서 열기") */
    function openChg(reqId) {
        return hb.api('/api/pms/chg-reqs/' + reqId).then(function (res) {
            var d = res.data;
            var go = project && project.projId === d.req.projId ? Promise.resolve() : openProject(d.req.projId, d.req.taskId);
            return go.then(function () { showChg(d); });
        }, fail());
    }

    function showChg(d) {
        chg = d;
        var r = d.req;
        var open = r.status === 'REQUESTED' || r.status === 'IN_APPROVAL';
        document.getElementById('chgTitle').textContent = hb.t('js.pms.chg.dlgTitle', chgStatusName(r.status));
        document.getElementById('chgHead').textContent = hb.t('js.pms.chg.head', (r.wbsNo || '-') + ' ' + (r.taskNm || '-'),
            r.requesterNm, hb.fmt.datetime(r.createdAt));
        document.getElementById('chgPlan').textContent = hb.t('js.pms.chg.plan', r.beforeStart + ' ~ ' + r.beforeEnd,
            r.newStart + ' ~ ' + r.newEnd, d.curStart ? d.curStart + ' ~ ' + d.curEnd : '-');
        document.getElementById('chgReason').textContent = hb.t('js.pms.chg.reasonText', r.reason);
        document.getElementById('chgWarn').hidden = !d.taskChanged;
        var body = document.querySelector('#chgPreview tbody');
        body.textContent = '';
        d.preview.forEach(function (m) {
            var tr = hb.el('tr');
            [m.wbsNo, m.taskNm, m.oldStart + ' ~ ' + m.oldEnd, m.newStart + ' ~ ' + m.newEnd, '+' + m.shiftDays]
                .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
            body.appendChild(tr);
        });
        document.getElementById('chgPreview').hidden = !d.preview.length;
        document.getElementById('chgPreviewInfo').textContent = !open ? ''
            : d.preview.length ? hb.t('js.pms.chg.previewInfo', d.preview.length) : hb.t('js.pms.chg.previewNone');
        document.getElementById('chgFixed').textContent = open && d.fixed.length
            ? hb.t('js.pms.pushFixed', d.fixed.map(function (m) { return m.wbsNo + ' ' + m.taskNm; }).join(', ')) : '';
        var lines = document.getElementById('chgLines');
        lines.textContent = '';
        d.approval.lines.forEach(function (l) {
            lines.appendChild(hb.el('li', null, l.seq + '. ' + l.approverNm + ' · ' + l.status + (l.comment ? ' — ' + l.comment : '')));
        });
        document.getElementById('chgDecided').textContent = r.decidedAt
            ? hb.t('js.pms.chg.decided', chgStatusName(r.status), r.decidedBy, hb.fmt.datetime(r.decidedAt), r.decideNote || '') : '';
        var canAct = d.canDecide || d.approval.myTurn;
        var canWithdraw = d.canWithdraw || d.approval.canWithdraw;
        document.getElementById('chgApprove').hidden = !canAct;
        document.getElementById('chgApprove').disabled = d.taskChanged;
        document.getElementById('chgReject').hidden = !canAct;
        document.getElementById('chgWithdraw').hidden = !canWithdraw;
        document.getElementById('chgCommentBox').hidden = !(canAct || canWithdraw);
        document.getElementById('chgComment').value = '';
        if (!chgDialog.open) { chgDialog.showModal(); }
    }

    /** 처리: 바로 처리 요청 = PMS API, 결재 중 요청 = 플랫폼 결재 API(같은 콜백이 계획일을 바꾼다) */
    function chgAct(btn, kind) {
        var d = chg;
        var comment = document.getElementById('chgComment').value;
        var call;
        if (kind === 'withdraw') {
            call = d.canWithdraw ? hb.api('/api/pms/chg-reqs/' + d.req.reqId + '/withdraw', {method: 'POST', body: {rowVer: d.req.rowVer}})
                : hb.api('/api/platform/approvals/' + d.approval.aprId + '/withdraw', {method: 'POST', body: {comment: comment}});
        } else if (d.canDecide) {
            call = hb.api('/api/pms/chg-reqs/' + d.req.reqId + '/decide', {method: 'POST',
                body: {approve: kind === 'approve', note: comment, rowVer: d.req.rowVer}});
        } else {
            call = hb.api('/api/platform/approvals/' + d.approval.aprId + '/' + kind, {method: 'POST', body: {comment: comment}});
        }
        hb.busy(btn, function () { return call; }).then(function (res) {
            if (!res) { return; }
            chgDialog.close();
            // 승인은 후행을 밀지 않는다 — 밀릴 후행이 있었으면 PM 에게 [의존 위반 밀기] 안내
            var push = kind === 'approve' && d.preview.length ? hb.t('js.pms.chg.approvedPush', d.preview.length) : '';
            return refreshProject(d.req.taskId).then(function () { notify(push || hb.t('js.common.saved'), !!push); });
        }, function (err) {
            notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
            // 409 = 작업·요청이 그사이 바뀜 → 상세를 다시 읽어 보여 준다
            if (err && err.status === 409) { hb.api('/api/pms/chg-reqs/' + d.req.reqId).then(function (r) { showChg(r.data); }); }
        });
    }
    document.getElementById('chgApprove').addEventListener('click', function () { chgAct(this, 'approve'); });
    document.getElementById('chgReject').addEventListener('click', function () {
        var btn = this;
        pmsUi.ask(hb.t('js.pms.chg.dlgReject'), hb.t('js.pms.chg.confirmReject'), hb.t('js.pms.chg.reject'), true).then(function (ok) {
            if (ok) { chgAct(btn, 'reject'); }
        });
    });
    document.getElementById('chgWithdraw').addEventListener('click', function () { chgAct(this, 'withdraw'); });
    document.getElementById('chgClose').addEventListener('click', function () { chgDialog.close(); });

    // ------------------------------------------------------------------ 시작

    hb.dirty.watch(projForm);
    hb.dirty.watch(taskForm);
    hb.loadMessages().then(function () {
        hb.page.init();
        gantt = pmsGantt('#gantt', {
            emptyText: hb.t('js.pms.noTasks'),
            columns: [
                {key: 'wbs', label: hb.t('js.pms.col.wbs'), width: 52},
                {key: 'name', label: hb.t('js.pms.col.taskNm'), width: 220, tree: true},
                {key: 'owner', label: hb.t('js.pms.col.owner'), width: 72},
                {key: 'start', label: hb.t('js.pms.col.planStart'), width: 88},
                {key: 'end', label: hb.t('js.pms.col.planEnd'), width: 88},
                {key: 'pct', label: hb.t('js.pms.col.progress'), width: 50, align: 'right'},
                {key: 'status', label: hb.t('js.pms.col.status'), width: 64},
                {key: 'slip', label: hb.t('js.pms.col.slip'), width: 48, align: 'right'},
                {key: 'hours', label: hb.t('js.pms.col.hours'), width: 72, align: 'right'}],
            onSelect: function (row) {
                var prev = current;
                pmsUi.leaveOk().then(function (ok) {
                    if (!ok) {
                        if (prev) { gantt.select(prev.taskId); }
                        return;
                    }
                    newSlot = null;
                    showTask(row ? findTask(row.id) : null);
                    updateTools();
                });
            },
            onActivate: function (row) {
                hb.page.openDetail();
                var first = taskForm.querySelector('input:not([disabled]), select:not([disabled])');
                if (first) { first.focus(); }
            }
        });
        gantt.setScale('week');     // 프로젝트는 몇 달 — 주 눈금이 한눈에 들어온다
        sp = hb.searchPanel(document.getElementById('search'), {
            onSearch: function () { return loadList(); },
            // 이미 열린 탭에 결재함 "문서 열기"(?q.chgReq=) — 첫 로드와 같게 그 요청을 열고 목록 조회는 건너뛴다. 칸은 비운다
            onLink: function (q) {
                if (!q.chgReq) { return false; }
                sp.setValue('chgReq', '');
                openChg(Number(q.chgReq));
                return true;
            }
        });
        return Promise.all([hb.loadSession(), hb.codeList('PMS_PROJ_TYPE'), hb.api(API + '/users'), hb.api(API + '/plants'),
            hb.api(API + '/templates'), hb.codes('PLM_ECO_STATUS'), hb.codes('PLM_CUST_APR')]);
    }).then(function (r) {
        ecoStatusNames = r[5];
        custAprNames = r[6];
        var typeSearch = document.getElementById('typeSearch');
        option(typeSearch, '', hb.t('js.pms.allTypes'));
        r[1].forEach(function (c) {
            typeNames[c.code] = c.name;
            option(typeSearch, c.code, c.name);
            option(pf('projType'), c.code, c.name);
        });
        users = r[2].data;
        option(pf('pmLoginId'), '', hb.t('js.pms.me'));
        option(tf('ownerLoginId'), '', hb.t('js.pms.noOwner'));
        r[2].data.forEach(function (u) {
            option(pf('pmLoginId'), u.loginId, u.userNm + ' (' + u.loginId + ')');
            option(tf('ownerLoginId'), u.loginId, u.userNm + ' (' + u.loginId + ')');
        });
        option(pf('plantCd'), '', hb.t('js.pms.noPlant'));
        r[3].data.forEach(function (p) { option(pf('plantCd'), p.code, p.code + ' · ' + p.name); });
        option(pf('tmplCd'), '', hb.t('js.pms.noTemplate'));
        r[4].data.forEach(function (t) {
            option(pf('tmplCd'), t.tmplCd, t.tmplCd + ' · ' + t.tmplNm + ' (' + (t.anchor === 'SOP' ? hb.t('js.pms.anchor.SOP')
                : hb.t('js.pms.anchor.START')) + ')');
        });
        grid = hb.grid('#projGrid', {
            progCd: 'pms-project', id: 'main', key: 'projId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {projType: 'projType'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {status: projStatusName},
            // 상태 = PMS 톤 표, 지연 작업 = 있으면 위험 배지(숫자 그대로)
            status: {status: pmsUi.TONES.project, lateCnt: function (v) { return v > 0 ? 'danger' : null; }},
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
            notify: notify
        });
        var host = /** @type {HTMLElement} */ (document.getElementById('projGrid'));
        host.addEventListener('click', function (e) {
            if (e.target instanceof Element && e.target.closest('tbody tr')) { openSelected(); }
        });
        host.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && e.target instanceof Element && e.target.closest('.hbg-scroll')) { openSelected(); }
        });
        return grid.ready.then(function () {
            // 결재함 "문서 열기"(?q.chgReq=) — 목록 대신 그 요청을 연다. 한 번 쓰면 비운다(다음 조회에 남지 않게)
            var link = sp.values().chgReq;
            if (link) {
                sp.setValue('chgReq', '');
                return openChg(Number(link));
            }
            return loadList();
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
