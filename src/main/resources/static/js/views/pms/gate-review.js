// @ts-check
/*
 * 게이트 심사 (pms-gate) — PMS 3단계 (docs/32 PM3).
 * 프로젝트 → 게이트 목록 → 게이트를 고르면 심사 이력 + 마지막(또는 고른) 심사 상세.
 * 작성 중 심사만 고친다(날짜·결정·요약·점검표·조건 한 벌 저장). 상신 = 결재(정책 Y) 또는 바로 결정(정책 N, APPROVE).
 * 결재 처리는 플랫폼 결재 API(/api/platform/approvals). 판정은 모두 서버.
 */
/**
 * @typedef {{taskId: number, wbsNo: string, taskNm: string, planStart: string, taskStatus: string, reviewId?: number | null,
 *   reviewNo?: number | null, reviewStatus?: string | null, decision?: string | null, reqCnt: number, readyCnt: number}} PmsGate
 * @typedef {{reviewId: number, gateTaskId: number, reviewNo: number, reviewDate: string, decision?: string | null, status: string,
 *   summary?: string | null, reqCnt?: number | null, readyCnt?: number | null, aprId?: number | null, decidedBy?: string | null,
 *   rowVer: number}} PmsReview
 * @typedef {{checkNm: string, result?: string | null, note?: string | null}} PmsCheck
 * @typedef {{title: string, ownerLoginId?: string | null, dueDate?: string | null, issueNo?: string | null}} PmsCond
 * @typedef {{seq: number, approverNm: string, lineType: string, status: string, comment?: string | null}} PmsAprLine
 * @typedef {{review: PmsReview, gate: PmsGate, projNo: string, projNm: string, checks: PmsCheck[], conds: PmsCond[],
 *   scope: any[], approval: {aprId?: number | null, status?: string | null, myTurn: boolean, canWithdraw: boolean, lines: PmsAprLine[]},
 *   canEdit: boolean, approvalRequired: boolean, canDecideDirect: boolean}} PmsReviewDetail
 */
(function () {
    'use strict';

    var API = '/api/pms/projects';
    var R_API = '/api/pms/gate-reviews';
    var APR = '/api/platform/approvals';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var gateBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#gates tbody'));
    var scopeBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#scope tbody'));
    var detailView = /** @type {HTMLElement} */ (document.getElementById('detailView'));
    var reviewForm = /** @type {HTMLFormElement} */ (document.getElementById('reviewForm'));
    var checksHost = /** @type {HTMLElement} */ (document.getElementById('checks'));
    var condsHost = /** @type {HTMLElement} */ (document.getElementById('conds'));
    /** @type {number | null} */
    var projId = null;
    /** @type {PmsGate[]} */
    var gates = [];
    /** @type {PmsGate | null} */
    var gate = null;
    /** @type {PmsReviewDetail | null} */
    var detail = null;
    /** @type {{loginId: string, userNm: string, userId: number}[]} */
    var users = [];
    /** @type {HB.SearchPanel} */
    var sp;
    /** 프로젝트 고르기 grid(docs/40 §10.2 D1) @type {HB.Grid} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 오류 → 한 줄 + 칸·행 오류 표시 @param {any} err */
    function fail(err) {
        if (err && err.code === 'STALE') { return; }
        notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
        var errors = (err && err.errors) || [];
        hb.showFieldErrors(reviewForm, errors.filter(/** @param {any} e */ function (e) { return !e.rowKey; }));
        document.querySelectorAll('[data-row-key]').forEach(function (row) {
            var k = row.getAttribute('data-row-key');
            hb.showFieldErrors(row, errors.filter(/** @param {any} e */ function (e) { return e.rowKey === k; }));
        });
    }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {HTMLElement} body @param {number} cols @param {string} text */
    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string | null | undefined} d */
    function decisionName(d) {
        /** @type {Record<string, string>} */
        var m = {GO: hb.t('js.pms.decision.GO'), CONDITIONAL: hb.t('js.pms.decision.CONDITIONAL'), NO_GO: hb.t('js.pms.decision.NO_GO')};
        return d ? (m[d] || d) : '';
    }
    /** @param {string | null | undefined} s */
    function reviewStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.review.DRAFT'), IN_APPROVAL: hb.t('js.pms.review.IN_APPROVAL'), DECIDED: hb.t('js.pms.review.DECIDED'),
            CANCELLED: hb.t('js.pms.review.CANCELLED')};
        return s ? (m[s] || s) : '';
    }
    /** @param {string} s */
    function projStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.pms.status.DRAFT'), ACTIVE: hb.t('js.pms.status.ACTIVE'), ON_HOLD: hb.t('js.pms.status.ON_HOLD'),
            CLOSED: hb.t('js.pms.status.CLOSED'), CANCELLED: hb.t('js.pms.status.CANCELLED')};
        return m[s] || s;
    }
    /** @param {string} s */
    function taskStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {NOT_STARTED: hb.t('js.pms.task.NOT_STARTED'), IN_PROGRESS: hb.t('js.pms.task.IN_PROGRESS'),
            DONE: hb.t('js.pms.gatePassed'), CANCELLED: hb.t('js.pms.task.CANCELLED')};
        return m[s] || s;
    }
    /** @param {string} s */
    function delivStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {NONE: hb.t('js.pms.deliv.NONE'), SUBMITTED: hb.t('js.pms.deliv.SUBMITTED'), APPROVED: hb.t('js.pms.deliv.APPROVED'),
            REJECTED: hb.t('js.pms.deliv.REJECTED'), WAIVED: hb.t('js.pms.deliv.WAIVED')};
        return m[s] || s;
    }

    // ------------------------------------------------------------------ 프로젝트·게이트

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

    /** 고른 행 열기 — 클릭·Enter 만(↑↓ 는 고르기만: 키보드 이동마다 게이트를 읽지 않는다) */
    function openSelected() {
        var p = grid.selected();
        if (p) { openProject(Number(p.projId), p.projNo + ' · ' + p.projNm); }
    }

    function initGrid() {
        grid = hb.grid('#projGrid', {
            progCd: 'pms-gate', id: 'main', key: 'projId',
            url: '/api/pms/gate-projects/grid', columnsUrl: '/api/pms/gate-projects/columns', actions: ACTIONS, params: params,
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

    /** @param {number} id @param {string} title @param {number | null} [gateId] @param {number | null} [reviewId] */
    function openProject(id, title, gateId, reviewId) {
        return pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            return loadProject(id, title, gateId, reviewId);
        });
    }

    /** @param {number} id @param {string} title @param {number | null} [gateId] @param {number | null} [reviewId] */
    function loadProject(id, title, gateId, reviewId) {
        return hb.latest('gates', hb.api(API + '/' + id + '/gates')).then(function (res) {
            projId = id;
            gates = res.data;
            detailView.hidden = false;
            /** @type {HTMLElement} */ (document.getElementById('projTitle')).textContent = title;
            drawGates();
            var g = gateId ? gates.filter(function (x) { return x.taskId === gateId; })[0] : null;
            return selectGate(g || null, reviewId);
        }, fail);
    }

    function drawGates() {
        gateBody.textContent = '';
        if (!gates.length) { emptyRow(gateBody, 6, hb.t('js.pms.noGates')); }
        gates.forEach(function (g) {
            var tr = hb.el('tr', 'hb-row-pick');
            tr.tabIndex = 0;
            tr.setAttribute('data-id', String(g.taskId));
            tr.setAttribute('aria-selected', gate && gate.taskId === g.taskId ? 'true' : 'false');
            [g.wbsNo, g.taskNm, g.planStart].forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
            tr.appendChild(hb.el('td', g.reqCnt && g.readyCnt < g.reqCnt ? 'pms-late' : null, g.readyCnt + ' / ' + g.reqCnt));
            var rv = hb.el('td');
            if (g.reviewNo) {
                rv.appendChild(document.createTextNode('#' + g.reviewNo + ' '));
                rv.appendChild(pmsUi.badge('review', g.reviewStatus, reviewStatusName(g.reviewStatus)));
                if (g.decision) { rv.appendChild(document.createTextNode(' ')); rv.appendChild(pmsUi.badge('decision', g.decision, decisionName(g.decision))); }
            }
            tr.appendChild(rv);
            var st = hb.el('td');
            st.appendChild(pmsUi.badge('task', g.taskStatus, taskStatusName(g.taskStatus)));
            tr.appendChild(st);
            tr.addEventListener('click', function () { pickGate(g); });
            tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pickGate(g); } });
            gateBody.appendChild(tr);
        });
    }

    /** @param {PmsGate} g */
    function pickGate(g) {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            return selectGate(g).then(function () { hb.page.openDetail(); });
        });
    }

    /** @param {PmsGate | null} g @param {number | null} [reviewId] */
    function selectGate(g, reviewId) {
        gate = g;
        gateBody.querySelectorAll('tr[data-id]').forEach(function (tr) {
            tr.setAttribute('aria-selected', g && tr.getAttribute('data-id') === String(g.taskId) ? 'true' : 'false');
        });
        var list = /** @type {HTMLElement} */ (document.getElementById('reviewList'));
        list.textContent = '';
        var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newReviewBtn'));
        if (newBtn) {
            newBtn.hidden = !g || g.taskStatus === 'DONE' || g.taskStatus === 'CANCELLED'
                || g.reviewStatus === 'DRAFT' || g.reviewStatus === 'IN_APPROVAL';
        }
        /** @type {HTMLElement} */ (document.getElementById('mode')).textContent = g ? g.wbsNo + ' ' + g.taskNm : hb.t('js.pms.selectGate');
        if (!g) { showReview(null); return Promise.resolve(); }
        var gid = g.taskId;
        return hb.latest('reviews', hb.api('/api/pms/gates/' + gid + '/reviews')).then(function (res) {
            /** @type {PmsReview[]} */
            var rows = res.data;
            rows.forEach(function (r) {
                var li = hb.el('li');
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', '#' + r.reviewNo + ' ' + r.reviewDate + ' '
                    + reviewStatusName(r.status) + (r.decision ? ' · ' + decisionName(r.decision) : '')));
                b.type = 'button';
                b.addEventListener('click', function () {
                    pmsUi.leaveOk().then(function (ok) {
                        if (!ok) { return; }
                        hb.dirty.clear();
                        return loadReview(r.reviewId);
                    });
                });
                li.appendChild(b);
                list.appendChild(li);
            });
            var pick = reviewId || (rows.length ? rows[0].reviewId : null);
            if (!pick) { showReview(null); return; }
            return loadReview(pick);
        }, fail);
    }

    /** @param {number} id */
    function loadReview(id) {
        return hb.latest('review', hb.api(R_API + '/' + id)).then(function (res) { showReview(res.data); }, fail);
    }

    // ------------------------------------------------------------------ 심사 상세

    /** @param {PmsReviewDetail | null} d */
    function showReview(d) {
        detail = d;
        var box = /** @type {HTMLElement} */ (document.getElementById('reviewBox'));
        box.hidden = !d;
        /** @type {HTMLElement} */ (document.getElementById('scopeBox')).hidden = !d;
        if (!d) { return; }
        var r = d.review;
        var edit = CAN_SAVE && d.canEdit;
        /** @type {HTMLElement} */ (document.getElementById('reviewTitle')).textContent = hb.t('js.pms.reviewTitle', r.reviewNo, d.gate.taskNm);
        var state = reviewStatusName(r.status) + (r.decision ? ' · ' + decisionName(r.decision) : '')
            + (r.status === 'DECIDED' && r.reqCnt !== null && r.reqCnt !== undefined ? ' · ' + hb.t('js.pms.readyAtDecision', r.readyCnt, r.reqCnt) : '')
            + (r.decidedBy ? ' (' + r.decidedBy + ')' : '');
        /** @type {HTMLElement} */ (document.getElementById('reviewState')).textContent = state;
        hb.showFieldErrors(reviewForm, []);
        field('reviewDate').value = r.reviewDate || '';
        field('decision').value = r.decision || '';
        field('summary').value = r.summary || '';
        reviewForm.querySelectorAll('input, select, textarea').forEach(function (i) { /** @type {HTMLInputElement} */ (i).disabled = !edit; });
        checksHost.textContent = '';
        d.checks.forEach(function (c) { addCheckRow(c, edit); });
        condsHost.textContent = '';
        d.conds.forEach(function (c) { addCondRow(c, edit); });
        /** @type {HTMLElement} */ (document.getElementById('addCheckBtn')).hidden = !edit;
        /** @type {HTMLElement} */ (document.getElementById('addCondBtn')).hidden = !edit;
        /** @type {HTMLElement} */ (document.getElementById('editTools')).hidden = !edit;
        /** @type {HTMLElement} */ (document.getElementById('dangerTools')).hidden = !edit;
        /** @type {HTMLElement} */ (document.getElementById('submitBox')).hidden = !(edit && (d.approvalRequired || d.canDecideDirect));
        /** @type {HTMLElement} */ (document.getElementById('approverBox')).hidden = !d.approvalRequired;
        var submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById('submitReviewBtn'));
        submitBtn.textContent = d.approvalRequired ? hb.t('js.pms.submitApproval') : hb.t('js.pms.decideNow');
        // 이 게이트가 보는 산출물
        scopeBody.textContent = '';
        if (!d.scope.length) { emptyRow(scopeBody, 4, hb.t('js.pms.noDelivs')); }
        d.scope.forEach(function (x) {
            var tr = hb.el('tr');
            [x.wbsNo || '', x.delivNm, x.requiredYn === 'Y' ? hb.t('js.pms.required.Y') : hb.t('js.pms.required.N')]
                .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
            var ok = ['APPROVED', 'WAIVED'].indexOf(x.status) >= 0;
            var ds = hb.el('td', x.requiredYn === 'Y' && !ok ? 'pms-late' : null);
            ds.appendChild(pmsUi.badge('deliv', x.status, delivStatusName(x.status)));
            tr.appendChild(ds);
            scopeBody.appendChild(tr);
        });
        // 결재
        var a = d.approval;
        /** @type {HTMLElement} */ (document.getElementById('aprBox')).hidden = !a.aprId;
        var lines = /** @type {HTMLElement} */ (document.getElementById('aprLines'));
        lines.textContent = '';
        a.lines.forEach(function (l) {
            lines.appendChild(hb.el('li', null, l.seq + '. ' + l.approverNm + ' · ' + l.status + (l.comment ? ' — ' + l.comment : '')));
        });
        /** @type {HTMLElement} */ (document.getElementById('approveBtn')).hidden = !a.myTurn;
        /** @type {HTMLElement} */ (document.getElementById('rejectBtn')).hidden = !a.myTurn;
        /** @type {HTMLElement} */ (document.getElementById('withdrawBtn')).hidden = !a.canWithdraw;
        hb.dirty.clear(reviewForm);
    }

    /** @param {string} n */
    function field(n) { return /** @type {HTMLInputElement} */ (reviewForm.querySelector('[data-field="' + n + '"]')); }

    function renumber() {
        checksHost.querySelectorAll('[data-row-key]').forEach(function (row, i) { row.setAttribute('data-row-key', 'c' + i); });
        condsHost.querySelectorAll('[data-row-key]').forEach(function (row, i) { row.setAttribute('data-row-key', 'k' + i); });
    }

    /** 행마다 작은 폼(상세 폭이 좁아 표 대신 — SKILL §5) @param {PmsCheck | null} c @param {boolean} edit */
    function addCheckRow(c, edit) {
        var row = hb.el('div', 'hb-form pms-row-form');
        row.setAttribute('data-row-key', 'c');
        var nm = /** @type {HTMLInputElement} */ (hb.el('input'));
        nm.setAttribute('data-field', 'checkNm');
        nm.maxLength = 200;
        nm.value = c ? c.checkNm : '';
        var res = /** @type {HTMLSelectElement} */ (hb.el('select'));
        res.setAttribute('data-field', 'result');
        option(res, '', hb.t('js.pms.result.none'));
        option(res, 'OK', hb.t('js.pms.result.OK'));
        option(res, 'NG', hb.t('js.pms.result.NG'));
        option(res, 'NA', hb.t('js.pms.result.NA'));
        res.value = c && c.result ? c.result : '';
        var note = /** @type {HTMLInputElement} */ (hb.el('input'));
        note.setAttribute('data-field', 'note');
        note.maxLength = 500;
        note.value = c && c.note ? c.note : '';
        row.appendChild(labeled(hb.t('js.pms.col.checkNm'), nm, true));
        row.appendChild(labeled(hb.t('js.pms.col.result'), res, false));
        row.appendChild(labeled(hb.t('js.pms.col.note'), note, false));
        [nm, res, note].forEach(function (i) { i.disabled = !edit; i.addEventListener('input', markDirty); });
        if (edit) { row.appendChild(removeBtn(row)); }
        checksHost.appendChild(row);
        renumber();
    }

    /** @param {PmsCond | null} c @param {boolean} edit */
    function addCondRow(c, edit) {
        var row = hb.el('div', 'hb-form pms-row-form');
        row.setAttribute('data-row-key', 'k');
        var title = /** @type {HTMLInputElement} */ (hb.el('input'));
        title.setAttribute('data-field', 'title');
        title.maxLength = 200;
        title.value = c ? c.title : '';
        var owner = /** @type {HTMLSelectElement} */ (hb.el('select'));
        owner.setAttribute('data-field', 'ownerLoginId');
        option(owner, '', hb.t('js.pms.noOwner'));
        users.forEach(function (u) { option(owner, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        owner.value = c && c.ownerLoginId ? c.ownerLoginId : '';
        var due = /** @type {HTMLInputElement} */ (hb.el('input'));
        due.type = 'date';
        due.setAttribute('data-field', 'dueDate');
        due.value = c && c.dueDate ? c.dueDate : '';
        row.appendChild(labeled(hb.t('js.pms.col.condTitle'), title, true));
        row.appendChild(labeled(hb.t('js.pms.col.owner'), owner, false));
        row.appendChild(labeled(hb.t('js.pms.col.due'), due, false));
        if (c && c.issueNo) { row.appendChild(hb.el('span', 'hb-muted', hb.t('js.pms.issueCreated', c.issueNo))); }
        [title, owner, due].forEach(function (i) { i.disabled = !edit; i.addEventListener('input', markDirty); });
        if (edit) { row.appendChild(removeBtn(row)); }
        condsHost.appendChild(row);
        renumber();
    }

    /** @param {string} text @param {HTMLElement} input @param {boolean} wide */
    function labeled(text, input, wide) {
        var l = hb.el('label', wide ? 'hb-wide' : null);
        l.appendChild(hb.el('span', null, text));
        l.appendChild(input);
        return l;
    }

    /** @param {HTMLElement} row */
    function removeBtn(row) {
        var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.pms.removeRow')));
        b.type = 'button';
        b.addEventListener('click', function () { row.remove(); renumber(); markDirty(); });
        return b;
    }

    var dirtyRows = false;
    function markDirty() { dirtyRows = true; }

    /** @param {HTMLElement} row @param {string} n */
    function rv(row, n) { return /** @type {HTMLInputElement} */ (row.querySelector('[data-field="' + n + '"]')).value; }

    function body() {
        var d = /** @type {PmsReviewDetail} */ (detail);
        return {reviewDate: field('reviewDate').value, decision: field('decision').value, summary: field('summary').value,
            checks: Array.prototype.map.call(checksHost.querySelectorAll('[data-row-key]'), /** @param {HTMLElement} row */ function (row) {
                return {checkNm: rv(row, 'checkNm'), result: rv(row, 'result'), note: rv(row, 'note')};
            }),
            conds: Array.prototype.map.call(condsHost.querySelectorAll('[data-row-key]'), /** @param {HTMLElement} row */ function (row) {
                return {title: rv(row, 'title'), ownerLoginId: rv(row, 'ownerLoginId'), dueDate: rv(row, 'dueDate')};
            }),
            rowVer: d.review.rowVer};
    }

    /** 저장 후 다시 읽기 — 목록(마지막 심사)도 바뀐다 @param {string} [text] */
    function reload(text) {
        var d = /** @type {PmsReviewDetail} */ (detail);
        dirtyRows = false;
        hb.dirty.clear();
        return openProject(/** @type {number} */ (projId), /** @type {HTMLElement} */ (document.getElementById('projTitle')).textContent || '',
            d.gate.taskId, d.review.reviewId).then(function () { notify(text || hb.t('js.common.saved')); });
    }

    // ------------------------------------------------------------------ 버튼

    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newReviewBtn'));
    if (newBtn) {
        var nb = newBtn;
        nb.addEventListener('click', function () {
            var g = /** @type {PmsGate} */ (gate);
            if (!g) { return; }
            hb.busy(nb, function () { return hb.api('/api/pms/gates/' + g.taskId + '/reviews', {method: 'POST', body: {}}); }).then(function (res) {
                if (!res) { return; }
                return openProject(/** @type {number} */ (projId), /** @type {HTMLElement} */ (document.getElementById('projTitle')).textContent || '',
                    g.taskId, res.data).then(function () { notify(hb.t('js.pms.reviewCreated')); });
            }, fail);
        });
    }

    /** @type {HTMLElement} */ (document.getElementById('addCheckBtn')).addEventListener('click', function () { addCheckRow(null, true); markDirty(); });
    /** @type {HTMLElement} */ (document.getElementById('addCondBtn')).addEventListener('click', function () { addCondRow(null, true); markDirty(); });

    /** 작성 중 내용을 먼저 저장한 뒤 다음 동작 @returns {Promise<any>} */
    function saveFirst() {
        var d = /** @type {PmsReviewDetail} */ (detail);
        return hb.api(R_API + '/' + d.review.reviewId, {method: 'PUT', body: body()}).then(function () {
            return hb.api(R_API + '/' + d.review.reviewId);
        }).then(function (res) { detail = res.data; return res; });
    }

    var saveBtn = /** @type {HTMLButtonElement} */ (document.getElementById('saveReviewBtn'));
    saveBtn.addEventListener('click', function () {
        hb.busy(saveBtn, saveFirst).then(function (res) { if (res) { return reload(); } }, fail);
    });

    var submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById('submitReviewBtn'));
    submitBtn.addEventListener('click', function () {
        var d = /** @type {PmsReviewDetail} */ (detail);
        var approver = /** @type {HTMLSelectElement} */ (document.getElementById('approverSel')).value;
        if (d.approvalRequired && !approver) { notify(hb.t('js.pms.chooseApprover'), true); return; }
        pmsUi.ask(d.approvalRequired ? hb.t('js.pms.dlg.submit') : hb.t('js.pms.dlg.decide'),
            d.approvalRequired ? hb.t('js.pms.confirmSubmit') : hb.t('js.pms.confirmDecide'),
            d.approvalRequired ? hb.t('js.pms.dlg.submitOk') : hb.t('js.pms.dlg.decideOk')).then(function (ok) {
            if (!ok) { return; }
            hb.busy(submitBtn, function () {
                return saveFirst().then(function (res) {
                    var lines = d.approvalRequired ? [{userId: Number(approver), lineType: 'APPROVE'}] : null;
                    return hb.api(R_API + '/' + d.review.reviewId + '/submit', {method: 'POST', body: {lines: lines, rowVer: res.data.review.rowVer}});
                });
            }).then(function (res) {
                if (res) { return reload(d.approvalRequired ? hb.t('js.pms.submitted') : hb.t('js.pms.decided')); }
            }, fail);
        });
    });

    var cancelBtn = /** @type {HTMLButtonElement} */ (document.getElementById('cancelReviewBtn'));
    cancelBtn.addEventListener('click', function () {
        var d = /** @type {PmsReviewDetail} */ (detail);
        pmsUi.ask(hb.t('js.pms.dlg.cancelReview'), hb.t('js.pms.confirmCancelReview'), hb.t('js.pms.dlg.cancelReviewOk'), true).then(function (ok) {
            if (!ok) { return; }
            hb.busy(cancelBtn, function () {
                return hb.api(R_API + '/' + d.review.reviewId + '/cancel', {method: 'POST', body: {rowVer: d.review.rowVer}});
            }).then(function (res) { if (res) { return reload(); } }, fail);
        });
    });

    /** @param {string} id @param {string} path */
    function aprAct(id, path) {
        var btn = /** @type {HTMLButtonElement} */ (document.getElementById(id));
        btn.addEventListener('click', function () {
            var d = /** @type {PmsReviewDetail} */ (detail);
            var comment = /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value;
            hb.busy(btn, function () {
                return hb.api(APR + '/' + d.approval.aprId + '/' + path, {method: 'POST', body: {comment: comment}});
            }).then(function (res) {
                if (!res) { return; }
                /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value = '';
                return reload();
            }, fail);
        });
    }
    aprAct('approveBtn', 'approve');
    aprAct('rejectBtn', 'reject');
    aprAct('withdrawBtn', 'withdraw');

    // ------------------------------------------------------------------ 시작

    hb.dirty.watch(reviewForm);
    window.addEventListener('beforeunload', function (e) { if (dirtyRows) { e.preventDefault(); } });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: loadProjects});
        return Promise.all([hb.loadSession(), hb.api('/api/pms/gate-users')]);
    }).then(function (r) {
        users = r[1].data;
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('approverSel'));
        option(sel, '', hb.t('js.pms.chooseApprover'));
        var me = String(hb.session().loginId || '').toLowerCase();
        users.filter(function (u) { return u.loginId.toLowerCase() !== me; }).forEach(function (u) { option(sel, String(u.userId), u.userNm + ' (' + u.loginId + ')'); });
        return initGrid();
    }).then(function () {
        return loadProjects();
    }).then(null, function (/** @type {any} */ err) { notify(err && err.message, true); });
})();
