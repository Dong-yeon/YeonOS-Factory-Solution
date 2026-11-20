// @ts-check
/*
 * hb.calendar — 달력 그리드 (WAI-ARIA APG date picker grid, docs/16 I2·5.3)
 * - 여러 달을 나란히(months), 날짜 칸 하나만 tabindex=0(로빙), 나머지는 -1
 * - 키: ←→ 하루, ↑↓ 한 주, Home/End 주의 시작/끝, PageUp/PageDown 한 달, Shift+PageUp/PageDown 한 해, Enter/Space 선택
 * - 날짜 상태(선택 시작·끝·사이·비활성)는 바깥이 함수로 알려 준다 — 엔진은 상태를 갖지 않는다
 * - DOM 은 createElement + textContent, 스타일은 클래스만(CSP)
 * 의존: hb.dates, hb.el
 */
(function (global) {
    'use strict';

    var D = global.hb.dates;

    function locale() {
        var s = global.hb.session ? global.hb.session() : null;
        return s && s.localeCd === 'en' ? 'en-US' : 'ko-KR';
    }

    /** @param {number} y @param {number} m */
    function monthTitle(y, m) {
        return new Intl.DateTimeFormat(locale(), {year: 'numeric', month: 'long', timeZone: 'UTC'}).format(new Date(Date.UTC(y, m - 1, 1)));
    }

    /** @param {number} wd @param {'narrow' | 'long'} style */
    function weekdayName(wd, style) {
        // 2024-01-07 은 일요일
        return new Intl.DateTimeFormat(locale(), {weekday: style, timeZone: 'UTC'}).format(new Date(Date.UTC(2024, 0, 7 + wd)));
    }

    /** @param {string} iso */
    function longDate(iso) {
        return new Intl.DateTimeFormat(locale(), {dateStyle: 'full', timeZone: 'UTC'})
            .format(new Date(Date.UTC(+iso.substring(0, 4), +iso.substring(5, 7) - 1, +iso.substring(8, 10))));
    }

    /** @param {string} iso */
    function firstOfMonth(iso) {
        return iso.substring(0, 8) + '01';
    }

    /** @param {string} iso @param {number} n */
    function addMonths(iso, n) {
        var y = +iso.substring(0, 4);
        var m = +iso.substring(5, 7) - 1 + n;
        y += Math.floor(m / 12);
        m = ((m % 12) + 12) % 12 + 1;
        var d = Math.min(+iso.substring(8, 10), D.daysInMonth(y, m));
        return y + '-' + (m < 10 ? '0' : '') + m + '-' + (d < 10 ? '0' : '') + d;
    }

    /**
     * @param {HTMLElement} host  달력을 그릴 요소
     * @param {HB.CalendarOptions} opts  {months, weekStart(1=월), focus: 'YYYY-MM-DD',
     *               state(date) → 'start'|'end'|'single'|'in'|'preview'|null, disabled(date) → bool,
     *               onPick(date), onHover(date|null), labels: {prev, next}}
     * @return {HB.Calendar}
     */
    function calendar(host, opts) {
        var months = opts.months || 1;
        var weekStart = opts.weekStart === 0 ? 0 : 1;
        var focusDate = opts.focus || D.today();
        var view = firstOfMonth(focusDate);   // 첫 번째로 보이는 달
        var root = global.hb.el('div', 'hbc');

        var nav = global.hb.el('div', 'hbc-nav');
        var prev = global.hb.el('button', 'hbc-prev');
        prev.type = 'button';
        prev.setAttribute('aria-label', opts.labels.prev);
        var next = global.hb.el('button', 'hbc-next');
        next.type = 'button';
        next.setAttribute('aria-label', opts.labels.next);
        nav.appendChild(prev);
        nav.appendChild(next);
        var monthsEl = global.hb.el('div', 'hbc-months');
        root.appendChild(nav);
        root.appendChild(monthsEl);
        host.appendChild(root);

        prev.addEventListener('click', function () { moveView(-1); });
        next.addEventListener('click', function () { moveView(1); });

        /** @param {string} iso */
        function visible(iso) {
            var last = addMonths(view, months - 1);
            return iso >= view && iso.substring(0, 7) <= last.substring(0, 7);
        }

        /** @param {number} n */
        function moveView(n) {
            view = addMonths(view, n);
            if (!visible(focusDate)) {
                focusDate = addMonths(focusDate, n);
            }
            render(false);
        }

        /** @param {boolean} [takeFocus] */
        function render(takeFocus) {
            monthsEl.textContent = '';
            for (var i = 0; i < months; i++) {
                monthsEl.appendChild(renderMonth(addMonths(view, i)));
            }
            if (takeFocus) {
                focusCell();
            }
        }

        /** @param {string} first 그 달 1일 */
        function renderMonth(first) {
            var y = +first.substring(0, 4);
            var m = +first.substring(5, 7);
            var box = global.hb.el('div', 'hbc-month');
            var title = global.hb.el('div', 'hbc-title', monthTitle(y, m));
            title.id = 'hbc-' + Math.random().toString(36).slice(2);
            title.setAttribute('aria-live', 'polite');
            box.appendChild(title);
            var table = global.hb.el('table', 'hbc-grid');
            table.setAttribute('role', 'grid');
            table.setAttribute('aria-labelledby', title.id);
            var head = global.hb.el('tr');
            for (var w = 0; w < 7; w++) {
                var wd = (weekStart + w) % 7;
                var th = global.hb.el('th', wd === 0 ? 'hbc-sun' : wd === 6 ? 'hbc-sat' : null, weekdayName(wd, 'narrow'));
                th.setAttribute('scope', 'col');
                th.setAttribute('abbr', weekdayName(wd, 'long'));
                head.appendChild(th);
            }
            var thead = global.hb.el('thead');
            thead.appendChild(head);
            table.appendChild(thead);
            var tbody = global.hb.el('tbody');
            var lead = (D.weekday(first) - weekStart + 7) % 7;
            var day = D.add(first, -lead);
            for (var r = 0; r < 6; r++) {
                var tr = global.hb.el('tr');
                for (var c = 0; c < 7; c++) {
                    tr.appendChild(cell(day, day.substring(0, 7) === first.substring(0, 7)));
                    day = D.add(day, 1);
                }
                tbody.appendChild(tr);
                if (day.substring(0, 7) !== first.substring(0, 7) && r >= 3) {
                    break;
                }
            }
            table.appendChild(tbody);
            box.appendChild(table);
            return box;
        }

        /** @param {string} iso @param {boolean} inMonth */
        function cell(iso, inMonth) {
            var td = global.hb.el('td', 'hbc-day');
            if (!inMonth) {
                td.className += ' hbc-out';   // 이웃 달 날짜는 빈칸(두 달 표시에서 중복을 피함)
                return td;
            }
            td.textContent = String(+iso.substring(8, 10));
            td.setAttribute('role', 'gridcell');
            td.setAttribute('data-date', iso);
            td.setAttribute('aria-label', longDate(iso));
            td.tabIndex = iso === focusDate ? 0 : -1;
            var wd = D.weekday(iso);
            if (wd === 0) { td.className += ' hbc-sun'; }
            if (wd === 6) { td.className += ' hbc-sat'; }
            if (iso === D.today()) {
                td.className += ' hbc-today';
                td.setAttribute('aria-current', 'date');
            }
            paintCell(td);
            return td;
        }

        var STATES = ['hbc-start', 'hbc-end', 'hbc-single', 'hbc-in', 'hbc-preview', 'hbc-disabled'];

        /** 선택·비활성 표시만 다시 칠한다(DOM 은 그대로 — 마우스 미리보기에서 다시 만들지 않게) */
        /** @param {Element} td */
        function paintCell(td) {
            var iso = td.getAttribute('data-date') || '';
            STATES.forEach(function (c) { td.classList.remove(c); });
            var st = opts.state ? opts.state(iso) : null;
            if (st) { td.classList.add('hbc-' + st); }
            td.setAttribute('aria-selected', String(st === 'start' || st === 'end' || st === 'single'));
            if (opts.disabled && opts.disabled(iso)) {
                td.classList.add('hbc-disabled');
                td.setAttribute('aria-disabled', 'true');
            } else {
                td.removeAttribute('aria-disabled');
            }
        }

        function paint() {
            monthsEl.querySelectorAll('td[data-date]').forEach(paintCell);
        }

        function focusCell() {
            var td = monthsEl.querySelector('td[data-date="' + focusDate + '"]');
            if (td instanceof HTMLElement) { td.focus(); }
        }

        /** @param {string} iso @param {boolean} takeFocus */
        function setFocus(iso, takeFocus) {
            focusDate = iso;
            if (!visible(iso)) {
                // 앞으로 넘어가면 그 달이 마지막 칸에, 뒤로 넘어가면 첫 칸에 오게
                view = iso < view ? firstOfMonth(iso) : addMonths(firstOfMonth(iso), -(months - 1));
            }
            render(takeFocus);
        }

        /** 이벤트가 난 날짜 칸(없으면 null) @param {Event} e */
        function dayCell(e) {
            return e.target instanceof Element ? e.target.closest('td[data-date]') : null;
        }

        monthsEl.addEventListener('click', function (e) {
            var td = dayCell(e);
            if (!td || td.getAttribute('aria-disabled') === 'true') { return; }
            focusDate = td.getAttribute('data-date') || focusDate;
            opts.onPick(focusDate);
        });
        monthsEl.addEventListener('mouseover', function (e) {
            var td = dayCell(e);
            if (opts.onHover) { opts.onHover(td && td.getAttribute('aria-disabled') !== 'true' ? td.getAttribute('data-date') : null); }
        });
        monthsEl.addEventListener('keydown', function (e) {
            var td = dayCell(e);
            if (!td) { return; }
            var iso = td.getAttribute('data-date') || '';
            var wdOffset = (D.weekday(iso) - weekStart + 7) % 7;
            var to = null;
            switch (e.key) {
            case 'ArrowLeft': to = D.add(iso, -1); break;
            case 'ArrowRight': to = D.add(iso, 1); break;
            case 'ArrowUp': to = D.add(iso, -7); break;
            case 'ArrowDown': to = D.add(iso, 7); break;
            case 'Home': to = D.add(iso, -wdOffset); break;
            case 'End': to = D.add(iso, 6 - wdOffset); break;
            case 'PageUp': to = addMonths(iso, e.shiftKey ? -12 : -1); break;
            case 'PageDown': to = addMonths(iso, e.shiftKey ? 12 : 1); break;
            case 'Enter':
            case ' ':
                e.preventDefault();
                if (td.getAttribute('aria-disabled') !== 'true') { opts.onPick(iso); }
                return;
            default: return;
            }
            e.preventDefault();
            setFocus(to, true);
            if (opts.onHover) { opts.onHover(to); }
        });

        render(false);

        return {
            el: root,
            /** 상태가 바뀐 뒤 다시 그리기(포커스 유지 여부) */
            refresh: function (/** @type {boolean | undefined} */ takeFocus) { render(takeFocus); },
            /** 선택 상태만 다시 칠하기 */
            paint: paint,
            focus: function (/** @type {string | null | undefined} */ iso) { setFocus(iso || focusDate, true); },
            setMonths: function (/** @type {number} */ n) { months = n; render(false); }
        };
    }

    global.hb.calendar = calendar;
})(window);
