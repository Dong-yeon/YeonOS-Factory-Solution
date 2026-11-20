// @ts-check
/*
 * 모바일 홈 (/m, docs/74 화면 1) — 결재 대기 수·안 읽은 알림·오늘 확인할 것. 새 API 없음:
 *   결재 = /api/platform/approvals/box?box=TODO, 알림 = /api/platform/inbox(/count), 카드 = /api/home/cards(사무 홈과 같은 계산·권한).
 * 조각은 권한이 있을 때만 템플릿에 있다(없으면 요소가 없어 건너뛴다). 숫자가 있는 카드만 보인다 — 휴대폰은 "할 일"만.
 * 끊기면 위 띠가 알리고, 다시 연결되면 한 번 더 읽는다. 서버 글자는 textContent 로만.
 */
/**
 * @typedef {{title: string, detail: string, tone: string}} MHomeItem
 * @typedef {{code: string, title: string, count: number | null, tone: string, items: MHomeItem[], error: boolean}} MHomeCard
 * @typedef {{notifyId: number, eventNm: string, title: string, createdAt: string}} MHomeNotify
 */
(function () {
    'use strict';

    var msg = document.getElementById('msg');
    var aprCnt = document.getElementById('aprCnt');
    var aprTile = document.getElementById('aprTile');
    var inboxCnt = document.getElementById('inboxCnt');
    var inboxTile = document.getElementById('inboxTile');
    var cardsEl = document.getElementById('cards');
    var inboxEl = document.getElementById('inbox');
    var asOf = document.getElementById('asOf');
    var refreshBtn = /** @type {HTMLButtonElement} */ (document.getElementById('refreshBtn'));

    /** @param {HTMLElement | null} tile @param {HTMLElement | null} num @param {number} n */
    function setTile(tile, num, n) {
        if (num) { num.textContent = String(n); }
        if (tile) {
            if (n > 0) { tile.setAttribute('data-tone', 'warn'); } else { tile.removeAttribute('data-tone'); }
        }
    }

    function loadApprovals() {
        if (!aprCnt) { return Promise.resolve(); }
        return hb.api('/api/platform/approvals/box?box=TODO').then(function (res) {
            setTile(aprTile, aprCnt, (res.list || []).length);
        });
    }

    function loadInbox() {
        if (!inboxEl) { return Promise.resolve(); }
        var host = inboxEl;
        return Promise.all([hb.api('/api/platform/inbox/count'), hb.api('/api/platform/inbox?unread=true&size=5')]).then(function (r) {
            var counts = /** @type {Record<string, number>} */ (r[0].data || {});
            setTile(inboxTile, inboxCnt, counts.notify || 0);
            var list = /** @type {MHomeNotify[]} */ (r[1].list || []);
            hbM.clear(host);
            if (!list.length) { host.appendChild(hbM.empty(hb.t('js.m.home.noInbox'))); return; }
            list.forEach(function (n) {
                host.appendChild(hbM.card({title: n.title, meta: [n.eventNm + ' · ' + hb.fmt.datetime(n.createdAt)]}));
            });
        });
    }

    function loadCards() {
        if (!cardsEl) { return Promise.resolve(); }
        var host = cardsEl;
        return hb.api('/api/home/cards').then(function (res) {
            var view = /** @type {{asOf: string, cards: MHomeCard[]}} */ (res.data || {asOf: '', cards: []});
            if (asOf) { asOf.textContent = hb.t('js.home.asOf', hb.fmt.datetime(view.asOf)); }
            hbM.clear(host);
            var todo = (view.cards || []).filter(function (c) { return c.error || (c.count || 0) > 0; });
            if (!todo.length) { host.appendChild(hbM.empty(hb.t('js.m.home.noTodo'))); return; }
            todo.forEach(function (c) {
                var li = hbM.card({title: c.title, tone: hbM.tone(c.tone),
                    right: hb.el('span', 'm-card-count', c.error ? '!' : String(c.count)),
                    meta: c.error ? [hb.t('js.home.error')] : []});
                var items = (c.items || []).slice(0, 3);
                if (items.length && li.firstElementChild) {
                    var ul = hb.el('ul');
                    items.forEach(function (i) { ul.appendChild(hb.el('li', null, i.detail ? i.title + ' — ' + i.detail : i.title)); });
                    li.firstElementChild.appendChild(ul);
                }
                host.appendChild(li);
            });
        });
    }

    function load() {
        hbM.say(msg, '');
        return Promise.all([loadApprovals(), loadInbox(), loadCards()]).then(function () { /* 그렸다 */ }, function (err) {
            hbM.say(msg, hbM.online() ? err.message : hb.t('js.m.offlineRead'), true);
        });
    }

    refreshBtn.addEventListener('click', function () { hb.busy(refreshBtn, load); });
    hbM.ready().then(function () {
        var first = true;
        hbM.onNet(function (on) {
            if (first) { first = false; load(); return; }
            if (on) { load(); } else { hbM.say(msg, hb.t('js.m.offlineRead'), true); }
        });
    });
})();
