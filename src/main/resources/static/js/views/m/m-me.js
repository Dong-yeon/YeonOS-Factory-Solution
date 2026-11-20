// @ts-check
/*
 * 모바일 내 정보 (/m/me, docs/74 화면 6) — 화면 언어 바꾸기(기존 API POST /api/platform/my-info/locale, sys-my-info 저장 권한).
 * 바꾸면 화면을 다시 읽어 새 언어로 그린다. 저장 권한이 없으면 선택 칸이 템플릿에 없다(판정은 서버).
 */
(function () {
    'use strict';

    var sel = /** @type {HTMLSelectElement | null} */ (document.getElementById('localeSel'));
    var msg = document.getElementById('msg');
    if (!sel) { return; }
    var localeSel = sel;

    localeSel.addEventListener('change', function () {
        var before = localeSel.getAttribute('data-current') || '';
        if (!hbM.online()) {
            localeSel.value = before;
            hbM.say(msg, hb.t('js.m.offlineSave'), true);
            return;
        }
        localeSel.disabled = true;
        hb.api('/api/platform/my-info/locale', {method: 'POST', body: {localeCd: localeSel.value}}).then(function () {
            window.location.reload();
        }, function (err) {
            localeSel.disabled = false;
            localeSel.value = before;
            hbM.say(msg, err.message, true);
        });
    });
    hbM.ready();
})();
