// @ts-check
/*
 * hb.dateRange — 기간(From~To) 선택기 (docs/16 I1·I3·I4·I8, 5장)
 *   [시작 칸] ~ [종료 칸] [달력]  (n일)
 *   팝업: 왼쪽 프리셋 · 오른쪽 2개월 달력(좁으면 1개월) — 첫 클릭 시작, 두 번째 클릭 종료(마우스 미리보기)
 * - 키보드만으로: 칸에 직접 입력(20261001·1015 …) → Tab → Enter. Alt+↓ 로 팝업, Esc 로 닫기(포커스는 달력 버튼으로)
 * - 시작 > 종료: 달력 = 맞바꿈, 직접 입력 = 오류 표시(조용히 고치지 않음), 붙여넣기 = 정렬 + 안내. 서버도 다시 검사(DateRanges)
 * - 오늘·프리셋은 회사 시간대(hb.dates). 프리셋은 기준일 max 로 보정(활용 현황은 어제까지)
 * 선언: <div data-hb="dateRange" data-from-name="from" data-to-name="to" th:data-label="#{…}" data-default="last7"
 *        data-max="yesterday" data-max-days="366" data-presets="yesterday,last7,…"></div>
 * 의존: hb.js(hb.el·hb.t·hb.session), hb.dates, hb.calendar. CSS: page.css 의 .hb-dr*, .hbc*
 */
(function (global) {
    'use strict';

    var D = global.hb.dates;
    var seq = 0;

    /** 프리셋 이름 — I18nKeyGuardTest 가 리터럴 키만 인정하므로 표로 둔다 @param {string} key */
    function presetLabel(key) {
        switch (key) {
        case 'today': return global.hb.t('js.dr.preset.today');
        case 'yesterday': return global.hb.t('js.dr.preset.yesterday');
        case 'thisWeek': return global.hb.t('js.dr.preset.thisWeek');
        case 'last7': return global.hb.t('js.dr.preset.last7');
        case 'last30': return global.hb.t('js.dr.preset.last30');
        case 'thisMonth': return global.hb.t('js.dr.preset.thisMonth');
        case 'lastMonth': return global.hb.t('js.dr.preset.lastMonth');
        default: return key;
        }
    }

    /** @param {HB.DateRangeErrorCode} code @param {unknown} [a] @param {unknown} [b] */
    function errorText(code, a, b) {
        switch (code) {
        case 'REQUIRED': return global.hb.t('js.dr.err.required');
        case 'INVALID_DATE': return global.hb.t('js.dr.err.invalidDate');
        case 'INVALID_RANGE': return global.hb.t('js.dr.err.invalidRange');
        case 'RANGE_TOO_LONG': return global.hb.t('js.dr.err.tooLong', a, b);
        case 'OUT_OF_BOUNDS': return global.hb.t('js.dr.err.outOfBounds', a);
        default: return code;
        }
    }

    /** @type {HB.Hb['dateRange']} */
    function dateRange(host, options) {
        var o = Object.assign({
            fromName: host.getAttribute('data-from-name') || 'from',
            toName: host.getAttribute('data-to-name') || 'to',
            label: host.getAttribute('data-label') || '',   // 서버가 푼 문구(템플릿 th:data-label) — JS 는 js.* 문구만 알기 때문
            presets: (host.getAttribute('data-presets') || 'today,yesterday,thisWeek,last7,thisMonth,lastMonth').split(','),
            defaultValue: host.getAttribute('data-default') || null,
            max: host.getAttribute('data-max') || null,
            maxDays: +(host.getAttribute('data-max-days') || 0) || null,
            required: host.getAttribute('data-required') !== 'false',
            weekStart: 1
        }, options || {});
        var id = 'hbdr' + (++seq);
        /** @type {((v: HB.DateRangeValue) => void)[]} */
        var listeners = [];
        /** @type {{from: string | null, to: string | null, preset: string | null}} */
        var state = {from: null, to: null, preset: null};
        /** 팝업 안에서 고르는 중 @type {{from: string, to: string | null} | null} */
        var draft = null;
        /** @type {string | null} */
        var hover = null;

        function maxDate() {
            return D.resolve(o.max);
        }

        // ---------- DOM ----------
        host.classList.add('hb-dr-field');
        host.textContent = '';
        var label = global.hb.el('span', 'hb-dr-label', o.label);
        label.id = id + '-label';
        var box = global.hb.el('div', 'hb-dr');
        box.setAttribute('role', 'group');
        box.setAttribute('aria-labelledby', label.id);
        var fromIn = input(o.fromName, global.hb.t('js.dr.from'));
        var toIn = input(o.toName, global.hb.t('js.dr.to'));
        var tilde = global.hb.el('span', 'hb-dr-tilde', '~');
        tilde.setAttribute('aria-hidden', 'true');
        var days = global.hb.el('span', 'hb-dr-days');
        days.setAttribute('aria-live', 'polite');
        box.appendChild(fromIn);
        box.appendChild(tilde);
        box.appendChild(toIn);
        box.appendChild(days);
        var hint = global.hb.el('span', 'hb-dr-hint', global.hb.t('js.dr.format') + (o.maxDays ? ' · ' + global.hb.t('js.dr.max', o.maxDays) : ''));
        hint.id = id + '-hint';
        var err = global.hb.el('span', 'hb-field-error');
        err.id = id + '-err';
        err.setAttribute('role', 'alert');
        err.hidden = true;
        // 달력 버튼 없이 칸을 누르면 달력이 열린다(2026-10-26 사용자) — 형식 안내는 화면 대신 title·읽기 프로그램으로
        [fromIn, toIn].forEach(function (i) {
            i.setAttribute('aria-describedby', hint.id + ' ' + err.id);
            i.setAttribute('aria-haspopup', 'dialog');
            i.setAttribute('aria-expanded', 'false');
            i.setAttribute('aria-controls', id + '-pop');
            i.title = hint.textContent || '';
        });
        /** 달력을 연 칸 — 닫을 때 포커스를 돌려준다 @type {HTMLElement} */
        var opener = fromIn;

        var pop = global.hb.el('div', 'hb-dr-pop');
        pop.id = id + '-pop';
        pop.setAttribute('role', 'dialog');
        pop.setAttribute('aria-label', global.hb.t('js.dr.title'));
        pop.hidden = true;
        var presetList = global.hb.el('ul', 'hb-dr-presets');
        var calHost = global.hb.el('div', 'hb-dr-cal');
        var foot = global.hb.el('div', 'hb-dr-foot');
        var status = global.hb.el('span', 'hb-dr-status');
        status.setAttribute('aria-live', 'polite');
        var closeBtn = global.hb.el('button', 'hb-btn-secondary hb-btn-small', global.hb.t('js.common.close'));
        closeBtn.type = 'button';
        foot.appendChild(status);
        foot.appendChild(closeBtn);
        var body = global.hb.el('div', 'hb-dr-body');
        body.appendChild(presetList);
        body.appendChild(calHost);
        pop.appendChild(body);
        pop.appendChild(foot);

        host.appendChild(label);
        host.appendChild(box);
        host.appendChild(hint);
        host.appendChild(err);
        host.appendChild(pop);

        /** @param {string} name @param {string} ariaLabel */
        function input(name, ariaLabel) {
            var i = global.hb.el('input', 'hb-dr-input');
            i.type = 'text';
            i.name = name;
            i.autocomplete = 'off';
            i.spellcheck = false;
            i.inputMode = 'numeric';
            i.maxLength = 10;
            i.placeholder = 'YYYY-MM-DD';
            i.setAttribute('aria-label', ariaLabel);
            return i;
        }

        // ---------- 값·검증 ----------
        function show() {
            fromIn.value = state.from || '';
            toIn.value = state.to || '';
            days.textContent = state.from && state.to && state.from <= state.to
                ? global.hb.t('js.dr.days', D.diffDays(state.from, state.to)) : '';
        }

        /**
         * @param {HB.DateRangeErrorCode | null | undefined} code
         * @param {'from' | 'to'} [field]
         * @param {unknown} [a]
         * @param {unknown} [b]
         */
        function setError(code, field, a, b) {
            [fromIn, toIn].forEach(function (i) { i.removeAttribute('aria-invalid'); });
            if (!code) {
                err.hidden = true;
                err.textContent = '';
                return;
            }
            err.textContent = errorText(code, a, b);
            err.hidden = false;
            (field === 'from' ? fromIn : toIn).setAttribute('aria-invalid', 'true');
        }

        /** 칸의 글자 → state. 형식만 맞추고(20261001 → 2026-10-01) 값은 바꾸지 않는다 */
        function readInputs() {
            var f = D.parse(fromIn.value);
            var t = D.parse(toIn.value, {year: f.ok ? +f.value.substring(0, 4) : undefined});
            state.from = f.ok ? f.value : null;
            state.to = t.ok ? t.value : null;
            return {f: f, t: t};
        }

        /** 화면에 오류까지 표시 @return {HB.DateRangeCheck} */
        function validate() {
            var r = readInputs();
            /** @type {HB.DateRangeCheck} */
            var res = {ok: true};
            var mx = maxDate();
            if (!r.f.ok && r.f.code !== 'EMPTY') { res = {ok: false, code: 'INVALID_DATE', field: 'from'}; }
            else if (!r.t.ok && r.t.code !== 'EMPTY') { res = {ok: false, code: 'INVALID_DATE', field: 'to'}; }
            else if (o.required && !r.f.ok) { res = {ok: false, code: 'REQUIRED', field: 'from'}; }
            else if (o.required && !r.t.ok) { res = {ok: false, code: 'REQUIRED', field: 'to'}; }
            else if (state.from && state.to && state.from > state.to) { res = {ok: false, code: 'INVALID_RANGE', field: 'to'}; }
            else if (state.to && mx && state.to > mx) { res = {ok: false, code: 'OUT_OF_BOUNDS', field: 'to', a: mx}; }
            else if (state.from && state.to && o.maxDays && D.diffDays(state.from, state.to) > o.maxDays) {
                res = {ok: false, code: 'RANGE_TOO_LONG', field: 'to', a: D.diffDays(state.from, state.to), b: o.maxDays};
            }
            if (res.ok) {
                show();
                setError(null);
            } else {
                // 각 칸이 날짜로는 맞으면 형식만 맞춰 보여 준다(값은 그대로 — 순서·상한 오류는 사용자가 고친다)
                if (r.f.ok) { fromIn.value = r.f.value; }
                if (r.t.ok) { toIn.value = r.t.value; }
                days.textContent = '';
                setError(res.code, res.field, res.a, res.b);
            }
            return res;
        }

        function emit() {
            var v = value();
            host.dispatchEvent(new CustomEvent('hb:change', {detail: v, bubbles: true}));
            listeners.forEach(function (fn) { fn(v); });
        }

        /** @param {string | null} from @param {string | null} to @param {string | null} [preset] */
        function commit(from, to, preset) {
            state.from = from;
            state.to = to;
            state.preset = preset || null;
            show();
            validate();
            emit();
        }

        /** @return {HB.DateRangeValue} */
        function value() {
            return {from: state.from, to: state.to, preset: state.preset,
                days: state.from && state.to ? D.diffDays(state.from, state.to) : null};
        }

        /** @type {HB.DateRange['setValue']} */
        function setValue(v) {
            if (typeof v === 'string') {
                var p = D.preset(v, {max: maxDate(), maxDays: o.maxDays, weekStart: o.weekStart});
                if (p) { commit(p.from, p.to, v); }
                return;
            }
            commit(v && v.from || null, v && v.to || null, null);
        }

        // ---------- 칸 직접 입력·붙여넣기 ----------
        [fromIn, toIn].forEach(function (i) {
            i.addEventListener('input', function () { state.preset = null; });
            i.addEventListener('change', function () {
                var r = validate();
                if (r.ok) { emit(); }
            });
            i.addEventListener('keydown', function (e) {
                if (e.isComposing) { return; }   // 한글 조합 중 키는 건드리지 않는다
                if (e.altKey && e.key === 'ArrowDown') {
                    e.preventDefault();
                    opener = i;
                    open(true);
                } else if (e.key === 'Escape' && !pop.hidden) {
                    e.preventDefault();
                    close(true);
                }
            });
            // 누르면 연다 — 포커스는 칸에 둬서 그대로 쳐 넣거나 붙여 넣을 수 있다(달력 안 키보드 이동은 Alt+↓)
            i.addEventListener('click', function () {
                if (i.disabled || i.readOnly) { return; }
                opener = i;
                open(false);
            });
            i.addEventListener('paste', function (e) {
                var text = e.clipboardData ? e.clipboardData.getData('text') : '';
                var r = D.parseRange(text);
                if (!r) { return; }            // 날짜 하나 — 칸에 그대로
                e.preventDefault();
                if (!r.ok) {
                    i.value = text.trim().substring(0, 10);
                    setError('INVALID_DATE', i === fromIn ? 'from' : 'to');
                    return;
                }
                fromIn.value = r.from;
                toIn.value = r.to;
                state.preset = null;
                var v = validate();
                if (v.ok) {
                    emit();
                    if (r.swapped) { days.textContent += ' · ' + global.hb.t('js.dr.swapped'); }
                }
            });
        });

        // ---------- 팝업 ----------
        /** @type {HB.Calendar | null} */
        var cal = null;

        /** @param {string} iso @return {HB.CalendarCellState | null} */
        function stateOf(iso) {
            var a = draft ? draft.from : state.from;
            var b = draft ? (draft.to || hover) : state.to;
            if (!a) { return null; }
            if (!b) { return iso === a ? 'single' : null; }
            var lo = a <= b ? a : b;
            var hi = a <= b ? b : a;
            if (iso === lo && iso === hi) { return 'single'; }
            if (iso === lo) { return 'start'; }
            if (iso === hi) { return 'end'; }
            if (iso > lo && iso < hi) { return draft && !draft.to ? 'preview' : 'in'; }
            return null;
        }

        /** @param {string} iso */
        function disabledOf(iso) {
            var mx = maxDate();
            if (mx && iso > mx) { return true; }
            // 시작을 고른 뒤에는 상한 밖 날짜를 막는다(양쪽 방향)
            if (draft && draft.from && !draft.to && o.maxDays) {
                var lo = iso < draft.from ? iso : draft.from;
                var hi = iso < draft.from ? draft.from : iso;
                return D.diffDays(lo, hi) > o.maxDays;
            }
            return false;
        }

        function renderPresets() {
            presetList.textContent = '';
            o.presets.forEach(function (key) {
                var p = D.preset(key, {max: maxDate(), maxDays: o.maxDays, weekStart: o.weekStart});
                if (!p) { return; }
                var li = global.hb.el('li');
                var b = global.hb.el('button', 'hb-dr-preset');
                b.type = 'button';
                b.setAttribute('aria-pressed', String(state.preset === key));
                b.appendChild(global.hb.el('span', 'hb-dr-preset-name', presetLabel(key)));
                var from = p.from;
                var to = p.to;
                b.appendChild(global.hb.el('span', 'hb-dr-preset-range', from === to ? from : from + ' ~ ' + to));
                b.addEventListener('click', function () {
                    commit(from, to, key);
                    close(true);
                });
                li.appendChild(b);
                presetList.appendChild(li);
            });
        }

        function updateStatus() {
            if (draft && !draft.to) {
                status.textContent = global.hb.t('js.dr.pickEnd', draft.from);
            } else {
                status.textContent = global.hb.t('js.dr.pickStart') + (o.maxDays ? ' · ' + global.hb.t('js.dr.max', o.maxDays) : '');
            }
        }

        function monthsFor() {
            return global.matchMedia('(max-width: 599px)').matches ? 1 : 2;
        }

        /** @param {boolean} [focusCal] 달력으로 포커스를 옮긴다(키보드로 열 때, 기본 true) */
        function open(focusCal) {
            if (!pop.hidden) { return; }
            validate();
            draft = null;
            hover = null;
            renderPresets();
            updateStatus();
            pop.hidden = false;
            [fromIn, toIn].forEach(function (x) { x.setAttribute('aria-expanded', 'true'); });
            // 화면 오른쪽을 넘으면 오른쪽 정렬(클래스만 — 인라인 style 없음)
            pop.classList.remove('hb-dr-pop-right');
            if (pop.getBoundingClientRect().right > global.innerWidth - 8) { pop.classList.add('hb-dr-pop-right'); }
            var start = state.from || maxDate() || D.today();
            // 두 달 표시면 시작 달이 왼쪽 — 종료가 다음 달이면 그대로 보인다
            calHost.textContent = '';
            cal = global.hb.calendar(calHost, {
                months: monthsFor(), weekStart: o.weekStart, focus: start,
                state: stateOf, disabled: disabledOf,
                labels: {prev: global.hb.t('js.dr.prevMonth'), next: global.hb.t('js.dr.nextMonth')},
                onHover: function (iso) {
                    if (draft && !draft.to && iso !== hover) { hover = iso; if (cal) { cal.paint(); } }
                },
                onPick: function (iso) {
                    if (!draft || draft.to) {
                        draft = {from: iso, to: null};
                        hover = null;
                        updateStatus();
                        if (cal) { cal.paint(); }
                        return;
                    }
                    var a = draft.from;
                    var lo = a <= iso ? a : iso;          // 달력은 두 점이 범위 — 거꾸로 골라도 맞바꾼다(I3)
                    var hi = a <= iso ? iso : a;
                    draft = null;
                    commit(lo, hi, null);
                    close(true);
                }
            });
            if (focusCal !== false) { cal.focus(start); }
        }

        /** @param {boolean} [returnFocus] */
        function close(returnFocus) {
            if (pop.hidden) { return; }
            pop.hidden = true;
            draft = null;
            hover = null;
            [fromIn, toIn].forEach(function (x) { x.setAttribute('aria-expanded', 'false'); });
            calHost.textContent = '';
            cal = null;
            if (returnFocus) { opener.focus(); }
        }

        closeBtn.addEventListener('click', function () { close(true); });
        pop.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                close(true);
            }
        });
        document.addEventListener('mousedown', function (e) {
            if (!pop.hidden && !host.contains(/** @type {Node | null} */ (e.target))) { close(false); }
        });

        // ---------- 시작값 ----------
        if (o.defaultValue) { setValue(o.defaultValue); } else { show(); }

        return {
            value: value,
            setValue: setValue,
            validate: validate,
            /** 서버 요청용 — 프리셋이면 이 순간 다시 계산(내일 열어도 "지난 7일") */
            toQuery: function () {
                if (state.preset) { setValue(state.preset); }
                var r = validate();
                return r.ok ? {from: state.from, to: state.to} : null;
            },
            on: function (/** @type {string} */ name, /** @type {(v: HB.DateRangeValue) => void} */ fn) { if (name === 'hb:change') { listeners.push(fn); } },
            setDisabled: function (/** @type {boolean} */ b) {
                [fromIn, toIn].forEach(function (x) { x.disabled = !!b; });
                if (b) { close(false); }
            },
            /** 서버 필드 오류(from/to)를 이 컨트롤에 표시 */
            showServerError: function (/** @type {string} */ field, /** @type {string} */ message) {
                setError('INVALID_RANGE', field === 'from' ? 'from' : 'to');
                err.textContent = message;
            },
            open: open,
            close: close
        };
    }

    global.hb.dateRange = dateRange;
})(window);
