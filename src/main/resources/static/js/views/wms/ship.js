// @ts-check
/* 출하 (wms-ship) — P4 2부 B6a (docs/30 12.7). 출하 목록 → 오른쪽: 신규(고객 수주 행 고르기) 또는 머리·줄·피킹할 칸·피킹.
   피킹할 칸은 줄의 "제안"(차감 전략 W4 — 출하 대기·폐기 위치 제외)으로 채우고 수량만 고친다. 피킹·확정·출하 취소는 누른 순간 만든
   요청 id 로 한 번만(응답이 끊겨 다시 눌러도 같은 id). 상태 동작은 머리 rowVer 와 함께(409 → 다시 읽기). 공통 hb.* 는 고치지 않는다.
   팔레트(B6b, 12.10): 피킹 표의 체크를 골라 OPEN 팔레트에 담기·빼기, 팔레트 행에서 닫기(SSCC·라벨)·다시 열기·취소.
   운송 정보·사진(docs/58): 운송 행의 rowVer 로 따로 저장(머리 rowVer 와 무관), 사진은 플랫폼 첨부(대상 = 이 화면·지시 id). */
/**
 * @typedef {{shipId: number, shipNo: string, plantCd: string, custCd: string, custNm: string, stageNodeId: number, stagePath: string,
 *   planDate: string, status: string, remark?: string | null, shippedAt?: string | null, shippedBy?: string | null,
 *   cancelReason?: string | null, rowVer: number, lineCount: number, carrierNm?: string | null, vehicleNo?: string | null,
 *   trackingNo?: string | null, transportRowVer?: number | null, transportAt?: string | null, transportBy?: string | null}} WmsShipHead
 * @typedef {{fileId: number, name: string, ext: string, size: number}} WmsShipFile
 * @typedef {{shipLineId: number, lineNo: number, orderNo: string, orderLineNo: number, itemCd: string, itemNm: string, unitCd: string,
 *   lotCtrlYn: string, qty: string | number, pickedQty: string | number, shippedQty: string | number,
 *   availQty?: string | number | null, reservedOther?: string | number | null, netQty?: string | number | null,
 *   shortQty?: string | number | null}} WmsShipLine
 * @typedef {{pickId: number, shipLineId: number, fromPath: string, lotNo?: string | null, qty: string | number, status: string,
 *   palletId?: number | null, palletNo?: string | null}} WmsShipPick
 * @typedef {{palletId: number, palletNo: string, sscc?: string | null, status: string, closedAt?: string | null,
 *   closedBy?: string | null, pickCount: number, rowVer: number}} WmsShipPallet
 * @typedef {{noteId: number, title: string, body?: string | null, ackRequiredYn: string, lineIds: number[], acked: boolean,
 *   ackedAt?: string | null, ackedBy?: string | null, ackWorkerNm?: string | null}} WmsShipNote
 * @typedef {{head: WmsShipHead, lines: WmsShipLine[], picks: WmsShipPick[], pallets: WmsShipPallet[], notes: WmsShipNote[]}} WmsShipView
 * @typedef {{orderLineId: number, orderNo: string, lineNo: number, itemCd: string, itemNm: string, unitCd: string, dueDate: string,
 *   openQty: string | number, availQty?: string | number | null, reservedQty?: string | number | null,
 *   netQty?: string | number | null}} WmsShipOrderLine
 * @typedef {{nodeId: number, path: string, lotNo?: string | null, takeQty: string, availQty: string}} WmsShipSuggest
 * @typedef {{nodeId: number, path: string, lotId?: number | null, lotNo?: string | null, qty: string, holdYn: string}} WmsShipStock
 * @typedef {{nodeId: number, path: string}} WmsShipOpt
 * @typedef {{shipLineId: number, itemCd: string, fromNodeId: number, path: string, lotNo: string | null, qty: string}} WmsShipPend
 */
(function () {
    'use strict';

    var API = '/api/wms/ships';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var PROG = document.body.getAttribute('data-prog') || 'wms-ship';
    /** 출하 목록 그리드(UI 단계 3 ① — /grid·/columns) @type {HB.Grid | null} */
    var grid = null;
    /** 동작 직후 목록을 다시 읽을 때 — 이미 그린 상세를 그리드 onSelect 가 한 번 더 읽지 않게 @type {number | null} */
    var quietId = null;
    var info = /** @type {HTMLElement} */ (document.getElementById('shipInfo'));
    var noteBox = /** @type {HTMLElement} */ (document.getElementById('noteBox'));
    var noteList = /** @type {HTMLElement} */ (document.getElementById('noteList'));
    var newBox = /** @type {HTMLElement} */ (document.getElementById('newBox'));
    var newForm = /** @type {HTMLFormElement} */ (document.getElementById('newForm'));
    var orderBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#orderLines tbody'));
    var actBox = /** @type {HTMLElement} */ (document.getElementById('actBox'));
    var labelForm = /** @type {HTMLFormElement} */ (document.getElementById('labelForm'));
    var lineHost = /** @type {HTMLElement} */ (document.getElementById('lineHost'));
    var lineBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#lines tbody'));
    var pendBox = /** @type {HTMLElement} */ (document.getElementById('pendBox'));
    var pendBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#pending tbody'));
    var pickHost = /** @type {HTMLElement} */ (document.getElementById('pickHost'));
    var pickBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#picks tbody'));
    var palletBox = /** @type {HTMLElement} */ (document.getElementById('palletBox'));
    var palletBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#pallets tbody'));
    var palletTools = document.getElementById('palletTools');
    var palletSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('palletSel'));
    var palletLabelForm = /** @type {HTMLFormElement | null} */ (document.getElementById('palletLabelForm'));
    var transBox = /** @type {HTMLElement} */ (document.getElementById('transBox'));
    var transForm = /** @type {HTMLFormElement} */ (document.getElementById('transForm'));
    var transMeta = /** @type {HTMLElement} */ (document.getElementById('transMeta'));
    var fileList = /** @type {HTMLElement} */ (document.getElementById('files'));
    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));   // SAVE 권한 없으면 없음
    /** 운송 칸을 채운 기준(지시 id:운송 rowVer) — 같으면 다시 그려도(피킹 뒤 등) 치던 값을 덮지 않는다 @type {string | null} */
    var transKey = null;
    var transDirty = false;
    var stockBox = /** @type {HTMLElement} */ (document.getElementById('stockBox'));
    var stockTitle = /** @type {HTMLElement} */ (document.getElementById('stockTitle'));
    var stockForm = /** @type {HTMLFormElement} */ (document.getElementById('stockForm'));
    var stockScan = /** @type {HTMLInputElement} */ (document.getElementById('stockScan'));
    var stockBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#stockRows tbody'));
    /** 다른 칸을 보고 있는 줄 @type {WmsShipLine | null} */
    var stockLine = null;
    var scanForm = /** @type {HTMLFormElement} */ (document.getElementById('scanForm'));
    var scanText = /** @type {HTMLInputElement} */ (document.getElementById('scanText'));
    /** 스캔으로 연 팔레트 — 팔레트 표에서 표시하고 담을 팔레트로 고른다 @type {number | null} */
    var scanPallet = null;
    /** @type {WmsShipView | null} */
    var current = null;
    /** @type {WmsShipPend[]} */
    var pending = [];
    /** 동작마다 누른 순간 만든 요청 id — 성공하면 버린다 @type {Record<string, string | null>} */
    var reqs = {pick: null, confirm: null, undo: null};
    /** @type {HB.SearchPanel} */
    var sp;
    /** 공장별 선택지를 마지막으로 읽은 공장 — 열린 탭에 다른 공장 링크가 오면 다시 읽는다(QA L-b) @type {string | null} */
    var optPlant = null;
    /** @type {HB.NumberControl} */
    var copiesNum;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var pickNames = {};
    /** @type {Record<string, string>} */
    var palletNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** 상태 → hb-status 톤(docs/40 부록 A — 진행 = normal, 끝 = success, 대기·취소 = neutral) @type {Record<string, HB.StatusTone>} */
    var TONE = {DRAFT: 'neutral', PICKING: 'normal', SHIPPED: 'success', CANCELLED: 'neutral', PICKED: 'normal', UNPICKED: 'neutral',
        OPEN: 'normal', CLOSED: 'success'};

    /** 상태 배지 칸 @param {string} status @param {Record<string, string>} names @param {string} [extra] */
    function statusCell(status, names, extra) {
        var td = hb.el('td');
        td.appendChild(hb.status(TONE[status] || 'neutral', names[status] || status));
        if (extra) { td.appendChild(document.createTextNode(' ' + extra)); }
        return td;
    }

    /** 사유를 묻는다(취소 = null) @param {string} title @param {string} message @param {string} ok @param {boolean} [danger] */
    function askReason(title, message, ok, danger) {
        return hb.dialog.prompt({title: title, message: message, label: hb.t('js.wmsShip.reason'), required: true, maxLength: 500,
            ok: ok, danger: !!danger});
    }

    /** @param {string | number | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {string} id */
    function btn(id) { return /** @type {HTMLButtonElement | null} */ (document.getElementById(id)); }

    /** @param {HTMLFormElement} f @param {string} name */
    function ff(f, name) { return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + name + '"]')); }

    /** @param {HTMLElement} body @param {number} cols @param {string} text */
    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', text));
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /** @param {HTMLInputElement} inp @param {string} label */
    function qtyInput(inp, label) {
        inp.inputMode = 'decimal';
        inp.maxLength = 30;
        inp.setAttribute('aria-label', label);
        return inp;
    }

    // ------------------------------------------------------------------ 목록

    /**
     * 목록 다시 읽기 — selectId(새로 만든 지시)는 그리드가 골라 onSelect 로 연다. 없으면 지금 연 지시를 그대로 고른다(상세는 다시 읽지 않음)
     * @param {number} [selectId]
     */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        var keep = selectId != null ? selectId : current ? current.head.shipId : null;
        quietId = selectId != null ? null : keep;
        return grid.reload(false, keep).then(function () { quietId = null; });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('wmsShip', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {WmsShipView} */ (res.data));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    // ------------------------------------------------------------------ 상세

    /** @param {WmsShipView | null} v */
    function show(v) {
        if (!current || !v || current.head.shipId !== v.head.shipId) {
            pending = [];
            reqs = {pick: null, confirm: null, undo: null};
            if (!v || !v.pallets.some(function (t) { return t.palletId === scanPallet; })) { scanPallet = null; }
        }
        current = v;
        newBox.hidden = true;
        lineBody.textContent = '';
        pickBody.textContent = '';
        if (!v) {
            info.textContent = hb.t('js.wmsShip.selectFirst');
            [actBox, labelForm, lineHost, pendBox, pickHost, palletBox, noteBox, transBox].forEach(function (x) { x.hidden = true; });
            transKey = null;
            transDirty = false;
            closeStock();
            return;
        }
        var h = v.head;
        info.textContent = hb.t('js.wmsShip.title', h.shipNo, h.custCd + ' · ' + h.custNm, statusNames[h.status] || h.status, h.stagePath, h.planDate)
            + (h.cancelReason ? ' — ' + h.cancelReason : '')
            + (h.shippedAt ? ' — ' + hb.t('js.wmsShip.shippedBy', hb.fmt.datetime(h.shippedAt), h.shippedBy || '') : '');
        var st = h.status;
        var open = st === 'DRAFT' || st === 'PICKING';
        actBox.hidden = st === 'CANCELLED';
        /** @type {Record<string, boolean>} */
        var showBtn = {confirmBtn: st === 'PICKING', unpickBtn: st === 'PICKING', undoBtn: st === 'SHIPPED', cancelBtn: st === 'DRAFT'};
        Object.keys(showBtn).forEach(function (k) {
            var b = btn(k);
            if (b) { b.hidden = !showBtn[k]; }
        });
        labelForm.hidden = !CAN_SAVE || st !== 'PICKING';
        drawNotes(v, open);
        lineHost.hidden = false;
        v.lines.forEach(function (l) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, l.orderNo + '-' + l.orderLineNo));
            var itemTd = hb.el('td', 'hb-wrap', l.itemCd + ' ' + l.itemNm);
            reserveNote(itemTd, l);
            noteBadge(itemTd, v, l);
            tr.appendChild(itemTd);
            tr.appendChild(hb.el('td', 'hb-num', q(l.qty) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', 'hb-num', q(l.pickedQty)));
            tr.appendChild(hb.el('td', 'hb-num', q(l.shippedQty)));
            var cell = hb.el('td');
            var left = Number(l.qty) - Number(l.pickedQty);
            if (CAN_SAVE && open && left > 0) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.wmsShip.suggest')));
                b.type = 'button';
                b.setAttribute('aria-label', hb.t('js.wmsShip.suggestFor', l.itemCd));
                b.addEventListener('click', function () { suggest(l, b); });
                cell.appendChild(b);
                var o = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.wmsShip.otherBins')));
                o.type = 'button';
                o.setAttribute('aria-label', hb.t('js.wmsShip.otherBinsFor', l.itemCd));
                o.addEventListener('click', function () { openStock(l, o); });
                cell.appendChild(o);
            }
            tr.appendChild(cell);
            lineBody.appendChild(tr);
        });
        pickHost.hidden = !v.picks.length;
        v.picks.forEach(function (p) {
            var l = v.lines.find(function (x) { return x.shipLineId === p.shipLineId; });
            var tr = hb.el('tr', p.status === 'UNPICKED' ? 'hb-tree-dim' : null);
            var c = hb.el('td');
            if (CAN_SAVE && open && p.status === 'PICKED') {
                var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
                cb.type = 'checkbox';
                cb.className = 'wms-unpick';
                cb.value = String(p.pickId);
                cb.setAttribute('aria-label', hb.t('js.wmsShip.unpickPick', p.fromPath));
                c.appendChild(cb);
            }
            tr.appendChild(c);
            tr.appendChild(hb.el('td', null, l ? l.itemCd : ''));
            tr.appendChild(hb.el('td', 'hb-wrap', p.fromPath + (p.lotNo ? ' · LOT ' + p.lotNo : '')));
            tr.appendChild(hb.el('td', 'hb-num', q(p.qty)));
            tr.appendChild(statusCell(p.status, pickNames));
            tr.appendChild(hb.el('td', null, p.palletNo || ''));
            pickBody.appendChild(tr);
        });
        drawPallets(v, open);
        drawPending();
        drawTransport(h);
        closeStock();                                    // 다시 그리면(피킹·취소 뒤) 다른 칸 수량이 낡는다 — 닫고 줄에서 다시 연다
        hb.dirty.clear();
        if (transDirty) { hb.dirty.mark(transForm); }    // 운송 칸에 치던 값은 남아 있다 — 떠날 때 경고 유지
    }

    // ---------- 운송 정보·사진(docs/58) ----------
    /** 운송 칸 — 지시가 바뀌었거나 운송 rowVer 가 바뀌었을 때만 채운다. 취소된 지시는 읽기만 @param {WmsShipHead} h */
    function drawTransport(h) {
        transBox.hidden = false;
        var editable = CAN_SAVE && h.status !== 'CANCELLED';
        var key = h.shipId + ':' + (h.transportRowVer == null ? '-' : h.transportRowVer);
        if (key !== transKey) {
            transKey = key;
            transDirty = false;
            ff(transForm, 'carrierNm').value = h.carrierNm || '';
            ff(transForm, 'vehicleNo').value = h.vehicleNo || '';
            ff(transForm, 'trackingNo').value = h.trackingNo || '';
        }
        ['carrierNm', 'vehicleNo', 'trackingNo'].forEach(function (k) { /** @type {HTMLInputElement} */ (ff(transForm, k)).readOnly = !editable; });
        var save = btn('transSaveBtn');
        if (save) { save.hidden = !editable; }
        if (fileInput) { fileInput.hidden = !editable; }
        transMeta.textContent = h.transportAt ? hb.t('js.wmsShip.transportMeta', hb.fmt.datetime(h.transportAt), h.transportBy || '') : '';
        loadFiles(h, editable);
    }

    /** @param {WmsShipHead} h @param {boolean} editable */
    function loadFiles(h, editable) {
        var id = h.shipId;
        hb.latest('wmsShipFiles', hb.api('/api/platform/files?targetType=' + encodeURIComponent(PROG) + '&targetId=' + id)).then(function (res) {
            if (!current || current.head.shipId !== id) { return; }
            fileList.textContent = '';
            var list = /** @type {WmsShipFile[]} */ (res.list || []);
            if (!list.length) {
                fileList.appendChild(hb.el('li', 'hb-muted', hb.t('js.wmsShip.noPhotos')));
            }
            list.forEach(function (f) {
                var li = hb.el('li');
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.name));
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (editable) {
                    var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                    del.type = 'button';
                    del.setAttribute('aria-label', hb.t('js.wmsShip.photoRemoveFor', f.name));
                    del.addEventListener('click', function () { deleteFile(f, del); });
                    li.appendChild(del);
                }
                fileList.appendChild(li);
            });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsShipFile} f @param {HTMLButtonElement} b */
    function deleteFile(f, b) {
        hb.dialog.confirm({title: hb.t('js.wmsShip.photoDelete'), message: hb.t('js.wmsShip.photoDeleteAsk', f.name),
            ok: hb.t('js.wmsShip.photoDeleteOk'), danger: true}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(b, function () { return hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'}); }).then(function () {
                if (current) { loadFiles(current.head, true); }
            }, function (err) { notify(errText(err), true); });
        });
    }

    transForm.addEventListener('input', function () { transDirty = true; });
    hb.dirty.watch(transForm);
    transForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var save = btn('transSaveBtn');
        if (!current || !save) { return; }
        var h = current.head;
        var body = {rowVer: h.transportRowVer == null ? null : h.transportRowVer,
            carrierNm: ff(transForm, 'carrierNm').value, vehicleNo: ff(transForm, 'vehicleNo').value, trackingNo: ff(transForm, 'trackingNo').value};
        hb.busy(save, function () { return hb.api(API + '/' + h.shipId + '/transport', {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            transDirty = false;
            hb.dirty.clear(transForm);
            show(/** @type {WmsShipView} */ (res.data));
            notify(hb.t('js.wmsShip.transportSaved'));
            return load();
        }, function (err) { notify(errText(err), true); });
    });

    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            if (!file || !current) { return; }
            var id = current.head.shipId;
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(id));
            fd.append('file', file);
            upInput.disabled = true;
            hb.upload('/api/platform/files', fd).then(function () {
                notify(hb.t('js.common.saved'));
                if (current && current.head.shipId === id) { loadFiles(current.head, true); }
            }, function (err) {
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            }).then(function () { upInput.value = ''; upInput.disabled = false; });
        });
    }

    /**
     * 출하 특이사항(docs/56) — 제목·내용, 확인 필수면 확인 상태와 [확인](열린 지시·SAVE). 확인하면 응답(상세)으로 다시 그리고 목록 숫자도
     * @param {WmsShipView} v @param {boolean} open
     */
    function drawNotes(v, open) {
        var list = v.notes || [];
        noteList.textContent = '';
        noteBox.hidden = !list.length;
        list.forEach(function (n) {
            var li = hb.el('li');
            var need = n.ackRequiredYn === 'Y';
            li.appendChild(hb.status(need ? (n.acked ? 'success' : 'warning') : 'info',
                need ? (n.acked ? hb.t('js.wmsShip.noteAcked') : hb.t('js.wmsShip.noteNeedAck')) : hb.t('js.wmsShip.noteInfo')));
            li.appendChild(hb.el('b', null, ' ' + n.title));
            if (n.body) { li.appendChild(hb.el('div', 'hb-wrap', n.body)); }
            if (n.acked) {
                li.appendChild(hb.el('div', 'hb-muted', hb.t('js.wmsShip.noteAckBy', n.ackWorkerNm || n.ackedBy || '',
                    n.ackedAt ? hb.fmt.datetime(n.ackedAt) : '')));
            } else if (CAN_SAVE && open) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', need ? null : 'hb-btn-secondary', hb.t('js.wmsShip.noteAck')));
                b.type = 'button';
                b.setAttribute('aria-label', hb.t('js.wmsShip.noteAckFor', n.title));
                b.addEventListener('click', function () { ackNote(n, b); });
                li.appendChild(b);
            }
            noteList.appendChild(li);
        });
    }

    /** @param {WmsShipNote} n @param {HTMLButtonElement} b */
    function ackNote(n, b) {
        if (!current) { return; }
        var id = current.head.shipId;
        hb.busy(b, function () { return hb.api(API + '/' + id + '/notes/' + n.noteId + '/ack', {method: 'POST', body: {}}); }).then(function (res) {
            if (!res) { return; }
            show(/** @type {WmsShipView} */ (res.data));
            notify(hb.t('js.wmsShip.noteAckDone', n.title));
            return load();
        }, function (err) { notify(errText(err), true); });
    }

    /** 줄에 걸린 특이사항 수 — 확인 필수 미확인이 있으면 노랑 @param {HTMLElement} host @param {WmsShipView} v @param {WmsShipLine} l */
    function noteBadge(host, v, l) {
        var mine = (v.notes || []).filter(function (n) { return n.lineIds.indexOf(l.shipLineId) >= 0; });
        if (!mine.length) { return; }
        var open = mine.some(function (n) { return n.ackRequiredYn === 'Y' && !n.acked; });
        var d = hb.el('div');
        d.appendChild(hb.status(open ? 'warning' : 'info', hb.t('js.wmsShip.noteBadge', mine.length)));
        host.appendChild(d);
    }

    /**
     * 출하 예약 경고(docs/53 (가) — 막지 않음): 가용 · 다른 지시 예약 · 순 가용, 모자라면 노란 줄 하나 더. 열린 지시만(숫자가 없으면 아무것도 안 함)
     * @param {HTMLElement} host @param {WmsShipLine} l
     */
    function reserveNote(host, l) {
        if (l.availQty == null) { return; }
        host.appendChild(hb.el('div', 'hb-muted', hb.t('js.wmsShip.reserve', q(l.availQty), q(l.reservedOther || 0), q(l.netQty || 0))));
        if (Number(l.shortQty) > 0) { host.appendChild(hb.el('div', 'hb-warn', hb.t('js.wmsShip.reserveShort', q(l.shortQty || 0)))); }
    }

    /** @param {WmsShipLine} l */
    function reserveText(l) {
        if (l.availQty == null) { return ''; }
        return hb.t('js.wmsShip.reserve', q(l.availQty), q(l.reservedOther || 0), q(l.netQty || 0))
            + (Number(l.shortQty) > 0 ? ' — ' + hb.t('js.wmsShip.reserveShort', q(l.shortQty || 0)) : '');
    }

    /** 줄의 피킹 제안을 피킹할 칸에 더한다(같은 줄의 앞 제안은 바꾼다) @param {WmsShipLine} l @param {HTMLButtonElement} b */
    function suggest(l, b) {
        if (!current) { return; }
        var id = current.head.shipId;
        hb.busy(b, function () { return hb.api(API + '/' + id + '/lines/' + l.shipLineId + '/suggest'); }).then(function (res) {
            if (!res) { return; }
            var list = /** @type {WmsShipSuggest[]} */ (res.data);
            pending = pending.filter(function (p) { return p.shipLineId !== l.shipLineId; });
            list.forEach(function (s) {
                pending.push({shipLineId: l.shipLineId, itemCd: l.itemCd, fromNodeId: s.nodeId, path: s.path, lotNo: s.lotNo || null, qty: s.takeQty});
            });
            reqs.pick = null;
            drawPending();
            // 제안 머리 = 줄의 예약 숫자(docs/53 (가)) — 모자라면 경고로
            notify(list.length ? reserveText(l) : hb.t('js.wmsShip.noStock', l.itemCd), !list.length || Number(l.shortQty) > 0);
        }, function (err) { notify(errText(err), true); });
    }

    // ------------------------------------------------------------------ 다른 칸 (12.7.5 — 제안 밖 칸 직접 지정·스캔)

    /** 줄의 다른 칸 패널을 연다 @param {WmsShipLine} l @param {HTMLButtonElement} b */
    function openStock(l, b) {
        stockLine = l;
        stockScan.value = '';
        stockTitle.textContent = hb.t('js.wmsShip.otherBinsTitle', l.itemCd, l.itemNm);
        loadStock(b, '').then(function () { if (!stockBox.hidden) { stockScan.focus(); } });
    }

    function closeStock() {
        stockLine = null;
        stockBox.hidden = true;
        stockBody.textContent = '';
    }

    /** @param {HTMLButtonElement} b @param {string} scan */
    function loadStock(b, scan) {
        if (!current || !stockLine) { return Promise.resolve(); }
        var l = stockLine;
        var url = API + '/' + current.head.shipId + '/lines/' + l.shipLineId + '/stock' + (scan ? '?scan=' + encodeURIComponent(scan) : '');
        return hb.busy(b, function () { return hb.latest('wmsShipStock', hb.api(url)); }).then(function (res) {
            if (!res) { return; }
            var data = /** @type {{rows: WmsShipStock[], message: string | null}} */ (res.data);
            drawStock(l, data.rows);
            stockBox.hidden = false;
            if (scan) {
                notify(data.rows.length ? data.message : (data.message || hb.t('js.wmsShip.noStock', l.itemCd)), !data.rows.length);
                stockScan.select();
            } else if (!data.rows.length) {
                notify(hb.t('js.wmsShip.noStock', l.itemCd), true);
            }
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsShipLine} l @param {WmsShipStock[]} rows */
    function drawStock(l, rows) {
        stockBody.textContent = '';
        if (!rows.length) { emptyRow(stockBody, 3, hb.t('js.wmsShip.noStock', l.itemCd)); }
        rows.forEach(function (s) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-wrap', s.path + (s.lotNo ? ' · LOT ' + s.lotNo : '')));
            tr.appendChild(hb.el('td', 'hb-num', q(s.qty) + ' ' + l.unitCd));
            var c = hb.el('td');
            if (s.holdYn === 'Y') {
                c.appendChild(hb.el('span', 'hb-badge', hb.t('js.wmsShip.lotHold')));
            } else {
                var a = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.wmsShip.addBin')));
                a.type = 'button';
                a.setAttribute('aria-label', hb.t('js.wmsShip.addBinFor', s.path));
                a.addEventListener('click', function () { addBin(l, s); });
                c.appendChild(a);
            }
            tr.appendChild(c);
            stockBody.appendChild(tr);
        });
    }

    /**
     * 피킹할 칸에 더한다 — 수량 = min(칸 가용, 줄의 남은 수량 − 이미 넣은 다른 칸). 같은 칸·LOT 을 다시 누르면 더하지 않고 바꾼다(두 번 눌림 방지).
     * 넘침·재고는 서버 pick 이 다시 본다
     * @param {WmsShipLine} l @param {WmsShipStock} s
     */
    function addBin(l, s) {
        var lot = s.lotNo || null;
        var same = function (/** @type {WmsShipPend} */ p) { return p.shipLineId === l.shipLineId && p.fromNodeId === s.nodeId && p.lotNo === lot; };
        var others = pending.filter(function (p) { return p.shipLineId === l.shipLineId && !same(p); })
            .reduce(function (t, p) { return t + (Number(p.qty) || 0); }, 0);
        var room = Number(l.qty) - Number(l.pickedQty) - others;
        if (room <= 0) { notify(hb.t('js.wmsShip.lineFull', l.itemCd), true); return; }
        var take = String(Math.min(Number(s.qty), room));
        pending = pending.filter(function (p) { return !same(p); });
        pending.push({shipLineId: l.shipLineId, itemCd: l.itemCd, fromNodeId: s.nodeId, path: s.path, lotNo: lot, qty: take});
        reqs.pick = null;
        drawPending();
        notify(hb.t('js.wmsShip.binAdded', s.path, q(take)));
    }

    stockForm.addEventListener('submit', function (e) {
        e.preventDefault();
        loadStock(/** @type {HTMLButtonElement} */ (document.getElementById('stockFindBtn')), stockScan.value.trim());
    });
    bind('stockCloseBtn', function () { closeStock(); });

    /** 팔레트 표·담을 팔레트 선택지 @param {WmsShipView} v @param {boolean} open */
    function drawPallets(v, open) {
        var list = v.pallets || [];
        palletBody.textContent = '';
        palletBox.hidden = !list.length && !(CAN_SAVE && open);
        if (palletTools) { palletTools.hidden = !open; }
        if (palletLabelForm) { palletLabelForm.hidden = !open; }
        if (palletSel) {
            var sel = palletSel;
            var keep = sel.value;
            sel.textContent = '';
            list.filter(function (t) { return t.status === 'OPEN'; }).forEach(function (t) { option(/** @type {HTMLSelectElement} */ (sel), String(t.palletId), t.palletNo); });
            // 스캔한 팔레트 → 고르던 팔레트(아직 OPEN 이면) → 가장 최근 OPEN 팔레트
            if (scanPallet && sel.querySelector('option[value="' + scanPallet + '"]')) {
                sel.value = String(scanPallet);
            } else if (keep && sel.querySelector('option[value="' + keep + '"]')) {
                sel.value = keep;
            } else if (sel.options.length) {
                sel.selectedIndex = sel.options.length - 1;
            }
            // 열린 팔레트가 없으면 담을 곳 선택·담기 버튼을 숨긴다(빈 선택지가 좁은 화면에서 찌그러짐)
            sel.hidden = !sel.options.length;
            var putBtn = btn('palletPutBtn');
            if (putBtn) { putBtn.hidden = !sel.options.length; }
        }
        if (!list.length) { emptyRow(palletBody, 5, hb.t('js.wmsShip.noPallets')); }
        list.forEach(function (t) {
            var tr = hb.el('tr', t.status === 'CANCELLED' ? 'hb-tree-dim' : null);
            if (scanPallet === t.palletId) { tr.setAttribute('aria-selected', 'true'); }
            tr.appendChild(hb.el('td', null, t.palletNo));
            tr.appendChild(hb.el('td', null, t.sscc || ''));
            tr.appendChild(statusCell(t.status, palletNames, t.closedAt ? hb.fmt.datetime(t.closedAt) + ' ' + (t.closedBy || '') : ''));
            tr.appendChild(hb.el('td', 'hb-num', String(t.pickCount)));
            var c = hb.el('td');
            if (CAN_SAVE && open) {
                if (t.status === 'OPEN' && t.pickCount > 0) { c.appendChild(palletBtn('js.wmsShip.palletClose', t, closePallet)); }
                if (t.status === 'OPEN' && t.pickCount === 0) { c.appendChild(palletBtn('js.wmsShip.palletCancel', t, cancelPallet)); }
                if (t.status === 'CLOSED') { c.appendChild(palletBtn('js.wmsShip.palletReopen', t, reopenPallet)); }
            }
            tr.appendChild(c);
            palletBody.appendChild(tr);
        });
    }

    /** @param {string} key @param {WmsShipPallet} t @param {(b: HTMLButtonElement, t: WmsShipPallet) => void} fn */
    function palletBtn(key, t, fn) {
        var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t(key)));
        b.type = 'button';
        b.setAttribute('aria-label', hb.t(key) + ' ' + t.palletNo);
        b.addEventListener('click', function () { fn(b, t); });
        return b;
    }

    /** @param {HTMLButtonElement} b @param {WmsShipPallet} t */
    function closePallet(b, t) {
        var printer = palletLabelForm ? ff(palletLabelForm, 'printerId').value : '';
        var tpl = palletLabelForm ? ff(palletLabelForm, 'tplId').value : '';
        hb.dialog.confirm({title: hb.t('js.wmsShip.dlg.palletClose', t.palletNo), message: hb.t('js.wmsShip.palletClosePrompt', t.palletNo, t.pickCount),
            ok: hb.t('js.wmsShip.palletClose')}).then(function (yes) {
            if (!yes) { return; }
            act(b, '/pallets/' + t.palletId + '/close', {printerId: printer ? Number(printer) : null, tplId: tpl ? Number(tpl) : null}, null,
            function (d) {
                var r = /** @type {{labelJobs: number}} */ (d);
                return hb.t('js.wmsShip.palletClosed', t.palletNo) + (r.labelJobs ? ' ' + hb.t('js.wmsShip.labelQueued', r.labelJobs) : '');
            });
        });
    }

    /** @param {HTMLButtonElement} b @param {WmsShipPallet} t */
    function reopenPallet(b, t) {
        askReason(hb.t('js.wmsShip.dlg.palletReopen', t.palletNo), hb.t('js.wmsShip.palletReopenPrompt', t.palletNo),
            hb.t('js.wmsShip.palletReopen')).then(function (reason) {
            if (reason === null) { return; }
            act(b, '/pallets/' + t.palletId + '/reopen', {reason: reason}, null, function () { return hb.t('js.wmsShip.palletReopened', t.palletNo); });
        });
    }

    /** @param {HTMLButtonElement} b @param {WmsShipPallet} t */
    function cancelPallet(b, t) {
        act(b, '/pallets/' + t.palletId + '/cancel', {}, null, function () { return hb.t('js.wmsShip.palletCancelled', t.palletNo); });
    }

    /** 피킹 표에서 체크한 피킹 id */
    function checkedPicks() {
        return /** @type {number[]} */ (Array.prototype.map.call(pickBody.querySelectorAll('input.wms-unpick:checked'),
            function (/** @type {HTMLInputElement} */ c) { return Number(c.value); }));
    }

    function drawPending() {
        pendBody.textContent = '';
        pendBox.hidden = !pending.length;
        pending.forEach(function (p, i) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, p.itemCd));
            tr.appendChild(hb.el('td', 'hb-wrap', p.path + (p.lotNo ? ' · LOT ' + p.lotNo : '')));
            var c = hb.el('td');
            var inp = qtyInput(/** @type {HTMLInputElement} */ (hb.el('input', 'wms-pick-qty')), hb.t('js.wmsShip.pickQtyFor', p.path));
            inp.value = q(p.qty);
            inp.setAttribute('data-row', String(i + 1));
            inp.addEventListener('change', function () { p.qty = inp.value.trim().replace(/,/g, ''); reqs.pick = null; });
            c.appendChild(inp);
            tr.appendChild(c);
            var d = hb.el('td');
            var x = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.wmsShip.remove')));
            x.type = 'button';
            x.addEventListener('click', function () { pending.splice(i, 1); reqs.pick = null; drawPending(); });
            d.appendChild(x);
            tr.appendChild(d);
            pendBody.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 동작

    /**
     * 머리 rowVer 와 함께 동작. 409(낡음)면 다시 읽는다. key 가 있으면 그 동작의 요청 id 를 붙이고 성공하면 버린다
     * @param {HTMLButtonElement} b @param {string} path @param {Record<string, unknown>} extra @param {string | null} key
     * @param {(data: any) => string} done
     */
    function act(b, path, extra, key, done) {
        if (!current) { return Promise.resolve(); }
        var id = current.head.shipId;
        if (key && !reqs[key]) { reqs[key] = reqId(); }
        var body = Object.assign({rowVer: current.head.rowVer}, key ? {clientReqId: reqs[key]} : {}, extra);
        return hb.busy(b, function () { return hb.api(API + '/' + id + path, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            if (key) { reqs[key] = null; }
            var data = res.data;
            var text = done(data);
            show(/** @type {WmsShipView} */ (data.view || data));
            return load().then(function () { notify(text); });
        }, function (err) {
            notify(errText(err), true);
            markPendErrors(err.errors || []);
            if (key && err.status && err.status !== 409 && err.status < 500) { reqs[key] = null; }   // 고쳐서 다시 보낼 입력 — 새 요청
            if (err.status === 409 && err.code === 'CONFLICT') { open(id); }
        });
    }

    /** 피킹 줄 오류(rowKey = 피킹할 칸 순번)를 그 수량 칸에 @param {HB.FieldError[]} errors */
    function markPendErrors(errors) {
        pendBody.querySelectorAll('input.wms-pick-qty').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        errors.forEach(function (e) {
            var inp = e.rowKey ? pendBody.querySelector('input.wms-pick-qty[data-row="' + e.rowKey + '"]') : null;
            if (inp) {
                inp.setAttribute('aria-invalid', 'true');
                inp.setAttribute('title', e.message);
            }
        });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = btn(id);
        if (b) {
            var bb = b;
            bb.addEventListener('click', function () { fn(bb); });
        }
    }

    bind('pickBtn', function (b) {
        var picks = pending.filter(function (p) { return p.qty && Number(p.qty) > 0; }).map(function (p) {
            return {shipLineId: p.shipLineId, fromNodeId: p.fromNodeId, lotNo: p.lotNo, qty: p.qty};
        });
        if (!picks.length) { notify(hb.t('js.wmsShip.nothingToPick'), true); return; }
        act(b, '/pick', {picks: picks}, 'pick', function () { pending = []; return hb.t('js.wmsShip.picked', picks.length); });
    });
    bind('unpickBtn', function (b) {
        var ids = checkedPicks();
        if (!ids.length) { notify(hb.t('js.wmsShip.pickUnpick'), true); return; }
        askReason(hb.t('js.wmsShip.dlg.unpick'), hb.t('js.wmsShip.unpickPrompt', ids.length), hb.t('js.wmsShip.dlg.unpickOk')).then(function (reason) {
            if (reason === null) { return; }
            act(b, '/unpick', {pickIds: ids, reason: reason}, null, function () { return hb.t('js.wmsShip.unpicked', ids.length); });
        });
    });
    bind('confirmBtn', function (b) {
        if (!current) { return; }
        var picked = current.picks.filter(function (p) { return p.status === 'PICKED'; }).length;
        var printer = ff(labelForm, 'printerId').value;
        var tpl = ff(labelForm, 'tplId').value;
        hb.dialog.confirm({title: hb.t('js.wmsShip.dlg.confirm', current.head.shipNo), message: hb.t('js.wmsShip.confirmShip', current.head.shipNo, picked),
            ok: hb.t('js.wmsShip.dlg.confirmOk')}).then(function (yes) {
            if (!yes) { return; }
            act(b, '/confirm', {printerId: printer ? Number(printer) : null, tplId: tpl ? Number(tpl) : null,
            copies: copiesNum.value() ? Number(copiesNum.value()) : null}, 'confirm', function (d) {
            var r = /** @type {{warnings: string[], labelJobs: number, replay: boolean}} */ (d);
            return hb.t(r.replay ? 'js.wmsShip.replayed' : 'js.wmsShip.shipped')
                + (r.labelJobs ? ' ' + hb.t('js.wmsShip.labelQueued', r.labelJobs) : '')
                + (r.warnings && r.warnings.length ? ' ' + hb.t('js.wmsShip.warnings', r.warnings.join(', ')) : '');
            });
        });
    });
    bind('undoBtn', function (b) {
        if (!current) { return; }
        askReason(hb.t('js.wmsShip.dlg.undo', current.head.shipNo), hb.t('js.wmsShip.undoPrompt', current.head.shipNo), hb.t('js.wmsShip.dlg.undoOk'), true)
            .then(function (reason) {
                if (reason === null) { return; }
                act(b, '/undo', {reason: reason}, 'undo', function () { return hb.t('js.wmsShip.undone'); });
            });
    });
    bind('cancelBtn', function (b) {
        if (!current) { return; }
        askReason(hb.t('js.wmsShip.dlg.cancel', current.head.shipNo), hb.t('js.wmsShip.cancelPrompt', current.head.shipNo), hb.t('js.wmsShip.dlg.cancelOk'), true)
            .then(function (reason) {
                if (reason === null) { return; }
                act(b, '/cancel', {reason: reason}, null, function () { return hb.t('js.wmsShip.cancelled'); });
            });
    });

    bind('palletNewBtn', function (b) {
        act(b, '/pallets', {}, null, function (d) {
            var v = /** @type {WmsShipView} */ (d);
            var last = v.pallets[v.pallets.length - 1];
            if (palletSel) { palletSel.value = ''; }               // 새 팔레트를 고르게(drawPallets 가 최근 OPEN 으로)
            return hb.t('js.wmsShip.palletCreated', last ? last.palletNo : '');
        });
    });
    bind('palletPutBtn', function (b) {
        var ids = checkedPicks();
        if (!ids.length) { notify(hb.t('js.wmsShip.pickPallet'), true); return; }
        if (!palletSel || !palletSel.value) { notify(hb.t('js.wmsShip.palletNone'), true); return; }
        var no = palletSel.options[palletSel.selectedIndex].text;
        act(b, '/pallets/picks', {palletId: Number(palletSel.value), pickIds: ids}, null, function () {
            return hb.t('js.wmsShip.palletPutDone', ids.length, no);
        });
    });
    bind('palletOutBtn', function (b) {
        var ids = checkedPicks();
        if (!ids.length) { notify(hb.t('js.wmsShip.pickPallet'), true); return; }
        act(b, '/pallets/picks', {palletId: null, pickIds: ids}, null, function () { return hb.t('js.wmsShip.palletOutDone', ids.length); });
    });

    // ------------------------------------------------------------------ 팔레트 스캔 (B6b-2)

    scanForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var text = scanText.value;
        var b = /** @type {HTMLButtonElement} */ (document.getElementById('scanBtn'));
        hb.busy(b, function () { return hb.api(API + '/scan', {method: 'POST', body: {text: text}}); }).then(function (res) {
            if (!res) { return; }
            var hit = /** @type {{shipId: number, shipNo: string, palletId: number, palletNo: string, sscc: string | null, status: string}} */ (res.data);
            scanText.value = '';
            scanPallet = hit.palletId;
            return open(hit.shipId).then(function () {
                hb.page.openDetail();
                notify(hb.t('js.wmsShip.scanHit', hit.palletNo, palletNames[hit.status] || hit.status, hit.shipNo));
                scanText.focus();
            });
        }, function (err) {
            notify(errText(err), true);
            scanText.select();
        });
    });

    // ------------------------------------------------------------------ 신규

    bind('newBtn', function () {
        show(null);
        info.textContent = hb.t('js.wmsShip.newTitle', plantSel.value);
        newBox.hidden = false;
        newForm.reset();
        orderBody.textContent = '';
        hb.showFieldErrors(newForm, []);
        hb.page.openDetail();
    });
    bind('loadOrdersBtn', function (b) {
        hb.showFieldErrors(newForm, []);
        var cust = ff(newForm, 'custCd').value.trim();
        hb.busy(b, function () {
            return hb.api(API + '/order-lines?custCd=' + encodeURIComponent(cust) + '&plantCd=' + encodeURIComponent(plantSel.value));
        }).then(function (res) {
            if (!res) { return; }
            var list = /** @type {WmsShipOrderLine[]} */ (res.data);
            orderBody.textContent = '';
            if (!list.length) { emptyRow(orderBody, 4, hb.t('js.wmsShip.noOrders')); }
            list.forEach(function (o) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, o.orderNo + '-' + o.lineNo + ' · ' + o.dueDate));
                tr.appendChild(hb.el('td', 'hb-wrap', o.itemCd + ' ' + o.itemNm));
                var openTd = hb.el('td', 'hb-num', q(o.openQty) + ' ' + o.unitCd);
                if (o.netQty != null) {          // 순 가용(docs/53 (가)) — 남은 수주보다 적으면 노란 경고, 저장은 된다
                    openTd.appendChild(hb.el('div', Number(o.openQty) > Number(o.netQty) ? 'hb-warn' : 'hb-muted',
                        hb.t('js.wmsShip.orderReserve', q(o.netQty), q(o.availQty || 0), q(o.reservedQty || 0))));
                }
                tr.appendChild(openTd);
                var c = hb.el('td');
                var inp = qtyInput(/** @type {HTMLInputElement} */ (hb.el('input', 'wms-ship-qty')), hb.t('js.wmsShip.shipQtyFor', o.orderNo, o.lineNo));
                inp.setAttribute('data-line', String(o.orderLineId));
                c.appendChild(inp);
                tr.appendChild(c);
                orderBody.appendChild(tr);
            });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(newForm, err.errors);
        });
    });
    bind('createBtn', function (b) {
        hb.showFieldErrors(newForm, []);
        /** @type {{orderLineId: number, qty: string}[]} */
        var lines = [];
        orderBody.querySelectorAll('input.wms-ship-qty').forEach(function (i) {
            var inp = /** @type {HTMLInputElement} */ (i);
            if (inp.value.trim() !== '') { lines.push({orderLineId: Number(inp.getAttribute('data-line')), qty: inp.value.trim().replace(/,/g, '')}); }
        });
        var body = {plantCd: plantSel.value, custCd: ff(newForm, 'custCd').value, stageNodeId: ff(newForm, 'stageNodeId').value
            ? Number(ff(newForm, 'stageNodeId').value) : null, planDate: ff(newForm, 'planDate').value, remark: ff(newForm, 'remark').value,
            lines: lines};
        hb.busy(b, function () { return hb.api(API, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var v = /** @type {WmsShipView} */ (res.data);
            hb.dirty.clear(newForm);
            return load(v.head.shipId).then(function () { notify(hb.t('js.wmsShip.created', v.head.shipNo)); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(newForm, err.errors);
        });
    });
    newForm.addEventListener('submit', function (e) { e.preventDefault(); });
    labelForm.addEventListener('submit', function (e) { e.preventDefault(); });
    if (palletLabelForm) { palletLabelForm.addEventListener('submit', function (e) { e.preventDefault(); }); }
    hb.dirty.watch(newForm);

    /** 공장별 선택지 — 출하 대기 위치·출하 라벨 프린터 */
    function loadPlant() {
        optPlant = plantSel.value;
        var plant = encodeURIComponent(plantSel.value);
        return Promise.all([hb.api(API + '/stages?plantCd=' + plant), hb.api(API + '/printers?plantCd=' + plant),
            hb.api(API + '/printers?usage=PALLET&plantCd=' + plant)]).then(function (r) {
            var stage = /** @type {HTMLSelectElement} */ (document.getElementById('stageSel'));
            stage.textContent = '';
            option(stage, '', hb.t('js.common.choose'));
            /** @type {WmsShipOpt[]} */ (r[0].data).forEach(function (n) { option(stage, String(n.nodeId), n.path); });
            var pr = /** @type {HTMLSelectElement} */ (ff(labelForm, 'printerId'));
            pr.textContent = '';
            option(pr, '', hb.t('js.wmsShip.noPrint'));
            /** @type {WmsShipOpt[]} */ (r[1].data).forEach(function (n) { option(pr, String(n.nodeId), n.path); });
            if (palletLabelForm) {
                var pp = /** @type {HTMLSelectElement} */ (ff(palletLabelForm, 'printerId'));
                pp.textContent = '';
                option(pp, '', hb.t('js.wmsShip.noPrint'));
                /** @type {WmsShipOpt[]} */ (r[2].data).forEach(function (n) { option(pp, String(n.nodeId), n.path); });
            }
        }, function (err) { notify(errText(err), true); });
    }

    plantSel.addEventListener('change', function () { loadPlant(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('WMS_SHIP_STATUS'), hb.codes('WMS_PICK_STATUS'),
            hb.api(API + '/templates'), hb.codes('WMS_PALLET_STATUS'), hb.api(API + '/templates?usage=PALLET')]);
    }).then(function (r) {
        statusNames = r[2];
        pickNames = r[3];
        palletNames = r[5];
        if (palletLabelForm) {
            var ptpl = /** @type {HTMLSelectElement} */ (ff(palletLabelForm, 'tplId'));
            option(ptpl, '', hb.t('js.wmsShip.defaultTpl'));
            /** @type {WmsShipOpt[]} */ (r[6].data).forEach(function (n) { option(ptpl, String(n.nodeId), n.path); });
        }
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        var tpl = /** @type {HTMLSelectElement} */ (ff(labelForm, 'tplId'));
        option(tpl, '', hb.t('js.wmsShip.defaultTpl'));
        /** @type {WmsShipOpt[]} */ (r[4].data).forEach(function (n) { option(tpl, String(n.nodeId), n.path); });
        copiesNum = hb.number(/** @type {HTMLInputElement} */ (ff(labelForm, 'copies')));
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; },
            // 열린 탭에 다른 공장 링크 — 공장 칸을 값으로 바꾸면 change 가 안 나므로 대기 칸·프린터 선택지를 여기서 다시 읽는다(QA L-b). 조회는 그대로
            onLink: function () { if (plantSel.value !== optPlant) { loadPlant(); } return false; }});
        var g = hb.grid('#shipGrid', {
            progCd: PROG, id: 'main', key: 'shipId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            status: {status: TONE},                                                // 상태 칸 = 배지(docs/40 부록 A)
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },   // 취소 지시 흐리게
            onSelect: function (row) {
                var id = Number(row.shipId);
                if (quietId === id) { quietId = null; return; }
                open(id);
                hb.page.openDetail();
            },
            notify: notify
        });
        grid = g;
        // 홈 링크(?q.plantCd)는 sp.ready 에서 채워진다 — 그 뒤 공장으로 대기 위치·프린터를 읽는다(docs/37 20장)
        return sp.ready.then(function () { return loadPlant(); });
    }).then(function () {
        show(null);
        if (!grid) { return null; }
        var g = grid;
        return g.ready.then(function () { return g.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
