// @ts-check
/*
 * 모바일 현황 조회 (/m/status, docs/74 화면 5) — 읽기 전용, 기존 API 만.
 * - 오늘 작업지시: /api/production/wos?from=오늘&to=오늘 (계획 시작이 오늘인 지시) — 상태별 건수 + 카드. 카드를 누르면 /{id} 의 공정 진행(끝난 공정 / 전체)
 * - 설비 상태: /api/cms/state/plants → 고른 공장의 /api/cms/state?plantCd= (그 공장 시간대 오늘) — 상태 분류별 대수 + 설비 카드(지금 구간)
 * 조각은 권한이 있을 때만 템플릿에 있다. "오늘" 은 서버가 준 날짜(hbM.today). 서버 글자는 textContent 로만.
 */
/**
 * @typedef {{woId: number, woNo: string, plantCd: string, itemCd: string, itemNm: string, planQty: string, unitCd: string, status: string,
 *            priority: number, procCnt: number}} MWo
 * @typedef {{opSeq: number, procNm: string, status: string, equipCd: string | null}} MWoProc
 */
(function () {
    'use strict';

    var msg = document.getElementById('msg');
    var woSum = document.getElementById('woSum');
    var woList = document.getElementById('woList');
    var equipSum = document.getElementById('equipSum');
    var equipList = document.getElementById('equipList');
    var plantBox = document.getElementById('plantBox');
    var plantSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('plantSel'));
    var refreshBtn = /** @type {HTMLButtonElement} */ (document.getElementById('refreshBtn'));
    var plantsLoaded = false;

    /** @param {HTMLElement} host @param {Array<[string, number]>} pairs */
    function chips(host, pairs) {
        hbM.clear(host);
        pairs.forEach(function (p) {
            var li = hb.el('li', null, p[0]);
            li.appendChild(hb.el('b', null, String(p[1])));
            host.appendChild(li);
        });
    }

    /** @param {string} status @return {HB.StatusTone} */
    function woTone(status) {
        return status === 'IN_PROGRESS' ? 'info' : status === 'DONE' || status === 'CLOSED' ? 'success' : status === 'CANCELLED' ? 'neutral' : 'normal';
    }

    /** 공정 진행 — 카드 아래에 한 번만 붙인다 @param {MWo} w @param {HTMLLIElement} li */
    function showProcs(w, li) {
        var box = li.firstElementChild;
        if (!box || box.getAttribute('data-open') === '1') { return; }
        var host = box;
        host.setAttribute('data-open', '1');
        Promise.all([hb.api('/api/production/wos/' + encodeURIComponent(String(w.woId))), hb.codeNames('WO_PROC_STATUS')]).then(function (r) {
            var procs = /** @type {MWoProc[]} */ (r[0].data.procs || []);
            var names = r[1];
            var done = procs.filter(function (p) { return p.status === 'DONE'; }).length;
            host.appendChild(hb.el('div', 'm-card-meta', hb.t('js.m.status.procs', done, procs.length)));
            var ul = hb.el('ul');
            procs.forEach(function (p) {
                ul.appendChild(hb.el('li', null, p.opSeq + '. ' + p.procNm + ' — ' + hb.fmt.label(names, p.status) + (p.equipCd ? ' · ' + p.equipCd : '')));
            });
            host.appendChild(ul);
        }, function (err) {
            host.removeAttribute('data-open');
            hbM.say(msg, err.message, true);
        });
    }

    function loadWos() {
        if (!woSum || !woList) { return Promise.resolve(); }
        var sum = woSum;
        var list = woList;
        var day = encodeURIComponent(hbM.today);
        return Promise.all([hb.api('/api/production/wos?from=' + day + '&to=' + day), hb.codeNames('WO_STATUS')]).then(function (r) {
            var data = /** @type {{rows: MWo[], more: boolean}} */ (r[0].data || {rows: [], more: false});
            var names = r[1];
            /** @type {Record<string, number>} */
            var counts = {};
            data.rows.forEach(function (w) { counts[w.status] = (counts[w.status] || 0) + 1; });
            chips(sum, Object.keys(counts).map(function (s) { return /** @type {[string, number]} */ ([hb.fmt.label(names, s), counts[s]]); }));
            hbM.clear(list);
            if (!data.rows.length) { list.appendChild(hbM.empty(hb.t('js.m.status.noWo'))); return; }
            data.rows.forEach(function (w) {
                var li = hbM.card({title: w.woNo, right: hb.status(woTone(w.status), hb.fmt.label(names, w.status)),
                    meta: [w.itemCd + ' ' + w.itemNm, hb.t('js.m.status.plan', w.planQty, w.unitCd, w.plantCd)],
                    onClick: function () { showProcs(w, li); }});
                list.appendChild(li);
            });
            if (data.more) { list.appendChild(hbM.empty(hb.t('js.m.status.more'))); }
        });
    }

    function loadPlants() {
        if (!plantSel || plantsLoaded) { return Promise.resolve(); }
        var sel = plantSel;
        return hb.api('/api/cms/state/plants').then(function (res) {
            var plants = /** @type {MPlant[]} */ (res.data || []);
            sel.textContent = '';
            plants.forEach(function (p) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, p.plantNm + ' (' + p.plantCd + ')'));
                o.value = p.plantCd;
                sel.appendChild(o);
            });
            if (plantBox) { plantBox.hidden = plants.length < 2; }   // 하나면 고를 것이 없다
            plantsLoaded = true;
        });
    }

    function loadEquips() {
        if (!equipSum || !equipList || !plantSel) { return Promise.resolve(); }
        var sum = equipSum;
        var list = equipList;
        var sel = plantSel;
        return loadPlants().then(function () {
            hbM.clear(sum);
            hbM.clear(list);
            if (!sel.value) { list.appendChild(hbM.empty(hb.t('js.m.status.noEquip'))); return; }
            return Promise.all([hb.latest('m-status-equip', hb.api('/api/cms/state?plantCd=' + encodeURIComponent(sel.value))),
                hb.codeNames('STATE_CAT')]).then(function (r) {
                var now = hbM.equipNow(r[0].data);
                var names = r[1];
                var zone = r[0].data.zone;
                /** @type {Record<string, number>} */
                var counts = {};
                now.forEach(function (x) {
                    var k = x.cur ? x.cur.stateCat : '';
                    counts[k] = (counts[k] || 0) + 1;
                });
                chips(sum, Object.keys(counts).map(function (k) {
                    return /** @type {[string, number]} */ ([k ? hb.fmt.label(names, k) : hb.t('js.m.equip.noLog'), counts[k]]);
                }));
                if (!now.length) { list.appendChild(hbM.empty(hb.t('js.m.status.noEquip'))); return; }
                now.forEach(function (x) {
                    var cur = x.cur;
                    list.appendChild(hbM.card({title: x.equip.equipCd + ' ' + x.equip.equipNm,
                        right: cur ? hb.status(hbM.equipTone(cur.stateCat), hb.fmt.label(names, cur.stateCat)) : hb.status('neutral', hb.t('js.m.equip.noLog')),
                        tone: cur && cur.stateCat === 'UDT' ? 'alert' : null,
                        meta: cur ? [hb.t('js.m.status.since', hb.fmt.datetime(cur.startAt, zone)) + (cur.reasonNm ? ' · ' + cur.reasonNm : ''), cur.woNo] : []}));
                });
            });
        });
    }

    function load() {
        hbM.say(msg, '');
        return Promise.all([loadWos(), loadEquips()]).then(function () { /* 그렸다 */ }, function (err) {
            if (!err.stale) { hbM.say(msg, hbM.online() ? err.message : hb.t('js.m.offlineRead'), true); }
        });
    }

    refreshBtn.addEventListener('click', function () { hb.busy(refreshBtn, load); });
    if (plantSel) {
        plantSel.addEventListener('change', function () {
            loadEquips().then(null, function (err) { if (!err.stale) { hbM.say(msg, err.message, true); } });
        });
    }
    hbM.ready().then(function () {
        var first = true;
        hbM.onNet(function (on) {
            if (first) { first = false; load(); return; }
            if (on) { load(); } else { hbM.say(msg, hb.t('js.m.offlineRead'), true); }
        });
    });
})();
