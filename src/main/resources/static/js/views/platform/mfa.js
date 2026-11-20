// @ts-check
/*
 * 두 번째 인증 (docs/36 14장). VERIFY: 코드 확인 → 셸 / ENROLL: 비밀 키 받기 → 첫 코드 확인 → 복구 코드 10개 → 셸.
 * SELF(내 정보에서 스스로 등록, 16.7): ENROLL 과 같은 단계, 끝나면 돌아갈 곳(data-back)으로.
 * 비밀 키·복구 코드는 화면에만(textContent), 저장하지 않는다. CSP 때문에 인라인 스크립트를 쓰지 않는다.
 */
(function () {
    'use strict';
    var API = '/api/platform/mfa';
    var state = document.body.getAttribute('data-state');

    /** @param {string} id */
    function el(id) { return /** @type {HTMLElement} */ (document.getElementById(id)); }

    /** @param {HTMLElement} out @param {string} text */
    function show(out, text) {
        out.textContent = text;
        out.hidden = !text;
    }

    /** @param {{message: string, details?: string[]}} err */
    function errText(err) {
        return [err.message].concat(err.details || []).join(' ');
    }

    function goShell() {
        window.location.replace(state === 'SELF' ? (document.body.getAttribute('data-back') || '/main') : '/main');
    }

    /** @param {string[]} codes */
    function showRecovery(codes) {
        var box = el('enrollBox');
        if (box) { box.hidden = true; }
        var ul = el('recoveryList');
        ul.textContent = '';
        codes.forEach(function (c) { ul.appendChild(hb.el('li', null, c)); });
        el('recoveryBox').hidden = false;
        el('doneBtn').focus();
    }

    var verifyForm = /** @type {HTMLFormElement | null} */ (document.getElementById('verifyForm'));
    if (verifyForm) {
        var vInput = /** @type {HTMLInputElement} */ (el('verifyCode'));
        var vBtn = /** @type {HTMLButtonElement} */ (el('verifyBtn'));
        verifyForm.addEventListener('submit', function (e) {
            e.preventDefault();
            hb.busy(vBtn, function () {
                return hb.api(API + '/verify', {method: 'POST', body: {code: vInput.value}});
            }).then(function (res) {
                if (res) { goShell(); }
            }, function (err) {
                vInput.value = '';
                show(el('verifyMsg'), errText(err));
                vInput.focus();
            });
        });
        vInput.focus();
    }

    var enrollForm = /** @type {HTMLFormElement | null} */ (document.getElementById('enrollForm'));
    if (enrollForm && (state === 'ENROLL' || state === 'SELF')) {
        var eInput = /** @type {HTMLInputElement} */ (el('enrollCode'));
        var eBtn = /** @type {HTMLButtonElement} */ (el('enrollBtn'));
        hb.loadMessages().then(function () {
            return hb.api(API + '/enroll/start', {method: 'POST', body: {}});
        }).then(function (res) {
            el('secret').textContent = res.data.secret.replace(/(.{4})/g, '$1 ').trim();
            el('otpUri').textContent = res.data.otpauthUri;
            eInput.focus();
        }, function (err) { show(el('enrollMsg'), errText(err)); });
        enrollForm.addEventListener('submit', function (e) {
            e.preventDefault();
            hb.busy(eBtn, function () {
                return hb.api(API + '/enroll/confirm', {method: 'POST', body: {code: eInput.value}});
            }).then(function (res) {
                if (res) { showRecovery(res.data.recoveryCodes); }
            }, function (err) {
                eInput.value = '';
                show(el('enrollMsg'), errText(err));
                eInput.focus();
            });
        });
    }

    var done = document.getElementById('doneBtn');
    if (done) { done.addEventListener('click', goShell); }
    hb.loadMessages();
})();
