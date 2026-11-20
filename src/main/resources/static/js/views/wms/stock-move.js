// @ts-check
/* 재고 이동·조정 (wms-move) — P4 1부 W3 (docs/30 4.3). 스캔(위치·품목 채우기) → 이동/조정 입력 → 원장 행 미리보기 확인 → 확정 1회.
   요청 id(clientReqId)는 확정을 **누른 순간** 만들고, 성공하면 버린다 — 응답이 끊겨 다시 눌러도 같은 id 라 두 번 안 들어간다.
   판정(재고 부족·혼적·위치·LOT 품목·미래 시각)은 서버. 공통 hb.* 는 고치지 않는다(docs/29 2.4). */
/**
 * @typedef {{nodeId: number, nodeType: string, nodeCd: string, nodeNm: string, path: string, locUsage: string,
 *   defaultStatus?: string | null, stockYn: string}} WmsMoveNode
 * @typedef {{itemId: number, itemCd: string, itemNm: string, unitCd: string, lotCtrlYn: string, useYn: string}} WmsMoveItem
 * @typedef {{type: string, nodeId?: number | null, nodeCd?: string | null, path?: string | null, itemId?: number | null,
 *   itemCd?: string | null, itemNm?: string | null, unitCd?: string | null, ok: boolean, message: string,
 *   lotNo?: string | null}} WmsMoveScan
 * @typedef {{trxId: number, trxType: string, itemCd: string, path: string, qty: string | number, unitCd: string,
 *   occurredAt: string, reversalOfId?: number | null}} WmsMoveRecent
 */
(function () {
    'use strict';

    var API = '/api/wms/move';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var scanForm = /** @type {HTMLFormElement} */ (document.getElementById('scanForm'));
    var scanText = /** @type {HTMLInputElement} */ (document.getElementById('scanText'));
    var scanResult = /** @type {HTMLOutputElement} */ (document.getElementById('scanResult'));
    var entry = /** @type {HTMLElement} */ (document.getElementById('entry'));
    var commonForm = /** @type {HTMLFormElement} */ (document.getElementById('commonForm'));
    var moveForm = /** @type {HTMLFormElement} */ (document.getElementById('moveForm'));
    var adjForm = /** @type {HTMLFormElement} */ (document.getElementById('adjForm'));
    var moveBox = /** @type {HTMLElement} */ (document.getElementById('moveBox'));
    var adjBox = /** @type {HTMLElement} */ (document.getElementById('adjBox'));
    var itemCd = /** @type {HTMLInputElement} */ (document.getElementById('itemCd'));
    var itemNm = /** @type {HTMLInputElement} */ (document.getElementById('itemNm'));
    var lotNoInp = /** @type {HTMLInputElement} */ (document.getElementById('lotNo'));
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    var clearBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('clearBtn'));
    var recentBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#recent tbody'));
    /** @type {WmsMoveItem | null} */
    var item = null;
    /** @type {WmsMoveNode[]} */
    var nodes = [];
    /** 스캔으로 채운 칸이 있으면 SCAN, 아니면 KEY */
    var scanned = false;
    /** 확정을 누른 순간 만든 요청 id — 성공하거나 입력이 바뀌면 버린다 @type {string | null} */
    var pendingReq = null;
    /** @type {HB.NumberControl} */
    var qtyNum;
    /** @type {HB.NumberControl} */
    var deltaNum;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var reasonNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** @param {string | number | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {ParentNode} root @param {string} name */
    function field(root, name) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (root.querySelector('[data-field="' + name + '"]'));
    }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    function kind() {
        var r = /** @type {HTMLInputElement | null} */ (document.querySelector('input[name="kind"]:checked'));
        return r ? r.value : 'MOVE';
    }

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /** 입력이 바뀌면 앞서 만든 요청 id 는 다른 요청이다 */
    function changed() { pendingReq = null; }

    // ------------------------------------------------------------------ 선택지

    /** @param {HTMLSelectElement} sel @param {string} grp @param {string} first */
    function fillCode(sel, grp, first) {
        return hb.codeList(grp).then(function (list) {
            sel.textContent = '';
            if (first === 'choose') { option(sel, '', hb.t('js.wmsMove.choose')); }
            list.forEach(function (c) { option(sel, c.code, c.name); });
            if (first !== 'choose') { sel.value = first; }
        });
    }

    function drawNodes() {
        entry.querySelectorAll('select.hb-node-sel').forEach(function (s) {
            var sel = /** @type {HTMLSelectElement} */ (s);
            var keep = sel.value;
            sel.textContent = '';
            option(sel, '', hb.t('js.wmsMove.choose'));
            nodes.forEach(function (n) {
                option(sel, String(n.nodeId), n.path + ' · ' + n.nodeNm + (n.stockYn === 'Y' ? '' : ' ' + hb.t('js.wmsMove.noStock')));
            });
            sel.value = keep;
            if (sel.selectedIndex < 0) { sel.selectedIndex = 0; }
        });
    }

    function loadPlant() {
        var plant = plantSel.value;
        nodes = [];
        drawNodes();
        if (!plant) { return Promise.resolve(); }
        return Promise.all([
            hb.latest('wmsMoveNodes', hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plant))).then(function (res) {
                nodes = /** @type {WmsMoveNode[]} */ (res.data);
                drawNodes();
            }),
            loadRecent()
        ]).then(null, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    function loadRecent() {
        var plant = plantSel.value;
        return hb.latest('wmsMoveRecent', hb.api(API + '/recent?plantCd=' + encodeURIComponent(plant))).then(function (res) {
            var list = /** @type {WmsMoveRecent[]} */ (res.list || []);
            recentBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.wmsMove.none')));
                td.colSpan = 5;
                tr.appendChild(td);
                recentBody.appendChild(tr);
            }
            list.forEach(function (t) {
                var tr = hb.el('tr', t.reversalOfId ? 'hb-tree-dim' : null);
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(t.occurredAt)));
                tr.appendChild(hb.el('td', null, typeNames[t.trxType] || t.trxType));
                tr.appendChild(hb.el('td', null, t.itemCd));
                tr.appendChild(hb.el('td', 'hb-wrap', t.path));
                tr.appendChild(hb.el('td', 'hb-num', q(t.qty) + ' ' + t.unitCd));
                recentBody.appendChild(tr);
            });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsMoveItem | null} it */
    function setItem(it) {
        item = it;
        itemNm.value = it ? it.itemNm + ' (' + it.unitCd + ')' : '';
    }

    function lookupItem() {
        var cd = itemCd.value.trim();
        if (!cd) { setItem(null); return Promise.resolve(); }
        if (item && item.itemCd === cd.toUpperCase()) { return Promise.resolve(); }
        return hb.latest('wmsMoveItem', hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            var it = /** @type {WmsMoveItem} */ (res.data);
            itemCd.value = it.itemCd;
            setItem(it);
        }, function (err) {
            if (err.stale) { return; }
            setItem(null);
            hb.showFieldErrors(commonForm, [{field: 'itemId', code: 'NO_SUCH_CODE', message: hb.t('js.wmsMove.itemNotFound')}]);
        });
    }

    /** 도착(조정) 위치가 QC·불량 구역이면 그 기본 상태를 제안한다 — 사용자가 고른 상태는 덮지 않는다 */
    function suggestStatus(/** @type {HTMLSelectElement} */ nodeSel, /** @type {HTMLSelectElement} */ statusSel) {
        var n = nodes.find(function (x) { return String(x.nodeId) === nodeSel.value; });
        var prev = statusSel.getAttribute('data-suggested');
        if (n && n.defaultStatus && (statusSel.value === 'AVAILABLE' || statusSel.value === prev)) {
            statusSel.value = n.defaultStatus;
            statusSel.setAttribute('data-suggested', n.defaultStatus);
        } else if ((!n || !n.defaultStatus) && prev && statusSel.value === prev) {
            statusSel.value = 'AVAILABLE';                 // 앞 위치가 제안한 상태가 다음 위치로 따라가지 않게
            statusSel.removeAttribute('data-suggested');
        }
    }

    // ------------------------------------------------------------------ 스캔

    /** 위치 스캔 = 비어 있는 다음 위치 칸(이동: 출발 → 도착, 조정: 위치) @param {number} nodeId */
    function fillNode(nodeId) {
        var v = String(nodeId);
        if (kind() === 'ADJUST') {
            field(adjForm, 'nodeId').value = v;
            return;
        }
        var from = field(moveForm, 'fromNodeId');
        var to = field(moveForm, 'toNodeId');
        if (!from.value) {
            from.value = v;
        } else {
            to.value = v;
        }
        suggestStatus(/** @type {HTMLSelectElement} */ (to), /** @type {HTMLSelectElement} */ (field(moveForm, 'toStatus')));
    }

    scanForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var text = scanText.value;
        if (!text.trim()) { return; }
        hb.latest('wmsMoveScan', hb.api(API + '/scan', {method: 'POST', body: {plantCd: plantSel.value, text: text}})).then(function (res) {
            var r = /** @type {WmsMoveScan} */ (res.data);
            scanText.value = '';
            scanResult.textContent = r.message;
            scanResult.classList.toggle('hb-msg-error', !r.ok);
            if (!r.ok) { return; }
            changed();
            scanned = true;
            if (r.type === 'LOC' && r.nodeId) {
                fillNode(r.nodeId);
                notify(hb.t('js.wmsMove.scanLoc', r.path || r.nodeCd));
            } else if (r.type === 'LOT' && r.itemId) {
                itemCd.value = r.itemCd || '';
                setItem({itemId: r.itemId, itemCd: r.itemCd || '', itemNm: r.itemNm || '', unitCd: r.unitCd || '', lotCtrlYn: 'Y', useYn: 'Y'});
                lotNoInp.value = r.lotNo || '';
                notify(hb.t('js.wmsMove.scanLot', r.lotNo));
            } else if (r.type === 'ITEM' && r.itemId) {
                itemCd.value = r.itemCd || '';
                setItem({itemId: r.itemId, itemCd: r.itemCd || '', itemNm: r.itemNm || '', unitCd: r.unitCd || '', lotCtrlYn: 'N', useYn: 'Y'});
                notify(hb.t('js.wmsMove.scanItem', r.itemCd));
            }
            scanText.focus();
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    });

    // ------------------------------------------------------------------ 확정

    /** 원장 행 미리보기 글자(확인 창) */
    function preview() {
        var unit = (item ? item.unitCd : '') + (lotNoInp.value.trim() ? ' · LOT ' + lotNoInp.value.trim() : '');
        /** @param {string} id */
        var place = function (id) {
            var n = nodes.find(function (x) { return String(x.nodeId) === id; });
            return n ? n.path : '?';
        };
        if (kind() === 'ADJUST') {
            var d = deltaNum.value() || '';
            var st = field(adjForm, 'stockStatus').value;
            return hb.t('js.wmsMove.previewAdjust', typeNames[d.charAt(0) === '-' ? 'ADJ_OUT' : 'ADJ_IN'], place(field(adjForm, 'nodeId').value),
                statusNames[st] || st, q(d), unit, reasonNames[field(adjForm, 'reasonCd').value] || '');
        }
        var qty = qtyNum.value() || '';
        var fs = field(moveForm, 'fromStatus').value;
        var ts = field(moveForm, 'toStatus').value;
        var same = field(moveForm, 'fromNodeId').value === field(moveForm, 'toNodeId').value;
        return hb.t('js.wmsMove.previewMove', typeNames[same ? 'STATUS_OUT' : 'MOVE_OUT'], place(field(moveForm, 'fromNodeId').value),
            statusNames[fs] || fs, q('-' + qty), typeNames[same ? 'STATUS_IN' : 'MOVE_IN'], place(field(moveForm, 'toNodeId').value),
            statusNames[ts] || ts, q(qty), unit);
    }

    function body() {
        /** @param {ParentNode} root @param {string} n */
        var num = function (root, n) { var v = field(root, n).value; return v ? Number(v) : null; };
        /** @type {Record<string, unknown>} */
        var b = {plantCd: plantSel.value, itemId: item ? item.itemId : null, lotNo: lotNoInp.value.trim() || null,
            occurredAt: field(commonForm, 'occurredAt').value || null,
            remark: field(commonForm, 'remark').value, entryMethod: scanned ? 'SCAN' : 'KEY'};
        if (kind() === 'ADJUST') {
            b.nodeId = num(adjForm, 'nodeId');
            b.stockStatus = field(adjForm, 'stockStatus').value;
            b.delta = deltaNum.value();
            b.reasonCd = field(adjForm, 'reasonCd').value || null;
        } else {
            b.fromNodeId = num(moveForm, 'fromNodeId');
            b.fromStatus = field(moveForm, 'fromStatus').value;
            b.toNodeId = num(moveForm, 'toNodeId');
            b.toStatus = field(moveForm, 'toStatus').value;
            b.qty = qtyNum.value();
        }
        return b;
    }

    function clearEntry() {
        changed();
        scanned = false;
        itemCd.value = '';
        lotNoInp.value = '';
        setItem(null);
        field(commonForm, 'occurredAt').value = '';
        field(commonForm, 'remark').value = '';
        ['fromNodeId', 'toNodeId'].forEach(function (n) { field(moveForm, n).value = ''; });
        suggestStatus(/** @type {HTMLSelectElement} */ (field(moveForm, 'toNodeId')), /** @type {HTMLSelectElement} */ (field(moveForm, 'toStatus')));
        field(adjForm, 'nodeId').value = '';
        qtyNum.reset();
        deltaNum.reset();
        hb.showFieldErrors(entry, []);
        [commonForm, moveForm, adjForm].forEach(function (f) { hb.dirty.clear(f); });
    }

    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            hb.showFieldErrors(entry, []);
            var num = kind() === 'ADJUST' ? deltaNum : qtyNum;
            var chk = num.validate();
            if (!chk.ok && chk.code !== 'REQUIRED') { return; }
            lookupItem().then(function () {
                return hb.dialog.confirm({title: hb.t('js.wmsMove.dlg.post'), message: preview(),
                    ok: hb.t('js.wmsMove.dlg.postOk')}).then(function (yes) {
                    if (!yes) { return; }
                    if (!pendingReq) { pendingReq = reqId(); }
                    var b = body();
                    b.clientReqId = pendingReq;
                    var url = API + (kind() === 'ADJUST' ? '/adjust' : '/move');
                    return hb.busy(btn, function () { return hb.api(url, {method: 'POST', body: b}); }).then(function (res) {
                        if (!res) { return; }
                        var r = /** @type {{trxIds: number[], replay: boolean, warnings?: string[]}} */ (res.data);
                        clearEntry();
                        return loadRecent().then(function () {
                            var warn = r.warnings && r.warnings.length ? r.warnings.join(' ') : '';   // 적치 검사 경고(W-12, 저장은 됨)
                            notify(hb.t(r.replay ? 'js.wmsMove.replayed' : 'js.wmsMove.posted', r.trxIds.join(', ')) + (warn ? ' ' + warn : ''), !!warn);
                        });
                    }, function (err) {
                        notify(errText(err), true);
                        hb.showFieldErrors(entry, err.errors);
                        if (err.status && err.status !== 409 && err.status < 500) { changed(); }   // 고쳐서 다시 보낼 입력 — 새 요청
                    });
                });
            });
        });
    }
    if (clearBtn) { clearBtn.addEventListener('click', clearEntry); }
    var recentBtn = document.getElementById('recentBtn');
    if (recentBtn) { recentBtn.addEventListener('click', function () { hb.page.openDetail(); }); }

    document.querySelectorAll('input[name="kind"]').forEach(function (r) {
        r.addEventListener('change', function () {
            var adj = kind() === 'ADJUST';
            moveBox.hidden = adj;
            adjBox.hidden = !adj;
            changed();
            hb.showFieldErrors(entry, []);
        });
    });
    itemCd.addEventListener('change', function () { scanned = false; lookupItem(); });
    var toNode = /** @type {HTMLSelectElement} */ (field(moveForm, 'toNodeId'));
    toNode.addEventListener('change', function () {
        suggestStatus(toNode, /** @type {HTMLSelectElement} */ (field(moveForm, 'toStatus')));
    });
    [commonForm, moveForm, adjForm].forEach(function (f) {
        f.addEventListener('input', changed);
        f.addEventListener('change', changed);
        f.addEventListener('submit', function (e) { e.preventDefault(); });
        hb.dirty.watch(f);
    });
    plantSel.addEventListener('change', function () { changed(); loadPlant(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('INV_TRX_TYPE'), hb.codes('STOCK_STATUS'),
            hb.codes('WMS_ADJ_REASON')]);
    }).then(function (r) {
        typeNames = r[2];
        statusNames = r[3];
        reasonNames = r[4];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        qtyNum = hb.number(/** @type {HTMLInputElement} */ (field(moveForm, 'qty')));
        deltaNum = hb.number(/** @type {HTMLInputElement} */ (field(adjForm, 'delta')));
        return Promise.all([
            fillCode(/** @type {HTMLSelectElement} */ (field(moveForm, 'fromStatus')), 'STOCK_STATUS', 'AVAILABLE'),
            fillCode(/** @type {HTMLSelectElement} */ (field(moveForm, 'toStatus')), 'STOCK_STATUS', 'AVAILABLE'),
            fillCode(/** @type {HTMLSelectElement} */ (field(adjForm, 'stockStatus')), 'STOCK_STATUS', 'AVAILABLE'),
            fillCode(/** @type {HTMLSelectElement} */ (field(adjForm, 'reasonCd')), 'WMS_ADJ_REASON', 'choose'),
            loadPlant()]);
    }).then(function () { scanText.focus(); }, function (err) { notify(err && err.message, true); });
})();
