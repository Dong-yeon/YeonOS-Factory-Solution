// @ts-check
/*
 * 산출물 현황 (pms-deliverable) — PMS 3단계 (docs/32 PM3).
 * 프로젝트 → 단계별 준비율 + 산출물 표 → 고르면 오른쪽에 칸 폼(PM)·제출(담당·PM)·결정(APPROVE)·이력.
 * 판정은 모두 서버(행 권한·상태 전이) — 화면은 버튼만 숨긴다. 제출은 파일 1개, 제출마다 새 파일.
 * PM6: QMS 문서 리비전 연결(배포본 = 승인) — 연결된 칸은 파일 제출·손 승인·반려 버튼을 숨긴다(서버도 거부).
 */
/**
 * @typedef {{delivId: number, projId: number, taskId: number, seq: number, delivNm: string, requiredYn: string, formDocNo?: string | null,
 *   ownerLoginId?: string | null, ownerNm?: string | null, dueDate?: string | null, due?: string | null, status: string,
 *   refId?: number | null, fileName?: string | null, waiveReason?: string | null, decisionNote?: string | null,
 *   decidedBy?: string | null, submittedBy?: string | null, subCnt: number, rowVer: number, taskNm: string, wbsNo?: string | null,
 *   phaseNm?: string | null, late: boolean, canSubmit: boolean, refType?: string | null, docNo?: string | null,
 *   docNm?: string | null, docRev?: string | null, docStatus?: string | null, ppapEcoNo?: string | null,
 *   ppapStatus?: string | null}} PmsDeliv
 * @typedef {{phaseTaskId: number, wbsNo: string, phaseNm: string, reqCnt: number, readyCnt: number, pct: number}} PmsPhase
 * @typedef {{projId: number, projNo: string, projNm: string, custNm?: string | null, sopDate?: string | null, status: string}} PmsProj
 * @typedef {{project: PmsProj, rows: PmsDeliv[], phases: PmsPhase[], today: string, canManage: boolean, canApprove: boolean,
 *   tasks: {taskId: number, wbsNo: string, taskNm: string}[]}} PmsDelivView
 */
(function () {
    'use strict';

    var API = '/api/pms/projects';
    var D_API = '/api/pms/deliverables';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var delivBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#delivs tbody'));
    var detailView = /** @type {HTMLElement} */ (document.getElementById('detailView'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('delivForm'));
    var submitForm = /** @type {HTMLFormElement} */ (document.getElementById('submitForm'));
    var decideForm = /** @type {HTMLFormElement} */ (document.getElementById('decideForm'));
    var docForm = /** @type {HTMLFormElement} */ (document.getElementById('docForm'));
    var apqpForm = /** @type {HTMLFormElement} */ (document.getElementById('apqpForm'));
    var DOC_REF = 'QMS_DOC_REV';
    var PPAP_REF = 'PLM_PPAP';
    /** PFMEA·관리계획서 리비전(docs/41 A3-1) — 한 번이라도 확정 = 승인 */
    var FMEA_REF = 'APQP_FMEA';
    var CP_REF = 'APQP_CP';
    var onlyOpen = /** @type {HTMLInputElement} */ (document.getElementById('onlyOpen'));
    /** @type {PmsDelivView | null} */
    var view = null;
    /** @type {PmsDeliv | null} */
    var current = null;
    var adding = false;
    /** @type {{taskId: number, wbsNo: string, taskNm: string}[]} */
    var tasks = [];
    /** @type {HB.SearchPanel} */
    var sp;
    /** 프로젝트 고르기 grid(docs/40 §10.2 D1) @type {HB.Grid} */
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
            notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
            if (f) { hb.showFieldErrors(f, err && err.errors); }
        };
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function field(f, n) { return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]')); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 상태 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function statusName(s) {
        /** @type {Record<string, string>} */
        var m = {NONE: hb.t('js.pms.deliv.NONE'), SUBMITTED: hb.t('js.pms.deliv.SUBMITTED'), APPROVED: hb.t('js.pms.deliv.APPROVED'),
            REJECTED: hb.t('js.pms.deliv.REJECTED'), WAIVED: hb.t('js.pms.deliv.WAIVED')};
        return m[s] || s;
    }

    /** @param {string} a */
    function actionName(a) {
        /** @type {Record<string, string>} */
        var m = {SUBMIT: hb.t('js.pms.hist.SUBMIT'), LINK: hb.t('js.pms.hist.LINK'), APPROVE: hb.t('js.pms.hist.APPROVE'), REJECT: hb.t('js.pms.hist.REJECT'),
            WAIVE: hb.t('js.pms.hist.WAIVE'), UNWAIVE: hb.t('js.pms.hist.UNWAIVE')};
        return m[a] || a;
    }

    /** QMS 문서 리비전 상태 @param {string | null | undefined} s */
    function docStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.docRev.DRAFT'), IN_APPROVAL: hb.t('js.pms.docRev.IN_APPROVAL'),
            RELEASED: hb.t('js.pms.docRev.RELEASED'), OBSOLETE: hb.t('js.pms.docRev.OBSOLETE')};
        return s ? (m[s] || s) : '';
    }

    /** PPAP 패키지 상태 @param {string | null | undefined} s */
    function ppapStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.ppap.DRAFT'), SUBMITTED: hb.t('js.pms.ppap.SUBMITTED'), APPROVED: hb.t('js.pms.ppap.APPROVED'),
            INTERIM: hb.t('js.pms.ppap.INTERIM'), REJECTED: hb.t('js.pms.ppap.REJECTED')};
        return s ? (m[s] || s) : '';
    }

    /** "문서 번호 리비전 (상태)" · "PPAP ECO 번호 (상태)" @param {PmsDeliv} d */
    function docText(d) {
        if (d.refType === PPAP_REF) {
            return 'PPAP ' + (d.ppapEcoNo || '') + (d.ppapStatus ? ' (' + ppapStatusName(d.ppapStatus) + ')' : '');
        }
        return (d.docNo || '?') + ' ' + (d.docRev || '') + (d.docStatus ? ' (' + docStatusName(d.docStatus) + ')' : '');
    }

    /** QMS 문서·PPAP·PFMEA·관리계획서에 연결된 칸 — 파일·손 승인을 받지 않는다 @param {PmsDeliv | null} d */
    function isLinked(d) {
        return !!d && [DOC_REF, PPAP_REF, FMEA_REF, CP_REF].indexOf(d.refType || '') >= 0;
    }

    /** "PFMEA 번호 rev n (상태)" @param {PmsDeliv} d */
    function apqpText(d) {
        /** @type {Record<string, string>} */
        var st = {DRAFT: hb.t('js.pms.apqp.DRAFT'), IN_APPROVAL: hb.t('js.pms.apqp.IN_APPROVAL'), RELEASED: hb.t('js.pms.apqp.RELEASED'),
            OBSOLETE: hb.t('js.pms.apqp.OBSOLETE')};
        var kind = d.refType === FMEA_REF ? hb.t('js.pms.apqp.FMEA') : hb.t('js.pms.apqp.CP');
        return kind + ' ' + (d.docNo || '?') + ' ' + (d.docRev || '') + (d.docStatus ? ' (' + (st[d.docStatus] || d.docStatus) + ')' : '');
    }

    /** 상태 설명에 붙는 연결 문구 @param {PmsDeliv} d */
    function linkedText(d) {
        if (d.refType === FMEA_REF || d.refType === CP_REF) { return ' — ' + hb.t('js.pms.apqpLinked', apqpText(d)); }
        if (d.refType === DOC_REF) { return ' — ' + hb.t('js.pms.docLinked', docText(d)); }
        if (d.refType === PPAP_REF) { return ' — ' + docText(d); }
        return '';
    }

    /** @param {HTMLElement} body @param {number} cols @param {string} text */
    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    // ------------------------------------------------------------------ 프로젝트 목록

    /** 프로젝트 상태 이름 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function projStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.status.DRAFT'), ACTIVE: hb.t('js.pms.status.ACTIVE'), ON_HOLD: hb.t('js.pms.status.ON_HOLD'),
            CLOSED: hb.t('js.pms.status.CLOSED'), CANCELLED: hb.t('js.pms.status.CANCELLED')};
        return m[s] || s;
    }

    /** grid 조회 조건 — 상태(진행 중/전체)·검색어 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {status: String(v.status || '')};
        if (v.keyword) { q.keyword = v.keyword; }
        return q;
    }

    /** 프로젝트 고르기 = hb.grid(서버 페이징·정렬). 다시 읽으면 선택이 풀려 같은 행을 다시 눌러도 열린다 */
    function loadProjects() {
        return grid.reload(true);
    }

    /** 고른 행 열기 — 클릭·Enter 만(↑↓ 는 고르기만: 키보드 이동마다 산출물을 읽지 않는다) */
    function openSelected() {
        var p = grid.selected();
        if (p) { openProject(Number(p.projId)); }
    }

    function initGrid() {
        grid = hb.grid('#projGrid', {
            progCd: 'pms-deliverable', id: 'main', key: 'projId',
            url: '/api/pms/deliverable-projects/grid', columnsUrl: '/api/pms/deliverable-projects/columns', actions: ACTIONS,
            params: params,
            format: {status: projStatusName},
            // 상태 = PMS 톤 표(pms-ui.js) — 칸 글자로 hb-status 배지
            status: {status: pmsUi.TONES.project},
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
        return grid.ready;
    }

    // ------------------------------------------------------------------ 산출물

    /** @param {number} projId @param {number | null} [selectId] */
    function openProject(projId, selectId) {
        return pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            return hb.latest('delivs', hb.api(API + '/' + projId + '/deliverables'))
                .then(function (res) {
                    view = res.data;
                    tasks = res.data.tasks;
                    detailView.hidden = false;
                    draw();
                    var pick = selectId && view ? view.rows.filter(function (d) { return d.delivId === selectId; })[0] : null;
                    show(pick || null);
                }, fail());
        });
    }

    function draw() {
        var v = /** @type {PmsDelivView} */ (view);
        /** @type {HTMLElement} */ (document.getElementById('projTitle')).textContent = v.project.projNo + ' · ' + v.project.projNm;
        var req = 0, ready = 0;
        v.phases.forEach(function (p) { req += p.reqCnt; ready += p.readyCnt; });
        /** @type {HTMLElement} */ (document.getElementById('projSummary')).textContent = hb.t('js.pms.readySummary', ready, req);
        var ph = /** @type {HTMLElement} */ (document.getElementById('phases'));
        ph.textContent = '';
        v.phases.forEach(function (p) {
            var li = hb.el('li', 'pms-phase' + (p.reqCnt && p.readyCnt === p.reqCnt ? ' pms-phase-ok' : ''));
            li.appendChild(hb.el('span', 'pms-phase-nm', p.phaseNm));
            li.appendChild(hb.el('span', 'pms-phase-pct', p.reqCnt ? p.pct + '% (' + p.readyCnt + '/' + p.reqCnt + ')' : '—'));
            var bar = hb.el('span', 'pms-meter');
            var fill = hb.el('span', 'pms-meter-fill');
            fill.style.width = (p.reqCnt ? p.pct : 0) + '%';
            bar.appendChild(fill);
            li.appendChild(bar);
            ph.appendChild(li);
        });
        delivBody.textContent = '';
        var rows = v.rows.filter(function (d) { return !onlyOpen.checked || ['APPROVED', 'WAIVED'].indexOf(d.status) < 0; });
        /** @type {HTMLElement} */ (document.getElementById('count')).textContent = hb.t('js.pms.delivCount', rows.length, v.rows.length);
        if (!rows.length) { emptyRow(delivBody, 8, hb.t('js.pms.noDelivs')); }
        rows.forEach(function (d) {
            var tr = hb.el('tr', 'hb-row-pick');
            tr.tabIndex = 0;
            tr.setAttribute('data-id', String(d.delivId));
            tr.setAttribute('aria-selected', current && current.delivId === d.delivId ? 'true' : 'false');
            [d.wbsNo || '', d.taskNm, d.delivNm, d.requiredYn === 'Y' ? hb.t('js.pms.required.Y') : hb.t('js.pms.required.N'),
                d.ownerNm || ''].forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
            if (d.formDocNo) { tr.children[2].appendChild(pmsUi.formLink(d.formDocNo)); }   // 빈 서식(QMS 관리문서, docs/32 4.17)
            tr.appendChild(hb.el('td', d.late ? 'pms-late' : null, d.due || ''));
            var st = hb.el('td');
            st.appendChild(pmsUi.badge('deliv', d.status, statusName(d.status)));
            tr.appendChild(st);
            var fileTd = hb.el('td');
            if (d.refType === FMEA_REF || d.refType === CP_REF) {
                fileTd.appendChild(hb.el('span', 'pms-doc-link', apqpText(d)));
            } else if (d.refType === DOC_REF || d.refType === PPAP_REF) {
                fileTd.appendChild(hb.el('span', 'pms-doc-link', docText(d)));
            } else if (d.refId && d.fileName) {
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, d.fileName));
                a.href = '/api/platform/files/' + d.refId;
                a.addEventListener('click', function (e) { e.stopPropagation(); });
                fileTd.appendChild(a);
            }
            tr.appendChild(fileTd);
            tr.addEventListener('click', function () { pick(d); });
            tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(d); } });
            delivBody.appendChild(tr);
        });
        var add = /** @type {HTMLButtonElement} */ (document.getElementById('addBtn'));
        add.hidden = !(CAN_SAVE && v.canManage);
    }

    /** @param {PmsDeliv} d */
    function pick(d) {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(d);
            hb.page.openDetail();
        });
    }

    /** @param {PmsDeliv | null} d */
    function show(d) {
        current = d;
        adding = false;
        fillForm(d);
        delivBody.querySelectorAll('tr[data-id]').forEach(function (tr) {
            tr.setAttribute('aria-selected', d && tr.getAttribute('data-id') === String(d.delivId) ? 'true' : 'false');
        });
    }

    /** @param {PmsDeliv | null} d */
    function fillForm(d) {
        var v = /** @type {PmsDelivView} */ (view);
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(submitForm, []);
        hb.showFieldErrors(decideForm, []);
        var manage = CAN_SAVE && v.canManage;
        field(form, 'delivNm').value = d ? d.delivNm : '';
        field(form, 'requiredYn').value = d ? d.requiredYn : 'Y';
        field(form, 'formDocNo').value = d ? (d.formDocNo || '') : '';
        field(form, 'ownerLoginId').value = d ? (d.ownerLoginId || '') : '';
        field(form, 'dueDate').value = d ? (d.dueDate || '') : '';
        var taskSel = /** @type {HTMLSelectElement} */ (document.getElementById('taskSel'));
        taskSel.textContent = '';
        tasks.forEach(function (t) { option(taskSel, String(t.taskId), t.wbsNo + ' ' + t.taskNm); });
        if (d) { taskSel.value = String(d.taskId); }
        taskSel.disabled = !adding;
        form.querySelectorAll('input, select').forEach(function (i) {
            if (i !== taskSel) { /** @type {HTMLInputElement} */ (i).disabled = !(manage && (adding || !!d)); }
        });
        /** @type {HTMLElement} */ (document.getElementById('mode')).textContent = adding ? hb.t('js.pms.addingDeliv')
            : (d ? hb.t('js.pms.editingDeliv', d.wbsNo || '', d.delivNm) : hb.t('js.pms.selectDeliv'));
        var note = d ? statusName(d.status) + (linkedText(d)) + (d.waiveReason ? ' — ' + d.waiveReason : '') + (d.decisionNote ? ' — ' + d.decisionNote : '')
            + (d.decidedBy ? ' (' + d.decidedBy + ')' : '') : '';
        /** @type {HTMLElement} */ (document.getElementById('stateNote')).textContent = note;
        var save = document.getElementById('saveBtn');
        if (save) { save.hidden = !(manage && (adding || !!d)); }
        var del = document.getElementById('deleteBtn');
        if (del) { del.hidden = !(manage && d && d.status === 'NONE' && d.subCnt === 0); }
        var linked = isLinked(d);
        /** @type {HTMLElement} */ (document.getElementById('submitBox')).hidden = !(CAN_SAVE && d && d.canSubmit) || linked;
        /** @type {HTMLElement} */ (document.getElementById('docBox')).hidden = !(CAN_SAVE && d && d.canSubmit);
        hb.showFieldErrors(docForm, []);
        field(docForm, 'docNo').value = d && d.refType === DOC_REF ? (d.docNo || '') : '';
        field(docForm, 'rev').value = '';
        field(docForm, 'note').value = '';
        hb.showFieldErrors(apqpForm, []);
        field(apqpForm, 'kind').value = d && d.refType === CP_REF ? 'CP' : 'FMEA';
        field(apqpForm, 'apqpNo').value = d && (d.refType === FMEA_REF || d.refType === CP_REF) ? (d.docNo || '') : '';
        field(apqpForm, 'apqpRev').value = '';
        field(apqpForm, 'note').value = '';
        /** @type {HTMLInputElement} */ (document.getElementById('fileInput')).value = '';
        field(submitForm, 'note').value = '';
        var decideBox = /** @type {HTMLElement} */ (document.getElementById('decideBox'));
        decideBox.hidden = !(v.canApprove && d);
        decideForm.querySelectorAll('[data-decide]').forEach(function (b) {
            var a = b.getAttribute('data-decide');
            var s = d ? d.status : '';
            var on = a === 'APPROVE' || a === 'REJECT' ? s === 'SUBMITTED' && !linked
                : a === 'WAIVE' ? ['NONE', 'SUBMITTED', 'REJECTED'].indexOf(s) >= 0 : s === 'WAIVED' && !(d && d.refId);
            /** @type {HTMLButtonElement} */ (b).hidden = !on;
        });
        field(decideForm, 'note').value = '';
        loadHistory(d);
        hb.dirty.clear(form);
    }

    /** @param {PmsDeliv | null} d */
    function loadHistory(d) {
        var box = /** @type {HTMLElement} */ (document.getElementById('histBox'));
        var list = /** @type {HTMLElement} */ (document.getElementById('histList'));
        list.textContent = '';
        box.hidden = !d || (d.subCnt === 0 && d.status === 'NONE');
        if (box.hidden || !d) { return; }
        hb.latest('hist', hb.api(D_API + '/' + d.delivId + '/history')).then(function (res) {
            res.data.forEach(/** @param {any} h */ function (h) {
                var li = hb.el('li');
                li.appendChild(hb.el('span', 'pms-status', actionName(h.action)));
                li.appendChild(hb.el('span', 'hb-muted', hb.fmt.datetime(h.createdAt) + ' ' + h.createdBy));
                if (h.fileId && h.fileName) {
                    var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, h.fileName));
                    a.href = '/api/platform/files/' + h.fileId;
                    li.appendChild(a);
                }
                if (h.docLabel) { li.appendChild(hb.el('span', 'pms-doc-link', h.docLabel)); }
                if (h.note) { li.appendChild(hb.el('span', null, h.note)); }
                list.appendChild(li);
            });
        }, function (/** @type {any} */ err) { if (!err || err.code !== 'STALE') { fail()(err); } });
    }

    // ------------------------------------------------------------------ 버튼

    /** @type {HTMLButtonElement} */ (document.getElementById('addBtn')).addEventListener('click', function () {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            current = null;
            adding = true;
            fillForm(null);
            hb.page.openDetail();
            field(form, 'delivNm').focus();
        });
    });

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            var v = /** @type {PmsDelivView} */ (view);
            var d = current;
            /** @type {Record<string, unknown>} */
            var b = {};
            ['delivNm', 'requiredYn', 'formDocNo', 'ownerLoginId', 'dueDate'].forEach(function (n) { b[n] = field(form, n).value; });
            if (adding) { b.taskId = Number(field(form, 'taskId').value); } else if (d) { b.rowVer = d.rowVer; } else { return; }
            hb.showFieldErrors(form, []);
            hb.busy(sb, function () {
                return adding ? hb.api(API + '/' + v.project.projId + '/deliverables', {method: 'POST', body: b})
                    : hb.api(D_API + '/' + (d && d.delivId), {method: 'PUT', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear(form);
                return openProject(v.project.projId, adding ? res.data : d && d.delivId).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(form));
        });
    }

    var deleteBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('deleteBtn'));
    if (deleteBtn) {
        var db = deleteBtn;
        db.addEventListener('click', function () {
            var d = /** @type {PmsDeliv} */ (current);
            var v = /** @type {PmsDelivView} */ (view);
            if (!d) { return; }
            pmsUi.ask(hb.t('js.pms.dlg.deleteDeliv'), hb.t('js.pms.confirmDeleteDeliv', d.delivNm), hb.t('js.pms.dlg.delete'), true).then(function (ok) {
                if (!ok) { return; }
                hb.busy(db, function () { return hb.api(D_API + '/' + d.delivId, {method: 'DELETE'}); }).then(function (res) {
                    if (!res) { return; }
                    return openProject(v.project.projId).then(function () { notify(hb.t('js.common.saved')); });
                }, fail());
            });
        });
    }

    var submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById('submitBtn'));
    submitBtn.addEventListener('click', function () {
        var d = /** @type {PmsDeliv} */ (current);
        var v = /** @type {PmsDelivView} */ (view);
        var input = /** @type {HTMLInputElement} */ (document.getElementById('fileInput'));
        var file = input.files && input.files[0];
        if (!d) { return; }
        if (!file) { notify(hb.t('js.pms.chooseFile'), true); return; }
        var asked = d.status === 'APPROVED'
            ? pmsUi.ask(hb.t('js.pms.dlg.resubmit'), hb.t('js.pms.confirmResubmit'), hb.t('js.pms.dlg.resubmitOk'))
            : Promise.resolve(true);
        asked.then(function (ok) {
            if (!ok) { return; }
            var fd = new FormData();
            fd.append('file', /** @type {File} */ (file));
            fd.append('rowVer', String(d.rowVer));
            fd.append('note', field(submitForm, 'note').value);
            hb.busy(submitBtn, function () { return hb.upload(D_API + '/' + d.delivId + '/submit', fd); }).then(function (res) {
                if (!res) { return; }
                return openProject(v.project.projId, d.delivId).then(function () { notify(hb.t('js.pms.submitted')); });
            }, fail(submitForm));
        });
    });

    decideForm.querySelectorAll('[data-decide]').forEach(function (el) {
        var btn = /** @type {HTMLButtonElement} */ (el);
        btn.addEventListener('click', function () {
            var d = /** @type {PmsDeliv} */ (current);
            var v = /** @type {PmsDelivView} */ (view);
            if (!d) { return; }
            var body = {action: btn.getAttribute('data-decide'), note: field(decideForm, 'note').value, rowVer: d.rowVer};
            hb.busy(btn, function () { return hb.api(D_API + '/' + d.delivId + '/decide', {method: 'POST', body: body}); }).then(function (res) {
                if (!res) { return; }
                return openProject(v.project.projId, d.delivId).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(decideForm));
        });
    });

    var docLinkBtn = /** @type {HTMLButtonElement} */ (document.getElementById('docLinkBtn'));
    docLinkBtn.addEventListener('click', function () {
        var d = /** @type {PmsDeliv} */ (current);
        var v = /** @type {PmsDelivView} */ (view);
        if (!d) { return; }
        var body = {docNo: field(docForm, 'docNo').value, rev: field(docForm, 'rev').value, note: field(docForm, 'note').value,
            rowVer: d.rowVer};
        hb.showFieldErrors(docForm, []);
        hb.busy(docLinkBtn, function () { return hb.api(D_API + '/' + d.delivId + '/doc-link', {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                var r = res.data;
                return openProject(v.project.projId, d.delivId).then(function () {
                    notify(hb.t(r.status === 'APPROVED' ? 'js.pms.docLinkApproved' : 'js.pms.docLinkPending', r.docNo + ' ' + r.rev));
                });
            }, fail(docForm));
    });

    var apqpLinkBtn = /** @type {HTMLButtonElement} */ (document.getElementById('apqpLinkBtn'));
    apqpLinkBtn.addEventListener('click', function () {
        var d = /** @type {PmsDeliv} */ (current);
        var v = /** @type {PmsDelivView} */ (view);
        if (!d) { return; }
        var rev = field(apqpForm, 'apqpRev').value.trim();
        var body = {kind: field(apqpForm, 'kind').value, no: field(apqpForm, 'apqpNo').value,
            rev: rev === '' ? null : (/^\d+$/.test(rev) ? Number(rev) : rev), note: field(apqpForm, 'note').value, rowVer: d.rowVer};
        hb.showFieldErrors(apqpForm, []);
        hb.busy(apqpLinkBtn, function () { return hb.api(D_API + '/' + d.delivId + '/apqp-link', {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                var r = res.data;
                return openProject(v.project.projId, d.delivId).then(function () {
                    notify(hb.t(r.status === 'APPROVED' ? 'js.pms.apqpLinkApproved' : 'js.pms.apqpLinkPending', r.docNo + ' ' + r.rev));
                });
            }, fail(apqpForm));
    });

    var ppapLinkBtn = /** @type {HTMLButtonElement} */ (document.getElementById('ppapLinkBtn'));
    ppapLinkBtn.addEventListener('click', function () {
        var d = /** @type {PmsDeliv} */ (current);
        var v = /** @type {PmsDelivView} */ (view);
        if (!d) { return; }
        hb.showFieldErrors(docForm, []);
        hb.busy(ppapLinkBtn, function () {
            return hb.api(D_API + '/' + d.delivId + '/ppap-link', {method: 'POST', body: {note: field(docForm, 'note').value, rowVer: d.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            var r = res.data;
            return openProject(v.project.projId, d.delivId).then(function () {
                notify(hb.t(r.status === 'APPROVED' ? 'js.pms.ppapLinkApproved' : 'js.pms.ppapLinkPending'));
            });
        }, fail(docForm));
    });

    var syncDocsBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('syncDocsBtn'));
    if (syncDocsBtn) {
        var sdb = syncDocsBtn;
        sdb.addEventListener('click', function () {
            if (!view) { return; }
            var id = view.project.projId;
            hb.busy(sdb, function () { return hb.api(API + '/' + id + '/deliverables/sync-docs', {method: 'POST', body: {}}); })
                .then(function (res) {
                    if (!res) { return; }
                    var n = res.data;
                    return openProject(id, current && current.delivId).then(function () { notify(hb.t('js.pms.docSynced', n)); });
                }, fail());
        });
    }

    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    if (excelBtn) {
        var eb = excelBtn;
        eb.addEventListener('click', function () {
            if (!view) { return; }
            var id = view.project.projId;
            hb.busy(eb, function () { return hb.download(API + '/' + id + '/deliverables/export', {}); }).then(null, fail());
        });
    }

    onlyOpen.addEventListener('change', function () { if (view) { draw(); } });

    // ------------------------------------------------------------------ 시작

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: loadProjects});
        return Promise.all([hb.loadSession(), hb.api('/api/pms/deliverable-users')]);
    }).then(function (r) {
        var owner = /** @type {HTMLSelectElement} */ (document.getElementById('ownerSel'));
        option(owner, '', hb.t('js.pms.noOwner'));
        r[1].data.forEach(/** @param {any} u */ function (u) { option(owner, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        return initGrid();
    }).then(function () {
        return loadProjects();
    }).then(null, function (/** @type {any} */ err) { notify(err && err.message, true); });
})();
