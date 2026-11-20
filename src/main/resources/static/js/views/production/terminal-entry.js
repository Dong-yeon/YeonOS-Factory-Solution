// @ts-check
/*
 * 현장 단말 진입 (prd-pop 공정 단말 · wms-pda 창고 단말) — docs/36 21장. 두 화면이 같이 쓴다(body data-kind = pop | pda).
 * - [단말 화면 열기] = 새 탭으로 /pop(공정) · /pop/wh(창고). 미등록 기기면 POP 체인이 /pop/activate 로 보낸다(그대로).
 * - 이 기기 상태: POP 체인의 /api/pop/session 을 그대로 불러 본다 — 사무 로그인이 단말에 로그인시키지 않는다(docs/25 4.6 분리 유지).
 *   hb.api 를 쓰지 않는다(401 을 사무 로그인 만료로 다루므로). 등록 단말의 회사가 지금 회사와 다르면 단말 이름은 보이지 않는다.
 * - 바로가기(POP 단말 관리·현장 단말 앱)는 그 화면 권한이 있을 때만 — 서버가 판정(/api/terminal-entry/{kind}/links).
 */
/** @typedef {{compCd?: string, terminalCd?: string, terminalNm?: string, terminalType?: string}} TermEntrySession */
(function () {
    'use strict';

    var KIND = document.body.getAttribute('data-kind') === 'pda' ? 'pda' : 'pop';
    /** 이 화면이 여는 단말 화면과 그 단말 유형 */
    var TARGET = KIND === 'pda' ? {url: '/pop/wh', type: 'PDA'} : {url: '/pop', type: 'POP'};
    var state = /** @type {HTMLElement} */ (document.getElementById('deviceState'));
    var openBtn = /** @type {HTMLButtonElement} */ (document.getElementById('openBtn'));
    var activateBtn = /** @type {HTMLButtonElement} */ (document.getElementById('activateBtn'));

    /** 새 탭 — 단말 화면이 이 사무 창을 건드리지 못하게(noopener) @param {string} url */
    function openTab(url) {
        var w = window.open(url, '_blank', 'noopener');
        if (w) { w.opener = null; }
    }

    openBtn.addEventListener('click', function () { openTab(TARGET.url); });
    activateBtn.addEventListener('click', function () { openTab('/pop/activate'); });

    /** @param {string} text @param {boolean} [warn] */
    function show(text, warn) {
        state.textContent = text;
        state.classList.toggle('hb-msg-error', !!warn);
    }

    /**
     * 이 기기 상태 문구 — 등록 안 됨 / 다른 회사 단말 / 다른 유형 단말 / 이 화면 유형 단말(이름)
     * @param {TermEntrySession | null} s 등록 단말이 아니면 null @param {string | undefined} myComp
     */
    function describe(s, myComp) {
        activateBtn.hidden = !!s;
        if (!s) {
            show(hb.t('js.termEntry.notRegistered'), true);
            return;
        }
        if (!myComp || s.compCd !== myComp) {
            show(hb.t('js.termEntry.otherComp'), true);   // B사 단말 이름을 A사 화면에 보이지 않는다
            return;
        }
        var name = (s.terminalNm || '') + ' (' + (s.terminalCd || '') + ')';
        if (s.terminalType !== TARGET.type) {
            show(hb.t(TARGET.type === 'PDA' ? 'js.termEntry.wrongTypePop' : 'js.termEntry.wrongTypePda', name), true);
            return;
        }
        show(hb.t('js.termEntry.registered', name));
    }

    /** @return {Promise<TermEntrySession | null>} */
    function deviceSession() {
        return fetch('/api/pop/session', {credentials: 'same-origin', headers: {Accept: 'application/json'}}).then(function (res) {
            if (!res.ok) { return null; }
            return res.json().then(function (/** @type {{data?: TermEntrySession}} */ j) { return j && j.data ? j.data : null; });
        }, function () { return null; });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), deviceSession(), hb.api('/api/terminal-entry/' + KIND + '/links')]);
    }).then(function (r) {
        describe(r[1], r[0] ? r[0].compCd : undefined);
        /** @type {{terminalMgt: boolean, pdaApp: boolean}} */
        var links = r[2].data;
        /** @type {HTMLElement} */ (document.getElementById('terminalMgtItem')).hidden = !links.terminalMgt;
        /** @type {HTMLElement} */ (document.getElementById('pdaAppItem')).hidden = !links.pdaApp;
        /** @type {HTMLElement} */ (document.getElementById('linkBox')).hidden = !links.terminalMgt && !links.pdaApp;
    }).then(null, function (err) { show(err && err.message ? err.message : String(err), true); });
})();
