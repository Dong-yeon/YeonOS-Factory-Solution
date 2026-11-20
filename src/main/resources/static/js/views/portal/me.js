// @ts-check
/* 협력사 포털 내 정보 — 자기 비밀번호 바꾸기 (docs/42 7장 2). 검사·문구는 서버(회사 사용자 내 정보와 같은 MyInfoService) */
(function () {
    'use strict';
    var form = /** @type {HTMLFormElement} */ (document.getElementById('pwdForm'));
    var btn = /** @type {HTMLButtonElement} */ (document.getElementById('pwdBtn'));
    var out = /** @type {HTMLElement} */ (document.getElementById('pwdMsg'));
    var ok = /** @type {HTMLElement} */ (document.getElementById('pwdOk'));
    var cur = /** @type {HTMLInputElement} */ (document.getElementById('currentPassword'));
    var pw = /** @type {HTMLInputElement} */ (document.getElementById('newPassword'));
    var cf = /** @type {HTMLInputElement} */ (document.getElementById('confirmPassword'));

    /** @param {string[]} lines */
    function show(lines) {
        out.textContent = '';
        lines.forEach(function (text) {
            var li = document.createElement('li');
            li.textContent = text;
            out.appendChild(li);
        });
        out.hidden = !lines.length;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        ok.hidden = true;
        if (pw.value !== cf.value) {
            show([hb.t('js.myInfo.passwordConfirmMismatch')]);
            return;
        }
        hb.busy(btn, function () {
            return hb.api('/api/portal/me/password', {
                method: 'POST', body: {currentPassword: cur.value, newPassword: pw.value}
            });
        }).then(function (res) {
            if (!res) { return; }
            cur.value = ''; pw.value = ''; cf.value = '';
            show([]);
            ok.hidden = false;
        }, function (err) {
            show([err.message].concat(err.details || []));
        });
    });
    hb.loadMessages();
})();
