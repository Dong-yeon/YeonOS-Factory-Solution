// @ts-check
/*
 * hb.esign — 전자서명 재인증 대화상자 (docs/36 16장)
 *   hb.esign({meaning: 'APPROVE', targetType: 'qms_mrb', targetId: 12, title: 'MRB-2026-0012'})
 *     .then(function (token) { if (token) { 업무 요청 본문에 esignToken: token 을 싣는다 } });
 *   비밀번호(+ MFA 를 쓰는 사람은 인증 코드)를 다시 받아 5분·한 번 쓰는 토큰을 돌려준다. 취소하면 null.
 *   판정(비밀번호·코드·5회 실패 세션 끊기·토큰 묶음)은 서버. 비밀번호는 칸에만 두고 닫을 때 지운다.
 * 의존: hb.js(hb.api·hb.busy·hb.el·hb.t). DOM 은 textContent 로만.
 */
(function (global) {
    'use strict';

    var hb = global.hb;
    var API = '/api/platform/esign';
    var seq = 0;

    /** 의미 문구 — 키를 이어 붙이지 않게 고정 목록 @param {string} code */
    function meaningText(code) {
        /** @type {Record<string, string>} */
        var m = {
            AUTHOR: hb.t('js.esign.meaning.AUTHOR'), REVIEW: hb.t('js.esign.meaning.REVIEW'),
            APPROVE: hb.t('js.esign.meaning.APPROVE')
        };
        return m[code] || code;
    }

    /** @param {string} labelText @param {string} type @param {string} autocomplete */
    function field(labelText, type, autocomplete) {
        var label = hb.el('label', 'hb-wide');
        label.appendChild(hb.el('span', null, labelText));
        var input = hb.el('input');
        input.type = type;
        input.setAttribute('autocomplete', autocomplete);
        input.spellcheck = false;
        label.appendChild(input);
        return {label: label, input: input};
    }

    /** @type {HB.Hb['esign']} */
    function esign(opt) {
        return hb.api(API + '/requirement').then(function (req) {
            var needCode = !!(req.data && req.data.mfa);
            return new Promise(function (resolve) {
                var id = 'hbesign' + (++seq);
                var done = false;
                var d = hb.el('dialog', 'hb-dialog hb-esign');
                d.setAttribute('aria-labelledby', id + '-t');
                var title = hb.el('h2', null, hb.t('js.esign.title', meaningText(opt.meaning)));
                title.id = id + '-t';
                d.appendChild(title);
                var form = hb.el('form', 'hb-form');
                form.setAttribute('autocomplete', 'off');
                if (opt.title) { form.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.esign.target', opt.title))); }
                form.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.esign.note')));
                var pw = field(hb.t('js.esign.password'), 'password', 'current-password');
                form.appendChild(pw.label);
                var code = needCode ? field(hb.t('js.esign.code'), 'text', 'one-time-code') : null;
                if (code) {
                    code.input.inputMode = 'numeric';
                    code.input.maxLength = 6;
                    form.appendChild(code.label);
                }
                d.appendChild(form);
                var msg = hb.el('p', 'hb-msg');
                msg.setAttribute('role', 'status');
                d.appendChild(msg);
                var bar = hb.el('div', 'hb-dialog-bar');
                var cancel = hb.el('button', 'hb-btn-secondary', hb.t('js.esign.cancel'));
                cancel.type = 'button';
                var sign = hb.el('button', null, hb.t('js.esign.sign'));
                sign.type = 'submit';
                sign.setAttribute('form', id + '-f');
                form.id = id + '-f';
                bar.appendChild(cancel);
                bar.appendChild(sign);
                d.appendChild(bar);

                /** @param {string | null} token */
                function finish(token) {
                    if (done) { return; }
                    done = true;
                    pw.input.value = '';
                    if (code) { code.input.value = ''; }
                    if (d.open) { d.close(); }
                    d.remove();
                    resolve(token);
                }
                cancel.addEventListener('click', function () { finish(null); });
                d.addEventListener('close', function () { finish(null); });
                form.addEventListener('submit', function (e) {
                    e.preventDefault();
                    msg.textContent = '';
                    msg.classList.remove('hb-msg-error');
                    hb.busy(sign, function () {
                        return hb.api(API + '/token', {method: 'POST', body: {
                            meaningCd: opt.meaning, targetType: opt.targetType, targetId: opt.targetId,
                            password: pw.input.value, code: code ? code.input.value : null
                        }});
                    }).then(function (res) {
                        if (res && res.data) { finish(res.data.token); }
                    }, function (err) {
                        msg.textContent = err.message;
                        msg.classList.add('hb-msg-error');
                        pw.input.select();
                    });
                });
                document.body.appendChild(d);
                d.showModal();
                pw.input.focus();
            });
        });
    }

    hb.esign = esign;
})(window);
