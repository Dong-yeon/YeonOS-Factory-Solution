// @ts-check
/* 운영자 콘솔 (/ops) — 서버 데이터는 textContent 로만 */
/**
 * @typedef {{compCd: string, compNm: string, statusCd: string, timeZone: string}} OpsCompany
 * @typedef {[string, string]} OpsCol 열 = [필드, 제목키]
 */
(function () {
    'use strict';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    /** 고른 회사 — 아래 버튼은 상세가 보일 때(= 고른 뒤)만 누를 수 있다 @type {OpsCompany} */
    var current;

    /** @param {string} id */
    function elem(id) { return /** @type {HTMLElement} */ (document.getElementById(id)); }
    /** @param {string} id */
    function input(id) { return /** @type {HTMLInputElement} */ (document.getElementById(id)); }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 목록 → 표 @param {HB.Row[]} rows @param {OpsCol[]} cols */
    function table(rows, cols) {
        var t = hb.el('table', 'hb-table');
        var tr = hb.el('tr');
        cols.forEach(function (c) { tr.appendChild(hb.el('th', null, hb.t(c[1]))); });
        var thead = hb.el('thead');
        thead.appendChild(tr);
        t.appendChild(thead);
        var tbody = hb.el('tbody');
        rows.forEach(function (r) {
            var row = hb.el('tr');
            cols.forEach(function (c) {
                var v = r[c[0]];
                row.appendChild(hb.el('td', null, v === null || v === undefined ? '' : (/At$/.test(c[0]) ? hb.fmt.datetime(v) : v)));
            });
            tbody.appendChild(row);
        });
        t.appendChild(tbody);
        if (!rows.length) {
            var empty = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.ops.empty'));
            td.colSpan = cols.length;
            empty.appendChild(td);
            tbody.appendChild(empty);
        }
        return t;
    }

    /** @param {HTMLElement} el @param {Node} node */
    function show(el, node) {
        el.textContent = '';
        el.appendChild(node);
    }

    /**
     * 고른 회사 결과(활용 현황·지원 조회)를 그린다 — 응답 전에 다른 회사를 고르면 늦게 온 앞 회사 결과는 버린다
     * (안 버리면 B사 제목 아래 A사 숫자가 보인다)
     * @param {OpsCompany} c 요청할 때 고른 회사 @param {Node} node
     */
    function showResult(c, node) {
        if (c !== current) { return; }
        show(elem('result'), node);
    }

    function loadCompanies() {
        return hb.api('/api/ops/companies').then(function (res) {
            var tbody = /** @type {HTMLElement} */ (document.querySelector('#companies tbody'));
            tbody.textContent = '';
            /** @type {OpsCompany[]} */ (res.list || []).forEach(function (c) {
                var tr = hb.el('tr');
                [c.compCd, c.compNm, null, c.timeZone].forEach(function (v, i) {
                    var cell = hb.el('td', null, v);
                    if (i === 2) { cell.appendChild(sysUi.badge('comp', c.statusCd, c.statusCd)); }
                    tr.appendChild(cell);
                });
                var td = hb.el('td');
                var b = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.ops.select'));
                b.type = 'button';
                b.addEventListener('click', function () { select(c); });
                td.appendChild(b);
                tr.appendChild(td);
                tbody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    /** @param {OpsCompany} c */
    function select(c) {
        current = c;
        elem('detail').hidden = false;
        elem('detailTitle').textContent = c.compCd + ' · ' + c.compNm;
        elem('result').textContent = '';
    }

    function loadBackup() {
        hb.api('/api/ops/backup-health').then(function (res) {
            var ul = elem('backup');
            ul.textContent = '';
            /** @type {{status: string, warnings: string[]}} */
            var h = res.data;
            var li = hb.el('li');
            li.appendChild(sysUi.badge('health', h.status, h.status));
            ul.appendChild(li);
            h.warnings.forEach(function (w) { ul.appendChild(hb.el('li', 'hb-warn', w)); });
        });
    }

    function loadMyLog() {
        hb.api('/api/ops/log').then(function (res) {
            show(elem('myLog'), table(res.list || [], [['occurredAt', 'js.ops.col.time'],
                ['action', 'js.ops.col.action'], ['targetComp', 'js.ops.col.comp'], ['resultCd', 'js.ops.col.result'],
                ['ip', 'js.ops.col.ip'], ['detail', 'js.ops.col.detail']]));
        });
    }

    /** @param {string} id @param {() => void} fn */
    function bind(id, fn) {
        elem(id).addEventListener('click', fn);
    }

    var today = new Date();
    var weekAgo = new Date(today.getTime() - 7 * 86400000);
    input('to').value = today.toISOString().substring(0, 10);
    input('from').value = weekAgo.toISOString().substring(0, 10);

    hb.loadMessages().then(function () {
        return hb.api('/api/ops/auth/me');
    }).then(function (r) {
        elem('me').textContent = r.data.loginId;
        loadCompanies();
        loadBackup();
        loadMyLog();
    }, function (err) { notify(err.message, true); });

    var createForm = /** @type {HTMLFormElement} */ (document.getElementById('createForm'));
    createForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var form = createForm;
        /** @type {Record<string, string|string[]>} */
        var body = {};
        form.querySelectorAll('input[data-field]').forEach(function (i) {
            var field = /** @type {HTMLInputElement} */ (i);
            body[field.getAttribute('data-field') || ''] = field.value;
        });
        // 사용 모듈(docs/80) — 체크한 것만 켜고 나머지는 끈다. 필수 짝이 빠지면 서버가 MODULE_DEPENDENCY 로 알려 준다
        /** @type {string[]} */
        var modules = [];
        form.querySelectorAll('input[name="module"]').forEach(function (i) {
            var box = /** @type {HTMLInputElement} */ (i);
            if (box.checked) { modules.push(box.value); }
        });
        body.modules = modules;
        hb.busy(/** @type {HTMLButtonElement} */ (form.querySelector('button')), function () {
            return hb.api('/api/ops/companies', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.showFieldErrors(form, []);
            var p = elem('created');
            p.hidden = false;
            // 임시 비밀번호는 이 화면에서 한 번만 보인다 — 안전한 경로로 전달 후 화면을 닫는다
            p.textContent = hb.t('js.ops.created', res.data.compCd, res.data.adminLoginId, res.data.tempPassword);
            form.reset();
            loadCompanies();
            loadMyLog();
        }, function (err) {
            notify(err.details && err.details.length ? err.message + ' ' + err.details.join(' ') : err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    bind('usageBtn', function () {
        var c = current;
        var from = input('from').value;
        var to = input('to').value;
        hb.api('/api/ops/companies/' + encodeURIComponent(c.compCd) + '/usage?from=' + from + '&to=' + to)
            .then(function (res) {
                var box = hb.el('div');
                box.appendChild(table(res.data.days, [['date', 'js.ops.col.date'], ['users', 'js.ops.col.users'],
                    ['logins', 'js.ops.col.logins'], ['pageViews', 'js.ops.col.pageViews'], ['actions', 'js.ops.col.actions']]));
                box.appendChild(table(res.data.programs, [['progCd', 'js.ops.col.prog'], ['views', 'js.ops.col.pageViews'],
                    ['maxDailyUsers', 'js.ops.col.users']]));
                showResult(c, box);
                loadMyLog();
            }, function (err) { notify(err.message, true); });
    });

    /** @param {'LOGS' | 'FEEDBACK'} scope */
    function support(scope) {
        var c = current;
        hb.api('/api/ops/companies/' + encodeURIComponent(c.compCd) + '/support/' + scope).then(function (res) {
            /** @type {OpsCol[]} */
            var cols = scope === 'LOGS'
                ? [['occurredAt', 'js.ops.col.time'], ['loginId', 'js.ops.col.user'], ['eventCd', 'js.ops.col.action'],
                    ['progCd', 'js.ops.col.prog'], ['resultCd', 'js.ops.col.result']]
                : [['createdAt', 'js.ops.col.time'], ['progCd', 'js.ops.col.prog'], ['content', 'js.ops.col.detail'],
                    ['status', 'js.ops.col.result']];
            showResult(c, table(res.list || [], cols));
            loadMyLog();
        }, function (err) {
            notify(err.status === 403 ? hb.t('js.ops.noGrant') : err.message, true);
            loadMyLog();
        });
    }
    bind('logsBtn', function () { support('LOGS'); });
    bind('feedbackBtn', function () { support('FEEDBACK'); });

    bind('unlockBtn', function () {
        var id = input('unlockId').value;
        hb.api('/api/ops/companies/' + encodeURIComponent(current.compCd) + '/unlock', {method: 'POST', body: {loginId: id}})
            .then(function () { notify(hb.t('js.ops.unlocked', id)); loadMyLog(); },
                function (err) { notify(err.message, true); });
    });

    bind('resetMfaBtn', function () {
        var id = input('unlockId').value;
        var c = current;
        sysUi.ask('js.ops.dlg.resetMfa', hb.t('js.ops.confirmResetMfa', c.compCd, id), 'js.ops.dlg.resetMfaOk', true).then(function (ok) {
            if (!ok) { return; }
            hb.api('/api/ops/companies/' + encodeURIComponent(c.compCd) + '/reset-mfa', {method: 'POST', body: {loginId: id}})
                .then(function () { notify(hb.t('js.ops.mfaReset', id)); loadMyLog(); },
                    function (err) { notify(err.message, true); });
        });
    });

    /** @param {'SUSPENDED' | 'ACTIVE'} status */
    function setStatus(status) {
        var c = current;
        var suspend = status === 'SUSPENDED';
        sysUi.ask(suspend ? 'js.ops.dlg.suspend' : 'js.ops.dlg.activate', hb.t('js.ops.confirmStatus', c.compCd, status),
            suspend ? 'js.ops.dlg.suspendOk' : 'js.ops.dlg.activateOk', suspend).then(function (ok) {
            if (!ok) { return; }
            hb.api('/api/ops/companies/' + encodeURIComponent(c.compCd) + '/status', {method: 'POST', body: {status: status}})
                .then(function () { loadCompanies(); loadMyLog(); }, function (err) { notify(err.message, true); });
        });
    }
    bind('suspendBtn', function () { setStatus('SUSPENDED'); });
    bind('activateBtn', function () { setStatus('ACTIVE'); });

    bind('logoutBtn', function () {
        hb.api('/api/ops/auth/logout', {method: 'POST'}).then(function () { window.location.href = '/ops/login'; });
    });
})();
