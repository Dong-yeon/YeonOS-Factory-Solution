// @ts-check
/*
 * 사용 모듈 (sys-module) — docs/80 5장. 모듈마다 스위치, 바꾸면 필수 짝을 미리 검사해 안내(저장은 서버가 다시 검사).
 * 자동으로 함께 켜지 않는다 — 사용자가 직접 켠다(몰래 다른 값이 저장되지 않게).
 */
(function () {
    'use strict';

    /**
     * @typedef {{code: string, name: string, toggleable: boolean, on: boolean, progCount: number,
     *   requires: string[], recommends: string[], requiredBy: string[]}} SysModuleRow
     */

    var API = '/api/admin/modules';
    var canSave = (document.body.getAttribute('data-actions') || '').split(',').indexOf('SAVE') >= 0;
    var tbody = /** @type {HTMLElement} */ (document.querySelector('#moduleTable tbody'));
    var depMsg = /** @type {HTMLElement} */ (document.getElementById('depMsg'));
    var saveMsg = /** @type {HTMLElement} */ (document.getElementById('saveMsg'));
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    /** @type {SysModuleRow[]} */
    var rows = [];
    /** @type {Record<string, string>} */
    var names = {};

    /** @param {string} text @param {boolean} [error] */
    function notify(text, error) {
        saveMsg.textContent = text;
        saveMsg.hidden = !text;
        saveMsg.classList.toggle('hb-msg-error', !!error);
    }

    function requestKey() {
        return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID()
            : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    }

    /** 화면의 스위치 상태 → 켠 코드 목록 @returns {string[]} */
    function onCodes() {
        /** @type {string[]} */
        var out = [];
        tbody.querySelectorAll('input[data-module]').forEach(function (i) {
            var box = /** @type {HTMLInputElement} */ (i);
            if (box.checked) { out.push(box.getAttribute('data-module') || ''); }
        });
        return out;
    }

    /** 필수 짝이 빠진 것(서버 ModuleCatalog.brokenPairs 와 같은 규칙) — 안내 문구로 */
    function checkDeps() {
        var on = onCodes();
        /** @type {string[]} */
        var msgs = [];
        rows.forEach(function (r) {
            if (r.toggleable && on.indexOf(r.code) < 0) { return; }
            r.requires.forEach(function (need) {
                if (on.indexOf(need) < 0) { msgs.push(hb.t('js.sysModule.needs', r.name, names[need] || need)); }
            });
        });
        while (depMsg.firstChild) { depMsg.removeChild(depMsg.firstChild); }
        msgs.forEach(function (m) {
            var li = document.createElement('li');
            li.textContent = m;
            depMsg.appendChild(li);
        });
        depMsg.hidden = !msgs.length;
        if (saveBtn) { saveBtn.disabled = msgs.length > 0; }
        return msgs.length === 0;
    }

    /** @param {string[]} codes */
    function nameList(codes) {
        return codes.map(function (c) { return names[c] || c; }).join(', ');
    }

    /** @param {SysModuleRow[]} list */
    function render(list) {
        rows = list;
        names = {};
        list.forEach(function (r) { names[r.code] = r.name; });
        while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }
        list.forEach(function (r) {
            var tr = document.createElement('tr');
            var nameTd = document.createElement('td');
            nameTd.textContent = r.name;
            tr.appendChild(nameTd);

            var onTd = document.createElement('td');
            var label = document.createElement('label');
            label.className = 'hb-switch';
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.setAttribute('role', 'switch');
            box.setAttribute('data-module', r.code);
            box.setAttribute('aria-label', r.name);
            box.checked = r.on;
            box.disabled = !r.toggleable || !canSave;
            box.addEventListener('change', function () {
                hb.dirty.mark();
                notify('');
                checkDeps();
            });
            var state = document.createElement('span');
            state.textContent = r.toggleable ? '' : hb.t('js.sysModule.always');
            label.appendChild(box);
            label.appendChild(state);
            onTd.appendChild(label);
            tr.appendChild(onTd);

            var cntTd = document.createElement('td');
            cntTd.className = 'hb-num';
            cntTd.textContent = String(r.progCount);
            tr.appendChild(cntTd);

            var reqTd = document.createElement('td');
            reqTd.textContent = nameList(r.requires);
            tr.appendChild(reqTd);

            var recTd = document.createElement('td');
            recTd.textContent = nameList(r.recommends);
            tr.appendChild(recTd);
            tbody.appendChild(tr);
        });
        hb.dirty.clear();
        checkDeps();
    }

    function load() {
        return hb.api(API).then(function (res) {
            render(/** @type {SysModuleRow[]} */ (res.list || []));
        }, function (err) { notify(err.message, true); });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        /** @type {HTMLElement} */ (document.getElementById('reloadBtn')).addEventListener('click', function () {
            notify('');
            load();
        });
        if (saveBtn) {
            var btn = saveBtn;
            btn.addEventListener('click', function () {
                if (!checkDeps()) { return; }
                var key = requestKey();
                hb.busy(btn, function () {
                    return hb.api(API, {method: 'PUT', body: {on: onCodes()}, headers: {'X-Idempotency-Key': key}});
                }).then(function (res) {
                    if (!res) { return; }
                    render(/** @type {SysModuleRow[]} */ (res.list || []));
                    notify(hb.t('js.sysModule.saved'));
                }, function (err) {
                    notify(err.details && err.details.length ? err.message + ' ' + err.details.join(' ') : err.message, true);
                });
            });
        }
        load();
    });
})();
