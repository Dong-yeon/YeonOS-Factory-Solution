// @ts-check
/* 출하 추적(리콜) (qms-lot-trace, 읽기만) — docs/57 2.4. LOT 번호 → 계보 아래 LOT → 고객별 요약·출하 목록·창고에 남은 것. 잘림은 노란 줄.
   QMS 배지(37장) — LOT 마다 열린 NCR·걸린 봉쇄를 보여 이미 처리 중인 LOT 에 또 만들지 않게.
   주소 진입(35장) — ?lotNos=A,B&direction=BACKWARD 로 열면 채워서 바로 조회(검증은 서버 그대로).
   바로가기(docs/29_P3 33장) — 출하 줄 → 클레임 NCR 등록, 남은 재고 → 품목별 봉쇄 열기(그 LOT 만 미리 체크).
   역추적(docs/29_P3 32장) — 계보 위 LOT → 공급사별 요약·원인 LOT·남은 원인 LOT, "원인 LOT 으로 정방향" = 맨 처음 LOT 들을 입력에 넣고 정방향으로 */
/**
 * @typedef {{unitCd: string, qty: number}} LtUnitQty
 * @typedef {{custId: number, custCd: string, custNm: string, shipCnt: number, lotCnt: number, qty: LtUnitQty[]}} LtCust
 * @typedef {{rootLotNo: string, lotNo: string, itemCd: string | null, itemNm: string | null, depth: number, path: string, custCd: string,
 *   custNm: string, shipNo: string, plantCd: string, shippedAt: string | null, qty: number, unitCd: string, pallets: string, ssccs: string}} LtShip
 * @typedef {{lotNo: string, itemCd: string | null, itemNm: string | null, plantCd: string, path: string, locUsage: string | null,
 *   stockStatus: string, qty: number, lotDate: string | null}} LtStock
 * @typedef {{lotId: number, lotNo: string, itemCd: string, itemNm: string}} LtRoot
 * @typedef {{rootLotNo: string, lotNo: string, itemCd: string | null, itemNm: string | null, depth: number, path: string, relType: string | null,
 *   origin: string | null, supplierCd: string | null, supplierNm: string | null, supplierLotNo: string | null, leaf: boolean}} LtOrigin
 * @typedef {{supplierCustId: number | null, supplierCd: string | null, supplierNm: string | null, lotCnt: number, lotNos: string[],
 *   supplierLotNos: string[]}} LtSup
 * @typedef {{lotNo: string, openNcrNos: string[], containNos: string[]}} LtQms
 * @typedef {{direction: string, depth: number, roots: LtRoot[], unknownLotNos: string[], lotCnt: number, truncated: boolean, customers: LtCust[],
 *   shipments: LtShip[], origins: LtOrigin[], suppliers: LtSup[], stock: LtStock[], qms: LtQms[]}} LtView
 */
(function () {
    'use strict';

    var API = '/api/qms/lot-trace';
    var form = /** @type {HTMLFormElement} */ (document.getElementById('traceForm'));
    var lotNosEl = /** @type {HTMLTextAreaElement} */ (document.getElementById('lotNos'));
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var exportBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('exportBtn'));
    var toForwardBtn = /** @type {HTMLButtonElement} */ (document.getElementById('toForwardBtn'));
    var dirEl = /** @type {HTMLSelectElement} */ (document.getElementById('direction'));
    /** 입력 한 번에 넣을 수 있는 LOT 번호 수(서버 MAX_INPUT) */
    var MAX_INPUT = 100;
    /** 봉쇄 화면이 주소로 받는 LOT 수(contain.js preselect 상한) */
    var CONTAIN_MAX = 500;
    /** 맨 처음 원인 LOT 번호(정방향으로 넘길 것) @type {string[]} */
    var firstLots = [];
    /** LOT 번호 → QMS 일(이번 조회) @type {Record<string, LtQms>} */
    var qmsByLot = {};
    /** @type {HB.NumberControl} */
    var depth;
    /** 마지막 조회 조건(엑셀은 같은 조건) @type {{lotNos: string[], depth: number | null, direction: string} | null} */
    var last = null;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var usageNames = {};
    /** @type {Record<string, string>} */
    var relNames = {};
    /** @type {Record<string, string>} */
    var originNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLElement} tr @param {unknown} text @param {string} [cls] */
    function cell(tr, text, cls) {
        var td = hb.el('td', cls || null, text);
        tr.appendChild(td);
        return td;
    }

    /** @param {string} sel @param {number} cols @param {string} emptyKey @param {boolean} empty */
    function body(sel, cols, emptyKey, empty) {
        var tb = /** @type {HTMLElement} */ (document.querySelector(sel + ' tbody'));
        tb.replaceChildren();
        if (empty) {
            var tr = hb.el('tr');
            var td = cell(tr, hb.t(emptyKey), 'hb-muted');
            td.colSpan = cols;
            tb.appendChild(tr);
        }
        return tb;
    }

    /** 줄바꿈·쉼표·탭으로 나눈 LOT 번호(빈 것 빼고) */
    function parseNos() {
        return lotNosEl.value.split(/[\s,]+/).map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
    }

    /** @param {string} id @param {boolean} show */
    function toggle(id, show) {
        /** @type {HTMLElement} */ (document.getElementById(id)).hidden = !show;
    }

    /** 방향에 맞는 안내·결과 영역만 보인다 @param {boolean} back */
    function showMode(back) {
        toggle('hintFwd', !back);
        toggle('hintBack', back);
        toggle('custSection', !back);
        toggle('shipSection', !back);
        toggle('supSection', back);
        toggle('originSection', back);
    }

    /**
     * LOT 칸 뒤에 QMS 배지 — 열린 NCR(번호 → NCR 화면)·걸린 봉쇄(번호 → 봉쇄 화면). 일이 없으면 아무것도 안 붙인다(37장)
     * @param {HTMLElement} td @param {string} lotNo
     */
    function qmsBadge(td, lotNo) {
        var w = qmsByLot[lotNo];
        if (!w) { return; }
        var box = hb.el('span', 'hb-qms-lotwork');
        box.title = hb.t('js.qms.lotTrace.workTitle');
        w.openNcrNos.forEach(function (no) {
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, hb.t('js.qms.lotTrace.workNcr', no)));
            a.href = '/page/qms-ncr?ncrNo=' + encodeURIComponent(no);
            box.appendChild(a);
            box.appendChild(document.createTextNode(' '));
        });
        w.containNos.forEach(function (no) {
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, hb.t('js.qms.lotTrace.workContain', no)));
            a.href = '/page/qms-contain?q.keyword=' + encodeURIComponent(no);
            box.appendChild(a);
            box.appendChild(document.createTextNode(' '));
        });
        td.appendChild(document.createTextNode(' '));
        td.appendChild(box);
    }

    /** 출하 줄 → 클레임 NCR 등록(그 출하 LOT, 출처 CLAIM, 내용에 고객·출하·경로) @param {LtShip} s */
    function claimLink(s) {
        var desc = hb.t('js.qms.lotTrace.claimDesc', s.custCd, s.custNm, s.shipNo, s.path);
        var a = /** @type {HTMLAnchorElement} */ (hb.el('a', 'hb-btn-secondary hb-link-btn', hb.t('js.qms.lotTrace.claimNcr')));
        a.href = '/page/qms-ncr?lotNo=' + encodeURIComponent(s.lotNo) + '&source=CLAIM&desc=' + encodeURIComponent(desc);
        return a;
    }

    /**
     * 남은 재고 → 품목별 봉쇄 열기: 품목마다 LOT(중복 없이)과 LOT 이 생긴 날 범위를 넘겨 봉쇄 화면이 그 기간 후보를 찾고 이 LOT 만 체크한다
     * (gauge-calib 영향 LOT 과 같은 주소 모양). LOT 날을 모르는 줄은 뺀다
     * @param {LtView} v
     */
    function renderContainLinks(v) {
        var box = /** @type {HTMLElement} */ (document.getElementById('containLinks'));
        box.textContent = '';
        /** @type {Record<string, {lots: string[], from: string, to: string}>} */
        var byItem = {};
        v.stock.forEach(function (k) {
            if (!k.itemCd || !k.lotDate) { return; }
            var g = byItem[k.itemCd] || (byItem[k.itemCd] = {lots: [], from: k.lotDate, to: k.lotDate});
            if (g.lots.indexOf(k.lotNo) < 0) { g.lots.push(k.lotNo); }
            if (k.lotDate < g.from) { g.from = k.lotDate; }
            if (k.lotDate > g.to) { g.to = k.lotDate; }
        });
        var why = hb.t(v.direction === 'BACKWARD' ? 'js.qms.lotTrace.containReasonBack' : 'js.qms.lotTrace.containReason',
            v.roots.map(function (r) { return r.lotNo; }).join(', ')).slice(0, 500);
        Object.keys(byItem).sort().forEach(function (cd) {
            var g = byItem[cd];
            var lots = g.lots.slice(0, CONTAIN_MAX);
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', 'hb-btn-secondary hb-link-btn', hb.t('js.qms.lotTrace.openContain', cd, lots.length)));
            a.href = '/page/qms-contain?itemCd=' + encodeURIComponent(cd) + '&from=' + g.from + '&to=' + g.to
                + '&lots=' + encodeURIComponent(lots.join(',')) + '&reason=' + encodeURIComponent(why);
            box.appendChild(a);
        });
        box.hidden = !box.childElementCount;
    }

    /** @param {LtView} v */
    function renderBack(v) {
        var pb = body('#supTable', 5, 'js.qms.lotTrace.noSupplier', !v.suppliers.length);
        v.suppliers.forEach(function (x) {
            var tr = hb.el('tr');
            cell(tr, x.supplierCd || '');
            cell(tr, x.supplierCustId === null ? hb.t('js.qms.lotTrace.noSupplierName') : (x.supplierNm || ''), x.supplierCustId === null ? 'hb-muted' : undefined);
            cell(tr, x.lotCnt, 'hb-num');
            cell(tr, x.lotNos.join(', '));
            cell(tr, x.supplierLotNos.join(', '));
            pb.appendChild(tr);
        });
        var ob = body('#originTable', 8, 'js.qms.lotTrace.noOrigin', !v.origins.length);
        v.origins.forEach(function (o) {
            var tr = hb.el('tr');
            cell(tr, o.rootLotNo);
            qmsBadge(cell(tr, o.lotNo + (o.itemCd ? ' · ' + o.itemCd : '')), o.lotNo);
            cell(tr, o.depth, 'hb-num');
            cell(tr, o.path);
            cell(tr, o.relType ? (relNames[o.relType] || o.relType) : '');
            cell(tr, o.origin ? (originNames[o.origin] || o.origin) : '');
            cell(tr, o.supplierCd ? o.supplierCd + ' · ' + (o.supplierNm || '') + (o.supplierLotNo ? ' (' + o.supplierLotNo + ')' : '') : '');
            cell(tr, o.leaf ? hb.t('js.qms.lotTrace.first') : '');
            ob.appendChild(tr);
        });
        // 맨 처음 원인 LOT(뿌리 자신 제외, 중복 없이) — 정방향으로 넘겨 같은 원인으로 만든 다른 출하를 찾는다
        var seen = /** @type {Record<string, boolean>} */ ({});
        firstLots = [];
        v.origins.forEach(function (o) {
            if (o.leaf && o.depth > 0 && !seen[o.lotNo]) { seen[o.lotNo] = true; firstLots.push(o.lotNo); }
        });
        toForwardBtn.hidden = !firstLots.length;
    }

    /** @param {LtView} v */
    function render(v) {
        var back = v.direction === 'BACKWARD';
        qmsByLot = {};
        (v.qms || []).forEach(function (w) { qmsByLot[w.lotNo] = w; });
        showMode(back);
        var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
        summary.hidden = false;
        summary.textContent = back ? hb.t('js.qms.lotTrace.summaryBack', v.roots.length, v.lotCnt, v.suppliers.length, v.depth)
            : hb.t('js.qms.lotTrace.summary', v.roots.length, v.lotCnt, v.customers.length, v.shipments.length, v.depth);
        /** @type {HTMLElement} */ (document.getElementById('truncNote')).hidden = !v.truncated;
        var unknown = /** @type {HTMLElement} */ (document.getElementById('unknownNote'));
        unknown.hidden = !v.unknownLotNos.length;
        unknown.textContent = v.unknownLotNos.length ? hb.t('js.qms.lotTrace.unknown', v.unknownLotNos.join(', ')) : '';

        var cb = body('#custTable', 5, 'js.qms.lotTrace.noShip', !v.customers.length);
        v.customers.forEach(function (c) {
            var tr = hb.el('tr');
            cell(tr, c.custCd);
            cell(tr, c.custNm);
            cell(tr, c.shipCnt, 'hb-num');
            cell(tr, c.lotCnt, 'hb-num');
            cell(tr, c.qty.map(function (q) { return hb.numbers.format(q.qty, null) + ' ' + q.unitCd; }).join(' · '), 'hb-num');
            cb.appendChild(tr);
        });

        var sb = body('#shipTable', 10, 'js.qms.lotTrace.noShip', !v.shipments.length);
        v.shipments.forEach(function (s) {
            var tr = hb.el('tr');
            cell(tr, s.rootLotNo);
            qmsBadge(cell(tr, s.lotNo + (s.itemCd ? ' · ' + s.itemCd : '')), s.lotNo);
            cell(tr, s.depth, 'hb-num');
            cell(tr, s.path);
            cell(tr, s.custCd + ' · ' + s.custNm);
            cell(tr, s.shipNo + ' (' + s.plantCd + ')');
            cell(tr, s.shippedAt ? hb.fmt.datetime(s.shippedAt) : '');
            cell(tr, hb.numbers.format(s.qty, null) + ' ' + s.unitCd, 'hb-num');
            cell(tr, [s.pallets, s.ssccs].filter(function (x) { return !!x; }).join(' · '));
            cell(tr, '').appendChild(claimLink(s));
            sb.appendChild(tr);
        });

        var kb = body('#stockTable', 6, 'js.qms.lotTrace.noStock', !v.stock.length);
        v.stock.forEach(function (k) {
            var tr = hb.el('tr');
            qmsBadge(cell(tr, k.lotNo), k.lotNo);
            cell(tr, k.itemCd ? k.itemCd + ' · ' + (k.itemNm || '') : '');
            cell(tr, k.plantCd);
            cell(tr, k.path + (k.locUsage ? ' (' + (usageNames[k.locUsage] || k.locUsage) + ')' : ''));
            cell(tr, statusNames[k.stockStatus] || k.stockStatus);
            cell(tr, hb.numbers.format(k.qty, null), 'hb-num');
            kb.appendChild(tr);
        });
        renderContainLinks(v);
        if (back) {
            renderBack(v);
        } else {
            firstLots = [];
            toForwardBtn.hidden = true;
        }
        if (exportBtn) { exportBtn.disabled = back ? !v.origins.length : !v.shipments.length; }
    }

    /** 조회 — 결과를 보였으면 true @param {HTMLButtonElement} btn @returns {Promise<boolean>} */
    function run(btn) {
        if (!depth.validate().ok) { /** @type {HTMLElement} */ (document.getElementById('depth')).focus(); return Promise.resolve(false); }
        var d = depth.value();
        var req = {lotNos: parseNos(), depth: d === '' || d === null ? null : Number(d), direction: dirEl.value};
        hb.showFieldErrors(form, []);
        return hb.busy(btn, function () { return hb.api(API, {method: 'POST', body: req}); }).then(function (r) {
            if (r === null) { return false; }
            last = req;
            render(/** @type {LtView} */ (r.data));
            notify('');
            return true;
        }, function (err) {
            hb.showFieldErrors(form, err && err.errors);
            notify(err && err.message, true);
            return false;
        });
    }

    hb.loadMessages().then(function () {
        depth = hb.number(/** @type {HTMLInputElement} */ (document.getElementById('depth')));
        return Promise.all([hb.loadSession(), hb.codeNames('STOCK_STATUS'), hb.codeNames('WMS_LOC_USAGE'), hb.codeNames('LOT_REL_TYPE'),
            hb.codeNames('LOT_ORIGIN')]);
    }).then(function (r) {
        statusNames = r[1];
        usageNames = r[2];
        relNames = r[3];
        originNames = r[4];
        var traceBtn = /** @type {HTMLButtonElement} */ (document.getElementById('traceBtn'));
        form.addEventListener('submit', function (e) { e.preventDefault(); run(traceBtn); });
        // 방향을 바꾸면 안내만 바꾸고 결과는 다음 조회에서(이전 결과가 다른 방향으로 읽히지 않게 비운다)
        dirEl.addEventListener('change', function () {
            showMode(dirEl.value === 'BACKWARD');
            toggle('summary', false);
            toggle('truncNote', false);
            toggle('unknownNote', false);
            toggle('containLinks', false);
            ['#custTable', '#shipTable', '#supTable', '#originTable', '#stockTable'].forEach(function (sel) {
                /** @type {HTMLElement} */ (document.querySelector(sel + ' tbody')).replaceChildren();
            });
            last = null;
            firstLots = [];
            toForwardBtn.hidden = true;
            if (exportBtn) { exportBtn.disabled = true; }
        });
        // 다른 화면(NCR·8D·SPC 위반)에서: ?lotNos=&direction= — 채우고 바로 조회. 방향 값은 목록에 있는 것만(그 밖은 정방향)
        var qs = new URLSearchParams(window.location.search);
        var qNos = (qs.get('lotNos') || '').split(',').map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
        if (qNos.length) {
            lotNosEl.value = qNos.slice(0, MAX_INPUT).join('\n');
            dirEl.value = qs.get('direction') === 'BACKWARD' ? 'BACKWARD' : 'FORWARD';
            showMode(dirEl.value === 'BACKWARD');
            run(traceBtn);
        }
        toForwardBtn.addEventListener('click', function () {
            var all = firstLots.length;
            lotNosEl.value = firstLots.slice(0, MAX_INPUT).join('\n');
            dirEl.value = 'FORWARD';
            dirEl.dispatchEvent(new Event('change'));
            run(traceBtn).then(function (ok) {
                if (ok && all > MAX_INPUT) { notify(hb.t('js.qms.lotTrace.toForwardCut', MAX_INPUT, all)); }
            });
        });
        if (exportBtn) {
            var eb = exportBtn;
            eb.addEventListener('click', function () {
                if (!last) { return; }
                var body = last;
                hb.busy(eb, function () { return hb.download(API + '/export', body); }).then(null, function (err) { notify(err && err.message, true); });
            });
        }
    }).then(null, function (err) { notify(err && err.message, true); });
})();
