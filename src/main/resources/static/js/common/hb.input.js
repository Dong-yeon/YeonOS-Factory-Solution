// @ts-check
/*
 * 입력 컴포넌트 2·3단계 (docs/16 6장·7장): hb.select · hb.number · hb.searchPanel
 *   세그먼트·체크박스·토글은 네이티브 input + CSS(page.css .hb-seg·.hb-switch·.hb-check) — JS 없음.
 * 의존: hb.js(hb.el·hb.t·hb.codeList), hb.numbers. 조회 패널 안에 기간이 있으면 hb.dateRange(hb.dates·hb.calendar)도.
 * 서버 데이터는 textContent·value 로만 넣는다(innerHTML 금지).
 */
(function (global) {
    'use strict';

    var hb = global.hb;
    var N = hb.numbers;
    var seq = 0;

    /**
     * @template F
     * @param {Element} el
     * @param {string} name
     * @param {F} fallback
     * @return {string | F}
     */
    function attr(el, name, fallback) {
        var v = el.getAttribute(name);
        return v === null || v === '' ? fallback : v;
    }

    /** 칸 아래 오류 문구(하나만) — aria-invalid + aria-describedby 로 칸과 연결 @param {Element} input @param {string | null} text */
    function fieldError(input, text) {
        var id = input.getAttribute('data-hb-err');
        var box = id ? document.getElementById(id) : null;
        if (!text) {
            input.removeAttribute('aria-invalid');
            if (box) { box.hidden = true; box.textContent = ''; }
            return;
        }
        if (!box) {
            box = hb.el('span', 'hb-field-error');
            box.id = 'hbe' + (++seq);
            box.setAttribute('role', 'alert');
            input.setAttribute('data-hb-err', box.id);
            var described = input.getAttribute('aria-describedby');
            input.setAttribute('aria-describedby', (described ? described + ' ' : '') + box.id);
            input.insertAdjacentElement('afterend', box);
        }
        box.textContent = text;
        box.hidden = false;
        input.setAttribute('aria-invalid', 'true');
    }

    // ---------------------------------------------------------------- hb.select
    /**
     * 네이티브 <select> 감싸기 (6.1·7.3): 공통코드 채우기 + 첫 항목(전체·선택하세요). 키보드·스크린리더·모바일 피커는 브라우저 것.
     *   <select name="custType" data-hb="select" data-code="CUST_TYPE" data-blank="all|select"></select>
     * 고른 값이 새 목록에 있으면 유지한다(reload 때 값이 조용히 바뀌지 않게).
     */
    /** @type {HB.Hb['select']} */
    function select(el, options) {
        if (el.hbSelect) { return el.hbSelect; }
        /** @type {HB.SelectOptions} */
        var o = Object.assign({code: attr(el, 'data-code', null), blank: attr(el, 'data-blank', null)}, options || {});

        function blankText() {
            switch (o.blank) {
            case 'all': return hb.t('js.common.all');
            case 'select': return hb.t('js.common.choose');
            default: return null;
            }
        }

        /**
         * 목록에 없는 저장값(회사가 숨긴 시스템 코드·사용 중지한 코드, docs/68 Q4) — 첫 항목으로 바꾸면 저장 때 값이 조용히 지워지므로
         * 그 값 하나를 "(목록에 없음)" 선택지로 붙여 그대로 둔다. 이름은 이름표(사용 중지 포함)에서. 다른 값을 고르면 다음 setValue·reload 때 빠진다
         * @param {string} v
         */
        function addExtra(v) {
            var opt = hb.el('option', null, hb.t('js.common.codeNotListed', v));
            opt.value = v;
            opt.setAttribute('data-hb-extra', '1');
            el.appendChild(opt);
            el.value = v;
            if (o.code) {
                hb.codeNames(o.code).then(function (names) {
                    if (opt.parentNode === el && names[v]) { opt.textContent = hb.t('js.common.codeNotListed', names[v]); }
                });
            }
        }

        function removeExtra() {
            el.querySelectorAll('option[data-hb-extra]').forEach(function (x) { x.remove(); });
        }

        /** @param {HB.Code[]} list */
        function fill(list) {
            var keep = el.value;
            var keepExtra = !!el.querySelector('option[data-hb-extra]:checked');
            el.textContent = '';
            var b = blankText();
            if (b !== null) {
                var first = hb.el('option', null, b);
                first.value = '';
                el.appendChild(first);
            }
            list.forEach(function (c) {
                var opt = hb.el('option', null, c.name);
                opt.value = c.code;
                el.appendChild(opt);
            });
            if (keep && list.some(function (c) { return c.code === keep; })) {
                el.value = keep;
            } else if (keep && keepExtra) {
                addExtra(keep);   // 목록에 없는 저장값을 보고 있었다 — reload 로 지우지 않는다
            } else {
                el.selectedIndex = 0;
            }
        }

        /** @param {{code?: string}} [opts] @return {Promise<void>} */
        function reload(opts) {
            if (opts && opts.code) { o.code = opts.code; }
            if (!o.code) { return Promise.resolve(); }
            // 첫 줄(전체·선택하세요)은 화면 문구 — 코드 목록이 문구보다 먼저 와도 키가 보이지 않게 둘 다 기다린다
            return Promise.all([hb.codeList(o.code), hb.loadMessages()]).then(function (r) { fill(r[0]); });
        }

        var api = {
            ready: reload(),
            reload: reload,
            value: function () { return el.value; },
            /** 저장된 값 넣기 — 공통코드 select 에서 목록에 없는 값이면 "(목록에 없음)" 선택지로 남긴다(addExtra) */
            setValue: function (/** @type {unknown} */ v) {
                var s = v === null || v === undefined ? '' : String(v);
                removeExtra();
                el.value = s;
                if (el.value !== s) {
                    if (s && o.code) { addExtra(s); } else { el.selectedIndex = 0; }
                }
            },
            /** 기본값(data-default, 없으면 첫 항목) — 새 값이라 목록에 없는 기본값은 붙이지 않는다 */
            reset: function () {
                var d = attr(el, 'data-default', null);
                removeExtra();
                el.value = d === null ? '' : d;
                if (d === null || el.value !== d) { el.selectedIndex = 0; }
            }
        };
        el.hbSelect = api;
        return api;
    }

    // ---------------------------------------------------------------- hb.number
    /**
     * 숫자 칸 (6.5·7.6): type=text + inputmode, 오른쪽 정렬·고정폭 숫자.
     *   <input name="qty" data-hb="number" data-scale="3" data-min="0" data-max="9999999" data-negative="false">
     *   data-pad="false" = 자릿수는 상한으로만 쓰고 끝의 0 을 채우지 않는다(순질량 4.85 처럼 정밀도가 값마다 다른 칸)
     * - 포커스: 원값(1234.5) / 벗어나면 천 단위·자릿수(1,234.500). 해석 실패면 글자를 그대로 두고 오류(조용히 고치지 않음).
     * - 휠·↑↓ 증감 없음(type=number 를 쓰지 않는 이유). 값은 문자열 → 서버 BigDecimal.
     * - value() = '1234.500'(자릿수 고정 문자열, 비면 null, 오류면 null) / numberValue() = 표시·계산용 Number
     * 단위별 자릿수(data-unit-from)는 UNIT 공통코드에 소수 자릿수 속성이 생길 때(P1 품목, docs/16 7.6).
     */
    /** @type {HB.Hb['number']} */
    function number(el, options) {
        if (el.hbNumber) { return el.hbNumber; }
        var scaleAttr = attr(el, 'data-scale', null);
        /** @type {Required<HB.NumberOptions>} */
        var o = Object.assign({
            scale: scaleAttr === null ? null : Math.max(0, parseInt(scaleAttr, 10) || 0),
            min: attr(el, 'data-min', null),
            max: attr(el, 'data-max', null),
            allowNegative: attr(el, 'data-negative', 'false') === 'true',
            pad: attr(el, 'data-pad', 'true') !== 'false'
        }, options || {});
        var shownScale = o.pad ? o.scale : null;     // 표시·value() 의 자릿수 고정
        /** 정규화된 값('1234.5') 또는 null @type {string | null} */
        var current = null;
        /** 오류 코드 @type {string | null} */
        var invalid = null;

        el.type = 'text';
        el.inputMode = o.scale === 0 ? 'numeric' : 'decimal';
        el.autocomplete = 'off';
        el.spellcheck = false;
        el.classList.add('hb-num-input');

        /** @param {string} code */
        function errorText(code) {
            switch (code) {
            case 'NUMBER': return hb.t('js.num.err.number');
            case 'NEGATIVE': return hb.t('js.num.err.negative');
            case 'SCALE': return o.scale === 0 ? hb.t('js.num.err.integer') : hb.t('js.num.err.scale', o.scale);
            case 'MIN': return hb.t('js.num.err.min', N.format(o.min));
            case 'MAX': return hb.t('js.num.err.max', N.format(o.max));
            case 'REQUIRED': return hb.t('js.common.required');
            default: return code;
            }
        }

        /** 칸 글자 해석 → current·오류 표시 @return {HB.NumberCheck} */
        function read() {
            /** @type {HB.NumberCheck} */
            var r = N.parse(el.value, {scale: o.scale, min: o.min, max: o.max, allowNegative: o.allowNegative});
            if (r.ok && r.value === null && el.required) { r = {ok: false, code: 'REQUIRED'}; }
            current = r.ok ? r.value : null;
            invalid = r.ok ? null : r.code;
            fieldError(el, r.ok ? null : errorText(r.code));
            return r;
        }

        function display() {
            if (!invalid) { el.value = N.format(current, shownScale); }
        }

        el.addEventListener('focus', function () {
            if (!invalid && current !== null) {
                // 편집은 쉼표 없는 원값으로. value 를 바꾸면 선택이 끝으로 접힌다 — Tab 으로 들어와 브라우저가 전체 선택했거나
                // 도구·붙여넣기가 전체를 고른 상태였으면 다시 전체 선택(안 그러면 친 글자가 옛 값 뒤에 붙어 1234.5 + 7 → 1234.57)
                var all = el.value.length > 0 && el.selectionStart === 0 && el.selectionEnd === el.value.length;
                el.value = N.fixed(current, null) || '';
                if (all) { el.select(); }
            }
        });
        el.addEventListener('blur', function () {
            read();
            display();
        });
        el.addEventListener('input', function () { invalid = null; });

        var api = {
            /** 서버로 보낼 값: 자릿수 고정 문자열 | null (오류면 null — 먼저 validate) */
            value: function () {
                if (document.activeElement === el) { read(); }
                return invalid ? null : N.fixed(current, shownScale);
            },
            numberValue: function () {
                var v = api.value();
                return v === null ? null : Number(v);
            },
            setValue: function (/** @type {unknown} */ v) {
                var r = N.parse(v === null || v === undefined ? '' : String(v), {});
                current = r.ok ? r.value : null;
                invalid = null;
                fieldError(el, null);
                el.value = document.activeElement === el ? (N.fixed(current, null) || '') : N.format(current, shownScale);
            },
            /** 검사 + 오류 표시 */
            validate: function () {
                var r = read();
                if (document.activeElement !== el) { display(); }
                return r;
            },
            reset: function () { api.setValue(attr(el, 'data-default', null)); }
        };
        if (el.value) { api.setValue(el.value); }
        el.hbNumber = api;
        return api;
    }

    // ---------------------------------------------------------------- hb.searchPanel
    /**
     * 조회 조건 패널 (6.6·7.8, docs/16 I11·I12):
     *   <form data-hb="searchPanel" data-autofocus="true"> … 조건 … <button type="reset"> <button type="submit"> </form>
     *   var sp = hb.searchPanel(form, {onSearch: function (values) { … return promise; }});
     * - Enter = 조회(어느 칸에서든 — 폼 기본 제출). 한글 조합 중 Enter 는 조회하지 않는다(조합 확정만).
     * - 조회 전에 검사: 필수(required)·숫자·기간 — 실패하면 조회하지 않고 첫 오류 칸에 포커스.
     * - 조회 중에는 조회 버튼 aria-busy + 다시 누름 무시(onSearch 가 promise 를 돌려줄 때).
     * - values() = 모든 조건 → 객체 하나(기간 프리셋은 그 순간 다시 계산). 같은 이름 체크박스·multiple select 는 배열.
     * - reset() = 화면 기본값으로(native 기본값·data-default) — 조회는 하지 않는다.
     * - 첫 조건 자동 포커스(I12): data-autofocus="true" 이고 화면을 **처음 열 때** 그 탭이 보이는 경우만.
     *   탭을 옮겨 다닐 때는 포커스를 건드리지 않는다(iframe 은 처음 한 번만 만들어진다).
     */
    /**
     * 조회 패널이 맡은 컴포넌트
     * @typedef {{el: HTMLSelectElement, kind: 'select', api: HB.SelectControl}
     *   | {el: HTMLInputElement, kind: 'number', api: HB.NumberControl}
     *   | {el: HTMLElement, kind: 'dateRange', api: HB.DateRange}} Part
     */
    /** 값을 읽는 일반 칸(버튼·fieldset·output 은 조건이 아니다) @typedef {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} Control */

    /** @type {HB.Hb['searchPanel']} */
    function searchPanel(form, options) {
        if (form.hbSearchPanel) { return form.hbSearchPanel; }
        /** @type {HB.SearchPanelOptions} */
        var o = Object.assign({onSearch: null, onReset: null, onLink: null}, options || {});
        /** @type {Part[]} */
        var parts = [];
        var composing = false;
        /** @type {Promise<unknown> | null} */
        var running = null;
        form.classList.add('hb-sp');
        form.setAttribute('role', 'search');
        form.noValidate = true;  // 브라우저 기본 검사 말풍선 대신 우리 오류 문구

        form.querySelectorAll('[data-hb]').forEach(function (node) {
            var kind = node.getAttribute('data-hb');
            if (kind === 'select') {
                var sel = /** @type {HTMLSelectElement} */ (node);
                parts.push({el: sel, kind: kind, api: select(sel)});
            } else if (kind === 'number') {
                var inp = /** @type {HTMLInputElement} */ (node);
                parts.push({el: inp, kind: kind, api: number(inp)});
            } else if (kind === 'dateRange') {
                var host = /** @type {HTMLElement} */ (node);
                parts.push({el: host, kind: kind, api: host.hbDateRange || (host.hbDateRange = hb.dateRange(host))});
            }
        });

        /** 기간 컴포넌트의 시작·종료 칸 이름 @param {HTMLElement} el */
        function rangeNames(el) {
            return [el.getAttribute('data-from-name') || 'from', el.getAttribute('data-to-name') || 'to'];
        }

        /** @param {Element} control */
        function owner(control) {
            for (var i = 0; i < parts.length; i++) {
                if (parts[i].el === control || (parts[i].kind === 'dateRange' && parts[i].el.contains(control))) {
                    return parts[i];
                }
            }
            return null;
        }

        /** 이름 있는 일반 칸 목록(컴포넌트가 맡은 칸 제외, 버튼 제외) @return {Control[]} */
        function plainControls() {
            return Array.prototype.filter.call(form.elements, function (c) {
                return (c instanceof HTMLInputElement || c instanceof HTMLSelectElement || c instanceof HTMLTextAreaElement)
                    && c.name && !c.disabled && !/^(submit|reset|button|file)$/.test(c.type) && !owner(c);
            });
        }

        function values() {
            /** @type {HB.SearchValues} */
            var v = {};
            parts.forEach(function (p) {
                if (p.kind === 'dateRange') {
                    var q = p.api.toQuery();
                    var names = rangeNames(p.el);
                    v[names[0]] = q ? q.from : null;
                    v[names[1]] = q ? q.to : null;
                } else if (p.el.name) {
                    v[p.el.name] = p.api.value();
                }
            });
            /** @type {Record<string, string[]>} */
            var multi = {};
            plainControls().forEach(function (c) {
                if (c instanceof HTMLInputElement && c.type === 'checkbox') {
                    var same = form.querySelectorAll('input[type="checkbox"][name="' + c.name + '"]').length > 1;
                    if (same) {
                        multi[c.name] = multi[c.name] || (v[c.name] = []);
                        if (c.checked) { multi[c.name].push(c.value); }
                    } else {
                        v[c.name] = c.checked ? (c.value === 'on' ? 'Y' : c.value) : '';
                    }
                } else if (c instanceof HTMLInputElement && c.type === 'radio') {
                    if (c.checked) { v[c.name] = c.value; } else if (!(c.name in v)) { v[c.name] = ''; }
                } else if (c instanceof HTMLSelectElement && c.multiple) {
                    v[c.name] = Array.prototype.filter.call(c.options, function (x) { return x.selected; })
                        .map(function (x) { return x.value; });
                } else {
                    v[c.name] = c.tagName === 'SELECT' ? c.value : c.value.trim();
                }
            });
            return v;
        }

        /** @param {string} name @param {unknown} value */
        function setValue(name, value) {
            for (var i = 0; i < parts.length; i++) {
                var p = parts[i];
                if (p.kind !== 'dateRange' && p.el.name === name) {
                    p.api.setValue(value);
                    return;
                }
            }
            // 기간의 시작·종료 이름이면 기간 컴포넌트로(칸만 바꾸면 프리셋 상태가 남아 조회 때 덮어쓴다)
            for (var j = 0; j < parts.length; j++) {
                var dr = parts[j];
                if (dr.kind === 'dateRange' && rangeNames(dr.el).indexOf(name) >= 0) {
                    var cur = dr.api.value();
                    var isFrom = name === rangeNames(dr.el)[0];
                    var date = value === null || value === undefined || value === '' ? null : String(value);
                    dr.api.setValue({from: isFrom ? date : cur.from, to: isFrom ? cur.to : date});
                    return;
                }
            }
            var c = /** @type {RadioNodeList | Control | null} */ (form.elements.namedItem(name));
            if (!c) { return; }
            var text = value === null || value === undefined ? '' : String(value);
            if (c instanceof RadioNodeList || c.type === 'radio') {
                /** @type {HTMLInputElement[]} */
                var radios = Array.prototype.slice.call(c instanceof RadioNodeList ? c : [c]);
                // 목록에 없는 값이면 그대로 둔다(아무것도 안 고른 상태를 만들지 않음)
                if (radios.some(function (r) { return r.value === text; })) {
                    radios.forEach(function (r) { r.checked = r.value === text; });
                }
            } else if (c instanceof HTMLInputElement && c.type === 'checkbox') {
                c.checked = text === 'Y' || text === c.value;
            } else {
                c.value = text;
            }
        }

        /** 일반 칸의 지금 값을 그 칸의 기본값으로 기록(reset 이 되돌리는 값) @param {Control} c */
        function rememberDefault(c) {
            if (c instanceof HTMLInputElement && (c.type === 'checkbox' || c.type === 'radio')) {
                c.defaultChecked = c.checked;
            } else if (c instanceof HTMLSelectElement) {
                Array.prototype.forEach.call(c.options, function (/** @type {HTMLOptionElement} */ x) { x.defaultSelected = x.selected; });
            } else {
                c.defaultValue = c.value;
            }
        }

        /**
         * setDefault 로 정한 기간 기본값(프리셋이 아닌 날짜 — 예: 오늘-7 ~ 오늘+30). 있으면 reset 이 data-default 프리셋 대신 이 값으로
         * @type {Map<HTMLElement, {from: string | null, to: string | null}>}
         */
        var rangeDefaults = new Map();

        /** @param {unknown} v */
        function isoOrNull(v) { return v === null || v === undefined || v === '' ? null : String(v); }

        /**
         * 화면이 계산한 기본값(이번 달·기본 공장 등)을 넣으면서 기본값으로도 기록 — reset(링크 되돌림·[초기화])이 처음 연 탭과 같게.
         * 기간 컴포넌트: name = 시작·종료 칸 이름, value = {from, to}(둘 다) 또는 날짜 하나(그쪽만) — reset 이 data-default 프리셋 대신 그 값으로
         * @param {string} name @param {unknown} value
         */
        function setDefault(name, value) {
            for (var r = 0; r < parts.length; r++) {
                var dr = parts[r];
                if (dr.kind !== 'dateRange') { continue; }
                var names = rangeNames(dr.el);
                if (names.indexOf(name) < 0) { continue; }
                var prev = rangeDefaults.get(dr.el);
                var next;
                if (value !== null && typeof value === 'object') {
                    var both = /** @type {{from?: unknown, to?: unknown}} */ (value);
                    next = {from: isoOrNull(both.from), to: isoOrNull(both.to)};
                } else {
                    // 한쪽만: 다른 쪽은 앞서 정한 기본값, 없으면 지금 값
                    var cur = prev || dr.api.value();
                    next = name === names[0] ? {from: isoOrNull(value), to: cur.to} : {from: cur.from, to: isoOrNull(value)};
                }
                rangeDefaults.set(dr.el, next);
                dr.api.setValue({from: next.from, to: next.to});
                return;
            }
            setValue(name, value);
            for (var i = 0; i < parts.length; i++) {
                var p = parts[i];
                if (p.kind !== 'dateRange' && p.el.name === name) {
                    p.el.setAttribute('data-default', value === null || value === undefined ? '' : String(value));
                    return;
                }
            }
            var c = /** @type {RadioNodeList | Control | null} */ (form.elements.namedItem(name));
            if (c instanceof RadioNodeList) {
                Array.prototype.forEach.call(c, rememberDefault);
            } else if (c) {
                rememberDefault(c);
            }
        }

        /** 상태 복원: {name: value} 를 한 번에 (그리드 필터·이전 조건 등) @param {Record<string, unknown> | null | undefined} obj */
        function setValues(obj) {
            var all = obj || {};
            Object.keys(all).forEach(function (k) { setValue(k, all[k]); });
        }

        /** 첫 오류 칸(없으면 null) — 오류 문구 표시 + 포커스 @return {HTMLElement | null} */
        function validate() {
            /** @type {HTMLElement | null} */
            var first = null;
            parts.forEach(function (p) {
                var ok = p.kind === 'select' ? true : p.api.validate().ok;
                if (!ok && !first) {
                    first = p.kind === 'dateRange'
                        ? /** @type {HTMLElement | null} */ (p.el.querySelector('[aria-invalid="true"]') || p.el.querySelector('input'))
                        : p.el;
                }
            });
            plainControls().forEach(function (c) {
                if (c.type === 'radio' || c.type === 'checkbox') { return; }
                var empty = c.required && !String(c.value).trim();
                fieldError(c, empty ? hb.t('js.common.required') : null);
                if (empty && !first) { first = c; }
            });
            if (first) { /** @type {HTMLElement} */ (first).focus(); }
            return first;
        }

        function resetNative() {
            plainControls().forEach(function (c) {
                if (c instanceof HTMLInputElement && (c.type === 'checkbox' || c.type === 'radio')) {
                    c.checked = c.defaultChecked;
                } else if (c instanceof HTMLSelectElement) {
                    var d = Array.prototype.filter.call(c.options, function (x) { return x.defaultSelected; });
                    if (d.length) { c.value = d[0].value; } else { c.selectedIndex = 0; }
                } else {
                    c.value = c.defaultValue;
                }
                fieldError(c, null);
            });
        }

        function reset() {
            resetNative();
            parts.forEach(function (p) {
                if (p.kind === 'dateRange') {
                    var rd = rangeDefaults.get(p.el);
                    p.api.setValue(rd ? {from: rd.from, to: rd.to} : p.el.getAttribute('data-default') || {from: null, to: null});
                } else {
                    p.api.reset();
                }
            });
            if (o.onReset) { o.onReset(); }
        }

        function submitButton() {
            return form.querySelector('button[type="submit"], button:not([type])');
        }

        /** @return {Promise<unknown>} */
        function search() {
            if (running) { return running; }
            // 준비(공통코드 채우기 + 주소 링크 조건 채우기) 전에 부른 첫 조회는 준비를 기다린다 — ready 를 기다리지 않고
            // 바로 sp.search() 하는 화면에서 링크 조건이 첫 조회에 빠지던 것(홈 5단계 브라우저 확인, docs/37 17.7). 준비 뒤에는 그대로
            if (!readyDone) {
                running = readyGate.then(function () { running = null; return search(); });
                return running;
            }
            if (validate()) { return Promise.resolve(null); }
            var v = values();
            var result = o.onSearch ? o.onSearch(v) : null;
            if (!isThenable(result)) { return Promise.resolve(result); }
            var btn = submitButton();
            if (btn) { btn.setAttribute('aria-busy', 'true'); }
            var done = function () {
                running = null;
                if (btn) { btn.removeAttribute('aria-busy'); }
            };
            running = Promise.resolve(result).then(function (x) { done(); return x; }, function (e) { done(); throw e; });
            return running;
        }

        // 한글 조합: compositionstart~end 사이 Enter 는 조합 확정이지 조회가 아니다(브라우저마다 keydown 순서가 달라 둘 다 본다)
        form.addEventListener('compositionstart', function () { composing = true; });
        form.addEventListener('compositionend', function () { composing = false; });
        form.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && (e.isComposing || e.keyCode === 229 || composing)) { e.preventDefault(); }
        }, true);
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (composing) { return; }
            search().then(null, function () { /* 화면이 onSearch 안에서 알린다 */ });
        });
        form.addEventListener('reset', function (e) {
            e.preventDefault();
            reset();
        });

        // I12: 처음 열 때, 보이는 탭이면 첫 조건에 포커스
        if (form.getAttribute('data-autofocus') === 'true') {
            /** 셸의 탭 iframe(부모 문서 요소라 instanceof 로 확인하지 않는다) @type {HTMLElement | null} */
            var frame = null;
            try { frame = /** @type {HTMLElement | null} */ (global.frameElement); } catch (err) { frame = null; }
            var visible = !document.hidden && !(frame && frame.hidden);
            var active = document.activeElement;
            if (visible && (!active || active === document.body)) {
                /** @type {HTMLElement | undefined} */
                var target = Array.prototype.filter.call(form.elements, function (c) {
                    return !c.disabled && c.type !== 'hidden' && !/^(submit|reset|button)$/.test(c.type);
                })[0];
                if (target) { target.focus({preventScroll: true}); }
            }
        }

        // 화면 JS 가 패널을 만들기 전에 채운 값(예: 기본 기간 오늘-6~오늘)도 기본값으로 — 링크 되돌림·[초기화]가 HTML 빈 값이 아니라
        // 처음 연 탭과 같은 값으로 돌아가게(docs/37 14.2). 주소 링크 조건은 이 뒤(ready)에 채우므로 기본값에 섞이지 않는다
        plainControls().forEach(rememberDefault);

        var readyDone = false;
        /** 컴포넌트(공통코드 채우기 등) 준비 + 주소 링크 조건 채우기 @type {Promise<void>} */
        var readyPromise = Promise.all(parts.map(function (p) { return p.kind === 'select' ? p.api.ready : null; }))
            .then(function () { applyLinkQuery(setValues); readyDone = true; });
        /** 첫 조회 대기용 — 준비가 실패해도(공통코드 못 읽음) 조회는 막지 않는다(전과 같게). ready 자체의 실패는 화면이 그대로 본다 */
        var readyGate = readyPromise.then(null, function () { readyDone = true; });

        /**
         * 이미 열린 화면에 링크 조건(셸이 같은 탭을 다시 열 때, docs/37 14.2·22.2) — 조회 칸을 **기본값으로 되돌린 뒤** 링크 값을 채운다.
         * 링크에 없는 칸(검색어 등)에 이전 값이 남으면 목록 건수가 카드 숫자보다 적게 보인다. 그다음 화면 훅 onLink(조건 전체 — 조회 칸
         * 밖의 키 포함)를 부르고, 훅이 true 면 화면이 처리한 것(예: 그 문서 열기)이라 조회하지 않는다
         * @param {Record<string, string>} q
         */
        function link(q) {
            return readyPromise.then(function () {
                reset();
                setValues(q);
                var handled = o.onLink ? o.onLink(q) : false;
                return handled === true ? null : search();
            });
        }

        /** @type {HB.SearchPanel} */
        var api = {
            values: values,
            setValue: setValue,
            setValues: setValues,
            setDefault: setDefault,
            validate: validate,
            reset: reset,
            search: search,
            link: link,
            /** 컴포넌트(공통코드 채우기 등)가 준비되고, 주소의 조회 조건(q.이름)을 채운 뒤 — 화면은 이 뒤에 첫 조회를 한다 */
            ready: readyPromise
        };
        form.hbSearchPanel = api;
        return api;
    }

    /** 링크 조건 한도 — 홈 카드 등이 보내는 조건은 조회 칸 이름·짧은 값뿐(서버 HomeService.safeLink 와 같게) */
    var LINK_NAME = /^[a-zA-Z][a-zA-Z0-9]{0,29}$/;
    var LINK_MAX = 6;
    var LINK_VALUE_MAX = 200;
    var linkApplied = false;

    /**
     * 링크 조건 정리: 이름 형식·개수·길이를 넘으면 통째로 버린다(일부만 채우면 엉뚱한 조회가 된다)
     * @param {Record<string, unknown> | null | undefined} params @return {Record<string, string> | null}
     */
    function cleanLink(params) {
        if (!params) { return null; }
        var keys = Object.keys(params);
        if (!keys.length || keys.length > LINK_MAX) { return null; }
        /** @type {Record<string, string>} */
        var out = {};
        for (var i = 0; i < keys.length; i++) {
            var v = params[keys[i]];
            if (!LINK_NAME.test(keys[i]) || typeof v !== 'string' || v.length > LINK_VALUE_MAX) { return null; }
            out[keys[i]] = v;
        }
        return out;
    }

    /** 주소의 ?q.이름=값 → 처음 만든 조회 패널에 한 번만(docs/37 14.2). 없는 칸 이름은 setValue 가 무시한다 @param {(o: Record<string, unknown>) => void} setValues */
    function applyLinkQuery(setValues) {
        if (linkApplied) { return; }
        linkApplied = true;
        /** @type {Record<string, string>} */
        var raw = {};
        new URLSearchParams(global.location.search).forEach(function (v, k) {
            if (k.indexOf('q.') === 0) { raw[k.slice(2)] = v; }
        });
        var q = cleanLink(raw);
        if (q) { setValues(q); }
    }

    /**
     * 이미 열린 화면에 링크 조건 적용(셸이 같은 탭을 다시 열 때) — 조회 칸을 기본값으로 되돌리고 링크 값을 채운 뒤 화면 훅·조회
     * (SearchPanel.link). 패널이 없거나 조건이 틀리면 false
     * @type {HB.Hb['applyLinkQuery']}
     */
    function applyLink(params) {
        var q = cleanLink(params);
        var form = /** @type {HTMLFormElement | null} */ (document.querySelector('form[data-hb="searchPanel"]'));
        var sp = form && form.hbSearchPanel;
        if (!q || !sp) { return false; }
        sp.link(q).then(null, function () { /* 화면이 onSearch·onLink 안에서 알린다 */ });
        return true;
    }

    /** @param {unknown} x @return {x is PromiseLike<unknown>} */
    function isThenable(x) {
        return !!x && typeof (/** @type {{then?: unknown}} */ (x)).then === 'function';
    }

    hb.select = select;
    hb.number = number;
    hb.searchPanel = searchPanel;
    hb.applyLinkQuery = applyLink;
})(window);
