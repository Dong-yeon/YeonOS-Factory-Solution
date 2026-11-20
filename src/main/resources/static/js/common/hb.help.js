// @ts-check
/*
 * hb.help — 화면 도움말 대화상자 (docs/46): [도움말] → 이 화면이 왜 있고, 언제(어느 업무 단계에서) 쓰고, 무엇을 하는지
 *   + 앞·뒤 화면(누르면 그 탭 열기) + 이 화면의 칸 설명.
 * - 문구 = help.{progCd}(무엇을) · help.{progCd}.helpWhy · .helpWhen · .helpDo(동작마다 \n) — i18n, 모든 sys_prog 에 있어야 한다(ScreenHelpGuardTest)
 * - 앞·뒤 화면 = /js/common/help-flow.json(docs/46 화면 지도가 원본). 이름·권한은 내 메뉴(/api/platform/menu) —
 *   메뉴에 없는 화면은 열기 버튼 없이 "내 메뉴에 없음"으로 보인다(권한 판정은 서버, 여기서는 안내만)
 * - 칸 도움말(data-help-key → help.{progCd}.{field})은 그대로: 대화상자 안 "이 화면의 칸" 목록 + [칸 도움말 켜기](포커스를 따라가는 패널, hb.page)
 * - 글자는 textContent 로만. 모델 계산(model·flatMenu)은 DOM 없는 순수 함수 — node 시험(src/test/js/hb.help.test.js)
 */
(function (root) {
    'use strict';

    var PROG_CD = /^[a-z0-9][a-z0-9-]{1,49}$/;
    var MAX_LINKS = 5;

    /**
     * @param {unknown} list
     * @param {string} self
     * @returns {string[]}
     */
    function codes(list, self) {
        /** @type {string[]} */
        var out = [];
        if (!Array.isArray(list)) { return out; }
        list.forEach(function (c) {
            if (typeof c === 'string' && PROG_CD.test(c) && c !== self && out.indexOf(c) < 0 && out.length < MAX_LINKS) { out.push(c); }
        });
        return out;
    }

    /**
     * 대화상자에 보일 내용 — 없는 문구는 null(화면은 그 칸을 건너뛴다)
     * @param {string} prog
     * @param {(key: string) => (string | null)} text 키 → 문구, 없으면 null
     * @param {unknown} flow help-flow.json 의 이 화면 항목 {prev, next}
     * @returns {HB.HelpModel}
     */
    function model(prog, text, flow) {
        var base = 'help.' + prog;
        var f = flow && typeof flow === 'object' ? /** @type {{prev?: unknown, next?: unknown}} */ (flow) : {};
        return {
            what: sentences(text(base)),
            why: sentences(text(base + '.helpWhy')),
            when: sentences(text(base + '.helpWhen')),
            does: lines(text(base + '.helpDo')),
            note: lines(text(base + '.helpNote')),
            prev: codes(f.prev, prog),
            next: codes(f.next, prog)
        };
    }

    /** 줄(\n)마다 항목 하나 — 빈 줄·앞뒤 공백 정리 @param {string | null} s @returns {string[]} */
    function lines(s) {
        return s ? s.split('\n').map(function (x) { return x.trim(); }).filter(function (x) { return !!x; }) : [];
    }

    /**
     * 긴 문단을 문장(짧은 문단)으로 — 글 한 덩어리로 보이지 않게(2026-10-26 사용자 피드백). 줄바꿈이 있으면 줄 그대로,
     * 없으면 문장 끝(. ! ?) 뒤 공백에서 나눈다. 괄호 안 마침표는 나누지 않는다
     * @param {string | null} s @returns {string[]}
     */
    function sentences(s) {
        if (!s) { return []; }
        if (s.indexOf('\n') >= 0) { return lines(s); }
        /** @type {string[]} */
        var out = [];
        var depth = 0;
        var start = 0;
        for (var i = 0; i < s.length; i++) {
            var c = s.charAt(i);
            if (c === '(' || c === '（') { depth++; } else if ((c === ')' || c === '）') && depth > 0) { depth--; }
            else if (depth === 0 && (c === '.' || c === '!' || c === '?') && /\s/.test(s.charAt(i + 1))) {
                out.push(s.slice(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (s.slice(start).trim()) { out.push(s.slice(start).trim()); }
        return out;
    }

    /**
     * 메뉴 나무 → 화면 코드별 이름(처음 나온 이름)
     * @param {unknown} nodes /api/platform/menu 의 data
     * @returns {Record<string, string>}
     */
    function flatMenu(nodes) {
        /** @type {Record<string, string>} */
        var names = {};
        /** @param {unknown} list */
        function walk(list) {
            if (!Array.isArray(list)) { return; }
            list.forEach(function (n) {
                if (!n || typeof n !== 'object') { return; }
                var node = /** @type {{progCd?: unknown, name?: unknown, children?: unknown}} */ (n);
                if (typeof node.progCd === 'string' && typeof node.name === 'string'
                        && !Object.prototype.hasOwnProperty.call(names, node.progCd)) {
                    names[node.progCd] = node.name;
                }
                walk(node.children);
            });
        }
        walk(nodes);
        return names;
    }

    if (typeof module === 'object' && module.exports) {
        module.exports = {model: model, flatMenu: flatMenu, sentences: sentences};
        return;
    }

    var hb = /** @type {HB.Hb} */ (/** @type {any} */ (root).hb);

    /** @type {Promise<Record<string, unknown>> | null} */
    var flowLoad = null;
    /** @type {Promise<Record<string, string>> | null} */
    var menuLoad = null;

    function loadFlow() {
        if (!flowLoad) {
            flowLoad = fetch('/js/common/help-flow.json', {credentials: 'same-origin'})
                .then(function (r) { return r.ok ? r.json() : {}; })
                .then(function (j) { return /** @type {Record<string, unknown>} */ (j && typeof j === 'object' ? j : {}); },
                    function () { flowLoad = null; return /** @type {Record<string, unknown>} */ ({}); });
        }
        return flowLoad;
    }

    function loadMenu() {
        if (!menuLoad) {
            menuLoad = hb.api('/api/platform/menu').then(function (res) { return flatMenu(res && res.data); },
                function () { menuLoad = null; return {}; });
        }
        return menuLoad;
    }

    /** @param {string} key */
    function text(key) {
        var s = hb.t(key);
        return s && s !== key ? s : null;
    }

    /** @param {string} progCd @param {string} title */
    function openScreen(progCd, title) {
        var top = window.top && window.top !== window ? window.top.hbShell : null;
        if (top && top !== true) {
            top.open(progCd, title, null);
        } else {
            window.location.href = '/page/' + encodeURIComponent(progCd);
        }
    }

    /**
     * 칸 이름 — 감싼 label 의 첫 글자 칸(span), 없으면 label 의 글자 노드, aria-label, data-field 순
     * @param {Element} el
     */
    function fieldLabel(el) {
        var aria = el.getAttribute('aria-label');
        var label = el.closest('label');
        if (label) {
            var span = label.querySelector('span');
            if (span && span.textContent && span.textContent.trim()) { return span.textContent.trim(); }
            var own = '';
            label.childNodes.forEach(function (n) { if (n.nodeType === 3) { own += n.textContent; } });
            if (own.trim()) { return own.trim(); }
        }
        if (aria) { return aria; }
        return el.getAttribute('data-field') || el.getAttribute('name') || '';
    }

    /** 이 화면의 칸 도움말(문구가 있는 것만, 같은 키는 한 번) */
    function fields() {
        /** @type {{key: string, label: string, text: string}[]} */
        var out = [];
        /** @type {Record<string, boolean>} */
        var seen = {};
        document.querySelectorAll('[data-help-key]').forEach(function (el) {
            var key = el.getAttribute('data-help-key') || '';
            var s = key ? text('help.' + key) : null;
            if (!s || seen[key]) { return; }
            seen[key] = true;
            out.push({key: key, label: fieldLabel(el) || key, text: s});
        });
        return out;
    }

    /**
     * 제목 + 짧은 문단들(또는 항목 목록)
     * @param {string} title @param {string[]} parts @param {boolean} list true = 글머리 목록
     */
    function section(title, parts, list) {
        var sec = hb.el('section', 'hb-help-sec');
        sec.appendChild(hb.el('h3', null, title));
        if (list) {
            var ul = hb.el('ul', 'hb-help-list');
            parts.forEach(function (s) { ul.appendChild(hb.el('li', null, s)); });
            sec.appendChild(ul);
        } else {
            parts.forEach(function (s) { sec.appendChild(hb.el('p', null, s)); });
        }
        return sec;
    }

    /**
     * @param {string[]} list
     * @param {Record<string, string>} names
     * @param {HTMLDialogElement} d
     */
    function links(list, names, d) {
        var box = hb.el('div', 'hb-help-links');
        list.forEach(function (c) {
            var name = names[c];
            if (name) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-help-link', name));
                b.type = 'button';
                b.title = hb.t('js.help.openTab', name);
                b.addEventListener('click', function () { d.close(); openScreen(c, name); });
                box.appendChild(b);
            } else {
                var s = hb.el('span', 'hb-help-link-off', hb.t('js.help.notInMenu', c));
                box.appendChild(s);
            }
        });
        if (!list.length) { box.appendChild(hb.el('span', 'hb-muted', hb.t('js.help.noLink'))); }
        return box;
    }

    /**
     * @param {{prog: string, title: string, focusKey?: string | null, onFieldHelp?: (() => void) | null}} o
     * @returns {Promise<HTMLDialogElement>}
     */
    function open(o) {
        var opener = /** @type {HTMLElement | null} */ (document.activeElement);
        return Promise.all([loadFlow(), loadMenu()]).then(function (r) {
            var m = model(o.prog, text, r[0][o.prog]);
            var names = r[1];
            var d = /** @type {HTMLDialogElement} */ (hb.el('dialog', 'hb-dialog hb-help-dialog'));
            d.setAttribute('aria-labelledby', 'hbHelpDlgT');
            var h = hb.el('h2', null, hb.t('js.help.dialogTitle', o.title));
            h.id = 'hbHelpDlgT';
            d.appendChild(h);
            var body = hb.el('div', 'hb-dialog-body');
            var fs = fields();
            var now = o.focusKey ? fs.filter(function (f) { return f.key === o.focusKey; })[0] : null;
            if (now) {
                var cur = section(hb.t('js.help.nowField', now.label), [now.text], false);
                cur.classList.add('hb-help-now');
                body.appendChild(cur);
            }
            var what = hb.el('div', 'hb-help-what');
            (m.what.length ? m.what : [hb.t('js.help.none')]).forEach(function (s) { what.appendChild(hb.el('p', null, s)); });
            body.appendChild(what);
            if (m.why.length) { body.appendChild(section(hb.t('js.help.why'), m.why, false)); }
            if (m.when.length) { body.appendChild(section(hb.t('js.help.when'), m.when, false)); }
            if (m.does.length) { body.appendChild(section(hb.t('js.help.does'), m.does, true)); }
            if (m.note.length) {
                var note = section(hb.t('js.help.note'), m.note, true);
                note.classList.add('hb-help-note');
                body.appendChild(note);
            }
            var flow = section(hb.t('js.help.flow'), [], false);
            var grid = hb.el('div', 'hb-help-flow');
            grid.appendChild(hb.el('span', 'hb-help-flow-label', hb.t('js.help.prev')));
            grid.appendChild(links(m.prev, names, d));
            grid.appendChild(hb.el('span', 'hb-help-flow-label', hb.t('js.help.next')));
            grid.appendChild(links(m.next, names, d));
            flow.appendChild(grid);
            body.appendChild(flow);
            if (fs.length) {
                var det = hb.el('details', 'hb-help-fields');
                det.appendChild(hb.el('summary', null, hb.t('js.help.fields', fs.length)));
                var dl = hb.el('dl');
                fs.forEach(function (f) {
                    dl.appendChild(hb.el('dt', null, f.label));
                    dl.appendChild(hb.el('dd', null, f.text));
                });
                det.appendChild(dl);
                body.appendChild(det);
            }
            d.appendChild(body);
            var bar = hb.el('div', 'hb-dialog-bar');
            if (fs.length && o.onFieldHelp) {
                var onField = o.onFieldHelp;
                var fb = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.help.fieldMode')));
                fb.type = 'button';
                fb.title = hb.t('js.help.fieldModeTip');
                fb.addEventListener('click', function () { opener = null; d.close(); onField(); });
                bar.appendChild(fb);
            }
            var close = /** @type {HTMLButtonElement} */ (hb.el('button', null, hb.t('js.common.close')));
            close.type = 'button';
            close.addEventListener('click', function () { d.close(); });
            bar.appendChild(close);
            d.appendChild(bar);
            d.addEventListener('close', function () {
                d.remove();
                if (opener && document.contains(opener)) { opener.focus(); }
            });
            document.body.appendChild(d);
            d.showModal();
            close.focus();
            return d;
        });
    }

    hb.help = {open: open};
})(typeof window !== 'undefined' ? window : globalThis);
