// @ts-check
/*
 * 고객 요구사항 CSR (qms-csr) — P3 QMS Q4 (docs/29 D8).
 *   목록 표(상한 500) + 상세: 기본 폼 / override 4칸 / 적용 대상 표(한 벌, 품목·품목 클래스) / 첨부.
 * 검증은 서버(정해진 키·양의 정수). 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{csrId: number, custCd: string, custNm?: string | null, csrNo: string, csrRev: string, title: string,
 *   effectiveFrom: string, overrides: Record<string, number>, notifyReq?: Record<string, string> | null, useYn: string, remark?: string | null, targetText?: string | null,
 *   rowVer: number}} QmsCsr
 * @typedef {{targetType: string, targetCd: string, targetNm?: string | null}} QmsCsrTarget
 * @typedef {{csr: QmsCsr, targets: QmsCsrTarget[]}} QmsCsrDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/qms/csrs';
    var OV_KEYS = ['CONTAIN_DAYS', 'CLOSE_DAYS', 'NOTIFY_HOURS', 'RETENTION_YEARS'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('csrForm'));
    var ovForm = /** @type {HTMLFormElement} */ (document.getElementById('ovForm'));
    /** 4M 유형별 고객 통지 요구(docs/29_P3 52장) — 선택지 = 공통코드 QMS_4M_NOTIFY */
    var notifyForm = /** @type {HTMLFormElement} */ (document.getElementById('notifyForm'));
    var M4 = ['MAN', 'MACHINE', 'MATERIAL', 'METHOD', 'MEASURE', 'ENV', 'SUPPLIER', 'SITE'];
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** @type {QmsCsrDetail | null} */
    var detail = null;
    /** @type {QmsCsrTarget[]} */
    var targets = [];
    var targetsDirty = false;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var ovNames = {};

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
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** 4M 유형 → 고른 요구(NONE 도 보낸다 — 서버가 "요구 없음"으로 지움) @returns {Record<string, string>} */
    function notifyReq() {
        /** @type {Record<string, string>} */
        var out = {};
        M4.forEach(function (t) { out[t] = field(notifyForm, 'notifyReq.' + t).value || 'NONE'; });
        return out;
    }

    /** @param {Record<string, number>} ov */
    function ovText(ov) {
        return OV_KEYS.filter(function (k) { return ov && ov[k] != null; })
            .map(function (k) { return (ovNames[k] || k) + ' ' + ov[k]; }).join(', ');
    }

    // ---------- 목록 ----------

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 API/grid·/columns @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadList() {
        return grid ? grid.reload(false, detail ? detail.csr.csrId : undefined) : Promise.resolve();
    }

    function startGrid() {
        grid = hb.grid('#csrGrid', {
            progCd: PROG, id: 'main', key: 'csrId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: qmsUi.TONES.useYn},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {number} */ (row.csrId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    // ---------- 상세 ----------

    /** @param {QmsCsrDetail | null} d */
    function render(d) {
        detail = d;
        var c = d ? d.csr : null;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(ovForm, []);
        hb.showFieldErrors(notifyForm, []);
        M4.forEach(function (t) {
            var sel = /** @type {HTMLSelectElement} */ (field(notifyForm, 'notifyReq.' + t));
            sel.value = c && c.notifyReq && c.notifyReq[t] ? c.notifyReq[t] : 'NONE';
            sel.disabled = !CAN_SAVE;
        });
        field(form, 'custCd').value = c ? c.custCd : '';
        field(form, 'csrNo').value = c ? c.csrNo : '';
        field(form, 'csrRev').value = c ? c.csrRev : '';
        field(form, 'effectiveFrom').value = c ? c.effectiveFrom : '';
        field(form, 'title').value = c ? c.title : '';
        field(form, 'useYn').value = c ? c.useYn : 'Y';
        field(form, 'remark').value = c && c.remark ? c.remark : '';
        OV_KEYS.forEach(function (k) {
            var v = c && c.overrides ? c.overrides[k] : null;
            field(ovForm, 'overrides.' + k).value = v == null ? '' : String(v);
        });
        /** @type {NodeListOf<HTMLInputElement>} */ (document.querySelectorAll('#csrForm input, #ovForm input'))
            .forEach(function (i) { i.readOnly = !CAN_SAVE; });
        /** @type {HTMLInputElement} */ (field(form, 'custCd')).readOnly = !!c || !CAN_SAVE;
        /** @type {HTMLSelectElement} */ (field(form, 'useYn')).disabled = !CAN_SAVE;
        formMode.textContent = c ? hb.t('js.qms.csr.editing', c.custCd, c.csrNo, c.csrRev) : hb.t('js.qms.csr.creating');
        targets = d ? d.targets.map(function (t) { return {targetType: t.targetType, targetCd: t.targetCd, targetNm: t.targetNm}; }) : [];
        targetsDirty = false;
        renderTargets();
        document.querySelectorAll('#csrTable tbody tr').forEach(function (tr) {
            tr.classList.toggle('hb-qms-sel', !!c && tr.getAttribute('data-id') === String(c.csrId));
        });
        var fb = document.getElementById('fileBox');
        if (fb) { fb.hidden = !c; }
        loadFiles();
        hb.dirty.clear(form);
        hb.dirty.clear(ovForm);
    }

    function renderTargets() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#targetTable tbody'));
        tbody.textContent = '';
        targets.forEach(function (t, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row', String(i));
            var tdType = hb.el('td');
            var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
            [['ITEM', 'js.qms.csr.target.ITEM'], ['ITEM_CLASS', 'js.qms.csr.target.ITEM_CLASS']].forEach(function (p) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, hb.t(p[1])));
                o.value = p[0];
                sel.appendChild(o);
            });
            sel.value = t.targetType;
            sel.disabled = !CAN_SAVE;
            sel.addEventListener('change', function () { t.targetType = sel.value; t.targetNm = ''; targetsDirty = true; });
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
            tr.appendChild(hb.el('td', null, t.targetNm || ''));
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
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.csr.allItems')));
            td0.colSpan = 4;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
    }

    /** @param {HB.FieldError[] | undefined} errs */
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

    // ---------- 첨부 ----------

    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!detail) { return; }
        var id = detail.csr.csrId;
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + id)).then(function (res) {
            (res.list || []).forEach(/** @param {{fileId: number, name: string, size: number}} f */ function (f) {
                var li = hb.el('li');
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.name));
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (CAN_SAVE) {
                    var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        qmsUi.ask('fileDelete', hb.t('js.qms.doc.confirmFileDelete', f.name)).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}).then(function () { loadFiles(); },
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
            if (!file || !detail) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(detail.csr.csrId));
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                loadFiles();
            }, function (err) {
                upInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    // ---------- 읽기·저장 ----------

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { render(r.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
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

    function newCsr() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            render(null);
            hb.page.openDetail();
            field(form, 'custCd').focus();
        });
    }
    bind('newBtn', newCsr);
    bind('newBtnList', newCsr);
    bind('addTargetBtn', function () {
        targets.push({targetType: 'ITEM', targetCd: '', targetNm: ''});
        targetsDirty = true;
        renderTargets();
    });

    bind('saveBtn', function (btn) {
        var d = detail;
        /** @type {Record<string, string>} */
        var ov = {};
        OV_KEYS.forEach(function (k) {
            var v = field(ovForm, 'overrides.' + k).value.trim();
            if (v) { ov[k] = v; }
        });
        var b = {custCd: field(form, 'custCd').value, csrNo: field(form, 'csrNo').value, csrRev: field(form, 'csrRev').value,
            effectiveFrom: field(form, 'effectiveFrom').value, title: field(form, 'title').value, useYn: field(form, 'useYn').value,
            remark: field(form, 'remark').value, overrides: ov, notifyReq: notifyReq(),
            targets: targets.map(function (t) { return {targetType: t.targetType, targetCd: t.targetCd}; }),
            rowVer: d ? d.csr.rowVer : null};
        hb.busy(btn, function () {
            return d ? hb.api(API + '/' + d.csr.csrId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            hb.dirty.clear(ovForm);
            hb.dirty.clear(notifyForm);
            targetsDirty = false;
            var id = d ? d.csr.csrId : res.data;
            return load(id).then(loadList).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(errText(err), true);
            var rows = (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return !e.rowKey; });
            hb.showFieldErrors(form, rows.filter(/** @param {HB.FieldError} e */ function (e) { return (e.field || '').indexOf('overrides') !== 0 && (e.field || '').indexOf('notifyReq') !== 0; }));
            hb.showFieldErrors(notifyForm, rows.filter(/** @param {HB.FieldError} e */ function (e) { return (e.field || '').indexOf('notifyReq') === 0; }));
            hb.showFieldErrors(ovForm, rows.filter(/** @param {HB.FieldError} e */ function (e) { return (e.field || '').indexOf('overrides') === 0; }));
            markTargets(err.errors);
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    hb.dirty.watch(ovForm);
    hb.dirty.watch(notifyForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        ovNames = {CONTAIN_DAYS: hb.t('js.qms.csr.ov.CONTAIN_DAYS'), CLOSE_DAYS: hb.t('js.qms.csr.ov.CLOSE_DAYS'),
            NOTIFY_HOURS: hb.t('js.qms.csr.ov.NOTIFY_HOURS'), RETENTION_YEARS: hb.t('js.qms.csr.ov.RETENTION_YEARS')};
        sp = hb.searchPanel(search, {onSearch: function () { return confirmLeave().then(function (ok) { return ok && grid ? grid.reload(true) : null; }); }});
        var sels = M4.map(function (t) { return hb.select(/** @type {HTMLSelectElement} */ (field(notifyForm, 'notifyReq.' + t))).ready; });
        return Promise.all([hb.loadSession(), sp.ready].concat(sels));
    }).then(function () {
        render(null);
        return startGrid().then(function () { return sp.search(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
