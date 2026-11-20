// @ts-check
/* 다음 로그인 때 비밀번호 변경 (docs/36 D1). 바꾸면 셸(/main)로. CSP 때문에 인라인 스크립트를 쓰지 않는다 */
(function () {
    'use strict';
    var form = /** @type {HTMLFormElement} */ (document.getElementById('pwdForm'));
    var btn = /** @type {HTMLButtonElement} */ (document.getElementById('pwdBtn'));
    var out = /** @type {HTMLElement} */ (document.getElementById('pwdMsg'));
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
        if (pw.value !== cf.value) {
            show([hb.t('js.myInfo.passwordConfirmMismatch')]);
            return;
        }
        hb.busy(btn, function () {
            return hb.api('/api/platform/password-change', {
                method: 'POST', body: {currentPassword: cur.value, newPassword: pw.value}
            });
        }).then(function (res) {
            if (!res) { return; }
            cur.value = ''; pw.value = ''; cf.value = '';
            window.location.replace('/main');
        }, function (err) {
            show([err.message].concat(err.details || []));
        });
    });
    hb.loadMessages();
    cur.focus();
})();
