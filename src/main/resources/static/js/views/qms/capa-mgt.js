// @ts-check
/*
 * 시정조치 8D (qms-capa) — P3 QMS Q4 (docs/29 D7·D8).
 *   hb.grid(8D) + 상세: 기본 폼(부적합 번호 연결 — docs/29_P3 17장) / D1~D8 / 수평전개 표(한 벌) / 종결 결재 / 첨부.
 *   ?capaId= 로 그 8D 를, ?ncrNo= 로 부적합 번호를 채운 새 8D 를 연다(NCR 화면에서).
 * 단계·기한 판정은 서버. 다음 단계에 필요한 D 구역이 비면 서버가 칸 오류로 돌려준다. 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{capaId: number, capaNo: string, source: string, custCd?: string | null, custNm?: string | null, itemCd?: string | null,
 *   itemNm?: string | null, title: string, occurredOn: string, status: string, ownerUserId: number, ownerNm?: string | null,
 *   containDue: string, closeDue: string, dueState?: string | null, csrId?: number | null, csrLabel?: string | null,
 *   d1Team?: string | null, d2Problem?: string | null, d3Contain?: string | null, d4Cause?: string | null, d5Action?: string | null,
 *   d6Verify?: string | null, d7Prevent?: string | null, d8Close?: string | null, closedAt?: string | null,
 *   ncrId?: number | null, ncrNo?: string | null, ncrStatus?: string | null, ncrLotNo?: string | null,
 *   cancelReason?: string | null, spreadOpenCnt: number, rowVer: number}} QmsCapa
 * @typedef {{targetType: string, targetCd: string, targetNm?: string | null, status: string, note?: string | null}} QmsCapaSpread
 * @typedef {{seq: number, approverNm: string, lineType: string, status: string, actedAt?: string | null,
 *   comment?: string | null}} QmsAprLine
 * @typedef {{aprId?: number | null, status?: string | null, myTurn: boolean, canWithdraw: boolean, lines: QmsAprLine[]}} QmsApproval
 * @typedef {{rtnId: number, rtnNo: string, status: string, custCd: string, custNm: string, itemCd: string, itemNm: string,
 *   lotNo?: string | null, qty: number, unitCd: string, reasonCd: string, ncrId?: number | null, ncrNo?: string | null,
 *   capaNo?: string | null, receivedAt: string}} QmsCapaReturn
 * @typedef {{capa: QmsCapa, spreads: QmsCapaSpread[], approval: QmsApproval, approvalRequired: boolean,
 *   custReturns?: QmsCapaReturn[], returnsOtherCust?: number, returnsTruncated?: boolean, returnsMax?: number}} QmsCapaDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var API = '/api/qms/capas';
    var APR = '/api/platform/approvals';
    var D_FIELDS = ['d1Team', 'd2Problem', 'd3Contain', 'd4Cause', 'd5Action', 'd6Verify', 'd7Prevent', 'd8Close'];
    var NEXT = /** @type {Record<string, string>} */ ({OPEN: 'CONTAINED', CONTAINED: 'ANALYZED', ANALYZED: 'ACTIONED', ACTIONED: 'VERIFIED'});
    var FROZEN = ['CLOSING', 'CLOSED', 'CANCELLED'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('capaForm'));
    var dForm = /** @type {HTMLFormElement} */ (document.getElementById('dForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var statusLine = /** @type {HTMLElement} */ (document.getElementById('statusLine'));
    /** @type {QmsCapaDetail | null} */
    var detail = null;
    /** 수평전개 편집 중 목록 @type {QmsCapaSpread[]} */
    var spreads = [];
    /** 올리기 전 결재선 @type {{userId: number, approverNm: string, lineType: string}[]} */
    var lines = [];
    var spreadsDirty = false;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.SelectControl} */
    var sourceSel;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var dueNames = {};
    /** 부적합 상태 이름 @type {Record<string, string>} */
    var ncrStatusNames = {};
    /** @type {Record<string, string>} */ var rtnStatusNames = {};
    /** @type {Record<string, string>} */ var rtnReasonNames = {};
    /** @type {Record<string, string>} */
    var spreadTargetNames = {};
    /** @type {Record<string, string>} */
    var spreadStatusNames = {};

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

    function editable() {
        return CAN_SAVE && (!detail || FROZEN.indexOf(detail.capa.status) < 0);
    }

    /** 수평전개는 효과 확인(VERIFIED) 전까지만 */
    function spreadEditable() {
        return CAN_SAVE && (!detail || !!NEXT[detail.capa.status]);
    }

    // ---------- 상세 ----------

    /** 이어진 부적합 — 번호·상태 + NCR 화면 링크 @param {QmsCapa | null} c */
    function renderNcr(c) {
        var line = /** @type {HTMLElement} */ (document.getElementById('ncrLine'));
        line.hidden = !c || !c.ncrNo;
        if (!c || !c.ncrNo) { return; }
        /** @type {HTMLAnchorElement} */ (document.getElementById('ncrLink')).href = '/page/qms-ncr?ncrNo=' + encodeURIComponent(c.ncrNo);
        /** @type {HTMLElement} */ (document.getElementById('ncrState')).textContent =
            hb.t('js.qms.capa.ncrState', c.ncrNo, ncrStatusNames[c.ncrStatus || ''] || c.ncrStatus || '');
        // 출하 추적(35장): 부적합 LOT 의 원인(역추적)·나간 범위(정방향) — LOT 은 상세에서만 온다
        var back = /** @type {HTMLAnchorElement} */ (document.getElementById('capaTraceBack'));
        var fwd = /** @type {HTMLAnchorElement} */ (document.getElementById('capaTraceFwd'));
        back.hidden = fwd.hidden = !c.ncrLotNo;
        if (c.ncrLotNo) {
            back.href = '/page/qms-lot-trace?lotNos=' + encodeURIComponent(c.ncrLotNo) + '&direction=BACKWARD';
            fwd.href = '/page/qms-lot-trace?lotNos=' + encodeURIComponent(c.ncrLotNo) + '&direction=FORWARD';
        }
    }

    /** @param {QmsCapaDetail | null} d */
    function render(d) {
        // 같은 8D 를 다시 읽을 때(저장 뒤)는 올리기 전 결재선을 남긴다
        if (!d || !detail || detail.capa.capaId !== d.capa.capaId || d.capa.status !== 'VERIFIED') { lines = []; }
        detail = d;
        var c = d ? d.capa : null;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(dForm, []);
        field(form, 'capaNo').value = c ? c.capaNo : '';
        sourceSel.setValue(c ? c.source : '');
        field(form, 'occurredOn').value = c ? c.occurredOn : '';
        field(form, 'title').value = c ? c.title : '';
        field(form, 'custCd').value = c && c.custCd ? c.custCd : '';
        field(form, 'itemCd').value = c && c.itemCd ? c.itemCd : '';
        field(form, 'ncrNo').value = c && c.ncrNo ? c.ncrNo : '';
        renderNcr(c);
        var owner = /** @type {HTMLSelectElement} */ (field(form, 'ownerUserId'));
        owner.value = c ? String(c.ownerUserId) : '';
        if (c && owner.value !== String(c.ownerUserId)) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, c.ownerNm || String(c.ownerUserId)));
            o.value = String(c.ownerUserId);
            owner.appendChild(o);
            owner.value = o.value;
        }
        D_FIELDS.forEach(function (n) {
            var v = c ? /** @type {Record<string, any>} */ (c)[n] : null;
            field(dForm, n).value = v || '';
        });
        var edit = editable();
        /** @type {NodeListOf<HTMLInputElement | HTMLTextAreaElement>} */ (form.querySelectorAll('input:not([data-field="capaNo"]), textarea'))
            .forEach(function (i) { i.readOnly = !edit; });
        /** @type {NodeListOf<HTMLTextAreaElement>} */ (dForm.querySelectorAll('textarea')).forEach(function (i) { i.readOnly = !edit; });
        owner.disabled = !edit;
        /** @type {HTMLSelectElement} */ (field(form, 'source')).disabled = !!c || !CAN_SAVE;
        /** @type {HTMLInputElement} */ (field(form, 'occurredOn')).readOnly = !!c || !CAN_SAVE;
        formMode.textContent = c ? hb.t('js.qms.capa.editing', c.capaNo, statusNames[c.status] || c.status) : hb.t('js.qms.capa.creating');
        renderStatus();
        spreads = d ? d.spreads.map(function (s) { return {targetType: s.targetType, targetCd: s.targetCd, targetNm: s.targetNm, status: s.status, note: s.note}; }) : [];
        spreadsDirty = false;
        renderSpreads();
        renderReturns(d ? d.custReturns || [] : [], d);
        var st = c ? c.status : '';
        show('saveBtn', edit);
        show('advanceBtn', !!c && CAN_SAVE && !!NEXT[st]);
        var advance = document.getElementById('advanceBtn');
        if (advance && c && NEXT[st]) { advance.textContent = hb.t('js.qms.capa.advanceTo', statusNames[NEXT[st]] || NEXT[st]); }
        show('closeBtn', !!c && CAN_APPROVE && st === 'VERIFIED' && !!d && !d.approvalRequired);
        show('cancelBtn', !!c && CAN_SAVE && FROZEN.indexOf(st) < 0);
        show('approvalBox', !!c && !!d && d.approvalRequired && (st === 'VERIFIED' || st === 'CLOSING'));
        show('lineTools', !!c && CAN_SAVE && st === 'VERIFIED');
        renderApproval();
        show('fileBox', !!c);
        show('uploadBox', !!c && edit);
        loadFiles();
        hb.dirty.clear(form);
        hb.dirty.clear(dForm);
    }

    function renderStatus() {
        var c = detail ? detail.capa : null;
        statusLine.hidden = !c;
        if (!c) { return; }
        var parts = [hb.t('js.qms.capa.dues', c.containDue, c.closeDue)];
        if (c.dueState) { parts.push(dueNames[c.dueState] || c.dueState); }
        if (c.csrLabel) { parts.push(hb.t('js.qms.capa.byCsr', c.csrLabel)); }
        if (c.spreadOpenCnt > 0 && NEXT[c.status]) { parts.push(hb.t('js.qms.capa.spreadOpen', c.spreadOpenCnt)); }
        if (c.closedAt) { parts.push(hb.t('js.qms.capa.closedAt', hb.fmt.datetime(c.closedAt))); }
        if (c.cancelReason) { parts.push(hb.t('js.qms.capa.cancelled', c.cancelReason)); }
        statusLine.textContent = parts.join(' · ');
        statusLine.setAttribute('data-status', c.dueState || c.status);
    }

    /**
     * 묶인 고객 반품(docs/29_P3 44장) — 읽기만, 취소 반품도 상태로 보인다. 없으면 구획을 숨긴다.
     * 다른 고객 반품을 뺀 건수·200건 잘림은 안내 줄로(48장 R3-d)
     * @param {QmsCapaReturn[]} list @param {QmsCapaDetail | null} d
     */
    function renderReturns(list, d) {
        var other = d && d.returnsOtherCust ? d.returnsOtherCust : 0;
        var cut = !!(d && d.returnsTruncated);
        show('rtnBox', list.length > 0 || other > 0 || cut);
        var notice = /** @type {HTMLElement} */ (document.getElementById('rtnNotice'));
        var parts = [];
        if (other > 0) { parts.push(hb.t('js.qms.capa.rtnOtherCust', other)); }
        if (cut) { parts.push(hb.t('js.qms.capa.rtnTruncated', d && d.returnsMax ? d.returnsMax : 200)); }
        notice.textContent = parts.join(' ');
        notice.hidden = parts.length === 0;
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#rtnTable tbody'));
        tbody.textContent = '';
        list.forEach(function (r) {
            var tr = hb.el('tr');
            if (r.status === 'CANCELLED') { tr.className = 'hb-muted'; }
            var tdNo = hb.el('td');
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, r.rtnNo));
            a.href = '/page/wms-cust-rtn';
            tdNo.appendChild(a);
            tr.appendChild(tdNo);
            tr.appendChild(hb.el('td', null, rtnStatusNames[r.status] || r.status));
            tr.appendChild(hb.el('td', null, r.custCd + ' ' + (r.custNm || '')));
            tr.appendChild(hb.el('td', null, r.itemCd + ' ' + (r.itemNm || '')));
            tr.appendChild(hb.el('td', null, r.lotNo || ''));
            tr.appendChild(hb.el('td', 'hb-num', hb.fmt.num(r.qty) + ' ' + r.unitCd));
            tr.appendChild(hb.el('td', null, rtnReasonNames[r.reasonCd] || r.reasonCd));
            var tdNcr = hb.el('td');
            if (r.ncrNo) {
                var n = /** @type {HTMLAnchorElement} */ (hb.el('a', null, r.ncrNo));
                n.href = '/page/qms-ncr?ncrNo=' + encodeURIComponent(r.ncrNo);
                tdNcr.appendChild(n);
            }
            tr.appendChild(tdNcr);
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(r.receivedAt)));
            tbody.appendChild(tr);
        });
    }

    function renderSpreads() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#spreadTable tbody'));
        tbody.textContent = '';
        var edit = spreadEditable();
        spreads.forEach(function (s, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row', String(i));
            /** @param {string} name @param {string[]} codes @param {Record<string, string>} names @param {string} value @param {(v: string) => void} set */
            function selectCell(name, codes, names, value, set) {
                var td = hb.el('td');
                var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
                sel.setAttribute('data-col', name);
                codes.forEach(function (cd) {
                    var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, names[cd] || cd));
                    o.value = cd;
                    sel.appendChild(o);
                });
                sel.value = value;
                sel.disabled = !edit;
                sel.addEventListener('change', function () { set(sel.value); spreadsDirty = true; });
                td.appendChild(sel);
                tr.appendChild(td);
            }
            /** @param {string} name @param {string} value @param {number} max @param {(v: string) => void} set */
            function inputCell(name, value, max, set) {
                var td = hb.el('td');
                var inp = /** @type {HTMLInputElement} */ (hb.el('input'));
                inp.setAttribute('data-col', name);
                inp.value = value;
                inp.maxLength = max;
                inp.readOnly = !edit;
                inp.addEventListener('input', function () { set(inp.value); spreadsDirty = true; });
                td.appendChild(inp);
                tr.appendChild(td);
            }
            selectCell('targetType', ['ITEM', 'PROC', 'CUST'], spreadTargetNames, s.targetType, function (v) { s.targetType = v; s.targetNm = ''; });
            inputCell('targetCd', s.targetCd || '', 40, function (v) { s.targetCd = v; s.targetNm = ''; });
            tr.appendChild(hb.el('td', null, s.targetNm || ''));
            selectCell('status', ['OPEN', 'DONE', 'NA'], spreadStatusNames, s.status, function (v) { s.status = v; });
            inputCell('note', s.note || '', 500, function (v) { s.note = v; });
            var tdAct = hb.el('td');
            if (edit) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                del.type = 'button';
                del.addEventListener('click', function () { spreads.splice(i, 1); spreadsDirty = true; renderSpreads(); });
                tdAct.appendChild(del);
            }
            tr.appendChild(tdAct);
            tbody.appendChild(tr);
        });
        if (!spreads.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.capa.noSpreads')));
            td0.colSpan = 6;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
        show('addSpreadBtn', edit);
    }

    /** 행 키(배열 순번) 오류를 수평전개 행에 @param {HB.FieldError[] | undefined} errs */
    function markSpreads(errs) {
        document.querySelectorAll('#spreadTable [data-col]').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        (errs || []).forEach(function (e) {
            if (e.rowKey === undefined || e.rowKey === null) { return; }
            var el = document.querySelector('#spreadTable tr[data-row="' + e.rowKey + '"] [data-col="' + e.field + '"]');
            if (el) {
                el.setAttribute('aria-invalid', 'true');
                el.setAttribute('title', e.message);
            }
        });
    }

    /** 칸 오류를 기본 폼·D 폼에 나눠 표시 @param {HB.FieldError[] | undefined} errs */
    function showErrors(errs) {
        var rows = (errs || []).filter(/** @param {HB.FieldError} e */ function (e) { return !e.rowKey; });
        hb.showFieldErrors(form, rows.filter(function (e) { return D_FIELDS.indexOf(e.field || '') < 0; }));
        hb.showFieldErrors(dForm, rows.filter(function (e) { return D_FIELDS.indexOf(e.field || '') >= 0; }));
        markSpreads(errs);
    }

    function renderApproval() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#lineTable tbody'));
        tbody.textContent = '';
        if (!detail) { return; }
        var a = detail.approval;
        var building = detail.capa.status === 'VERIFIED';
        show('actTools', detail.capa.status === 'CLOSING' && (a.myTurn || a.canWithdraw));
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

    // ---------- 첨부 ----------

    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!detail) { return; }
        var id = detail.capa.capaId;
        var edit = editable();
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + id)).then(function (res) {
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
                        qmsUi.ask('fileDelete', hb.t('js.qms.doc.confirmFileDelete', f.name)).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}).then(function () { return reload(); },
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
            fd.append('targetId', String(detail.capa.capaId));
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                return reload();
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

    function reload() {
        if (!detail) { return Promise.resolve(); }
        var id = detail.capa.capaId;
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () { return load(id); });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function isDirty() {
        return hb.dirty.isDirty() || spreadsDirty;
    }

    function confirmLeave() {
        return qmsUi.leaveOk(isDirty());
    }

    function newCapa() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            render(null);
            hb.page.openDetail();
            field(form, 'title').focus();
        });
    }
    bind('newBtn', newCapa);
    bind('newBtnList', newCapa);
    bind('addSpreadBtn', function () {
        spreads.push({targetType: 'ITEM', targetCd: '', targetNm: '', status: 'OPEN', note: ''});
        spreadsDirty = true;
        renderSpreads();
    });

    bind('saveBtn', function (btn) {
        var d = detail;
        /** @type {Record<string, any>} */
        var b = {source: sourceSel.value(), occurredOn: field(form, 'occurredOn').value, title: field(form, 'title').value,
            custCd: field(form, 'custCd').value, itemCd: field(form, 'itemCd').value, ncrNo: field(form, 'ncrNo').value,
            ownerUserId: field(form, 'ownerUserId').value ? Number(field(form, 'ownerUserId').value) : null,
            spreads: spreadEditable() ? spreads.map(function (s) { return {targetType: s.targetType, targetCd: s.targetCd, status: s.status, note: s.note}; }) : null,
            rowVer: d ? d.capa.rowVer : null};
        D_FIELDS.forEach(function (n) { b[n] = field(dForm, n).value; });
        hb.busy(btn, function () {
            return d ? hb.api(API + '/' + d.capa.capaId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            hb.dirty.clear(dForm);
            spreadsDirty = false;
            var id = d ? d.capa.capaId : res.data;
            var g = grid;
            return (g ? g.reload(false, id) : Promise.resolve()).then(function () { return load(id); })
                .then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(errText(err), true);
            showErrors(err.errors);
        });
    });

    /** @param {HTMLButtonElement} btn @param {string} act @param {Record<string, any>} body @param {string} done */
    function capaAction(btn, act, body, done) {
        if (!detail) { return; }
        if (isDirty()) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
        var c = detail.capa;
        body.rowVer = c.rowVer;
        hb.busy(btn, function () { return hb.api(API + '/' + c.capaId + '/' + act, {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                return reload().then(function () { notify(done); });
            }, function (err) { notify(errText(err), true); showErrors(err.errors); });
    }
    bind('advanceBtn', function (btn) {
        if (!detail) { return; }
        var next = NEXT[detail.capa.status];
        if (!next) { return; }
        qmsUi.ask('advance', hb.t('js.qms.capa.confirmAdvance', statusNames[next] || next)).then(function (yes) {
            if (!yes) { return; }
            capaAction(btn, 'advance', {}, hb.t('js.qms.capa.advanced'));
        });
    });
    bind('closeBtn', function (btn) {
        if (!detail) { return; }
        qmsUi.ask('close', hb.t('js.qms.capa.confirmClose', detail.capa.capaNo)).then(function (yes) {
            if (!yes) { return; }
            capaAction(btn, 'close', {}, hb.t('js.qms.capa.closed'));
        });
    });
    bind('cancelBtn', function (btn) {
        if (!detail) { return; }
        qmsUi.reason('cancel', hb.t('js.qms.capa.cancelReason', detail.capa.capaNo)).then(function (reason) {
            if (reason === null) { return; }
            capaAction(btn, 'cancel', {reason: reason}, hb.t('js.qms.capa.cancelDone'));
        });
    });

    bind('addLineBtn', function () {
        var u = /** @type {HTMLSelectElement} */ (document.getElementById('lineUser'));
        if (!u.value) { return; }
        lines.push({userId: Number(u.value), approverNm: u.options[u.selectedIndex].text,
            lineType: /** @type {HTMLSelectElement} */ (document.getElementById('lineType')).value});
        renderApproval();
    });
    bind('submitBtn', function (btn) {
        if (!detail) { return; }
        if (isDirty()) { notify(hb.t('js.qms.doc.saveFirst'), true); return; }
        var c = detail.capa;
        qmsUi.ask('submit', hb.t('js.qms.capa.confirmSubmit', c.capaNo)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + c.capaId + '/submit', {method: 'POST', body: {rowVer: c.rowVer,
                    lines: lines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
            }).then(function (res) {
                if (!res) { return; }
                lines = [];
                return reload().then(function () { notify(hb.t('js.qms.doc.submitted')); });
            }, function (err) { notify(errText(err), true); showErrors(err.errors); });
        });
    });

    /** @param {string} id @param {string} path @param {boolean} needComment */
    function aprAct(id, path, needComment) {
        bind(id, function (btn) {
            if (!detail || !detail.approval.aprId) { return; }
            var aprId = detail.approval.aprId;
            var comment = /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value;
            if (needComment && !comment.trim()) { notify(hb.t('js.qms.doc.commentRequired'), true); return; }
            hb.busy(btn, function () {
                return hb.api(APR + '/' + aprId + '/' + path, {method: 'POST', body: {comment: comment},
                    headers: {'X-Idempotency-Key': String(Date.now()) + Math.random().toString(36).slice(2)}});
            }).then(function (res) {
                if (!res) { return; }
                /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value = '';
                return reload().then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) { notify(errText(err), true); });
        });
    }
    aprAct('approveBtn', 'approve', false);
    aprAct('rejectBtn', 'reject', true);
    aprAct('withdrawBtn', 'withdraw', false);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    hb.dirty.watch(dForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sourceSel = hb.select(/** @type {HTMLSelectElement} */ (field(form, 'source')));
        sp = hb.searchPanel(search, {onSearch: function () { var g = grid;
            return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null; }});
        return Promise.all([hb.codes('CAPA_STATUS'), hb.codes('CAPA_DUE_STATE'), hb.codes('CAPA_SPREAD_TARGET'), hb.codes('CAPA_SPREAD_STATUS'),
            hb.api(API + '/users'), Promise.all([hb.loadSession(), sp.ready, sourceSel.ready]), hb.codes('QMS_NCR_STATUS'),
            hb.codes('WMS_CUST_RTN_STATUS'), hb.codes('WMS_CUST_RTN_REASON')]);
    }).then(function (r) {
        ncrStatusNames = r[6];
        rtnStatusNames = r[7];
        rtnReasonNames = r[8];
        statusNames = r[0];
        dueNames = r[1];
        spreadTargetNames = r[2];
        spreadStatusNames = r[3];
        var owner = /** @type {HTMLSelectElement} */ (field(form, 'ownerUserId'));
        var me = /** @type {HTMLOptionElement} */ (hb.el('option', null, hb.t('js.qms.capa.ownerMe')));
        me.value = '';
        owner.appendChild(me);
        var lineUser = /** @type {HTMLSelectElement | null} */ (document.getElementById('lineUser'));
        /** @type {{userId: number, userNm: string}[]} */ (r[4].data).forEach(function (u) {
            [owner, lineUser].forEach(function (sel) {
                if (!sel) { return; }
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm));
                o.value = String(u.userId);
                sel.appendChild(o);
            });
        });
        grid = hb.grid('#capaGrid', {
            progCd: PROG, id: 'main', key: 'capaId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'qms_capa',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {status: 'status', source: 'source', dueState: 'dueState'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {status: qmsUi.TONES.capa, dueState: qmsUi.TONES.due},
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(row.capaId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () {
            render(null);
            // NCR 화면에서: ?capaId= 이어진 8D 열기 / ?ncrNo= 그 부적합으로 새 8D
            var qs = new URLSearchParams(window.location.search);
            var capaId = Number(qs.get('capaId'));
            if (capaId > 0) {
                load(capaId);
                hb.page.openDetail();
            } else if (qs.get('ncrNo') && CAN_SAVE) {
                field(form, 'ncrNo').value = qs.get('ncrNo') || '';
                hb.page.openDetail();
            }
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
