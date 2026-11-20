// @ts-check
/*
 * hb.dialog — 브라우저 기본 confirm/prompt 를 대신하는 화면 안 대화상자 (docs/40 D7·V-7, docs/12 5.7)
 *   hb.dialog.confirm({title: '작업지시 확정', message: '확정하면 …', ok: '확정'}).then(function (yes) { … });
 *   hb.dialog.prompt({title: '분할', label: '분할 수량', number: {scale: 3, min: '0.001', max: '120'}})
 *       .then(function (v) { if (v !== null) { … v 는 자릿수 고정 10진 문자열 … } });
 * - 제목 = 무엇을 하는지(동사), 버튼 = 동사. 위험 확인(danger)은 기본 포커스가 취소, 확정 버튼은 위험 톤
 * - Esc·취소 = false / null, 닫으면 연 요소로 포커스 복귀. 글자는 textContent 로만(서버 데이터 HTML 해석 금지)
 * - 선택 입력(required 아님)을 비우고 확정하면 prompt 는 '' 를 돌려준다 — null 은 취소뿐(예전엔 둘 다 null 이라
 *   "비우면 자동" 대화상자가 조용히 취소됐다: BOM·라우팅 새 리비전, 자산 해체 시각, LOT 재작업 수량 — docs/60 D1)
 * - 숫자 prompt 는 hb.numbers(천 단위·자릿수·범위 검증 — 쉼표 사고 방지)가 필요하다: 화면이 hb.numbers.js 를 싣는다
 * - 최대 길이(maxLength)는 확정 때 검사해 오류로 알린다(입력칸을 잘라 막지 않음 — 붙여넣기가 말없이 잘리지 않게)
 * - 기존 confirm/prompt 를 한꺼번에 바꾸지 않는다 — 모듈이 화면을 고칠 때 점진(V-7)
 * - 글 입력의 빈 값 판정은 hb.text(보이는 글자 — hb.text.js 를 이 파일 앞에 넣은 화면)로: U+200B·NBSP 만 넣은 필수 칸은 REQUIRED
 * 검증 계산(check)은 DOM 없는 순수 함수 — node 단위 시험(src/test/js/hb.dialog.test.js)
 */
(function (root) {
    'use strict';

    /**
     * 입력값 검증 — 오류면 {ok:false, code}, 아니면 {ok:true, value}(숫자는 정규화 10진 문자열, 빈 값은 null)
     * @param {string} raw
     * @param {{required?: boolean, maxLength?: number, number?: HB.NumberParseOptions | null}} opts
     * @param {HB.Numbers | null | undefined} numbers
     * @returns {{ok: true, value: string | null} | {ok: false, code: string}}
     */
    function check(raw, opts, numbers) {
        var s = String(raw == null ? '' : raw);
        // 글 입력은 보이지 않는 글자(U+200B·NBSP 등)도 걷어낸다 — 그것뿐이면 빈 값(필수면 REQUIRED, QA R4-a). hb.text.js 가 없으면 trim
        var hbText = /** @type {HB.Hb | undefined} */ (/** @type {any} */ (root).hb);
        var text = !opts.number && hbText && hbText.text ? hbText.text.strip(s) : s.trim();
        if (!text) {
            return opts.required ? {ok: false, code: 'REQUIRED'} : {ok: true, value: null};
        }
        if (opts.number) {
            if (!numbers) { throw new Error('hb.dialog.prompt number 는 hb.numbers.js 가 필요합니다'); }
            var p = numbers.parse(text, opts.number);
            if (!p.ok) { return {ok: false, code: p.code}; }
            var v = p.value;
            if (v === null) { return opts.required ? {ok: false, code: 'REQUIRED'} : {ok: true, value: null}; }
            return {ok: true, value: opts.number.scale != null ? numbers.fixed(v, opts.number.scale) : v};
        }
        if (opts.maxLength && text.length > opts.maxLength) { return {ok: false, code: 'TOO_LONG'}; }
        return {ok: true, value: text};
    }

    /**
     * prompt 의 확정 결과 — check 와 같고, 통과한 빈 값(선택 입력)은 null 이 아니라 '' (null = 취소만, docs/60 D1)
     * @param {string} raw
     * @param {{required?: boolean, maxLength?: number, number?: HB.NumberParseOptions | null}} opts
     * @param {HB.Numbers | null | undefined} numbers
     * @return {{ok: true, value: string} | {ok: false, code: string}}
     */
    function submitValue(raw, opts, numbers) {
        var r = check(raw, opts, numbers);
        return r.ok ? {ok: true, value: r.value === null ? '' : r.value} : r;
    }

    if (typeof module === 'object' && module.exports) {
        module.exports = {check: check, submitValue: submitValue};
        return;
    }

    var hb = /** @type {HB.Hb} */ (/** @type {any} */ (root).hb);
    var seq = 0;

    /** @param {string} key @param {string} fallback */
    function t(key, fallback) {
        var s = hb.t(key);
        return s && s !== key ? s : fallback;
    }

    /**
     * 공통 틀 — 제목·본문·버튼 두 개. 결과는 onClose 로
     * @param {{title: string, message?: string | null, ok?: string, cancel?: string, danger?: boolean}} o
     * @param {(form: HTMLFormElement) => (() => unknown) | null} build 입력 칸을 붙이고 "확인" 때 값 함수를 돌려준다
     * @returns {Promise<unknown>} 확인 = 값 함수의 결과(false 를 돌려주면 닫지 않음)
     */
    function open(o, build) {
        return new Promise(function (resolve) {
            var id = 'hbdlg' + (++seq);
            var opener = /** @type {HTMLElement | null} */ (document.activeElement);
            var d = /** @type {HTMLDialogElement} */ (hb.el('dialog', 'hb-dialog hb-dialog-sm'));
            d.setAttribute('aria-labelledby', id + '-t');
            var h = hb.el('h2', null, o.title);
            h.id = id + '-t';
            d.appendChild(h);
            var form = /** @type {HTMLFormElement} */ (hb.el('form', 'hb-dialog-body'));
            form.id = id + '-f';
            form.noValidate = true;
            if (o.message) {
                var p = hb.el('p', 'hb-dialog-msg', o.message);
                p.id = id + '-m';
                d.setAttribute('aria-describedby', p.id);
                form.appendChild(p);
            }
            var getValue = build(form);
            d.appendChild(form);
            var bar = hb.el('div', 'hb-dialog-bar');
            var cancel = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', o.cancel || t('js.dialog.cancel', '취소')));
            cancel.type = 'button';
            var ok = /** @type {HTMLButtonElement} */ (hb.el('button', o.danger ? 'hb-btn-danger' : null, o.ok || t('js.dialog.ok', '확인')));
            ok.type = 'submit';
            ok.setAttribute('form', form.id);
            bar.appendChild(cancel);
            bar.appendChild(ok);
            d.appendChild(bar);
            var done = false;
            /** @param {unknown} v */
            function finish(v) {
                if (done) { return; }
                done = true;
                if (d.open) { d.close(); }
                d.remove();
                if (opener && document.contains(opener)) { opener.focus(); }
                resolve(v);
            }
            cancel.addEventListener('click', function () { finish(getValue ? null : false); });
            d.addEventListener('cancel', function (e) { e.preventDefault(); finish(getValue ? null : false); });   // Esc
            form.addEventListener('submit', function (e) {
                e.preventDefault();
                if (!getValue) {
                    finish(true);
                    return;
                }
                var v = getValue();
                if (v !== false) { finish(v); }
            });
            document.body.appendChild(d);
            d.showModal();
            var first = /** @type {HTMLElement | null} */ (form.querySelector('select, input'));
            (o.danger ? cancel : first || ok).focus();   // 위험 확인은 기본 포커스를 취소에
        });
    }

    /** 검증 오류 문구 — 키를 이어 붙이지 않게 고정 목록 @param {string} code */
    function errorText(code) {
        /** @type {Record<string, string>} */
        var m = {
            REQUIRED: hb.t('js.dialog.err.REQUIRED'), TOO_LONG: hb.t('js.dialog.err.TOO_LONG'),
            NUMBER: hb.t('js.dialog.err.NUMBER'), NEGATIVE: hb.t('js.dialog.err.NEGATIVE'), SCALE: hb.t('js.dialog.err.SCALE'),
            MIN: hb.t('js.dialog.err.MIN'), MAX: hb.t('js.dialog.err.MAX')
        };
        return m[code] || code;
    }

    /** @type {HB.Dialogs['confirm']} */
    function confirm(o) {
        return /** @type {Promise<boolean>} */ (open(o, function () { return null; }));
    }

    /** @type {HB.Dialogs['prompt']} */
    function prompt(o) {
        var numbers = hb.numbers || null;
        if (o.number && !numbers) { throw new Error('hb.dialog.prompt number 는 hb.numbers.js 가 필요합니다'); }
        return /** @type {Promise<string | null>} */ (open(o, function (form) {
            var label = hb.el('label', 'hb-dialog-field');
            label.appendChild(hb.el('span', null, o.label));
            var input = /** @type {HTMLInputElement} */ (hb.el('input', o.number ? 'hb-num-input' : null));
            input.type = 'text';
            input.setAttribute('autocomplete', 'off');
            if (o.number) { input.inputMode = o.number.scale ? 'decimal' : 'numeric'; }
            // maxlength 속성은 걸지 않는다 — 붙여넣은 긴 글이 말없이 잘려 저장됐다(QA O-1). 확정 때 TOO_LONG 으로 알리고 입력은 그대로
            if (o.required) { input.setAttribute('aria-required', 'true'); }
            input.value = o.value == null ? '' : (o.number && numbers ? numbers.format(o.value, o.number.scale) : String(o.value));
            label.appendChild(input);
            form.appendChild(label);
            var err = hb.el('p', 'hb-field-error');
            err.id = 'hbdlg-err-' + seq;
            err.hidden = true;
            input.setAttribute('aria-describedby', err.id);
            form.appendChild(err);
            return function () {
                var r = submitValue(input.value, {required: o.required, maxLength: o.maxLength, number: o.number}, numbers);
                if (!r.ok) {
                    err.textContent = errorText(r.code);
                    err.hidden = false;
                    input.setAttribute('aria-invalid', 'true');
                    input.focus();
                    return false;
                }
                return r.value;   // 비운 확정 = '' (취소 = null 과 구분)
            };
        }));
    }

    /**
     * 사유 코드 + 메모(docs/68 Q1 — 집계할 사유). 코드는 공통코드 o.code 의 선택 목록(사용 중·회사 숨김 제외)에서 필수,
     * 메모는 선택(o.memoRequired 면 필수). 취소 = null, 확인 = {code, memo}(메모를 비우면 '')
     * @type {HB.Dialogs['reasonCode']}
     */
    function reasonCode(o) {
        return /** @type {Promise<{code: string, memo: string} | null>} */ (open(o, function (form) {
            var codeLabel = hb.el('label', 'hb-dialog-field');
            codeLabel.appendChild(hb.el('span', null, o.codeLabel));
            var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
            sel.setAttribute('aria-required', 'true');
            var blank = hb.el('option', null, t('js.common.choose', '선택'));
            /** @type {HTMLOptionElement} */ (blank).value = '';
            sel.appendChild(blank);
            hb.codeList(o.code).then(function (list) {
                list.forEach(function (c) {
                    var opt = /** @type {HTMLOptionElement} */ (hb.el('option', null, c.name));
                    opt.value = c.code;
                    sel.appendChild(opt);
                });
            });
            codeLabel.appendChild(sel);
            form.appendChild(codeLabel);
            var codeErr = hb.el('p', 'hb-field-error');
            codeErr.id = 'hbdlg-cerr-' + seq;
            codeErr.hidden = true;
            sel.setAttribute('aria-describedby', codeErr.id);
            form.appendChild(codeErr);

            var memoLabel = hb.el('label', 'hb-dialog-field');
            memoLabel.appendChild(hb.el('span', null, o.label));
            var input = /** @type {HTMLInputElement} */ (hb.el('input'));
            input.type = 'text';
            input.setAttribute('autocomplete', 'off');
            if (o.memoRequired) { input.setAttribute('aria-required', 'true'); }
            memoLabel.appendChild(input);
            form.appendChild(memoLabel);
            var err = hb.el('p', 'hb-field-error');
            err.id = 'hbdlg-err-' + seq;
            err.hidden = true;
            input.setAttribute('aria-describedby', err.id);
            form.appendChild(err);
            return function () {
                codeErr.hidden = true;
                sel.removeAttribute('aria-invalid');
                err.hidden = true;
                input.removeAttribute('aria-invalid');
                if (!sel.value) {
                    codeErr.textContent = errorText('REQUIRED');
                    codeErr.hidden = false;
                    sel.setAttribute('aria-invalid', 'true');
                    sel.focus();
                    return false;
                }
                var r = submitValue(input.value, {required: !!o.memoRequired, maxLength: o.maxLength}, null);
                if (!r.ok) {
                    err.textContent = errorText(r.code);
                    err.hidden = false;
                    input.setAttribute('aria-invalid', 'true');
                    input.focus();
                    return false;
                }
                return {code: sel.value, memo: r.value};
            };
        }));
    }

    hb.dialog = {confirm: confirm, prompt: prompt, reasonCode: reasonCode, check: check, submitValue: submitValue};
})(typeof window !== 'undefined' ? window : globalThis);
