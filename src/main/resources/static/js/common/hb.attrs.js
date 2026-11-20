// @ts-check
/*
 * 클래스 속성 칸 (P1 7b, docs/22 5b): 속성 정의(mst_class_prop)대로 칸을 그리고 attrs 값을 모은다.
 *   var a = hb.attrs(box, {editable: true});  a.render(props, values);  a.values() → {속성 코드: 값}
 * 형식: TEXT 글자 · NUM hb.number(자릿수) · DATE 날짜 · BOOL 예/아니오 · LIST 선택지. 빈 칸은 값 없음(null).
 * 칸 이름은 data-field="attrs.{속성 코드}" — 서버 칸 오류(hb.showFieldErrors)가 그대로 붙는다.
 * 사용 중지 속성은 저장된 값이 있을 때만 읽기 전용으로 보인다. 서버 데이터는 textContent·value 로만 넣는다.
 */
(function (global) {
    'use strict';

    var hb = global.hb;

    /** @typedef {{prop: HB.ClassProp, el: HTMLInputElement | HTMLSelectElement, num: HB.NumberControl | null}} Field */

    /** @type {HB.Hb['attrs']} */
    function attrs(box, options) {
        var o = Object.assign({editable: true}, options || {});
        /** @type {Field[]} */
        var fields = [];

        /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
        function option(sel, value, text) {
            var opt = hb.el('option', null, text);
            opt.value = value;
            sel.appendChild(opt);
        }

        /** @param {HB.ClassProp} p @param {unknown} v @return {{el: HTMLInputElement | HTMLSelectElement, num: HB.NumberControl | null}} */
        function control(p, v) {
            var name = 'attrs.' + p.propCd;
            /** @type {HTMLInputElement | HTMLSelectElement} */
            var el;
            /** @type {HB.NumberControl | null} */
            var num = null;
            if (p.dataType === 'LIST' || p.dataType === 'BOOL') {
                var sel = hb.el('select');
                el = sel;
                option(sel, '', '');
                if (p.dataType === 'BOOL') {
                    option(sel, 'true', hb.t('js.attrs.yes'));
                    option(sel, 'false', hb.t('js.attrs.no'));
                    el.value = v === true ? 'true' : v === false ? 'false' : '';
                } else {
                    (p.options || []).forEach(function (x) { option(sel, x, x); });
                    el.value = v === null || v === undefined ? '' : String(v);
                    if (v !== null && v !== undefined && el.value !== String(v)) {
                        option(sel, String(v), String(v));      // 선택지에서 빠진 지금 값 — 지우지 않고 보여 준다
                        el.value = String(v);
                    }
                }
            } else {
                var inp = hb.el('input');
                el = inp;
                if (p.dataType === 'DATE') {
                    inp.type = 'date';
                } else if (p.dataType === 'TEXT') {
                    inp.maxLength = 500;
                }
                if (p.dataType === 'NUM') {
                    el.setAttribute('data-scale', String(p.scale || 0));
                    el.setAttribute('data-pad', 'false');
                    el.setAttribute('data-negative', 'true');
                    el.setAttribute('data-min', '-999999999999999');
                    el.setAttribute('data-max', '999999999999999');
                    num = hb.number(inp);
                    num.setValue(v === null || v === undefined ? null : String(v));
                } else {
                    el.value = v === null || v === undefined ? '' : String(v);
                }
            }
            el.setAttribute('data-field', name);
            el.setAttribute('aria-label', p.propNm);
            var active = p.useYn === 'Y';
            if (!o.editable || !active) {
                if (el instanceof HTMLSelectElement) { el.disabled = true; } else { el.readOnly = true; }
            }
            return {el: el, num: num};
        }

        /** @type {HB.Attrs['render']} */
        function render(props, values) {
            box.textContent = '';
            fields = [];
            /** @type {Record<string, unknown>} */
            var vals = values || {};
            if (!props || !props.length) {
                box.appendChild(hb.el('p', 'hb-muted', hb.t('js.attrs.none')));
                return;
            }
            props.forEach(function (p) {
                var c = control(p, vals[p.propCd]);
                var lab = hb.el('label', p.dataType === 'TEXT' ? 'hb-wide-2' : null);
                var title = p.propNm + (p.unitNm ? ' (' + p.unitNm + ')' : '') + (p.requiredYn === 'Y' && p.useYn === 'Y' ? ' *' : '')
                    + (p.useYn === 'Y' ? '' : ' — ' + hb.t('js.attrs.inactive'));
                lab.appendChild(hb.el('span', null, title));
                lab.appendChild(c.el);
                box.appendChild(lab);
                fields.push({prop: p, el: c.el, num: c.num});
            });
        }

        /** 모든 칸 값 → {코드: 값}. 빈 칸은 null(서버가 키를 지운다) @return {Record<string, string | boolean | null>} */
        function values() {
            /** @type {Record<string, string | boolean | null>} */
            var out = {};
            fields.forEach(function (f) {
                var p = f.prop;
                var raw;
                if (f.num) {
                    raw = f.num.value();
                } else {
                    raw = f.el.value;
                }
                if (raw === '' || raw === null || raw === undefined) {
                    out[p.propCd] = null;
                } else if (p.dataType === 'BOOL') {
                    out[p.propCd] = raw === 'true';
                } else {
                    out[p.propCd] = String(raw);
                }
            });
            return out;
        }

        function validate() {
            return fields.every(function (f) { return !f.num || f.num.validate().ok; });
        }

        return {render: render, values: values, validate: validate};
    }

    hb.attrs = attrs;
})(window);
