// @ts-check
/* 내 정보 화면 (sys-my-info). CSP 때문에 인라인 스크립트를 쓰지 않는다 */
(function () {
    'use strict';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var select = /** @type {HTMLSelectElement} */ (document.getElementById('localeCd'));
    hb.dirty.watch(document);

    /** @param {string} id @param {string | undefined} text */
    function setText(id, text) {
        /** @type {HTMLElement} */ (document.getElementById(id)).textContent = text || '';
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return hb.api('/api/platform/session');
    }).then(function (res) {
        /** @type {HB.Session} */
        var s = res.data;
        setText('compCd', s.compCd);
        setText('loginId', s.loginId);
        setText('userNm', s.userNm);
        select.value = s.localeCd;
        hb.dirty.clear();
    }, function (err) {
        msg.textContent = err.message;
    });

    var saveBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));   // SAVE 권한 없으면 없음
    if (saveBtnEl) {
        var saveBtn = saveBtnEl;
        saveBtn.addEventListener('click', function () {
            hb.busy(saveBtn, function () {
                return hb.api('/api/platform/my-info/locale', {method: 'POST', body: {localeCd: select.value}});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                msg.textContent = hb.t('js.myInfo.saved');
                // 셸 전체를 새 언어로 다시 그린다
                (window.top || window).location.reload();
            }, function (err) {
                msg.textContent = err.message;
            });
        });
    }
})();

/* 비밀번호 변경 */
(function () {
    'use strict';
    var pwdBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('pwdBtn'));   // SAVE 권한 없으면 구역째 없음
    if (!pwdBtn) { return; }
    var btn = pwdBtn;
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
    }

    btn.addEventListener('click', function () {
        if (pw.value !== cf.value) {
            show([hb.t('js.myInfo.passwordConfirmMismatch')]);
            return;
        }
        hb.busy(btn, function () {
            return hb.api('/api/platform/my-info/password', {
                method: 'POST', body: {currentPassword: cur.value, newPassword: pw.value}
            });
        }).then(function (res) {
            if (!res) { return; }
            cur.value = ''; pw.value = ''; cf.value = '';
            hb.dirty.clear();
            show([hb.t('js.myInfo.passwordChanged')]);
        }, function (err) {
            show([err.message].concat(err.details || []));
        });
    });

})();

/* 2단계 인증 상태·복구 코드 (docs/36 14장) */
(function () {
    'use strict';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var mfaStatus = /** @type {HTMLElement} */ (document.getElementById('mfaStatus'));
    var mfaRegen = /** @type {HTMLElement} */ (document.getElementById('mfaRegen'));
    hb.loadMessages().then(function () { return hb.api('/api/platform/mfa/status'); }).then(function (res) {
        /** @type {{available: boolean, enrolled: boolean, required: boolean, recoveryLeft: number, enrolledAt: string | null}} */
        var st = res.data;
        mfaStatus.textContent = st.enrolled
            ? hb.t('js.mfa.enrolled', hb.fmt.datetime(st.enrolledAt), st.recoveryLeft)
            : (st.required ? hb.t('js.mfa.requiredNext') : hb.t('js.mfa.notEnrolled'));
        mfaRegen.hidden = !st.enrolled;
        // 스스로 등록(16.7) — 쓸 수 있고 아직 등록하지 않았을 때만
        /** @type {HTMLElement} */ (document.getElementById('mfaEnrollLink')).hidden = !(st.available && !st.enrolled);
    }, function (err) { mfaStatus.textContent = err.message; });
    var regenBtn = /** @type {HTMLButtonElement} */ (document.getElementById('mfaRegenBtn'));
    regenBtn.addEventListener('click', function () {
        var code = /** @type {HTMLInputElement} */ (document.getElementById('mfaCode'));
        sysUi.ask('js.mfa.dlg.regen', hb.t('js.mfa.confirmRegen'), 'js.mfa.dlg.regenOk', true).then(function (ok) {
            if (!ok) { return; }
            hb.busy(regenBtn, function () {
                return hb.api('/api/platform/mfa/recovery', {method: 'POST', body: {code: code.value}});
            }).then(function (res) {
                if (!res) { return; }
                code.value = '';
                var ul = /** @type {HTMLElement} */ (document.getElementById('mfaCodes'));
                ul.textContent = '';
                res.data.recoveryCodes.forEach(function (/** @type {string} */ c) { ul.appendChild(hb.el('li', null, c)); });
                msg.textContent = hb.t('js.mfa.regenDone');
            }, function (err) { msg.textContent = err.message; });
        });
    });
})();

/* 이 기기로 알림 받기 — Web Push (docs/45 9장 N3). 서버 공개 키로 브라우저 구독 → 서버에 저장. 끄기 = 브라우저 구독 해지 + 서버 삭제 */
(function () {
    'use strict';
    var API = '/api/platform/push';
    var section = /** @type {HTMLElement} */ (document.getElementById('pushSection'));
    var status = /** @type {HTMLElement} */ (document.getElementById('pushStatus'));
    var onBtn = /** @type {HTMLButtonElement} */ (document.getElementById('pushOnBtn'));
    var offBtn = /** @type {HTMLButtonElement} */ (document.getElementById('pushOffBtn'));
    var list = /** @type {HTMLElement} */ (document.getElementById('pushDevices'));
    /** @type {string | null} */
    var serverKey = null;

    /** base64url → 바이트(applicationServerKey) @param {string} s */
    function bytes(s) {
        var b = window.atob(s.replace(/-/g, '+').replace(/_/g, '/') + '===='.slice(0, (4 - s.length % 4) % 4));
        var out = new Uint8Array(b.length);
        for (var i = 0; i < b.length; i++) { out[i] = b.charCodeAt(i); }
        return out;
    }

    function supported() {
        return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext;
    }

    /** @returns {Promise<PushSubscription | null>} */
    function current() {
        return navigator.serviceWorker.getRegistration('/').then(function (reg) {
            return reg ? reg.pushManager.getSubscription() : null;
        });
    }

    /** 기기 이름(브라우저가 알려 준 대로 짧게 — 서버가 다시 자른다) */
    function label() {
        var ua = navigator.userAgent;
        var os = /iPhone|iPad/.test(ua) ? 'iOS' : /Android/.test(ua) ? 'Android' : /Windows/.test(ua) ? 'Windows'
            : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
        var br = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : '';
        return (os + ' ' + br).trim() || 'browser';
    }

    /** @param {{pushSubId: number, deviceLabel: string | null, createdAt: string, lastSentAt: string | null}[]} devices */
    function renderDevices(devices) {
        list.textContent = '';
        if (!devices.length) {
            list.appendChild(hb.el('li', 'hb-muted', hb.t('js.myInfo.push.noDevice')));
        }
        devices.forEach(function (d) {
            var li = hb.el('li', null, (d.deviceLabel || '-') + ' · ' + hb.t('js.myInfo.push.since', hb.fmt.date(d.createdAt)));
            var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.myInfo.push.remove')));
            del.type = 'button';
            del.addEventListener('click', function () {
                hb.busy(del, function () {
                    return hb.api(API + '/subscriptions', {method: 'DELETE', body: {pushSubId: d.pushSubId}});
                }).then(function (res) { if (res) { return refresh(); } }, function (err) { status.textContent = err.message; });
            });
            li.appendChild(del);
            list.appendChild(li);
        });
    }

    function refresh() {
        return hb.api(API).then(function (res) {
            /** @type {{enabled: boolean, publicKey: string | null, devices: any[]}} */
            var st = res.data;
            section.hidden = false;
            serverKey = st.publicKey;
            renderDevices(st.devices || []);
            if (!st.enabled) {
                status.textContent = hb.t('js.myInfo.push.disabled');
                onBtn.hidden = true; offBtn.hidden = true;
                return;
            }
            if (!supported()) {
                status.textContent = hb.t('js.myInfo.push.unsupported');
                onBtn.hidden = true; offBtn.hidden = true;
                return;
            }
            return current().then(function (sub) {
                var denied = Notification.permission === 'denied';
                status.textContent = hb.t(sub ? 'js.myInfo.push.on' : denied ? 'js.myInfo.push.denied' : 'js.myInfo.push.off');
                onBtn.hidden = !!sub || denied;
                offBtn.hidden = !sub;
            });
        }, function (err) { section.hidden = false; status.textContent = err.message; });
    }

    onBtn.addEventListener('click', function () {
        var key = serverKey;
        if (!key) { return; }
        var k = key;
        hb.busy(onBtn, function () {
            return Notification.requestPermission().then(function (perm) {
                if (perm !== 'granted') { throw new Error(hb.t('js.myInfo.push.denied')); }
                return navigator.serviceWorker.ready;
            }).then(function (reg) {
                // 브라우저 쪽 실패(시크릿 창·푸시 서비스에 못 닿음 등)는 브라우저 원문 대신 안내 문구로
                return reg.pushManager.subscribe({userVisibleOnly: true, applicationServerKey: bytes(k)})
                    .then(null, function () { throw new Error(hb.t('js.myInfo.push.failed')); });
            }).then(function (sub) {
                var j = sub.toJSON();
                return hb.api(API + '/subscriptions', {method: 'POST', body: {endpoint: j.endpoint, keys: j.keys, label: label()}})
                    .then(null, function (err) { sub.unsubscribe(); throw err; });   // 서버가 거부하면 브라우저 구독도 거둔다
            });
        }).then(function (res) { if (res) { return refresh(); } }, function (err) { status.textContent = err.message; });
    });

    offBtn.addEventListener('click', function () {
        hb.busy(offBtn, function () {
            return current().then(function (sub) {
                if (!sub) { return null; }
                var endpoint = sub.endpoint;
                return sub.unsubscribe().then(function () {
                    return hb.api(API + '/subscriptions', {method: 'DELETE', body: {endpoint: endpoint}}).then(null, function () { return null; });
                });
            });
        }).then(function () { return refresh(); }, function (err) { status.textContent = err.message; });
    });

    hb.loadMessages().then(refresh);
})();
