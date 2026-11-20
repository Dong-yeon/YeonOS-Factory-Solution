// @ts-check
/* 설비 상태 타임라인 (cms-state) — MES P2 6단계 (docs/25 4.8). 공장·날짜 → 설비별 하루 막대 + 구간 표 + 연결 로그.
   막대 위치·폭은 CSSOM(el.style)으로 — 인라인 style 속성이 아니라 CSP 가 막지 않는다. 색만으로 구분하지 않게 범례·표에 글자. */
/**
 * @typedef {{equipId: number, equipCd: string, equipNm: string, nodeCd?: string | null}} StEquip
 * @typedef {{stateLogId: number, equipId: number, stateCat: string, reasonCd?: string | null, reasonNm?: string | null,
 *   plannedYn?: string | null, startAt: string, endAt?: string | null, woNo?: string | null, source: string,
 *   packState?: string | null}} StInterval
 * @typedef {{connLogId: number, equipId?: number | null, equipCd?: string | null, terminalCd: string, conn: string,
 *   seqFrom?: number | null, seqTo?: number | null, occurredAt: string}} StConn
 * @typedef {{plantCd: string, zone: string, day: string, from: string, to: string, equips: StEquip[], intervals: StInterval[],
 *   conns: StConn[]}} StTimeline
 */
(function () {
    'use strict';

    var API = '/api/cms/state';
    var CATS = ['PRD', 'SBY', 'ENG', 'SDT', 'UDT', 'NST', 'UNKNOWN'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var bars = /** @type {HTMLElement} */ (document.getElementById('bars'));
    var legend = /** @type {HTMLElement} */ (document.getElementById('legend'));
    var dayInfo = /** @type {HTMLElement} */ (document.getElementById('dayInfo'));
    var nowSum = /** @type {HTMLElement} */ (document.getElementById('nowSum'));
    var equipFilter = /** @type {HTMLSelectElement} */ (document.getElementById('equipFilter'));
    var intBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#intervals tbody'));
    var connBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#conns tbody'));
    /** @type {Record<string, string>} */
    var catNames = {};
    /** @type {Record<string, string>} */
    var connNames = {};
    /** @type {StTimeline | null} */
    var data = null;
    /** @type {HB.SearchPanel} */
    var sp;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} cat */
    function catName(cat) { return catNames[cat] || cat; }

    /** 'HH:mm' (공장 시간대) @param {string} iso @param {string} zone */
    function hm(iso, zone) { return hb.fmt.datetime(iso, zone).substring(11); }

    /** @param {HTMLElement} tbody @param {number} cols @param {string} text */
    function emptyRow(tbody, cols, text) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', text));
        td.colSpan = cols;
        tr.appendChild(td);
        tbody.appendChild(tr);
    }

    function drawLegend() {
        legend.textContent = '';
        CATS.forEach(function (c) {
            var li = hb.el('li', 'hb-st-legend-item');
            li.appendChild(hb.el('span', 'hb-st-swatch hb-st-' + c.toLowerCase()));
            li.appendChild(hb.el('span', null, c + ' ' + catName(c)));
            legend.appendChild(li);
        });
    }

    /**
     * 하루 [from, to) 안으로 자른 구간(ms). 열린 구간은 지금(또는 하루 끝)까지
     * @param {StInterval} iv @param {number} from @param {number} to @param {number} now
     */
    function clip(iv, from, to, now) {
        var s = Math.max(new Date(iv.startAt).getTime(), from);
        var e = Math.min(iv.endAt ? new Date(iv.endAt).getTime() : Math.min(now, to), to);
        return e > s ? {s: s, e: e} : null;
    }

    /** @param {StTimeline} t */
    function drawBars(t) {
        bars.textContent = '';
        var from = new Date(t.from).getTime();
        var to = new Date(t.to).getTime();
        var span = to - from;
        var now = Date.now();
        // 눈금 0·6·12·18·24시
        var axis = hb.el('div', 'hb-st-row hb-st-axis');
        axis.appendChild(hb.el('div', 'hb-st-label'));
        var ticks = hb.el('div', 'hb-st-track hb-st-ticks');
        [0, 6, 12, 18, 24].forEach(function (h) {
            var tick = hb.el('span', 'hb-st-tick', String(h).padStart(2, '0') + ':00');
            tick.style.left = (h / 24 * 100) + '%';
            ticks.appendChild(tick);
        });
        axis.appendChild(ticks);
        bars.appendChild(axis);
        if (t.equips.length && !t.intervals.length) {
            bars.appendChild(hb.el('p', 'hb-muted hb-st-empty', hb.t('js.cmsState.noDataDay')));
        }
        if (!t.equips.length) {
            bars.appendChild(hb.el('p', 'hb-muted', hb.t('js.cmsState.noEquip')));
            return;
        }
        t.equips.forEach(function (eq) {
            var row = hb.el('div', 'hb-st-row');
            var label = hb.el('div', 'hb-st-label');
            label.appendChild(hb.el('strong', null, eq.equipCd));
            label.appendChild(hb.el('span', 'hb-muted', eq.equipNm + (eq.nodeCd ? ' · ' + eq.nodeCd : '')));
            /** @type {Record<string, number>} */
            var total = {};
            var track = hb.el('div', 'hb-st-track');
            track.setAttribute('role', 'img');
            t.intervals.filter(function (iv) { return iv.equipId === eq.equipId; }).forEach(function (iv) {
                var c = clip(iv, from, to, now);
                if (!c) { return; }
                total[iv.stateCat] = (total[iv.stateCat] || 0) + (c.e - c.s);
                var seg = hb.el('span', 'hb-st-seg hb-st-' + iv.stateCat.toLowerCase());
                seg.style.left = ((c.s - from) / span * 100) + '%';
                seg.style.width = Math.max((c.e - c.s) / span * 100, 0.15) + '%';
                seg.title = hb.t('js.cmsState.segTitle', catName(iv.stateCat), hm(iv.startAt, t.zone),
                    iv.endAt ? hm(iv.endAt, t.zone) : hb.t('js.cmsState.open'), iv.reasonNm || (iv.packState ? 'PackML ' + iv.packState : ''));
                track.appendChild(seg);
            });
            var parts = CATS.filter(function (c) { return total[c]; }).map(function (c) {
                return c + ' ' + Math.round(total[c] / 60000) + hb.t('js.cmsState.min');
            });
            // 하루 전체가 비면 위에 한 번만 알리고 행마다 반복하지 않는다(docs/40 D14)
            var summary = parts.length ? parts.join(' · ') : (t.intervals.length ? hb.t('js.cmsState.noData') : '');
            track.setAttribute('aria-label', eq.equipCd + ': ' + summary);
            label.appendChild(hb.el('span', 'hb-st-sum', summary));
            row.appendChild(label);
            row.appendChild(track);
            bars.appendChild(row);
        });
    }

    /**
     * 지금(지난 날짜면 하루 끝 직전) 설비마다 걸친 구간의 분류를 세어 한 줄로 — docs/40 D13.
     * 걸친 구간이 없는 설비는 "기록 없음"(통신 끊김 UNKNOWN 과 다르다 — 그건 구간이 있다)
     * @param {StTimeline} t
     */
    function drawNow(t) {
        nowSum.textContent = '';
        var from = new Date(t.from).getTime();
        var to = new Date(t.to).getTime();
        var now = Date.now();
        var today = now >= from && now < to;
        var at = today ? now : to - 1;
        /** @type {Record<string, number>} */
        var count = {};
        var none = 0;
        t.equips.forEach(function (eq) {
            var hit = t.intervals.filter(function (iv) {
                if (iv.equipId !== eq.equipId) { return false; }
                var s = new Date(iv.startAt).getTime();
                var e = iv.endAt ? new Date(iv.endAt).getTime() : Infinity;
                return s <= at && at < e;
            }).pop();
            if (hit) { count[hit.stateCat] = (count[hit.stateCat] || 0) + 1; } else { none++; }
        });
        nowSum.hidden = !t.equips.length;
        if (!t.equips.length) { return; }
        nowSum.appendChild(hb.el('strong', 'hb-st-now-title',
            today ? hb.t('js.cmsState.nowTitle', hm(new Date(now).toISOString(), t.zone)) : hb.t('js.cmsState.endTitle')));
        CATS.filter(function (c) { return count[c]; }).forEach(function (c) {
            var chip = hb.el('span', 'hb-st-now-item');
            chip.appendChild(hb.el('span', 'hb-st-swatch hb-st-' + c.toLowerCase()));
            chip.appendChild(hb.el('span', null, c + ' ' + catName(c)));
            chip.appendChild(hb.el('b', 'hb-st-now-n', hb.t('js.cmsState.units', count[c])));
            nowSum.appendChild(chip);
        });
        if (none) {
            var chip = hb.el('span', 'hb-st-now-item hb-muted');
            chip.appendChild(hb.el('span', null, hb.t('js.cmsState.noRecord')));
            chip.appendChild(hb.el('b', 'hb-st-now-n', hb.t('js.cmsState.units', none)));
            nowSum.appendChild(chip);
        }
    }

    /** @param {StTimeline} t */
    function drawIntervals(t) {
        intBody.textContent = '';
        var only = equipFilter.value ? Number(equipFilter.value) : null;
        /** @type {Record<number, string>} */
        var cd = {};
        t.equips.forEach(function (e) { cd[e.equipId] = e.equipCd; });
        var rows = t.intervals.filter(function (iv) { return only === null || iv.equipId === only; });
        /** @type {HTMLElement} */ (document.getElementById('intCount')).textContent = hb.t('js.cmsState.count', rows.length);
        if (!rows.length) {
            emptyRow(intBody, 7, hb.t('js.cmsState.noData'));
            return;
        }
        var now = Date.now();
        rows.forEach(function (iv) {
            var tr = hb.el('tr');
            var cat = hb.el('td');
            cat.appendChild(hb.el('span', 'hb-st-swatch hb-st-' + iv.stateCat.toLowerCase()));
            cat.appendChild(document.createTextNode(' ' + iv.stateCat + ' ' + catName(iv.stateCat)));
            var end = iv.endAt ? new Date(iv.endAt).getTime() : now;
            var minutes = Math.max(0, Math.round((end - new Date(iv.startAt).getTime()) / 60000));
            tr.appendChild(hb.el('td', null, cd[iv.equipId] || String(iv.equipId)));
            tr.appendChild(cat);
            tr.appendChild(hb.el('td', 'hb-wrap', iv.reasonNm ? iv.reasonCd + ' · ' + iv.reasonNm
                + (iv.plannedYn === 'Y' ? ' (' + hb.t('js.cmsState.planned') + ')' : '') : iv.packState ? 'PackML ' + iv.packState : ''));
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(iv.startAt, t.zone)));
            tr.appendChild(hb.el('td', null, iv.endAt ? hb.fmt.datetime(iv.endAt, t.zone) : hb.t('js.cmsState.open')));
            tr.appendChild(hb.el('td', 'hb-num', String(minutes)));
            tr.appendChild(hb.el('td', null, iv.woNo || ''));
            intBody.appendChild(tr);
        });
    }

    /** @param {StTimeline} t */
    function drawConns(t) {
        connBody.textContent = '';
        /** @type {HTMLElement} */ (document.getElementById('connCount')).textContent = hb.t('js.cmsState.count', t.conns.length);
        if (!t.conns.length) {
            emptyRow(connBody, 5, hb.t('js.cmsState.noConn'));
            return;
        }
        t.conns.forEach(function (c) {
            var tr = hb.el('tr', c.conn === 'ONLINE' ? null : 'hb-st-conn-warn');
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(c.occurredAt, t.zone)));
            tr.appendChild(hb.el('td', null, c.terminalCd));
            tr.appendChild(hb.el('td', null, c.equipCd || hb.t('js.cmsState.wholeDevice')));
            tr.appendChild(hb.el('td', null, connNames[c.conn] || c.conn));
            tr.appendChild(hb.el('td', null, c.conn === 'GAP' ? c.seqFrom + ' ~ ' + c.seqTo : ''));
            connBody.appendChild(tr);
        });
    }

    /** @param {StTimeline} t */
    function fillEquipFilter(t) {
        var keep = equipFilter.value;
        while (equipFilter.options.length > 1) { equipFilter.remove(1); }
        t.equips.forEach(function (e) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, e.equipCd + ' · ' + e.equipNm));
            o.value = String(e.equipId);
            equipFilter.appendChild(o);
        });
        equipFilter.value = keep;
        if (equipFilter.value !== keep) { equipFilter.value = ''; }
    }

    function load() {
        var v = sp.values();
        notify('');
        var q = '?plantCd=' + encodeURIComponent(v.plantCd || '') + (v.date ? '&date=' + encodeURIComponent(v.date) : '');
        return hb.latest('timeline', hb.api(API + q)).then(function (res) {
            var t = /** @type {StTimeline} */ (res.data);
            data = t;
            if (!v.date) { sp.setValue('date', t.day); }
            dayInfo.textContent = hb.t('js.cmsState.day', t.day, t.zone);
            fillEquipFilter(t);
            drawNow(t);
            drawBars(t);
            drawIntervals(t);
            drawConns(t);
        }, function (/** @type {HB.ApiError} */ err) {
            if (err && err.stale) { return; }
            notify(err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message, true);
        });
    }

    equipFilter.addEventListener('change', function () { if (data) { drawIntervals(data); } });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codeList('STATE_CAT'), hb.codeList('CONN_EVENT')]);
    }).then(function (r) {
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, p.plantCd + ' · ' + p.plantNm));
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        r[2].forEach(function (c) { catNames[c.code] = c.name; });
        r[3].forEach(function (c) { connNames[c.code] = c.name; });
        drawLegend();
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return sp.ready;
    }).then(function () {
        if (!plantSel.value) {
            notify(hb.t('js.cmsState.noPlant'), true);
            return;
        }
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
