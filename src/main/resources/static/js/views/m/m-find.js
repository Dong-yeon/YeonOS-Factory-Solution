// @ts-check
/*
 * 모바일 찾기 (/m/find, docs/74 화면 3·M9) — LOT 번호·설비 코드를 입력하거나 스캔해 요약을 본다. 읽기 전용, 기존 API 만.
 * - LOT: /api/production/lots/by-no → /{id} — 상태(보류·소진·사용 가능)·잔량·출처·살아 있는 보류 근거·바로 위아래 계보(앞 5줄)
 * - 설비: /api/cms/state/plants → 공장마다 /api/cms/state?plantCd= (오늘 타임라인) 에서 그 코드의 설비를 찾아 지금 구간을 보여 준다
 * - 스캔(hb.scan): 입력 칸 Enter(웨지) 또는 APK 스캐너 이벤트. 내부 라벨 접두어 "LOT:" 는 떼고 찾는다. 서버가 다시 검증한다
 * - 값은 주소창에 남기지 않는다(조회 API 의 쿼리로만 — 사무 화면과 같음). 서버 글자는 textContent 로만
 */
/**
 * @typedef {{lotId: number, lotNo: string, itemCd: string, itemNm: string, origin: string, qtyCreated: string, balance: string,
 *            unitCd: string, woNo: string | null, supplierNm: string | null, supplierLotNo: string | null, occurredAt: string,
 *            holdYn: string, state: string}} MLot
 * @typedef {{parentLotNo: string, childLotNo: string, relType: string, qty: string, unitCd: string, reversed: boolean,
 *            reversalOfId: number | null}} MLotEdge
 * @typedef {{source: string, reason: string | null, heldAt: string}} MLotHold
 */
(function () {
    'use strict';

    var body = document.body;
    var canLot = body.getAttribute('data-can-lot') === 'true';
    var canEquip = body.getAttribute('data-can-equip') === 'true';
    var form = /** @type {HTMLFormElement} */ (document.getElementById('findForm'));
    var input = /** @type {HTMLInputElement} */ (document.getElementById('findInput'));
    var label = /** @type {HTMLElement} */ (document.getElementById('findLabel'));
    var btn = /** @type {HTMLButtonElement} */ (document.getElementById('findBtn'));
    var msg = document.getElementById('msg');
    var result = /** @type {HTMLElement} */ (document.getElementById('result'));
    var seg = document.getElementById('kindSeg');

    function kind() {
        var r = /** @type {HTMLInputElement | null} */ (document.querySelector('input[name="kind"]:checked'));
        return r ? r.value : canLot ? 'LOT' : 'EQUIP';
    }

    function syncLabel() {
        label.textContent = kind() === 'LOT' ? hb.t('js.m.find.lotNo') : hb.t('js.m.find.equipCd');
    }

    /** @param {string} title @param {Node} right */
    function head(title, right) {
        var h = hb.el('div', 'm-card-head');
        h.appendChild(hb.el('span', 'm-card-title', title));
        h.appendChild(right);
        return h;
    }

    /** @param {MLot} l */
    function lotBadge(l) {
        return l.state === 'HELD' ? hb.status('danger', hb.t('js.prdLot.held'))
            : l.state === 'EMPTY' ? hb.status('neutral', hb.t('js.prdLot.empty')) : hb.status('normal', hb.t('js.prdLot.active'));
    }

    /** @param {string} lotNo */
    function findLot(lotNo) {
        return hb.api('/api/production/lots/by-no?lotNo=' + encodeURIComponent(lotNo)).then(function (res) {
            return hb.api('/api/production/lots/' + encodeURIComponent(String(/** @type {MLot} */ (res.data).lotId)));
        }).then(function (res) {
            var d = /** @type {{lot: MLot, parents: MLotEdge[], children: MLotEdge[], holds: MLotHold[]}} */ (res.data);
            var l = d.lot;
            var origins = /** @type {Record<string, string>} */ ({RECEIVED: hb.t('js.prdLot.o.RECEIVED'), PRODUCED: hb.t('js.prdLot.o.PRODUCED'),
                SPLIT: hb.t('js.prdLot.o.SPLIT'), MERGE: hb.t('js.prdLot.o.MERGE'), RELABEL: hb.t('js.prdLot.o.RELABEL')});
            var box = hb.el('div', 'm-card');
            box.appendChild(head(l.lotNo, lotBadge(l)));
            box.appendChild(hbM.kv([
                [hb.t('js.prdLot.f.item'), l.itemCd + ' ' + l.itemNm],
                [hb.t('js.prdLot.f.qty'), l.balance + ' / ' + l.qtyCreated + ' ' + l.unitCd],
                [hb.t('js.prdLot.f.origin'), hb.fmt.label(origins, l.origin)],
                [hb.t('js.prdLot.f.source'), l.woNo || [l.supplierNm, l.supplierLotNo].filter(Boolean).join(' ')],
                [hb.t('js.m.find.createdAt'), hb.fmt.datetime(l.occurredAt)]
            ]));
            result.appendChild(box);

            if (d.holds && d.holds.length) {
                result.appendChild(hb.el('h2', null, hb.t('js.m.find.holds', d.holds.length)));
                var hl = hb.el('ul', 'm-list');
                d.holds.forEach(function (h) {
                    hl.appendChild(hbM.card({title: h.reason || h.source, tone: 'alert', meta: [h.source + ' · ' + hb.fmt.datetime(h.heldAt)]}));
                });
                result.appendChild(hl);
            }
            edges(hb.t('js.m.find.parents'), d.parents, true);
            edges(hb.t('js.m.find.children'), d.children, false);
        });
    }

    /** 바로 위·아래 계보 요약 — 되돌린 행(역분개와 그 원래 행)은 뺀다, 앞 5줄 @param {string} title @param {MLotEdge[]} list @param {boolean} up */
    function edges(title, list, up) {
        var rel = /** @type {Record<string, string>} */ ({CONSUME: hb.t('js.prdLot.r.CONSUME'), SPLIT: hb.t('js.prdLot.r.SPLIT'),
            MERGE: hb.t('js.prdLot.r.MERGE'), RELABEL: hb.t('js.prdLot.r.RELABEL'), REWORK: hb.t('js.prdLot.r.REWORK'),
            REPAIR: hb.t('js.prdLot.r.REPAIR'), SORT: hb.t('js.prdLot.r.SORT')});
        var live = (list || []).filter(function (e) { return !e.reversed && !e.reversalOfId; });
        result.appendChild(hb.el('h2', null, title + ' ' + live.length));
        var ul = hb.el('ul', 'm-list');
        if (!live.length) {
            ul.appendChild(hbM.empty(up ? hb.t('js.prdLot.noBack') : hb.t('js.prdLot.noFwd')));
        }
        live.slice(0, 5).forEach(function (e) {
            ul.appendChild(hbM.card({title: up ? e.parentLotNo : e.childLotNo, meta: [hb.fmt.label(rel, e.relType) + ' · ' + e.qty + ' ' + e.unitCd]}));
        });
        if (live.length > 5) { ul.appendChild(hbM.empty(hb.t('js.m.find.more', live.length - 5))); }
        result.appendChild(ul);
    }

    /** @param {string} code */
    function findEquip(code) {
        var want = code.toUpperCase();
        return Promise.all([hb.api('/api/cms/state/plants'), hb.codeNames('STATE_CAT')]).then(function (r) {
            var plants = /** @type {MPlant[]} */ (r[0].data || []);
            var cats = r[1];
            /** @param {number} i @return {Promise<boolean>} */
            function next(i) {
                if (i >= plants.length) { return Promise.resolve(false); }
                var p = plants[i];
                return hb.api('/api/cms/state?plantCd=' + encodeURIComponent(p.plantCd)).then(function (res) {
                    var hit = hbM.equipNow(res.data).filter(function (x) { return x.equip.equipCd.toUpperCase() === want; })[0];
                    if (!hit) { return next(i + 1); }
                    var cur = hit.cur;
                    var box = hb.el('div', 'm-card');
                    box.appendChild(head(hit.equip.equipCd + ' ' + hit.equip.equipNm,
                        cur ? hb.status(hbM.equipTone(cur.stateCat), hb.fmt.label(cats, cur.stateCat)) : hb.status('neutral', hb.t('js.m.equip.noLog'))));
                    box.appendChild(hbM.kv([
                        [hb.t('js.m.equip.plant'), p.plantNm + ' (' + p.plantCd + ')'],
                        [hb.t('js.m.equip.node'), hit.equip.nodeCd],
                        [hb.t('js.m.equip.since'), cur ? hb.fmt.datetime(cur.startAt, res.data.zone) : null],
                        [hb.t('js.m.equip.reason'), cur ? cur.reasonNm : null],
                        [hb.t('js.m.equip.wo'), cur ? cur.woNo : null]
                    ]));
                    result.appendChild(box);
                    return true;
                });
            }
            return next(0);
        }).then(function (found) {
            if (!found) { hbM.say(msg, hb.t('js.m.find.noEquip'), true); }
        });
    }

    /** @param {string} raw */
    function find(raw) {
        var v = hb.scan ? hb.scan.clean(raw) : raw.trim();
        var k = kind();
        if (/^LOT:/i.test(v)) {
            v = v.slice(4);
            if (canLot) { k = 'LOT'; }
        }
        hbM.say(msg, '');
        hbM.clear(result);
        if (!v) { hbM.say(msg, hb.t('js.m.find.empty'), true); return Promise.resolve(); }
        if (!hbM.online()) { hbM.say(msg, hb.t('js.m.offlineRead'), true); return Promise.resolve(); }
        input.value = v;
        return hb.latest('m-find', k === 'LOT' ? findLot(v) : findEquip(v)).then(null, function (err) {
            if (err.stale) { return; }
            hbM.clear(result);
            hbM.say(msg, err.status === 404 ? (k === 'LOT' ? hb.t('js.m.find.noLot') : hb.t('js.m.find.noEquip')) : err.message, true);
        });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        hb.busy(btn, function () { return find(input.value); });
    });
    if (seg) { seg.addEventListener('change', function () { syncLabel(); hbM.clear(result); hbM.say(msg, ''); input.focus(); }); }

    hbM.ready().then(function () {
        syncLabel();
        // 입력 칸 밖에 포커스가 있을 때의 빠른 키 입력(웨지)·APK 스캐너 이벤트 — 입력 칸 Enter 는 폼 submit 이 받는다
        if (hb.scan) { hb.scan.listen(function (r) { find(r.value); }); }
    });
})();
