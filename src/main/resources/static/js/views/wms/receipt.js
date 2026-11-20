// @ts-check
/* 자재·구매 입고 (wms-rcv) — P4 2부 B4a·B4b (docs/30 12.3·12.6). 품목을 고르면 열린 발주 행(SRM 없으면 칸을 숨김) — 고르면 구매 입고. 스캔(위치·품목·공급사 GS1) → 입력 → 원장 행 미리보기 확인 → 확정 1회.
   요청 id(clientReqId)는 확정을 **누른 순간** 만들고, 성공하면 버린다 — 응답이 끊겨 다시 눌러도 같은 id 라 LOT 이 두 개 생기지 않는다.
   판정(위치·공급사·공급사 LOT 중복·미래 시각·마감)은 서버. 공통 hb.* 는 고치지 않는다(docs/29 2.4). */
/**
 * @typedef {{nodeId: number, nodeType: string, nodeCd: string, nodeNm: string, path: string, locUsage: string,
 *   defaultStatus?: string | null, stockYn: string}} WmsRcvNode
 * @typedef {{itemId: number, itemCd: string, itemNm: string, unitCd: string, lotCtrlYn?: string, useYn?: string}} WmsRcvItem
 * @typedef {{type: string, ok: boolean, message: string, nodeId?: number | null, path?: string | null, itemId?: number | null,
 *   itemCd?: string | null, itemNm?: string | null, unitCd?: string | null, supplierLotNo?: string | null, expiry?: string | null,
 *   qty?: string | number | null, asnNo?: string | null}} WmsRcvScan
 * @typedef {{asnId: number, asnNo: string, custCd: string, custNm: string, supplierRef?: string | null, etaDate: string, status: string,
 *   openLineCnt: number}} WmsRcvAsnHead
 * @typedef {{asnLineId: number, lineNo: number, poLineId: number, poNo: string, poLineNo: number, itemId: number, itemCd: string,
 *   itemNm: string, unitCd: string, qty: string | number, recvQty: string | number, remainQty: string | number,
 *   supplierLotNo?: string | null, expiryDate?: string | null}} WmsRcvAsnLine
 * @typedef {{head: WmsRcvAsnHead, lines: WmsRcvAsnLine[]}} WmsRcvAsn
 * @typedef {{trxId: number, itemCd: string, lotNo?: string | null, path: string, qty: string | number, unitCd: string,
 *   occurredAt: string, reversalOfId?: number | null}} WmsRcvRecent
 * @typedef {{id: number, cd: string, nm: string}} WmsRcvOpt
 * @typedef {{poLineId: number, poNo: string, lineNo: number, supplierCd: string, dueDate: string, openQty: string | number,
 *   unitCd: string}} WmsRcvPoLine
 */
(function () {
    'use strict';

    var API = '/api/wms/rcv';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var scanForm = /** @type {HTMLFormElement} */ (document.getElementById('scanForm'));
    var scanText = /** @type {HTMLInputElement} */ (document.getElementById('scanText'));
    var scanResult = /** @type {HTMLOutputElement} */ (document.getElementById('scanResult'));
    var entry = /** @type {HTMLElement} */ (document.getElementById('entry'));
    var rcvForm = /** @type {HTMLFormElement} */ (document.getElementById('rcvForm'));
    var labelForm = /** @type {HTMLFormElement} */ (document.getElementById('labelForm'));
    var itemCd = /** @type {HTMLInputElement} */ (document.getElementById('itemCd'));
    var itemNm = /** @type {HTMLInputElement} */ (document.getElementById('itemNm'));
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    var clearBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('clearBtn'));
    var recentBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#recent tbody'));
    var poWrap = /** @type {HTMLElement} */ (document.getElementById('poWrap'));
    var asnBox = /** @type {HTMLElement} */ (document.getElementById('asnBox'));
    var asnForm = /** @type {HTMLFormElement} */ (document.getElementById('asnForm'));
    var asnSel = /** @type {HTMLSelectElement} */ (document.getElementById('asnSel'));
    var asnKey = /** @type {HTMLInputElement} */ (document.getElementById('asnKey'));
    var asnInfo = /** @type {HTMLElement} */ (document.getElementById('asnInfo'));
    var asnHost = /** @type {HTMLElement} */ (document.getElementById('asnHost'));
    var asnBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#asnLines tbody'));
    /** 지금 연 ASN @type {WmsRcvAsn | null} */
    var asn = null;
    /** 칸을 채운 ASN 행 — 발주 행을 바꾸면 끊는다 @type {WmsRcvAsnLine | null} */
    var asnPick = null;
    /** @type {WmsRcvPoLine[]} */
    var poLines = [];
    /** @type {WmsRcvItem | null} */
    var item = null;
    /** @type {WmsRcvNode[]} */
    var nodes = [];
    /** 스캔으로 채운 칸이 있으면 SCAN, 아니면 KEY */
    var scanned = false;
    /** 확정을 누른 순간 만든 요청 id — 성공하거나 입력이 바뀌면 버린다 @type {string | null} */
    var pendingReq = null;
    /** @type {HB.NumberControl} */
    var qtyNum;
    /** @type {HB.NumberControl} */
    var copiesNum;
    /** @type {Record<string, string>} */
    var statusNames = {};

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

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /** 입력이 바뀌면 앞서 만든 요청 id 는 다른 요청이다 */
    function changed() { pendingReq = null; }

    // ------------------------------------------------------------------ 선택지

    function drawNodes() {
        var sel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'nodeId'));
        var keep = sel.value;
        sel.textContent = '';
        option(sel, '', hb.t('js.wmsRcv.choose'));
        nodes.forEach(function (n) {
            // 재고를 안 두는 노드·폐기 위치·출하 대기 칸(W-9)은 고르지 못하게 — 서버가 같은 규칙으로 다시 본다
            if (n.stockYn !== 'Y' || n.locUsage === 'SCRAP' || n.locUsage === 'STAGING') { return; }
            option(sel, String(n.nodeId), n.path + ' · ' + n.nodeNm);
        });
        sel.value = keep;
        if (sel.selectedIndex < 0) { sel.selectedIndex = 0; }
    }

    /** @param {HTMLSelectElement} sel @param {WmsRcvOpt[]} list @param {string} first */
    function drawOpts(sel, list, first) {
        var keep = sel.value;
        sel.textContent = '';
        option(sel, '', hb.t(first));
        list.forEach(function (o) { option(sel, String(o.id), o.cd + ' · ' + o.nm); });
        sel.value = keep;
        if (sel.selectedIndex < 0) { sel.selectedIndex = 0; }
    }

    function loadPlant() {
        var plant = plantSel.value;
        nodes = [];
        drawNodes();
        if (!plant) { return Promise.resolve(); }
        return Promise.all([
            hb.latest('wmsRcvNodes', hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plant))).then(function (res) {
                nodes = /** @type {WmsRcvNode[]} */ (res.data);
                drawNodes();
            }),
            hb.latest('wmsRcvPrinters', hb.api(API + '/printers?plantCd=' + encodeURIComponent(plant))).then(function (res) {
                drawOpts(/** @type {HTMLSelectElement} */ (field(labelForm, 'printerId')), /** @type {WmsRcvOpt[]} */ (res.data),
                    'js.wmsRcv.noPrint');
            }),
            loadRecent()
        ]).then(null, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    function loadRecent() {
        var plant = plantSel.value;
        return hb.latest('wmsRcvRecent', hb.api(API + '/recent?plantCd=' + encodeURIComponent(plant))).then(function (res) {
            var list = /** @type {WmsRcvRecent[]} */ (res.list || []);
            recentBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.wmsRcv.none')));
                td.colSpan = 5;
                tr.appendChild(td);
                recentBody.appendChild(tr);
            }
            list.forEach(function (t) {
                var tr = hb.el('tr', t.reversalOfId ? 'hb-tree-dim' : null);
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(t.occurredAt)));
                tr.appendChild(hb.el('td', null, t.itemCd));
                tr.appendChild(hb.el('td', null, t.lotNo || ''));
                tr.appendChild(hb.el('td', 'hb-wrap', t.path));
                tr.appendChild(hb.el('td', 'hb-num', q(t.qty) + ' ' + t.unitCd));
                recentBody.appendChild(tr);
            });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsRcvItem | null} it @returns {Promise<void>} */
    function setItem(it) {
        var same = item && it && item.itemId === it.itemId;
        item = it;
        itemNm.value = it ? it.itemNm + ' (' + it.unitCd + ')' : '';
        if (!same) { checkQc(); }
        return same ? Promise.resolve() : loadPo();
    }

    /** 품질 상태 연동 공장의 수입검사 대상이면 상태를 QC 로 묶고 안내한다 — 저장 때 서버가 같은 규칙으로 다시 정한다(docs/30 12.15.6) */
    function checkQc() {
        if (!item || !plantSel.value) { lockQc(false); return; }
        hb.latest('wmsRcvQc', hb.api(API + '/qc-fixed?plantCd=' + encodeURIComponent(plantSel.value) + '&itemId=' + item.itemId))
            .then(function (res) { lockQc(res.data === true); }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {boolean} on */
    function lockQc(on) {
        var statusSel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'stockStatus'));
        var hint = /** @type {HTMLElement} */ (document.getElementById('qcHint'));
        var before = statusSel.getAttribute('data-before-qc');
        if (on && before === null) {
            statusSel.setAttribute('data-before-qc', statusSel.value);
            statusSel.value = 'QC';
        } else if (!on && before !== null) {
            statusSel.value = before;                       // 묶기 전에 고른 상태로
            statusSel.removeAttribute('data-before-qc');
        }
        statusSel.disabled = on;
        hint.hidden = !on;
    }

    /** 이 공장·품목의 열린 발주 행 — SRM 없는 설치본(enabled = false)은 칸을 숨긴다 */
    function loadPo() {
        var sel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'poLineId'));
        poLines = [];
        sel.textContent = '';
        option(sel, '', hb.t('js.wmsRcv.noPo'));
        poPicked();
        if (!item || !plantSel.value) { return Promise.resolve(); }
        return hb.latest('wmsRcvPo', hb.api(API + '/po-lines?plantCd=' + encodeURIComponent(plantSel.value) + '&itemId=' + item.itemId))
            .then(function (res) {
                var d = /** @type {{enabled: boolean, lines: WmsRcvPoLine[]}} */ (res.data);
                poWrap.hidden = !d.enabled;
                poLines = d.lines;
                poLines.forEach(function (l) {
                    option(sel, String(l.poLineId), hb.t('js.wmsRcv.poOpt', l.poNo, l.lineNo, l.supplierCd, l.dueDate, q(l.openQty), l.unitCd));
                });
            }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** 고른 발주 행 — 공급사는 발주 거래처로 고정(서버도 발주 거래처를 쓴다). ASN 행과 다른 발주 행이면 ASN 연결을 끊는다 */
    function poPicked() {
        var l = picked();
        if (asnPick && (!l || l.poLineId !== asnPick.poLineId)) { asnPick = null; showAsnPick(); }
        var sup = /** @type {HTMLInputElement} */ (field(rcvForm, 'supplierCd'));
        if (l) { sup.value = l.supplierCd; }
        sup.readOnly = !!l;
    }

    /** @returns {WmsRcvPoLine | undefined} */
    function picked() {
        var v = field(rcvForm, 'poLineId').value;
        return poLines.find(function (l) { return String(l.poLineId) === v; });
    }

    // ------------------------------------------------------------------ ASN (2부 R3b — SRM AsnQuery)

    /** 도착 예정 ASN 선택지 — SRM 이 없으면(발주 칸과 같이) 칸을 숨긴다 */
    function loadAsns() {
        asnSel.textContent = '';
        option(asnSel, '', hb.t('js.wmsRcv.asnChoose'));
        if (!plantSel.value) { asnBox.hidden = true; return Promise.resolve(); }
        var plant = encodeURIComponent(plantSel.value);
        return Promise.all([hb.api(API + '/po-lines?plantCd=' + plant), hb.api(API + '/asns?plantCd=' + plant)]).then(function (r) {
            asnBox.hidden = !(/** @type {{enabled: boolean}} */ (r[0].data)).enabled;
            /** @type {WmsRcvAsnHead[]} */ (r[1].data).forEach(function (a) {
                option(asnSel, a.asnNo, hb.t('js.wmsRcv.asnOpt', a.asnNo, a.custCd, a.supplierRef || '-', a.etaDate, a.openLineCnt));
            });
        }, function (err) { notify(errText(err), true); });
    }

    /** ASN 한 건 열기(번호·ASN: 바코드·협력사 납품서 번호) @param {string} key */
    function openAsn(key) {
        if (!key) { return Promise.resolve(); }
        return hb.latest('wmsRcvAsn', hb.api(API + '/asn?plantCd=' + encodeURIComponent(plantSel.value) + '&key=' + encodeURIComponent(key)))
            .then(function (res) {
                asn = /** @type {WmsRcvAsn} */ (res.data);
                drawAsn();
            }, function (err) {
                if (err.stale) { return; }
                asn = null;
                drawAsn();
                notify(err.status === 404 ? hb.t('js.wmsRcv.asnNotFound', key) : errText(err), true);
            });
    }

    function drawAsn() {
        asnBody.textContent = '';
        asnHost.hidden = !asn;
        if (!asn) { showAsnPick(); return; }
        var a = asn;
        a.lines.forEach(function (l) {
            var tr = hb.el('tr', Number(l.remainQty) > 0 ? null : 'hb-tree-dim');
            tr.appendChild(hb.el('td', null, l.poNo + '-' + l.poLineNo));
            tr.appendChild(hb.el('td', 'hb-wrap', l.itemCd + ' ' + l.itemNm));
            tr.appendChild(hb.el('td', 'hb-num', q(l.remainQty) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', null, l.supplierLotNo || ''));
            var c = hb.el('td');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.wmsRcv.asnFill')));
            b.type = 'button';
            b.setAttribute('aria-label', hb.t('js.wmsRcv.asnFillFor', l.poNo, l.poLineNo, l.itemCd));
            b.addEventListener('click', function () { fillFromAsn(l); });
            c.appendChild(b);
            tr.appendChild(c);
            asnBody.appendChild(tr);
        });
        showAsnPick();
    }

    function showAsnPick() {
        asnInfo.textContent = !asn ? '' : asnPick
            ? hb.t('js.wmsRcv.asnPicked', asn.head.asnNo, asnPick.lineNo, asnPick.poNo, asnPick.poLineNo)
            : hb.t('js.wmsRcv.asnTitle', asn.head.asnNo, asn.head.custCd + ' · ' + asn.head.custNm, asn.head.supplierRef || '-', asn.head.etaDate);
    }

    /** ASN 행 → 품목·발주 행·수량(남은 양, 발주 단위)·공급사 LOT·유통기한. 사람이 실물 수량으로 고칠 수 있다 @param {WmsRcvAsnLine} l */
    function fillFromAsn(l) {
        changed();
        itemCd.value = l.itemCd;
        setItem({itemId: l.itemId, itemCd: l.itemCd, itemNm: l.itemNm, unitCd: l.unitCd}).then(function () {
            var sel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'poLineId'));
            if (!poLines.some(function (p) { return p.poLineId === l.poLineId; })) {
                // 다 받아 열린 행 목록에 없는 발주 행(ASN 초과 입고) — 선택지에 넣어 둔다
                poLines.push({poLineId: l.poLineId, poNo: l.poNo, lineNo: l.poLineNo, supplierCd: asn ? asn.head.custCd : '', dueDate: '',
                    openQty: 0, unitCd: l.unitCd});
                option(sel, String(l.poLineId), l.poNo + '-' + l.poLineNo);
            }
            sel.value = String(l.poLineId);
            asnPick = l;
            poPicked();
            showAsnPick();
            if (Number(l.remainQty) > 0) { qtyNum.setValue(l.remainQty); }
            field(rcvForm, 'supplierLotNo').value = l.supplierLotNo || '';
            field(rcvForm, 'expiry').value = l.expiryDate || '';
            var warn = expiryWarn();
            notify(warn || '', !!warn);
        });
    }

    function lookupItem() {
        var cd = itemCd.value.trim();
        if (!cd) { setItem(null); return Promise.resolve(); }
        if (item && item.itemCd === cd.toUpperCase()) { return Promise.resolve(); }
        return hb.latest('wmsRcvItem', hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            var it = /** @type {WmsRcvItem} */ (res.data);
            itemCd.value = it.itemCd;
            setItem(it);
        }, function (err) {
            if (err.stale) { return; }
            setItem(null);
            hb.showFieldErrors(rcvForm, [{field: 'itemId', code: 'NO_SUCH_CODE', message: hb.t('js.wmsRcv.itemNotFound')}]);
        });
    }

    /** 입고 위치가 QC 구역이면 그 기본 상태를 제안한다 — 사용자가 고른 상태는 덮지 않는다 */
    function suggestStatus() {
        var nodeSel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'nodeId'));
        var statusSel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'stockStatus'));
        var n = nodes.find(function (x) { return String(x.nodeId) === nodeSel.value; });
        if (statusSel.disabled) { return; }               // QC 로 묶인 동안은 위치 제안이 덮지 않는다
        var prev = statusSel.getAttribute('data-suggested');
        if (n && n.defaultStatus && (statusSel.value === 'AVAILABLE' || statusSel.value === prev)) {
            statusSel.value = n.defaultStatus;
            statusSel.setAttribute('data-suggested', n.defaultStatus);
        } else if ((!n || !n.defaultStatus) && prev && statusSel.value === prev) {
            statusSel.value = 'AVAILABLE';                 // 앞 위치가 제안한 상태가 다음 위치로 따라가지 않게
            statusSel.removeAttribute('data-suggested');
        }
    }

    /** 유통기한이 오늘보다 앞이면 경고만(저장하지 않는다 — D-B9) */
    function expiryWarn() {
        var v = field(rcvForm, 'expiry').value;
        var d = new Date();
        var today = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
        return v && v < today ? hb.t('js.wmsRcv.expired', v) : '';
    }

    // ------------------------------------------------------------------ 스캔

    scanForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var text = scanText.value;
        if (!text.trim()) { return; }
        hb.latest('wmsRcvScan', hb.api(API + '/scan', {method: 'POST', body: {plantCd: plantSel.value, text: text}})).then(function (res) {
            var r = /** @type {WmsRcvScan} */ (res.data);
            scanText.value = '';
            scanResult.textContent = r.message;
            scanResult.classList.toggle('hb-msg-error', !r.ok);
            if (!r.ok) { return; }
            changed();
            scanned = true;
            if (r.type === 'ASN' && r.asnNo) {
                asnKey.value = r.asnNo;
                openAsn(r.asnNo);
                scanText.focus();
                return;
            }
            if (r.type === 'LOC' && r.nodeId) {
                var hit = nodes.find(function (x) { return x.nodeId === r.nodeId; });
                if (hit && hit.locUsage === 'STAGING') {
                    notify(hb.t('js.wmsRcv.stagingLoc', hit.path), true);     // 출하 대기 칸은 입고하지 않는다(W-9)
                    scanText.focus();
                    return;
                }
                field(rcvForm, 'nodeId').value = String(r.nodeId);
                suggestStatus();
            }
            if (r.itemId) {
                itemCd.value = r.itemCd || '';
                setItem({itemId: r.itemId, itemCd: r.itemCd || '', itemNm: r.itemNm || '', unitCd: r.unitCd || ''});
            }
            if (r.supplierLotNo) { field(rcvForm, 'supplierLotNo').value = r.supplierLotNo; }
            if (r.expiry) { field(rcvForm, 'expiry').value = r.expiry; }
            if (r.qty != null) { qtyNum.setValue(r.qty); }
            var warn = expiryWarn();
            if (warn) { notify(warn, true); }
            scanText.focus();
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    });

    // ------------------------------------------------------------------ 확정

    /** 원장 행 미리보기 글자(확인 창) */
    function preview() {
        var nodeId = field(rcvForm, 'nodeId').value;
        var n = nodes.find(function (x) { return String(x.nodeId) === nodeId; });
        var st = field(rcvForm, 'stockStatus').value;
        var sup = field(rcvForm, 'supplierCd').value.trim();
        var supLot = field(rcvForm, 'supplierLotNo').value.trim();
        var text = hb.t('js.wmsRcv.preview', n ? n.path : '?', statusNames[st] || st, q(qtyNum.value() || ''),
            item ? item.unitCd : '', (item ? item.itemCd : '') + (sup ? ' · ' + sup : '') + (supLot ? ' · ' + supLot : ''));
        var po = picked();
        if (po) { text += '\n' + hb.t('js.wmsRcv.previewPo', po.poNo, po.lineNo); }
        if (asnPick && asn) { text += '\n' + hb.t('js.wmsRcv.previewAsn', asn.head.asnNo, asnPick.lineNo); }
        var printer = field(labelForm, 'printerId').value;
        if (printer) { text += '\n' + hb.t('js.wmsRcv.previewLabel', copiesNum.value() || '1'); }
        var warn = expiryWarn();
        return warn ? text + '\n' + warn : text;
    }

    function body() {
        /** @param {ParentNode} root @param {string} n */
        var num = function (root, n) { var v = field(root, n).value; return v ? Number(v) : null; };
        /** @param {string} n */
        var str = function (n) { return field(rcvForm, n).value.trim() || null; };
        return {plantCd: plantSel.value, nodeId: num(rcvForm, 'nodeId'), itemId: item ? item.itemId : null, qty: qtyNum.value(),
            stockStatus: field(rcvForm, 'stockStatus').value, supplierCd: str('supplierCd'), supplierLotNo: str('supplierLotNo'),
            occurredAt: field(rcvForm, 'occurredAt').value || null, remark: str('remark'), entryMethod: scanned ? 'SCAN' : 'KEY',
            printerId: num(labelForm, 'printerId'), tplId: num(labelForm, 'tplId'),
            copies: copiesNum.value() ? Number(copiesNum.value()) : null, expiry: str('expiry'), clientReqId: pendingReq,
            poLineId: num(rcvForm, 'poLineId'), asnLineId: asnPick ? asnPick.asnLineId : null};
    }

    /** 다음 입고 준비 — 위치·상태·공급사·프린터는 남긴다(같은 납품의 다음 품목) @param {boolean} all */
    function clearEntry(all) {
        changed();
        scanned = false;
        itemCd.value = '';
        setItem(null);
        asnPick = null;
        showAsnPick();
        ['supplierLotNo', 'expiry', 'occurredAt', 'remark'].forEach(function (n) { field(rcvForm, n).value = ''; });
        qtyNum.reset();
        if (all) {
            field(rcvForm, 'nodeId').value = '';
            field(rcvForm, 'supplierCd').value = '';
            suggestStatus();
            field(labelForm, 'printerId').value = '';
            copiesNum.reset();
        }
        hb.showFieldErrors(entry, []);
        [rcvForm, labelForm].forEach(function (f) { hb.dirty.clear(f); });
    }

    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            hb.showFieldErrors(entry, []);
            var chk = qtyNum.validate();
            if (!chk.ok && chk.code !== 'REQUIRED') { return; }
            lookupItem().then(function () {
                return hb.dialog.confirm({title: hb.t('js.wmsRcv.dlg.post'), message: preview(),
                    ok: hb.t('js.wmsRcv.dlg.postOk')}).then(function (yes) {
                    if (!yes) { return; }
                    if (!pendingReq) { pendingReq = reqId(); }
                    var b = body();
                    return hb.busy(btn, function () { return hb.api(API, {method: 'POST', body: b}); }).then(function (res) {
                        if (!res) { return; }
                        var r = /** @type {{trxId: number, lotNo: string, replay: boolean, labelJobs: number, warnings: string[]}} */ (res.data);
                        var reopen = asn ? asn.head.asnNo : null;
                        clearEntry(false);
                        return Promise.all([loadRecent(), reopen ? openAsn(reopen) : null, loadAsns()]).then(function () {
                            var warn = r.warnings && r.warnings.length ? r.warnings.join(' ') : '';   // 적치 검사 경고(W-12, 저장은 됨)
                            notify(hb.t(r.replay ? 'js.wmsRcv.replayed' : 'js.wmsRcv.posted', r.lotNo, r.trxId)
                                + (r.labelJobs ? ' ' + hb.t('js.wmsRcv.labelQueued', r.labelJobs) : '') + (warn ? ' ' + warn : ''), !!warn);
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
    if (clearBtn) { clearBtn.addEventListener('click', function () { clearEntry(true); }); }
    var recentBtn = document.getElementById('recentBtn');
    if (recentBtn) { recentBtn.addEventListener('click', function () { hb.page.openDetail(); }); }

    itemCd.addEventListener('change', function () { scanned = false; lookupItem(); });
    field(rcvForm, 'nodeId').addEventListener('change', suggestStatus);
    field(rcvForm, 'poLineId').addEventListener('change', poPicked);
    [rcvForm, labelForm].forEach(function (f) {
        f.addEventListener('input', changed);
        f.addEventListener('change', changed);
        f.addEventListener('submit', function (e) { e.preventDefault(); });
        hb.dirty.watch(f);
    });
    plantSel.addEventListener('change', function () { changed(); loadPlant(); loadPo(); checkQc(); asn = null; drawAsn(); loadAsns(); });
    asnForm.addEventListener('submit', function (e) { e.preventDefault(); openAsn(asnKey.value.trim()); });
    asnSel.addEventListener('change', function () { if (asnSel.value) { asnKey.value = asnSel.value; openAsn(asnSel.value); } });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('STOCK_STATUS'), hb.api(API + '/templates')]);
    }).then(function (r) {
        statusNames = r[2];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        drawOpts(/** @type {HTMLSelectElement} */ (field(labelForm, 'tplId')), /** @type {WmsRcvOpt[]} */ (r[3].data), 'js.wmsRcv.defaultTpl');
        qtyNum = hb.number(/** @type {HTMLInputElement} */ (field(rcvForm, 'qty')));
        copiesNum = hb.number(/** @type {HTMLInputElement} */ (field(labelForm, 'copies')));
        var statusSel = /** @type {HTMLSelectElement} */ (field(rcvForm, 'stockStatus'));
        return hb.codeList('STOCK_STATUS').then(function (list) {
            statusSel.textContent = '';
            list.forEach(function (c) { option(statusSel, c.code, c.name); });
            statusSel.value = 'AVAILABLE';
            return Promise.all([loadPlant(), loadAsns()]);
        });
    }).then(function () { scanText.focus(); }, function (err) { notify(err && err.message, true); });
})();
