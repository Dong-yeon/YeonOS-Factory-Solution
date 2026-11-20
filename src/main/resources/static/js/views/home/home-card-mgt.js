// @ts-check
/*
 * 홈 카드 기본 세트 (home-card-mgt) — 홈화면 3단계 (docs/37 15장). 왼쪽 권한그룹 목록 → 오른쪽 카드 목록(보이기·위아래).
 * - 카드 목록 = 등록된 카드 전체(세트 순서 → 나머지 기본 순서). 숫자는 계산하지 않는다(제목·설명만)
 * - "이 그룹은 화면 권한이 없음" 은 안내일 뿐 — 세트에 넣어 두면 권한을 받았을 때 그 자리에 나온다
 * - 저장 = 한 벌(PUT ?ver=판 지문). 다른 사람이 먼저 저장했으면 409 → 다시 읽기 안내
 * - 서버 글자(그룹 이름·카드 제목)는 textContent 로만(hb.el)
 */
/**
 * @typedef {{authGrpCd: string, authGrpNm: string, sortNo: number, adminYn: string, useYn: string, cardCnt: number}} HomeMgtGroup
 * @typedef {{code: string, title: string, desc: string | null, progCd: string | null, progTitle: string | null,
 *            visible: boolean, hidden: boolean, inSet: boolean}} HomeMgtCard
 * @typedef {{group: HomeMgtGroup, ver: string, cards: HomeMgtCard[]}} HomeMgtDetail
 */
(function () {
    'use strict';

    var API = '/api/home/grp-cards';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var groupsEl = /** @type {HTMLElement} */ (document.getElementById('groups'));
    var cardList = /** @type {HTMLElement} */ (document.getElementById('cardList'));
    var detailTitle = /** @type {HTMLElement} */ (document.getElementById('detailTitle'));
    var setState = /** @type {HTMLElement} */ (document.getElementById('setState'));
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    var resetBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('resetBtn'));
    /** @type {HomeMgtGroup[]} */
    var groups = [];
    /** 고르고 있는 그룹 상세 @type {HomeMgtDetail | null} */
    var current = null;
    /** 화면에서 고친 카드 목록(순서 = 표시 순서) @type {HomeMgtCard[]} */
    var rows = [];

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 고친 세트를 두고 다른 그룹으로 — 화면 안 대화상자(docs/40 V-7) @return {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function setButtons() {
        [saveBtn, resetBtn].forEach(function (b) { if (b) { b.disabled = !current; } });
    }

    function drawGroups() {
        groupsEl.textContent = '';
        if (!groups.length) {
            groupsEl.appendChild(hb.el('li', 'hb-muted', hb.t('js.homeCardMgt.noGroups')));
        }
        groups.forEach(function (g) {
            var li = hb.el('li');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-home-grp'));
            b.type = 'button';
            var on = !!current && current.group.authGrpCd === g.authGrpCd;
            b.setAttribute('aria-pressed', on ? 'true' : 'false');
            b.appendChild(hb.el('span', 'hb-home-grp-name', g.authGrpNm));
            b.appendChild(hb.el('span', 'hb-muted', g.authGrpCd + (g.useYn === 'Y' ? '' : ' · ' + hb.t('js.yn.N'))));
            b.appendChild(hb.el('span', 'hb-home-grp-cnt',
                g.cardCnt ? hb.t('js.homeCardMgt.cardCnt', g.cardCnt) : hb.t('js.homeCardMgt.noSet')));
            b.addEventListener('click', function () {
                // 이미 연 그룹: 좁은 폭에서 서랍을 닫았다가 다시 누르면 서랍만 다시 연다
                if (on) { hb.page.openDetail(); return; }
                confirmLeave().then(function (yes) { if (yes) { openGroup(g.authGrpCd); } });
            });
            li.appendChild(b);
            groupsEl.appendChild(li);
        });
    }

    /** @param {number} focusIdx @param {string} [focusDir] */
    function drawCards(focusIdx, focusDir) {
        cardList.textContent = '';
        rows.forEach(function (r, i) {
            var li = hb.el('li');
            var label = hb.el('label', 'hb-home-set-name');
            var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
            cb.type = 'checkbox';
            cb.checked = !r.hidden;
            cb.disabled = !CAN_SAVE;
            cb.addEventListener('change', function () { r.hidden = !cb.checked; hb.dirty.mark(); });
            label.appendChild(cb);
            label.appendChild(hb.el('span', null, r.title));
            li.appendChild(label);
            var desc = r.desc || '';
            if (!r.visible) {
                // 권한 안내 — 화면 이름만(건수 없음)
                li.classList.add('hb-home-set-off');
                desc = hb.t('js.homeCardMgt.noPerm', r.progTitle || r.progCd || '') + (desc ? ' · ' + desc : '');
            }
            if (desc) { li.appendChild(hb.el('span', 'hb-home-set-desc hb-muted', desc)); }
            if (CAN_SAVE) {
                var tools = hb.el('span', 'hb-home-set-tools');
                [['up', '↑', -1], ['down', '↓', 1]].forEach(function (m) {
                    var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', String(m[1])));
                    b.type = 'button';
                    b.setAttribute('data-dir', String(m[0]));
                    b.setAttribute('aria-label', hb.t(m[0] === 'up' ? 'js.home.moveUp' : 'js.home.moveDown', r.title));
                    var j = i + /** @type {number} */ (m[2]);
                    b.disabled = j < 0 || j >= rows.length;
                    b.addEventListener('click', function () {
                        var tmp = rows[i];
                        rows[i] = rows[j];
                        rows[j] = tmp;
                        hb.dirty.mark();
                        drawCards(j, String(m[0]));
                    });
                    tools.appendChild(b);
                });
                li.appendChild(tools);
            }
            cardList.appendChild(li);
        });
        // 옮긴 뒤 같은 방향 버튼에 포커스를 남긴다(끝에 닿아 꺼졌으면 반대 방향) — 키보드로 계속 옮길 수 있게
        if (focusDir) {
            var row = cardList.children[focusIdx];
            var btn = row && /** @type {HTMLButtonElement | null} */ (row.querySelector('button[data-dir="' + focusDir + '"]:not(:disabled)')
                || row.querySelector('button:not(:disabled)'));
            if (btn) { btn.focus(); }
        }
    }

    /** @param {HomeMgtDetail} d */
    function show(d) {
        current = d;
        rows = d.cards.map(function (c) { return Object.assign({}, c); });
        hb.dirty.clear();
        detailTitle.textContent = hb.t('js.homeCardMgt.editing', d.group.authGrpNm);
        var inSet = d.cards.filter(function (c) { return c.inSet; }).length;
        setState.textContent = inSet ? hb.t('js.homeCardMgt.hasSet', inSet) : hb.t('js.homeCardMgt.noSetGuide');
        drawCards(0);
        drawGroups();
        setButtons();
    }

    /** @param {string} grp */
    function openGroup(grp) {
        return hb.latest('homeCardMgt.detail', hb.api(API + '/' + encodeURIComponent(grp))).then(function (res) {
            show(/** @type {HomeMgtDetail} */ (res.data));
            hb.page.openDetail();
            notify('');
        }, function (/** @type {HB.ApiError} */ err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} [selectGrp] */
    function loadGroups(selectGrp) {
        return hb.api(API).then(function (res) {
            groups = /** @type {HomeMgtGroup[]} */ (res.list || []);
            drawGroups();
            if (selectGrp) { return openGroup(selectGrp); }
        }, function (/** @type {HB.ApiError} */ err) { notify(err.message, true); });
    }

    /** 저장·지우기 결과 처리 — 409 는 다른 사람이 먼저 저장한 것 @param {HB.ApiError} err */
    function failed(err) {
        notify(err.status === 409 ? hb.t('js.homeCardMgt.conflict') : err.message, true);
    }

    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            if (!current) { return; }
            var d = current;
            hb.busy(sb, function () {
                return hb.api(API + '/' + encodeURIComponent(d.group.authGrpCd) + '?ver=' + encodeURIComponent(d.ver), {method: 'PUT',
                    body: rows.map(function (r) { return {code: r.code, hidden: r.hidden}; })}).then(function () {
                    hb.dirty.clear();
                    return loadGroups(d.group.authGrpCd).then(function () { notify(hb.t('js.common.saved')); });
                }, failed);
            });
        });
    }
    if (resetBtn) {
        var rb = resetBtn;
        rb.addEventListener('click', function () {
            if (!current) { return; }
            var d = current;
            hb.dialog.confirm({title: hb.t('js.homeCardMgt.dlg.reset', d.group.authGrpNm), message: hb.t('js.homeCardMgt.confirmReset', d.group.authGrpNm),
                ok: hb.t('js.homeCardMgt.dlg.resetOk'), danger: true}).then(function (yes) {
                if (!yes || current !== d) { return; }
                hb.busy(rb, function () {
                    return hb.api(API + '/' + encodeURIComponent(d.group.authGrpCd) + '/reset?ver=' + encodeURIComponent(d.ver),
                        {method: 'POST'}).then(function () {
                        hb.dirty.clear();
                        return loadGroups(d.group.authGrpCd).then(function () { notify(hb.t('js.homeCardMgt.resetDone')); });
                    }, failed);
                });
            });
        });
    }

    setButtons();
    hb.loadMessages().then(function () {
        hb.page.init();
        return loadGroups();
    });
})();
