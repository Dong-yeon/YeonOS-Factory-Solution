// @ts-check
/* 운영자 로그인 (/ops/login) — 1단계 비밀번호, 2단계 TOTP(첫 로그인은 등록) */
/** @typedef {{mfa: 'ENROLL' | string, secret?: string, otpauthUri?: string}} OpsPwResult */
(function () {
    'use strict';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var pwForm = /** @type {HTMLFormElement} */ (document.getElementById('pwForm'));
    var codeForm = /** @type {HTMLFormElement} */ (document.getElementById('codeForm'));
    /** @param {string} id */
    function input(id) { return /** @type {HTMLInputElement} */ (document.getElementById(id)); }
    /** @param {string} id */
    function elem(id) { return /** @type {HTMLElement} */ (document.getElementById(id)); }

    /** @param {HB.ApiError | null | undefined} err */
    function fail(err) {
        msg.textContent = err && err.message ? err.message : hb.t('js.error.server');
    }

    hb.loadMessages().then(function () {
        pwForm.addEventListener('submit', function (e) {
            e.preventDefault();
            msg.textContent = '';
            var btn = /** @type {HTMLButtonElement} */ (pwForm.querySelector('button'));
            hb.busy(btn, function () {
                return hb.api('/api/ops/auth/login', {method: 'POST', body: {
                    loginId: input('loginId').value,
                    password: input('password').value
                }});
            }).then(function (res) {
                if (!res) { return; }
                /** @type {OpsPwResult} */
                var data = res.data;
                input('password').value = '';
                pwForm.hidden = true;
                codeForm.hidden = false;
                if (data.mfa === 'ENROLL') {
                    elem('enroll').hidden = false;
                    elem('secret').textContent = data.secret || '';
                    elem('otpauth').textContent = data.otpauthUri || '';
                }
                input('code').focus();
            }, fail);
        });

        codeForm.addEventListener('submit', function (e) {
            e.preventDefault();
            msg.textContent = '';
            var btn = /** @type {HTMLButtonElement} */ (codeForm.querySelector('button'));
            hb.busy(btn, function () {
                return hb.api('/api/ops/auth/totp', {method: 'POST', body: {code: input('code').value}});
            }).then(function (res) {
                if (res) { window.location.href = '/ops'; }
            }, function (err) {
                input('code').value = '';
                fail(err);
            });
        });
    });
})();
