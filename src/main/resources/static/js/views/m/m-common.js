// @ts-check
/*
 * 모바일 웹앱 /m 공통 (docs/74) — hb.js 위에 얹는다. m/layout 조각이 화면 JS 앞에 싣는다.
 * - hbM.ready()       문구·세션(회사 시간대)을 받은 뒤 화면을 그린다
 * - hbM.onNet(fn)     연결 상태가 바뀔 때·처음 한 번 fn(online) — 위 띠 "오프라인" 표시는 여기서 맡는다.
 *                     오프라인 대기열은 없다(docs/45 A-3): 읽기는 안내만, 저장 단추는 화면이 끈다
 * - hbM.say(el, text, error)   상태 줄(textContent)
 * - hbM.card / hbM.empty / hbM.kv   카드·빈 줄·이름표 값 — 서버 글자는 textContent 로만(hb.el)
 * - hbM.key(prefix)   누른 순간 만드는 멱등 키(X-Idempotency-Key)
 * - hbM.today         서버가 준 회사 시간대 오늘(body data-today) — 기기 시계로 날짜를 만들지 않는다
 */
/**
 * @typedef {{equipId: number, equipCd: string, equipNm: string, nodeCd: string | null}} MEquip
 * @typedef {{equipId: number, stateCat: string, reasonNm: string | null, startAt: string, endAt: string | null, woNo: string | null}} MInterval
 * @typedef {{plantCd: string, plantNm: string}} MPlant
 */
var hbM = (function () {
    'use strict';

    var netEl = document.getElementById('mNet');
    /** @type {Array<(online: boolean) => void>} */
    var netFns = [];

    function online() { return navigator.onLine !== false; }

    function syncNet() {
        var on = online();
        if (netEl) { netEl.hidden = on; }
        netFns.forEach(function (fn) { fn(on); });
    }
    window.addEventListener('online', syncNet);
    window.addEventListener('offline', syncNet);
    syncNet();

    /** @param {(online: boolean) => void} fn */
    function onNet(fn) {
        netFns.push(fn);
        fn(online());
    }

    function ready() {
        return Promise.all([hb.loadMessages(), hb.loadSession()]);
    }

    /** @param {HTMLElement | null} el @param {string | null | undefined} text @param {boolean} [error] */
    function say(el, text, error) {
        if (!el) { return; }
        el.textContent = text || '';
        if (error) { el.setAttribute('data-tone', 'error'); } else { el.removeAttribute('data-tone'); }
    }

    /** @param {HTMLElement} host */
    function clear(host) { host.textContent = ''; }

    /**
     * 카드 한 장(li) — 제목 줄(왼쪽 제목, 오른쪽 배지·숫자) + 설명 줄들. onClick 이 있으면 카드 전체가 누를 곳(button)
     * @param {{title: string, right?: Node | null, meta?: Array<string | null | undefined>, tone?: string | null, onClick?: (() => void) | null}} o
     * @return {HTMLLIElement}
     */
    function card(o) {
        var li = /** @type {HTMLLIElement} */ (hb.el('li'));
        var box = o.onClick ? hb.el('button', 'm-card') : hb.el('div', 'm-card');
        if (o.onClick) {
            /** @type {HTMLButtonElement} */ (box).type = 'button';
            box.addEventListener('click', o.onClick);
        }
        if (o.tone) { box.setAttribute('data-tone', o.tone); }
        var head = hb.el('div', 'm-card-head');
        head.appendChild(hb.el('span', 'm-card-title', o.title));
        if (o.right) { head.appendChild(o.right); }
        box.appendChild(head);
        (o.meta || []).forEach(function (m) {
            if (m) { box.appendChild(hb.el('div', 'm-card-meta', m)); }
        });
        li.appendChild(box);
        return li;
    }

    /** @param {string} text @return {HTMLLIElement} */
    function empty(text) {
        return /** @type {HTMLLIElement} */ (hb.el('li', 'm-empty', text));
    }

    /**
     * 이름표 — 값 목록(dl.m-kv). 값이 비면 그 줄은 뺀다
     * @param {Array<[string, unknown]>} rows @return {HTMLElement}
     */
    function kv(rows) {
        var dl = hb.el('dl', 'm-kv');
        rows.forEach(function (r) {
            var v = r[1];
            if (v === null || v === undefined || v === '') { return; }
            dl.appendChild(hb.el('dt', null, r[0]));
            var dd = hb.el('dd');
            if (v instanceof Node) { dd.appendChild(v); } else { dd.textContent = String(v); }
            dl.appendChild(dd);
        });
        return dl;
    }

    /** 사무 홈 카드 톤(NEUTRAL·INFO·WARN·ALERT) → 카드 왼쪽 줄 톤 @param {string | null | undefined} t */
    function tone(t) {
        return t === 'ALERT' ? 'alert' : t === 'WARN' ? 'warn' : null;
    }

    /** @param {string} prefix */
    function key(prefix) {
        return prefix + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    }

    /**
     * select 를 선택지로 채운다 — 첫 줄은 빈 값(고르세요)
     * @param {HTMLSelectElement} sel @param {Array<{value: string, text: string}>} items @param {string} blank
     */
    function options(sel, items, blank) {
        sel.textContent = '';
        var first = /** @type {HTMLOptionElement} */ (hb.el('option', null, blank));
        first.value = '';
        sel.appendChild(first);
        items.forEach(function (i) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, i.text));
            o.value = i.value;
            sel.appendChild(o);
        });
    }

    /**
     * 설비별 "지금 상태"(docs/74 M9) — 오늘 타임라인(/api/cms/state)에서 설비마다 마지막 구간(끝나지 않은 구간 우선, 없으면 가장 늦게 시작한 구간).
     * 오늘 구간이 없는 설비는 cur = null
     * @param {{equips: MEquip[], intervals: MInterval[]}} timeline
     * @return {Array<{equip: MEquip, cur: MInterval | null}>}
     */
    function equipNow(timeline) {
        /** @type {Record<string, MInterval>} */
        var last = {};
        (timeline.intervals || []).forEach(function (i) {
            var k = String(i.equipId);
            var p = last[k];
            if (!p || (!i.endAt && p.endAt) || (!!i.endAt === !!p.endAt && i.startAt > p.startAt)) { last[k] = i; }
        });
        return (timeline.equips || []).map(function (e) { return {equip: e, cur: last[String(e.equipId)] || null}; });
    }

    /** 설비 상태 분류 → 배지 톤: 비계획 정지 = 위험, 통신 끊김 = 경고, 생산 = 정상, 그 밖 = 중립(색만으로 구분하지 않는다 — 글자 함께) @param {string | null} cat @return {HB.StatusTone} */
    function equipTone(cat) {
        return cat === 'UDT' ? 'danger' : cat === 'UNKNOWN' ? 'warning' : cat === 'PRD' ? 'normal' : 'neutral';
    }

    // PWA(docs/74 M5) — 정적 자원만 캐시하는 기존 워커. 실패해도 화면은 그대로
    if (hb.pwa) { hb.pwa.register(); }

    return {
        ready: ready, onNet: onNet, online: online, say: say, clear: clear, card: card, empty: empty, kv: kv, tone: tone, key: key,
        options: options, equipNow: equipNow, equipTone: equipTone, today: document.body.getAttribute('data-today') || ''
    };
})();
