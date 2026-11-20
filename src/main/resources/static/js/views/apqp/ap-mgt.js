// @ts-check
/*
 * AP 규칙표 (apqp-ap) — P6 APQP A1 (docs/41 4장, P3). 회사가 S·O·D 범위 → H/M/L 규칙을 입력한다(핸드북 표 시드 없음).
 * 덮임(1,000 조합을 정확히 한 번)은 입력하는 동안 화면에서 바로 세어 보여 주고, 저장 때 서버가 다시 판정한다.
 */
/**
 * @typedef {{setId: number, setCd: string, setNm: string, sampleYn: string, remark?: string | null, useYn: string, rowVer: number,
 *   ruleCnt: number, releasedCnt: number}} ApqpApSet
 * @typedef {{sevFrom: number, sevTo: number, occFrom: number, occTo: number, detFrom: number, detTo: number, ap: string}} ApqpApRule
 */
(function () {
    'use strict';

    var API = '/api/apqp/ap-sets';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var tbody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#sets tbody'));
    var rulesBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#rules tbody'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('setForm'));
    /** @type {ApqpApSet | null} */
    var current = null;
    var locked = false;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {any} err */
    function fail(err) {
        if (err && err.stale) { return; }
        notify(err && err.errors && err.errors.length ? (err.errors[0].rowKey ? '#' + err.errors[0].rowKey + ' ' : '') + err.errors[0].message
            : err && err.message, true);
        hb.showFieldErrors(form, err && err.errors);
    }

    /** @param {string} n */
    function field(n) { return /** @type {HTMLInputElement} */ (form.querySelector('[data-field="' + n + '"]')); }

    function useYn() {
        var r = /** @type {HTMLInputElement | null} */ (form.querySelector('input[name="useYn"]:checked'));
        return r ? r.value : 'N';
    }

    // ------------------------------------------------------------------ 목록

    /** @param {number | null} [selectId] */
    function loadList(selectId) {
        return hb.latest('apSets', hb.api(API)).then(function (res) {
            /** @type {ApqpApSet[]} */
            var rows = res.data;
            tbody.textContent = '';
            if (!rows.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.apqp.ap.none'));
                td0.colSpan = 5;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
            rows.forEach(function (s) {
                var tr = hb.el('tr', 'hb-row-pick' + (s.useYn === 'Y' ? '' : ' hb-tree-dim'));
                tr.tabIndex = 0;
                tr.setAttribute('data-id', String(s.setId));
                tr.setAttribute('aria-selected', current && current.setId === s.setId ? 'true' : 'false');
                tr.appendChild(hb.el('td', null, s.setCd));
                var nm = hb.el('td', null, s.setNm);
                if (s.sampleYn === 'Y') { nm.appendChild(document.createTextNode(' ')); nm.appendChild(hb.status('info', hb.t('js.apqp.ap.sample'))); }
                tr.appendChild(nm);
                tr.appendChild(hb.el('td', null, String(s.ruleCnt)));
                tr.appendChild(hb.el('td', null, s.releasedCnt ? String(s.releasedCnt) : ''));
                var u = hb.el('td');
                u.appendChild(hb.status(s.useYn === 'Y' ? 'normal' : 'neutral', s.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')));
                tr.appendChild(u);
                tr.addEventListener('click', function () { pick(s.setId); });
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(s.setId); } });
                tbody.appendChild(tr);
            });
            if (selectId) { return open(selectId); }
        }, fail);
    }

    /** @param {number} id */
    function pick(id) {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            return open(id).then(function () { hb.page.openDetail(); });
        });
    }

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('apSet', hb.api(API + '/' + id)).then(function (res) {
            show(res.data.set, res.data.rules);
        }, fail);
    }

    // ------------------------------------------------------------------ 상세

    /** @param {ApqpApSet | null} s @param {ApqpApRule[]} rules */
    function show(s, rules) {
        current = s;
        locked = !!s && s.releasedCnt > 0;
        hb.showFieldErrors(form, []);
        notify('');
        field('setCd').value = s ? s.setCd : '';
        field('setCd').readOnly = !!s;
        field('setNm').value = s ? s.setNm : '';
        field('remark').value = s && s.remark ? s.remark : '';
        form.querySelectorAll('input[name="useYn"]').forEach(function (el) {
            var r = /** @type {HTMLInputElement} */ (el);
            r.checked = r.value === (s ? s.useYn : 'N');
        });
        /** @type {HTMLElement} */ (document.getElementById('mode')).textContent = s
            ? s.setCd + ' · ' + s.setNm + (s.sampleYn === 'Y' ? ' (' + hb.t('js.apqp.ap.sample') + ')' : '') : hb.t('js.apqp.ap.creating');
        /** @type {HTMLElement} */ (document.getElementById('lockNote')).hidden = !locked;
        var copy = /** @type {HTMLButtonElement | null} */ (document.getElementById('copyBtn'));
        if (copy) { copy.hidden = !s; }
        tbody.querySelectorAll('tr[data-id]').forEach(function (tr) {
            tr.setAttribute('aria-selected', s && tr.getAttribute('data-id') === String(s.setId) ? 'true' : 'false');
        });
        rulesBody.textContent = '';
        rules.forEach(function (r) { addRuleRow(r); });
        /** @type {HTMLElement} */ (document.getElementById('addRuleBtn')).hidden = !CAN_SAVE || locked;
        coverage();
        hb.dirty.clear();
    }

    var FIELDS = ['sevFrom', 'sevTo', 'occFrom', 'occTo', 'detFrom', 'detTo'];

    /** @param {ApqpApRule | null} r */
    function addRuleRow(r) {
        var tr = hb.el('tr');
        tr.appendChild(hb.el('td', 'hb-muted', String(rulesBody.rows.length + 1)));
        [['sevFrom', 'sevTo'], ['occFrom', 'occTo'], ['detFrom', 'detTo']].forEach(function (pair) {
            var td = hb.el('td', 'apqp-range');
            pair.forEach(function (n, i) {
                var input = /** @type {HTMLInputElement} */ (hb.el('input', 'apqp-rating'));
                input.inputMode = 'numeric';
                input.maxLength = 2;
                input.setAttribute('data-rule', n);
                input.setAttribute('aria-label', ruleLabel(n));
                input.value = r ? String(/** @type {any} */ (r)[n]) : (i === 0 ? '1' : '10');
                input.disabled = !CAN_SAVE || locked;
                td.appendChild(input);
                if (i === 0) { td.appendChild(hb.el('span', 'hb-muted', '~')); }
            });
            tr.appendChild(td);
        });
        var apTd = hb.el('td');
        var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
        sel.setAttribute('data-rule', 'ap');
        sel.setAttribute('aria-label', 'AP');
        ['H', 'M', 'L'].forEach(function (c) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, c + ' ' + apName(c)));
            o.value = c;
            sel.appendChild(o);
        });
        sel.value = r ? r.ap : 'L';
        sel.disabled = !CAN_SAVE || locked;
        apTd.appendChild(sel);
        tr.appendChild(apTd);
        var rm = hb.el('td');
        if (CAN_SAVE && !locked) {
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.apqp.remove')));
            b.type = 'button';
            b.addEventListener('click', function () {
                tr.remove();
                renumber();
                coverage();
                hb.dirty.mark();
            });
            rm.appendChild(b);
        }
        tr.appendChild(rm);
        tr.addEventListener('input', function () { coverage(); hb.dirty.mark(); });
        tr.addEventListener('change', function () { coverage(); hb.dirty.mark(); });
        rulesBody.appendChild(tr);
    }

    function renumber() {
        Array.prototype.forEach.call(rulesBody.rows, function (/** @type {HTMLTableRowElement} */ tr, /** @type {number} */ i) {
            tr.cells[0].textContent = String(i + 1);
        });
    }

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} n */
    function ruleLabel(n) {
        /** @type {Record<string, string>} */
        var m = {sevFrom: hb.t('js.apqp.ap.sevFrom'), sevTo: hb.t('js.apqp.ap.sevTo'), occFrom: hb.t('js.apqp.ap.occFrom'),
            occTo: hb.t('js.apqp.ap.occTo'), detFrom: hb.t('js.apqp.ap.detFrom'), detTo: hb.t('js.apqp.ap.detTo')};
        return m[n] || n;
    }

    /** @param {string} c */
    function apName(c) {
        /** @type {Record<string, string>} */
        var m = {H: hb.t('js.apqp.ap.H'), M: hb.t('js.apqp.ap.M'), L: hb.t('js.apqp.ap.L')};
        return m[c] || c;
    }

    /** @returns {Record<string, unknown>[]} */
    function readRules() {
        return /** @type {Record<string, unknown>[]} */ (Array.prototype.map.call(rulesBody.rows, function (/** @type {HTMLTableRowElement} */ tr) {
            /** @type {Record<string, unknown>} */
            var r = {};
            FIELDS.forEach(function (n) {
                var v = /** @type {HTMLInputElement} */ (tr.querySelector('[data-rule="' + n + '"]')).value.trim();
                r[n] = /^\d{1,2}$/.test(v) ? Number(v) : null;
            });
            r.ap = /** @type {HTMLSelectElement} */ (tr.querySelector('[data-rule="ap"]')).value;
            return r;
        }));
    }

    /** 덮임을 화면에서 바로 센다(서버와 같은 규칙) — 빠짐·겹침 수와 앞 몇 개 예 */
    function coverage() {
        var hits = new Array(1000).fill(0);
        readRules().forEach(function (r) {
            var ok = FIELDS.every(function (n) { var v = /** @type {number | null} */ (r[n]); return v !== null && v >= 1 && v <= 10; });
            if (!ok) { return; }
            for (var s = /** @type {number} */ (r.sevFrom); s <= /** @type {number} */ (r.sevTo); s++) {
                for (var o = /** @type {number} */ (r.occFrom); o <= /** @type {number} */ (r.occTo); o++) {
                    for (var d = /** @type {number} */ (r.detFrom); d <= /** @type {number} */ (r.detTo); d++) {
                        hits[(s - 1) * 100 + (o - 1) * 10 + d - 1]++;
                    }
                }
            }
        });
        var miss = 0, over = 0;
        /** @type {string[]} */
        var missEx = [];
        hits.forEach(function (n, i) {
            if (n === 0) {
                miss++;
                if (missEx.length < 3) { missEx.push('S' + (Math.floor(i / 100) + 1) + '·O' + (Math.floor(i / 10) % 10 + 1) + '·D' + (i % 10 + 1)); }
            } else if (n > 1) {
                over++;
            }
        });
        var p = /** @type {HTMLElement} */ (document.getElementById('coverage'));
        p.textContent = '';
        p.appendChild(hb.status(miss === 0 && over === 0 ? 'success' : 'warning',
            miss === 0 && over === 0 ? hb.t('js.apqp.ap.complete') : hb.t('js.apqp.ap.incomplete', miss, over)));
        if (missEx.length) { p.appendChild(hb.el('span', 'hb-muted', ' ' + hb.t('js.apqp.ap.missingEx', missEx.join(', ')))); }
    }

    // ------------------------------------------------------------------ 버튼

    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newBtn'));
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            leaveOk().then(function (ok) {
                if (!ok) { return; }
                show(null, []);
                addRuleRow(null);
                coverage();
                hb.page.openDetail();
                field('setCd').focus();
            });
        });
    }

    /** @type {HTMLButtonElement} */ (document.getElementById('addRuleBtn')).addEventListener('click', function () {
        addRuleRow(null);
        coverage();
        hb.dirty.mark();
    });

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            var cur = current;
            var body = {setCd: field('setCd').value, setNm: field('setNm').value, remark: field('remark').value, useYn: useYn(),
                rowVer: cur ? cur.rowVer : null, rules: locked ? null : readRules()};
            hb.showFieldErrors(form, []);
            hb.busy(sb, function () {
                return cur ? hb.api(API + '/' + cur.setId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return loadList(cur ? cur.setId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, fail);
        });
    }

    var copyBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('copyBtn'));
    if (copyBtn) {
        var cb = copyBtn;
        cb.addEventListener('click', function () {
            var cur = current;
            if (!cur) { return; }
            var src = cur;
            hb.dialog.prompt({title: hb.t('js.apqp.ap.copyTitle', src.setCd), label: hb.t('js.apqp.ap.copyCd'), ok: hb.t('js.apqp.ap.copyOk'),
                required: true, maxLength: 20}).then(function (cd) {
                if (cd === null) { return; }
                hb.busy(cb, function () { return hb.api(API + '/' + src.setId + '/copy', {method: 'POST', body: {setCd: cd}}); })
                    .then(function (res) {
                        if (!res) { return; }
                        return loadList(res.data).then(function () { notify(hb.t('js.apqp.ap.copied')); });
                    }, fail);
            });
        });
    }

    form.addEventListener('input', function () { hb.dirty.mark(); });

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        show(null, []);
        return loadList();
    }).then(null, fail);
})();
