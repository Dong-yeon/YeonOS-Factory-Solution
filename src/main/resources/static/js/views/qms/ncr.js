// @ts-check
/*
 * 부적합(NCR) (qms-ncr) — P3 QMS 2부 Q8a (docs/29_P3 12.7).
 *   목록(검색·상태) + 새 부적합(?lotNo=&inspNo=&source= 로 채워 연다) → 상세: 머리 줄 / 내용 고침 / LOT 보류 근거(MES) /
 *   처분 행(통째 저장, 합 표시) / 종결·취소 / 특채 결재(결재선·상신·승인·반려·회수) / 이어진 8D(docs/29_P3 17장).
 *   ?ncrNo= 로 그 부적합을 연다(8D 화면에서). 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{ncrId: number, ncrNo: string, source: string, lotId: number, lotNo: string, itemCd: string, itemNm: string, unitCd: string,
 *   inspId?: number | null, inspNo?: string | null, defectId: number | null, defectCd: string | null, defectNm: string | null, ncQty: number,
 *   srcType?: string | null, srcQty?: number | null, description: string,
 *   occurredAt: string, status: string, holdYn: string, aprId?: number | null, createdAt: string, createdBy: string, rowVer: number,
 *   closedAt?: string | null, closedBy?: string | null, cancelReason?: string | null}} QmsNcr
 * @typedef {{dispId?: number, lineNo?: number, dispType: string, qty: number | string, note?: string | null, reworkLotNo?: string | null}} QmsNcrDisp
 * @typedef {{holdId: number, lotId: number, source: string, refId?: number | null, reason: string, heldAt: string, heldBy: string}} QmsNcrHold
 * @typedef {{seq: number, approverNm: string, lineType: string, status: string, actedAt?: string | null, comment?: string | null}} QmsNcrAprLine
 * @typedef {{aprId?: number | null, status?: string | null, myTurn: boolean, canWithdraw: boolean, lines: QmsNcrAprLine[]}} QmsNcrApproval
 * @typedef {{esignId: number, signerNm: string, meaningCd: string, authMethod: string, signedAt: string, current: boolean}} QmsSign
 * @typedef {{capaId: number, capaNo: string, status: string}} QmsNcrCapa
 * @typedef {{ncr: QmsNcr, disps: QmsNcrDisp[], holds: QmsNcrHold[], approval: QmsNcrApproval, autoHold: boolean,
 *   esignRequired: boolean, signatures: QmsSign[], capas: QmsNcrCapa[], blockedQty?: number | null}} QmsNcrDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/ncrs';
    var APR = '/api/platform/approvals';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var detailBox = /** @type {HTMLElement} */ (document.getElementById('detailBox'));
    var headLine = /** @type {HTMLElement} */ (document.getElementById('headLine'));
    var editForm = /** @type {HTMLFormElement} */ (document.getElementById('editForm'));
    var newForm = /** @type {HTMLFormElement | null} */ (document.getElementById('newForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** 조회 패널 — 홈 숫자 링크(?q.status=…)를 준비 단계에서 채운다 @type {HB.SearchPanel} */
    var sp;
    /** @type {QmsNcrDetail | null} */
    var detail = null;
    /** 화면에서 고치는 처분 행 @type {QmsNcrDisp[]} */
    var disps = [];
    /** 올리기 전 결재선 @type {{userId: number, approverNm: string, lineType: string}[]} */
    var lines = [];
    var dirty = false;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var sourceNames = {};
    /** @type {Record<string, string>} */
    var dispNames = {};
    /** @type {{defectId: number, defectCd: string, defectNm: string}[]} */
    var defects = [];
    /** 보류 근거 출처 이름 — MES LOT 화면과 같은 문구(글자 그대로 키) @type {Record<string, string>} */
    var holdSrcNames = {};
    /** 8D 단계 이름 @type {Record<string, string>} */
    var capaStatusNames = {};

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

    /** @param {ParentNode} root @param {string} name */
    function field(root, name) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (root.querySelector('[data-field="' + name + '"]'));
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var e = document.getElementById(id);
        if (e) { e.hidden = !on; }
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function fillDefects() {
        document.querySelectorAll('select.ncr-defect').forEach(function (s) {
            var sel = /** @type {HTMLSelectElement} */ (s);
            sel.textContent = '';
            sel.appendChild(hb.el('option', null, ''));
            defects.forEach(function (d) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, d.defectCd + ' ' + d.defectNm));
                o.value = String(d.defectId);
                sel.appendChild(o);
            });
        });
    }

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 /grid·/columns, 상태 배지·취소 행 흐림·미저장 이동 확인 @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadList() {
        return grid ? grid.reload(false, detail ? detail.ncr.ncrId : undefined) : Promise.resolve();
    }

    function startGrid() {
        grid = hb.grid('#ncrGrid', {
            progCd: PROG, id: 'main', key: 'ncrId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            filters: {status: 'status', source: 'source'},  // 근거(basis)는 조회 패널 값으로(sp.values)
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return dirty ? qmsUi.ask('leave', hb.t('js.qms.ncr.saveFirst')) : true; },
            status: {status: qmsUi.TONES.ncr},
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {number} */ (row.ncrId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { render(res.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    function reload() {
        return Promise.all([detail ? load(detail.ncr.ncrId) : Promise.resolve(), loadList()]);
    }

    /**
     * 주소로 채워 연 새 폼(출하 추적 클레임 NCR·검사 NG 등): 같은 LOT 의 열린 NCR 이 있으면 노란 줄 + 번호 링크 — 막지는 않는다
     * (다른 고객 클레임일 수 있다, 37장). LOT 번호 대소문자 그대로 같은 것만
     * @param {string} lotNo
     */
    function warnOpenSameLot(lotNo) {
        var note = document.getElementById('dupNote');
        if (!note || !lotNo) { return; }
        var box = note;
        hb.api(API + '?keyword=' + encodeURIComponent(lotNo) + '&status=OPEN_ALL').then(function (res) {
            var same = (res.data || []).filter(/** @param {QmsNcr} x */ function (x) { return x.lotNo === lotNo; });
            box.replaceChildren();
            box.hidden = !same.length;
            if (!same.length) { return; }
            box.appendChild(document.createTextNode(hb.t('js.qms.ncr.dupOpen', lotNo, same.length) + ' '));
            same.forEach(/** @param {QmsNcr} x */ function (x) {
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, x.ncrNo));
                a.href = '/page/qms-ncr?ncrNo=' + encodeURIComponent(x.ncrNo);
                box.appendChild(a);
                box.appendChild(document.createTextNode(' '));
            });
        }, function () { box.hidden = true; });
    }

    /** 출하 추적 화면 주소(35장) @param {string} lotNo @param {string} dir */
    function traceUrl(lotNo, dir) {
        return '/page/qms-lot-trace?lotNos=' + encodeURIComponent(lotNo) + '&direction=' + dir;
    }

    /** @param {QmsNcrDetail} d */
    function render(d) {
        if (!detail || detail.ncr.ncrId !== d.ncr.ncrId || d.ncr.status !== 'OPEN') { lines = []; }
        detail = d;
        dirty = false;
        formMode.hidden = true;
        detailBox.hidden = false;
        var n = d.ncr;
        var open = n.status === 'OPEN';
        headLine.textContent = hb.t('js.qms.ncr.head', n.ncrNo, statusNames[n.status] || n.status, n.lotNo, n.itemCd, n.itemNm,
            (sourceNames[n.source] || n.source) + (n.inspNo ? ' ' + n.inspNo : '')) + (n.cancelReason ? ' · ' + n.cancelReason : '')
            // 고객 반품이 만든 NCR(docs/29_P3 38장) — 불량 유형이 비어 있으면 골라 저장하라는 안내
            + (n.srcType === 'WMS_CUST_RTN' ? ' · ' + hb.t('js.qms.ncr.fromCustRtn') + (open ? ' · ' + hb.t('js.qms.ncr.cancelAtSource') : '') : '')
            // 반품 수량이 부적합 수량 상한(R3-c, 47장)
            + (n.srcQty != null && open ? ' · ' + hb.t('js.qms.ncr.srcQtyCap', hb.fmt.num(n.srcQty), n.unitCd) : '')
            + (n.defectId == null && n.status === 'OPEN' ? ' · ' + hb.t('js.qms.ncr.needDefect') : '');
        headLine.setAttribute('data-status', n.status === 'CLOSED' ? 'OK' : n.status === 'CANCELLED' ? 'CANCELLED' : 'NG');
        // 출하 추적(35장): 이 LOT 이 들어간 아래 LOT·출하 / 이 LOT 을 만든 위 LOT·공급사
        /** @type {HTMLAnchorElement} */ (document.getElementById('traceFwdLink')).href = traceUrl(n.lotNo, 'FORWARD');
        /** @type {HTMLAnchorElement} */ (document.getElementById('traceBackLink')).href = traceUrl(n.lotNo, 'BACKWARD');
        field(editForm, 'defectId').value = n.defectId == null ? '' : String(n.defectId);
        field(editForm, 'ncQty').value = String(n.ncQty);
        field(editForm, 'description').value = n.description;
        editForm.querySelectorAll('input, select').forEach(function (i) {
            /** @type {HTMLInputElement} */ (i).disabled = !open || !CAN_SAVE;
        });
        /** @type {HTMLElement} */ (document.getElementById('holdLine')).textContent =
            n.holdYn === 'Y' ? hb.t('js.qms.ncr.holdOn') : hb.t('js.qms.ncr.holdOff');
        var ul = /** @type {HTMLElement} */ (document.getElementById('holdList'));
        ul.textContent = '';
        d.holds.forEach(function (h) {
            var mine = h.source === 'QMS_NCR' && h.refId === n.ncrId;
            ul.appendChild(hb.el('li', mine ? null : 'hb-muted', hb.t('js.qms.ncr.holdRow', holdSrcNames[h.source] || h.source, h.reason, h.heldBy,
                hb.fmt.datetime(h.heldAt)) + (mine ? ' ' + hb.t('js.qms.ncr.thisNcr') : '')));
        });
        if (!d.holds.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.ncr.noHolds'))); }
        // NG 판정 뒤 같은 LOT 에 더 들어온 몫은 WMS 가 부적합(BLOCKED)으로 이어받는다 — 부적합 수량보다 많으면 고치라고 알림(docs/30 12.15.13)
        var warn = /** @type {HTMLElement} */ (document.getElementById('blockedWarn'));
        var over = d.blockedQty != null && d.blockedQty > Number(n.ncQty);
        warn.hidden = !over;
        warn.textContent = over ? hb.t('js.qms.ncr.blockedOver', hb.fmt.num(d.blockedQty), hb.fmt.num(n.ncQty)) : '';
        // 같은 품목·기간 의심 LOT 봉쇄(Q8b) — 품목·NCR 번호를 채워 연다
        /** @type {HTMLAnchorElement} */ (document.getElementById('containLink')).href = '/page/qms-contain?itemCd=' + encodeURIComponent(n.itemCd)
            + '&ncrNo=' + encodeURIComponent(n.ncrNo);
        renderCapas(d);
        disps = d.disps.map(function (x) { return {dispType: x.dispType, qty: x.qty, note: x.note || '', reworkLotNo: x.reworkLotNo || ''}; });
        renderDisps();
        show('editTools', open && CAN_SAVE);
        // 출처가 있는 NCR 은 출처 화면에서만 취소(43장 CR-b) — 숨김과 별개로 서버가 409 QMS_NCR_SOURCE_OWNED
        show('cancelBtn', open && CAN_DELETE && !n.srcType);
        renderApproval();
        renderSigns(d);
        show('fileBox', true);
        show('uploadBox', open && CAN_SAVE);
        loadFiles();
    }

    // ---------- 첨부 (특채 고객 승인 기록 등 — 처리 중(OPEN)일 때만 올리기·삭제, 서버 NcrFileLock) ----------

    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!detail) { return; }
        var id = detail.ncr.ncrId;
        var edit = detail.ncr.status === 'OPEN' && CAN_SAVE;
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
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}).then(loadFiles,
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
            fd.append('targetId', String(detail.ncr.ncrId));
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

    /** 이어진 8D — 번호·단계 + 8D 화면 링크, 새 8D 는 부적합 번호를 채워 연다 @param {QmsNcrDetail} d */
    function renderCapas(d) {
        var ul = /** @type {HTMLElement} */ (document.getElementById('capaList'));
        ul.textContent = '';
        d.capas.forEach(function (c) {
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, hb.t('js.qms.ncr.capaRow', c.capaNo, capaStatusNames[c.status] || c.status)));
            a.href = '/page/qms-capa?capaId=' + c.capaId;
            var li = hb.el('li');
            li.appendChild(a);
            ul.appendChild(li);
        });
        if (!d.capas.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.ncr.noCapas'))); }
        /** @type {HTMLAnchorElement} */ (document.getElementById('newCapaLink')).href = '/page/qms-capa?ncrNo=' + encodeURIComponent(d.ncr.ncrNo);
    }

    /** 전자서명(docs/29_P3 12.10) — 정책이 켜졌거나 서명이 있으면 보인다 @param {QmsNcrDetail} d */
    function renderSigns(d) {
        show('signBox', d.esignRequired || d.signatures.length > 0);
        /** @type {HTMLElement} */ (document.getElementById('esignLine')).textContent = d.esignRequired ? hb.t('js.qms.esign.required') : '';
        var ul = /** @type {HTMLElement} */ (document.getElementById('signList'));
        ul.textContent = '';
        /** @type {Record<string, string>} */
        var meanings = {AUTHOR: hb.t('js.esign.meaning.AUTHOR'), REVIEW: hb.t('js.esign.meaning.REVIEW'), APPROVE: hb.t('js.esign.meaning.APPROVE')};
        /** @type {Record<string, string>} */
        var methods = {PWD: hb.t('js.qms.esign.method.PWD'), PWD_TOTP: hb.t('js.qms.esign.method.PWD_TOTP')};
        d.signatures.forEach(function (sg) {
            ul.appendChild(hb.el('li', sg.current ? null : 'hb-msg-error', hb.t('js.qms.esign.row', sg.signerNm, meanings[sg.meaningCd] || sg.meaningCd,
                methods[sg.authMethod] || sg.authMethod, hb.fmt.datetime(sg.signedAt)) + (sg.current ? '' : ' ' + hb.t('js.qms.esign.changed'))));
        });
        if (!d.signatures.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.esign.none'))); }
    }

    /**
     * 정책이 켜졌으면 서명 대화상자 → 토큰, 아니면 토큰 없이(undefined). 취소하면 null
     * @param {string} meaning @returns {Promise<string | null | undefined>}
     */
    function signFor(meaning) {
        if (!detail || !detail.esignRequired) { return Promise.resolve(undefined); }
        return hb.esign({meaning: meaning, targetType: 'qms_ncr', targetId: detail.ncr.ncrId, title: detail.ncr.ncrNo});
    }

    function hasUseAsIs() {
        return disps.some(function (x) { return x.dispType === 'USE_AS_IS'; });
    }

    function renderDisps() {
        if (!detail) { return; }
        var open = detail.ncr.status === 'OPEN' && CAN_SAVE;
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#dispTable tbody'));
        tbody.textContent = '';
        disps.forEach(function (x, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row-key', 'd' + i);
            var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
            sel.setAttribute('data-field', 'dispType');
            Object.keys(dispNames).forEach(function (k) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, dispNames[k]));
                o.value = k;
                sel.appendChild(o);
            });
            sel.value = x.dispType;
            var qty = /** @type {HTMLInputElement} */ (hb.el('input'));
            qty.type = 'number';
            qty.min = '0';
            qty.step = '0.001';
            qty.setAttribute('data-field', 'qty');
            qty.value = String(x.qty);
            var note = /** @type {HTMLInputElement} */ (hb.el('input'));
            note.maxLength = 500;
            note.setAttribute('data-field', 'note');
            note.value = x.note || '';
            var rework = /** @type {HTMLInputElement} */ (hb.el('input'));
            rework.maxLength = 50;
            rework.setAttribute('data-field', 'reworkLotNo');
            rework.value = x.reworkLotNo || '';
            [sel, qty, note, rework].forEach(function (inp) {
                inp.disabled = !open;
                inp.addEventListener('input', function () {
                    x.dispType = sel.value;
                    x.qty = qty.value;
                    x.note = note.value;
                    x.reworkLotNo = rework.value;
                    dirty = true;
                    sumLine();
                    renderApproval();
                });
                var td = hb.el('td');
                td.appendChild(inp);
                tr.appendChild(td);
            });
            var tdDel = hb.el('td');
            if (open) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                del.type = 'button';
                del.addEventListener('click', function () { disps.splice(i, 1); dirty = true; renderDisps(); renderApproval(); });
                tdDel.appendChild(del);
            }
            tr.appendChild(tdDel);
            tbody.appendChild(tr);
        });
        sumLine();
    }

    function sumLine() {
        if (!detail) { return; }
        var sum = disps.reduce(function (s, x) { return s + (Number(x.qty) || 0); }, 0);
        var line = /** @type {HTMLElement} */ (document.getElementById('sumLine'));
        line.textContent = hb.t('js.qms.ncr.sum', hb.fmt.num(sum, 3, 0), hb.fmt.num(detail.ncr.ncQty, 3, 0));
        line.classList.toggle('hb-msg-error', Math.abs(sum - Number(detail.ncr.ncQty)) > 1e-9);
    }

    function renderApproval() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#lineTable tbody'));
        tbody.textContent = '';
        if (!detail) { return; }
        var n = detail.ncr;
        var a = detail.approval;
        var building = n.status === 'OPEN' && hasUseAsIs();
        show('approvalBox', building || n.status === 'PENDING');
        show('lineTools', building && CAN_SAVE);
        show('closeBtn', n.status === 'OPEN' && CAN_SAVE && !hasUseAsIs());
        show('actTools', n.status === 'PENDING' && (a.myTurn || a.canWithdraw));
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

    /** @param {HB.FieldError[] | undefined} errors */
    function showErrors(errors) {
        hb.showFieldErrors(/** @type {HTMLElement} */ (document.getElementById('detailBox')), errors || []);
    }

    // ---------- 동작 ----------

    if (newForm) {
        var nf = newForm;
        nf.addEventListener('submit', function (ev) {
            ev.preventDefault();
            var btn = /** @type {HTMLButtonElement} */ (nf.querySelector('button[type=submit]'));
            hb.busy(btn, function () {
                return hb.api(API, {method: 'POST', body: {lotNo: field(nf, 'lotNo').value.trim(), source: field(nf, 'source').value,
                    inspNo: field(nf, 'inspNo').value.trim() || null, defectId: field(nf, 'defectId').value ? Number(field(nf, 'defectId').value) : null,
                    ncQty: field(nf, 'ncQty').value || null, description: field(nf, 'description').value}});
            }).then(function (res) {
                if (!res) { return; }
                nf.reset();
                hb.showFieldErrors(nf, []);
                notify(hb.t('js.qms.ncr.created'));
                hb.page.openDetail();
                var id = /** @type {number} */ (res.data);
                return Promise.all([load(id), grid ? grid.reload(true, id) : null]);
            }, function (err) { notify(errText(err), true); hb.showFieldErrors(nf, err.errors || []); });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    editForm.addEventListener('input', function () { dirty = true; });

    bind('addDispBtn', function () {
        disps.push({dispType: 'SCRAP', qty: '', note: '', reworkLotNo: ''});
        dirty = true;
        renderDisps();
        renderApproval();
    });
    bind('saveBtn', function (btn) {
        if (!detail) { return; }
        var n = detail.ncr;
        hb.busy(btn, function () {
            return hb.api(API + '/' + n.ncrId, {method: 'PUT', body: {rowVer: n.rowVer,
                defectId: field(editForm, 'defectId').value ? Number(field(editForm, 'defectId').value) : null,
                ncQty: field(editForm, 'ncQty').value || null, description: field(editForm, 'description').value,
                disps: disps.map(function (x, i) {
                    return {rowKey: 'd' + i, dispType: x.dispType, qty: x.qty === '' ? null : x.qty, note: x.note || null,
                        reworkLotNo: x.reworkLotNo || null};
                })}});
        }).then(function (res) {
            if (!res) { return; }
            showErrors([]);
            return reload().then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) { notify(errText(err), true); showErrors(err.errors); });
    });
    bind('closeBtn', function (btn) {
        if (!detail) { return; }
        if (dirty) { notify(hb.t('js.qms.ncr.saveFirst'), true); return; }
        var n = detail.ncr;
        qmsUi.ask('close', hb.t('js.qms.ncr.confirmClose', n.ncrNo)).then(function (yes) {
            if (!yes) { return; }
            signFor('APPROVE').then(function (token) {
                if (token === null) { return; }
                return hb.busy(btn, function () {
                    return hb.api(API + '/' + n.ncrId + '/close', {method: 'POST', body: {rowVer: n.rowVer, esignToken: token || null}});
                });
            }).then(function (res) {
                if (!res) { return; }
                return reload().then(function () { notify(hb.t('js.qms.ncr.closed')); });
            }, function (err) { notify(errText(err), true); showErrors(err.errors); });
        });
    });
    bind('cancelBtn', function (btn) {
        if (!detail) { return; }
        var n = detail.ncr;
        qmsUi.reason('cancel', hb.t('js.qms.ncr.cancelReason', n.ncrNo)).then(function (reason) {
            if (reason === null) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + n.ncrId + '/cancel', {method: 'POST', body: {rowVer: n.rowVer, reason: reason}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return reload().then(function () { notify(hb.t('js.qms.ncr.cancelled')); });
                }, function (err) { notify(errText(err), true); });
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
        if (dirty) { notify(hb.t('js.qms.ncr.saveFirst'), true); return; }
        var n = detail.ncr;
        signFor('AUTHOR').then(function (token) {
            if (token === null) { return; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + n.ncrId + '/submit', {method: 'POST', body: {rowVer: n.rowVer, esignToken: token || null,
                    lines: lines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
            });
        }).then(function (res) {
            if (!res) { return; }
            lines = [];
            return reload().then(function () { notify(hb.t('js.qms.doc.submitted')); });
        }, function (err) { notify(errText(err), true); showErrors(err.errors); });
    });

    /** @param {string} id @param {string} path @param {boolean} needComment */
    function aprAct(id, path, needComment) {
        bind(id, function (btn) {
            if (!detail || !detail.approval.aprId) { return; }
            var aprId = detail.approval.aprId;
            var comment = /** @type {HTMLInputElement} */ (document.getElementById('aprComment')).value;
            if (needComment && !comment.trim()) { notify(hb.t('js.qms.doc.commentRequired'), true); return; }
            // 승인·합의 = 결재자 서명(E-2 — 정책이 켜졌을 때, 지금 차례 줄이 모두 합의면 REVIEW, 아니면 APPROVE). 반려·회수는 서명 없음
            var current = detail.approval.lines.filter(function (l) { return l.status === 'CURRENT'; });
            var meaning = current.length && current.every(function (l) { return l.lineType === 'AGREE'; }) ? 'REVIEW' : 'APPROVE';
            (path === 'approve' ? signFor(meaning) : Promise.resolve(undefined)).then(function (token) {
                if (token === null) { return; }
                return hb.busy(btn, function () {
                    return hb.api(APR + '/' + aprId + '/' + path, {method: 'POST', body: {comment: comment, esignToken: token || null},
                        headers: {'X-Idempotency-Key': String(Date.now()) + Math.random().toString(36).slice(2)}});
                });
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
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        var selects = [];
        if (newForm) { selects.push(hb.select(/** @type {HTMLSelectElement} */ (field(newForm, 'source')))); }
        return Promise.all([hb.codes('QMS_NCR_STATUS'), hb.codes('QMS_NCR_SOURCE'), hb.codes('QMS_NCR_DISP'), hb.api(API + '/defects'),
            hb.api(API + '/users'), hb.loadSession(), Promise.all(selects.map(function (s) { return s.ready; })), sp.ready, hb.codes('CAPA_STATUS')]);
    }).then(function (r) {
        capaStatusNames = r[8];
        statusNames = r[0];
        sourceNames = r[1];
        dispNames = r[2];
        defects = r[3].data || [];
        holdSrcNames = {MANUAL: hb.t('js.prdLot.src.MANUAL'), MES_CORRECT: hb.t('js.prdLot.src.MES_CORRECT'),
            QMS_NCR: hb.t('js.prdLot.src.QMS_NCR'), QMS_CONTAIN: hb.t('js.prdLot.src.QMS_CONTAIN')};
        fillDefects();
        var lineUser = /** @type {HTMLSelectElement | null} */ (document.getElementById('lineUser'));
        if (lineUser) {
            var lu = lineUser;
            (r[4].data || []).forEach(/** @param {{userId: number, userNm: string}} u */ function (u) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm));
                o.value = String(u.userId);
                lu.appendChild(o);
            });
        }
        var qs = new URLSearchParams(window.location.search);
        if (newForm && qs.get('lotNo')) {
            field(newForm, 'lotNo').value = qs.get('lotNo') || '';
            field(newForm, 'inspNo').value = qs.get('inspNo') || '';
            if (qs.get('source')) { field(newForm, 'source').value = qs.get('source') || ''; }
            // SPC 위반에서: ?desc= 내용 칸 미리 채움(칸 길이까지)
            if (qs.get('desc')) { field(newForm, 'description').value = (qs.get('desc') || '').slice(0, 2000); }
            field(newForm, 'description').focus();
            warnOpenSameLot((qs.get('lotNo') || '').trim());
        }
        // 조회 패널이 주소 조건(?q.status=…)을 채운 뒤 첫 조회
        return startGrid().then(function () { return sp.search(); }).then(function () {
            // 8D 화면에서: ?ncrNo= 그 부적합 열기(번호 정확히 같은 것만)
            var no = (qs.get('ncrNo') || '').trim().toUpperCase();
            if (!no) { return null; }
            return hb.api(API + '?keyword=' + encodeURIComponent(no)).then(function (res) {
                var hit = (res.data || []).filter(/** @param {QmsNcr} x */ function (x) { return x.ncrNo.toUpperCase() === no; })[0];
                if (hit) { hb.page.openDetail(); return load(hit.ncrId); }
                return null;
            });
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
