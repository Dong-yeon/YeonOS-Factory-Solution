// @ts-check
/*
 * 관리문서 (qms-doc) — P3 QMS Q3 (docs/29 D7, PLM D8).
 *   hb.grid(문서) + 상세: 문서 폼 / 적용 대상 표(한 벌) / 리비전 표 → 선택 리비전(변경 내용·배포·폐기·결재·첨부).
 * 상태 판정은 서버. 작성 중 리비전만 내용·첨부를 고친다. 결재 승인·반려·회수는 공통 결재 API. 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{docId: number, docNo: string, docType: string, docNm: string, useYn: string, remark?: string | null,
 *   rowVer: number}} QmsDoc
 * @typedef {{targetType: string, targetCd: string, targetNm?: string | null, useYn?: string | null}} QmsDocTarget
 * @typedef {{docRevId: number, rev: string, status: string, changeSummary?: string | null, releasedAt?: string | null,
 *   obsoletedAt?: string | null, fileCnt: number, rowVer: number, docNo: string}} QmsDocRev
 * @typedef {{seq: number, approverNm: string, lineType: string, status: string, actedAt?: string | null,
 *   comment?: string | null}} QmsDocLine
 * @typedef {{aprId?: number | null, status?: string | null, myTurn: boolean, canWithdraw: boolean, lines: QmsDocLine[]}} QmsDocApproval
 * @typedef {{doc: QmsDoc, targets: QmsDocTarget[], revs: QmsDocRev[], approval: QmsDocApproval,
 *   approvalRequired: boolean}} QmsDocDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/docs';
    var APR = '/api/platform/approvals';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('docForm'));
    var revForm = /** @type {HTMLFormElement} */ (document.getElementById('revForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** @type {QmsDocDetail | null} */
    var detail = null;
    /** 선택한 리비전 @type {QmsDocRev | null} */
    var rev = null;
    /** 적용 대상 편집 중 목록 @type {QmsDocTarget[]} */
    var targets = [];
    /** 올리기 전 결재선 @type {{userId: number, approverNm: string, lineType: string}[]} */
    var lines = [];
    var targetsDirty = false;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.SelectControl} */
    var typeSel;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var targetNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {{message: string, errors?: HB.FieldError[]}} err */
    function errText(err) {
        return err.message + (err.errors && err.errors.length
            ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : '');
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function field(f, n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var el = document.getElementById(id);
        if (el) { el.hidden = !on; }
    }

    // ---------- 문서 ----------

    /** @param {QmsDocDetail | null} d @param {number | null} [keepRevId] */
    function render(d, keepRevId) {
        detail = d;
        var doc = d ? d.doc : null;
        hb.showFieldErrors(form, []);
        field(form, 'docNo').value = doc ? doc.docNo : '';
        typeSel.setValue(doc ? doc.docType : '');
        field(form, 'docNm').value = doc ? doc.docNm : '';
        field(form, 'useYn').value = doc ? doc.useYn : 'Y';
        field(form, 'remark').value = doc && doc.remark ? doc.remark : '';
        /** @type {HTMLInputElement} */ (field(form, 'docNo')).readOnly = !!doc || !CAN_SAVE;
        /** @type {HTMLSelectElement} */ (field(form, 'docType')).disabled = !!doc || !CAN_SAVE;
        formMode.textContent = doc ? hb.t('js.qms.doc.editing', doc.docNo, doc.docNm) : hb.t('js.qms.doc.creating');
        targets = d ? d.targets.map(function (t) { return {targetType: t.targetType, targetCd: t.targetCd, targetNm: t.targetNm, useYn: t.useYn}; }) : [];
        targetsDirty = false;
        renderTargets();
        show('revSection', !!d);
        renderRevs();
        var keep = d && keepRevId ? d.revs.filter(function (r) { return r.docRevId === keepRevId; })[0] : null;
        var open = d ? d.revs.filter(function (r) { return r.status === 'DRAFT' || r.status === 'IN_APPROVAL'; })[0] : null;
        selectRev(keep || open || (d && d.revs.length ? d.revs[0] : null));
        hb.dirty.clear(form);
    }

    function renderTargets() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#targetTable tbody'));
        tbody.textContent = '';
        targets.forEach(function (t, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row', String(i));
            var tdType = hb.el('td');
            var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
            ['ITEM', 'ITEM_CLASS', 'PROC'].forEach(function (c) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, targetNames[c] || c));
                o.value = c;
                sel.appendChild(o);
            });
            sel.value = t.targetType;
            sel.disabled = !CAN_SAVE;
            sel.addEventListener('change', function () { t.targetType = sel.value; targetsDirty = true; });
            tdType.appendChild(sel);
            tr.appendChild(tdType);
            var tdCd = hb.el('td');
            var inp = /** @type {HTMLInputElement} */ (hb.el('input'));
            inp.value = t.targetCd || '';
            inp.maxLength = 40;
            inp.readOnly = !CAN_SAVE;
            inp.addEventListener('input', function () { t.targetCd = inp.value; t.targetNm = ''; targetsDirty = true; });
            tdCd.appendChild(inp);
            tr.appendChild(tdCd);
            tr.appendChild(hb.el('td', null, (t.targetNm || '') + (t.useYn === 'N' ? ' (' + hb.t('js.qms.inactive') + ')' : '')));
            var tdAct = hb.el('td');
            if (CAN_SAVE) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                del.type = 'button';
                del.addEventListener('click', function () { targets.splice(i, 1); targetsDirty = true; renderTargets(); });
                tdAct.appendChild(del);
            }
            tr.appendChild(tdAct);
            tbody.appendChild(tr);
        });
        if (!targets.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.doc.noTargets')));
            td0.colSpan = 4;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
    }

    /** 행 키(배열 순번) 오류를 대상 행에 @param {HB.FieldError[] | undefined} errs */
    function markTargets(errs) {
        document.querySelectorAll('#targetTable input, #targetTable select').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        (errs || []).forEach(function (e) {
            if (e.rowKey === undefined || e.rowKey === null) { return; }
            var el = document.querySelector('#targetTable tr[data-row="' + e.rowKey + '"] ' + (e.field === 'targetType' ? 'select' : 'input'));
            if (el) {
                el.setAttribute('aria-invalid', 'true');
                el.setAttribute('title', e.message);
            }
        });
    }

    // ---------- 리비전 ----------

    function renderRevs() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#revTable tbody'));
        tbody.textContent = '';
        (detail ? detail.revs : []).forEach(function (r) {
            var tr = hb.el('tr', 'hb-qms-click' + (r.status === 'OBSOLETE' ? ' hb-tree-dim' : '') + (rev && rev.docRevId === r.docRevId ? ' hb-qms-sel' : ''));
            tr.appendChild(hb.el('td', null, r.rev));
            tr.appendChild(qmsUi.cell('rev', r.status, statusNames[r.status]));
            tr.appendChild(hb.el('td', 'hb-wrap', r.changeSummary || ''));
            tr.appendChild(hb.el('td', null, r.releasedAt ? hb.fmt.datetime(r.releasedAt) : ''));
            tr.appendChild(hb.el('td', 'hb-num', String(r.fileCnt)));
            tr.addEventListener('click', function () {
                if (hb.dirty.isDirty()) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
                selectRev(r);
            });
            tbody.appendChild(tr);
        });
    }

    /** @param {QmsDocRev | null} r */
    function selectRev(r) {
        rev = r;
        lines = [];
        renderRevs();
        var draft = !!r && r.status === 'DRAFT';
        var revMode = /** @type {HTMLElement} */ (document.getElementById('revMode'));
        revMode.textContent = !r ? hb.t('js.qms.doc.noRevSel')
            : draft ? hb.t('js.qms.doc.revEditing', r.rev) : hb.t('js.qms.doc.revReadonly', r.rev, statusNames[r.status] || r.status);
        revForm.hidden = !r;
        hb.showFieldErrors(revForm, []);
        field(revForm, 'rev').value = r ? r.rev : '';
        field(revForm, 'changeSummary').value = r && r.changeSummary ? r.changeSummary : '';
        /** @type {NodeListOf<HTMLInputElement | HTMLTextAreaElement>} */ (revForm.querySelectorAll('input, textarea'))
            .forEach(function (i) { i.readOnly = !draft || !CAN_SAVE; });
        var approval = !!detail && detail.approvalRequired;
        show('saveRevBtn', draft && CAN_SAVE);
        show('releaseBtn', draft && CAN_APPROVE && !approval);
        show('obsoleteBtn', !!r && CAN_DELETE && (r.status === 'DRAFT' || r.status === 'RELEASED'));
        show('approvalBox', !!r && approval && (draft || r.status === 'IN_APPROVAL'));
        show('lineTools', draft && CAN_SAVE);
        renderApproval();
        show('fileBox', !!r);
        show('uploadBox', draft && CAN_SAVE);
        loadFiles();
        hb.dirty.clear(revForm);
    }

    function renderApproval() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#lineTable tbody'));
        tbody.textContent = '';
        if (!rev || !detail) { return; }
        var a = detail.approval;
        var building = rev.status === 'DRAFT';
        show('actTools', rev.status === 'IN_APPROVAL' && (a.myTurn || a.canWithdraw));
        show('approveBtn', a.myTurn);
        show('rejectBtn', a.myTurn);
        show('withdrawBtn', a.canWithdraw);
        /** @param {string} t */
        function lineName(t) {
            return t === 'AGREE' ? hb.t('js.qms.doc.line.AGREE') : t === 'REF' ? hb.t('js.qms.doc.line.REF') : hb.t('js.qms.doc.line.APPROVE');
        }
        if (building) {
            lines.forEach(function (l, i) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, String(i + 1)));
                tr.appendChild(hb.el('td', null, l.approverNm));
                tr.appendChild(hb.el('td', null, lineName(l.lineType)));
                var td = hb.el('td');
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                del.type = 'button';
                del.addEventListener('click', function () { lines.splice(i, 1); renderApproval(); });
                td.appendChild(del);
                tr.appendChild(td);
                tbody.appendChild(tr);
            });
            if (!lines.length) {
                var tr0 = hb.el('tr');
                var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.doc.noLines')));
                td0.colSpan = 4;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
            return;
        }
        a.lines.forEach(function (l) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, String(l.seq)));
            tr.appendChild(hb.el('td', null, l.approverNm));
            tr.appendChild(hb.el('td', null, lineName(l.lineType)));
            tr.appendChild(hb.el('td', null, l.status + (l.actedAt ? ' · ' + hb.fmt.datetime(l.actedAt) : '') + (l.comment ? ' · ' + l.comment : '')));
            tbody.appendChild(tr);
        });
    }

    // ---------- 첨부 (리비전별) ----------

    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!rev) { return; }
        var r = rev;
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + r.docRevId)).then(function (res) {
            (res.list || []).forEach(/** @param {{fileId: number, name: string, size: number}} f */ function (f) {
                var li = hb.el('li');
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.name));
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                if (r.status === 'OBSOLETE') { li.appendChild(hb.el('span', 'hb-qms-badge', hb.t('js.qms.doc.obsoleteFile'))); }
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (CAN_SAVE && r.status === 'DRAFT') {
                    var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        qmsUi.ask('fileDelete', hb.t('js.qms.doc.confirmFileDelete', f.name)).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}).then(function () { return reload(r.docRevId); },
                                function (err) { notify(err.message, true); });
                        });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
            if (!(res.list || []).length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.doc.noFiles'))); }
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));
    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            if (!file || !rev) { return; }
            var revId = rev.docRevId;
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(revId));
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                return reload(revId);
            }, function (err) {
                upInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    // ---------- 읽기·저장 ----------

    /** @param {number} docId @param {number | null} [keepRevId] */
    function load(docId, keepRevId) {
        return hb.latest('detail', hb.api(API + '/' + docId)).then(function (r) { render(r.data, keepRevId); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** 지금 문서를 다시 읽는다(목록도) @param {number | null} [keepRevId] */
    function reload(keepRevId) {
        if (!detail) { return Promise.resolve(); }
        var id = detail.doc.docId;
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () { return load(id, keepRevId); });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function confirmLeave() {
        return qmsUi.leaveOk(hb.dirty.isDirty() || targetsDirty);
    }

    function newDoc() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            render(null);
            hb.page.openDetail();
            field(form, 'docNm').focus();
        });
    }
    bind('newBtn', newDoc);
    bind('newBtnList', newDoc);
    bind('addTargetBtn', function () {
        targets.push({targetType: 'ITEM', targetCd: '', targetNm: ''});
        targetsDirty = true;
        renderTargets();
    });

    bind('saveBtn', function (btn) {
        var d = detail;
        var b = {docNo: field(form, 'docNo').value, docType: typeSel.value(), docNm: field(form, 'docNm').value,
            useYn: field(form, 'useYn').value, remark: field(form, 'remark').value,
            targets: targets.map(function (t) { return {targetType: t.targetType, targetCd: t.targetCd}; }),
            rowVer: d ? d.doc.rowVer : null};
        hb.busy(btn, function () {
            return d ? hb.api(API + '/' + d.doc.docId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            targetsDirty = false;
            var id = d ? d.doc.docId : res.data;
            var g = grid;
            return (g ? g.reload(false, id) : Promise.resolve()).then(function () { return load(id); })
                .then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(form, (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return !e.rowKey; }));
            markTargets(err.errors);
        });
    });

    bind('newRevBtn', function (btn) {
        if (!detail) { return; }
        if (hb.dirty.isDirty() || targetsDirty) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
        var id = detail.doc.docId;
        hb.busy(btn, function () { return hb.api(API + '/' + id + '/revisions', {method: 'POST', body: {}}); }).then(function (res) {
            if (!res) { return; }
            return reload(res.data).then(function () { notify(hb.t('js.qms.doc.revCreated')); });
        }, function (err) { notify(errText(err), true); });
    });

    bind('saveRevBtn', function (btn) {
        if (!rev) { return; }
        var r = rev;
        var b = {rev: field(revForm, 'rev').value, changeSummary: field(revForm, 'changeSummary').value, rowVer: r.rowVer};
        hb.busy(btn, function () { return hb.api(API + '/revisions/' + r.docRevId, {method: 'PUT', body: b}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(revForm);
            return reload(r.docRevId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) { notify(err.message, true); hb.showFieldErrors(revForm, err.errors); });
    });

    /** @param {HTMLButtonElement} btn @param {string} act @param {string} confirmText @param {string} done */
    function revAction(btn, act, confirmText, done) {
        if (!rev) { return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
        var r = rev;
        qmsUi.ask(act, confirmText).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () { return hb.api(API + '/revisions/' + r.docRevId + '/' + act, {method: 'POST', body: {rowVer: r.rowVer}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return reload(r.docRevId).then(function () { notify(done); });
                }, function (err) { notify(errText(err), true); });
        });
    }
    bind('releaseBtn', function (btn) {
        if (rev) { revAction(btn, 'release', hb.t('js.qms.doc.confirmRelease', rev.rev), hb.t('js.qms.doc.released')); }
    });
    bind('obsoleteBtn', function (btn) {
        if (rev) { revAction(btn, 'obsolete', hb.t('js.qms.doc.confirmObsolete', rev.rev), hb.t('js.qms.doc.obsoleted')); }
    });

    bind('addLineBtn', function () {
        var u = /** @type {HTMLSelectElement} */ (document.getElementById('lineUser'));
        if (!u.value) { return; }
        lines.push({userId: Number(u.value), approverNm: u.options[u.selectedIndex].text,
            lineType: /** @type {HTMLSelectElement} */ (document.getElementById('lineType')).value});
        renderApproval();
    });
    bind('submitBtn', function (btn) {
        if (!rev) { return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
        var r = rev;
        qmsUi.ask('submit', hb.t('js.qms.doc.confirmSubmit', r.rev)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/revisions/' + r.docRevId + '/submit', {method: 'POST', body: {rowVer: r.rowVer,
                    lines: lines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
            }).then(function (res) {
                if (!res) { return; }
                lines = [];
                return reload(r.docRevId).then(function () { notify(hb.t('js.qms.doc.submitted')); });
            }, function (err) { notify(errText(err), true); });
        });
    });

    /** @param {string} id @param {string} path @param {boolean} needComment */
    function aprAct(id, path, needComment) {
        bind(id, function (btn) {
            if (!detail || !rev || !detail.approval.aprId) { return; }
            var aprId = detail.approval.aprId;
            var revId = rev.docRevId;
            var comment = /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value;
            if (needComment && !comment.trim()) { notify(hb.t('js.qms.doc.commentRequired'), true); return; }
            hb.busy(btn, function () {
                return hb.api(APR + '/' + aprId + '/' + path, {method: 'POST', body: {comment: comment},
                    headers: {'X-Idempotency-Key': String(Date.now()) + Math.random().toString(36).slice(2)}});
            }).then(function (res) {
                if (!res) { return; }
                /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value = '';
                return reload(revId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) { notify(errText(err), true); });
        });
    }
    aprAct('approveBtn', 'approve', false);
    aprAct('rejectBtn', 'reject', true);
    aprAct('withdrawBtn', 'withdraw', false);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    hb.dirty.watch(revForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        typeSel = hb.select(/** @type {HTMLSelectElement} */ (field(form, 'docType')));
        sp = hb.searchPanel(search, {onSearch: function () { var g = grid;
            return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null; }});
        return Promise.all([hb.codes('DOC_REV_STATUS'), hb.codes('DOC_TARGET_TYPE'), CAN_SAVE ? hb.api(API + '/users') : Promise.resolve(null),
            Promise.all([hb.loadSession(), sp.ready, typeSel.ready])]);
    }).then(function (r) {
        statusNames = r[0];
        targetNames = r[1];
        var us = /** @type {HTMLSelectElement | null} */ (document.getElementById('lineUser'));
        if (us && r[2]) {
            var userSel = us;
            /** @type {{userId: number, userNm: string}[]} */ (r[2].data).forEach(function (u) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm));
                o.value = String(u.userId);
                userSel.appendChild(o);
            });
        }
        grid = hb.grid('#docGrid', {
            progCd: PROG, id: 'main', key: 'docId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_doc',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {docType: 'docType'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {openStatus: qmsUi.TONES.rev, useYn: qmsUi.TONES.useYn},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(row.docId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { render(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
