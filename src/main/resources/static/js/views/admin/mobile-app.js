// @ts-check
/*
 * 모바일 웹앱 안내 (sys-mobile-app) — docs/75 4장. 보기 전용: 이 서버의 /m 주소를 보여 주고 새 탭으로 열거나 복사한다.
 * 권한 판정 없음 — /m 의 각 조각은 서버가 사무 화면 권한으로 가른다(docs/74).
 */
(function () {
    'use strict';

    var url = window.location.origin + '/m';
    var msg = /** @type {HTMLElement} */ (document.getElementById('copyMsg'));

    /** @param {string} text @param {boolean} [error] */
    function show(text, error) {
        msg.textContent = text;
        msg.hidden = false;
        msg.classList.toggle('hb-msg-error', !!error);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        /** @type {HTMLElement} */ (document.getElementById('mobileUrl')).textContent = url;
        /** @type {HTMLElement} */ (document.getElementById('openBtn')).addEventListener('click', function () {
            var w = window.open('/m', '_blank', 'noopener');
            if (w) { w.opener = null; }
        });
        /** 클립보드 권한이 없을 때(내장 브라우저·http) — 주소 글자를 선택해 두어 Ctrl+C 로 복사하게 */
        function selectUrl() {
            var range = document.createRange();
            range.selectNodeContents(/** @type {HTMLElement} */ (document.getElementById('mobileUrl')));
            var sel = window.getSelection();
            if (sel) { sel.removeAllRanges(); sel.addRange(range); }
            show(hb.t('js.mobileApp.copyFail'), true);
        }
        /** @type {HTMLElement} */ (document.getElementById('copyBtn')).addEventListener('click', function () {
            if (!navigator.clipboard) {
                selectUrl();
                return;
            }
            navigator.clipboard.writeText(url).then(function () {
                show(hb.t('js.mobileApp.copied'));
            }, selectUrl);
        });
    });
})();
