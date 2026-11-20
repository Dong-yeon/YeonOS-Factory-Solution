// @ts-check
/* 로그인 화면. CSP 때문에 인라인 스크립트를 쓰지 않는다
 * - 두 번 누름 방지 (연타 → 실패 횟수 이중 증가 방지)
 * - 회사코드 기억(선택, 이 기기 localStorage — 비밀 정보 아님), Caps Lock 안내, 장애·점검 공지(/status.json)
 */
(function () {
    'use strict';
    var KEY = 'hb.login.compCd';
    var form = /** @type {HTMLFormElement} */ (document.querySelector('form'));
    // 설치형(onprem)은 회사코드 칸·기억 칸이 없다(th:unless)
    var comp = /** @type {HTMLInputElement | null} */ (document.getElementById('compCd'));
    var remember = /** @type {HTMLInputElement | null} */ (document.getElementById('rememberComp'));
    var pwd = /** @type {HTMLInputElement} */ (document.getElementById('password'));
    var caps = /** @type {HTMLElement} */ (document.getElementById('capsLock'));

    function storage() {
        try { return window.localStorage; } catch (e) { return null; }   // 사생활 모드 등
    }

    // 회사코드 기억 — 기억한 값이 있으면 채우고 아이디로 바로 이동
    var loginId = /** @type {HTMLInputElement} */ (document.getElementById('loginId'));
    var ls = storage();
    var saved = comp && ls ? ls.getItem(KEY) : null;
    if (comp && saved && /^[A-Za-z0-9_-]{1,20}$/.test(saved)) {
        comp.value = saved;
        if (remember) { remember.checked = true; }
    }
    // 비어 있는 첫 칸으로 (local 은 회사·아이디가 미리 채워져 비밀번호로 간다)
    if (comp && !comp.value) {
        comp.focus();
    } else if (!loginId.value) {
        loginId.focus();
    } else {
        pwd.focus();
    }

    form.addEventListener('submit', function (e) {
        var btn = /** @type {HTMLButtonElement} */ (form.querySelector('button'));
        if (btn.disabled) { e.preventDefault(); return; }
        btn.disabled = true;
        var s = storage();
        if (comp && remember && s) {
            if (remember.checked) { s.setItem(KEY, comp.value.trim().toUpperCase()); } else { s.removeItem(KEY); }
        }
    });

    // Caps Lock — 비밀번호 칸에서만
    /** @param {KeyboardEvent} e */
    function capsCheck(e) {
        if (e.getModifierState) { caps.hidden = !e.getModifierState('CapsLock'); }
    }
    pwd.addEventListener('keydown', capsCheck);
    pwd.addEventListener('keyup', capsCheck);
    pwd.addEventListener('blur', function () { caps.hidden = true; });

    // 장애·점검 공지 — 실패해도 로그인에는 영향 없음
    var notice = document.getElementById('statusNotice');
    if (typeof window.fetch === 'function' && notice) {
        var box = notice;
        fetch('/status.json', {cache: 'no-store', credentials: 'same-origin'}).then(function (r) {
            return r.ok ? r.json() : null;
        }).then(/** @param {{status?: string, messageKo?: string, messageEn?: string} | null} st */ function (st) {
            if (!st || st.status === 'ok') { return; }
            var en = (document.documentElement.lang || '').indexOf('en') === 0;
            box.textContent = (en ? st.messageEn : st.messageKo) || st.messageKo || box.getAttribute('data-default');
            box.hidden = false;
        }).catch(function () { /* 공지는 보조 정보 */ });
    }

    // PWA(docs/45 M-1): 로그인 화면 = 세션 없음(로그아웃·만료 뒤 모두 여기로) — 이 앱이 만든 캐시를 비운 뒤 워커를 등록한다
    var pwa = window.hb && window.hb.pwa;
    if (pwa) {
        var p = pwa;
        p.clearCaches().then(function () { return p.register(); });
    }
})();
