// @ts-check
/* 지원 세션 허락 (sys-support) */
/**
 * @typedef {{grantId: number, opLoginId: string, scopeCd: string, fromAt: string, toAt: string, reason: string,
 *   revokedAt?: string | null, active: boolean}} SupportGrant
 * @typedef {{occurredAt: string, opLoginId: string, action: string, resultCd: string, detail?: string | null}} SupportOpsLog
 */
(function () {
    'use strict';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLTableRowElement} tr @param {unknown} v */
    function cell(tr, v) {
        tr.appendChild(hb.el('td', null, v === null || v === undefined ? '' : v));
    }

    function loadGrants() {
        hb.api('/api/platform/support/grants').then(function (res) {
            var t = hb.el('table', 'hb-table');
            var head = hb.el('tr');
            ['js.support.col.op', 'js.support.col.scope', 'js.support.col.period', 'js.support.col.reason',
                'js.support.col.state', ''].forEach(function (k) { head.appendChild(hb.el('th', null, k ? hb.t(k) : '')); });
            t.appendChild(head);
            /** @type {SupportGrant[]} */ (res.list || []).forEach(function (g) {
                var tr = hb.el('tr');
                cell(tr, g.opLoginId);
                cell(tr, g.scopeCd);
                cell(tr, hb.fmt.datetime(g.fromAt) + ' ~ ' + hb.fmt.datetime(g.toAt));
                cell(tr, g.reason);
                cell(tr, g.revokedAt ? hb.t('js.support.revoked') : g.active ? hb.t('js.support.active') : hb.t('js.support.expired'));
                var td = hb.el('td');
                if (g.active && ACTIONS.indexOf('SAVE') >= 0) {
                    var b = hb.el('button', 'hb-btn-danger hb-btn-small', hb.t('js.support.revoke'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.api('/api/platform/support/grants/' + g.grantId + '/revoke', {method: 'POST'})
                            .then(function () { loadGrants(); }, function (err) { notify(err.message, true); });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                t.appendChild(tr);
            });
            var box = /** @type {HTMLElement} */ (document.getElementById('grants'));
            box.textContent = '';
            box.appendChild(t);
        }, function (err) { notify(err.message, true); });
    }

    function loadOpsLog() {
        hb.api('/api/platform/support/ops-log').then(function (res) {
            var t = hb.el('table', 'hb-table');
            var head = hb.el('tr');
            ['js.support.col.time', 'js.support.col.op', 'js.support.col.action', 'js.support.col.result',
                'js.support.col.detail'].forEach(function (k) { head.appendChild(hb.el('th', null, hb.t(k))); });
            t.appendChild(head);
            /** @type {SupportOpsLog[]} */ (res.list || []).forEach(function (l) {
                var tr = hb.el('tr');
                cell(tr, hb.fmt.datetime(l.occurredAt));
                cell(tr, l.opLoginId);
                cell(tr, l.action);
                cell(tr, l.resultCd);
                cell(tr, l.detail);
                t.appendChild(tr);
            });
            var box = /** @type {HTMLElement} */ (document.getElementById('opsLog'));
            box.textContent = '';
            box.appendChild(t);
        }, function (err) { notify(err.message, true); });
    }

    var grantForm = /** @type {HTMLFormElement | null} */ (document.getElementById('grantForm'));   // SAVE 권한 없으면 없음
    if (grantForm) {
        var form = grantForm;
        var submitBtn = /** @type {HTMLButtonElement} */ (form.querySelector('button'));
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            /** @type {Record<string, unknown>} */
            var body = {};
            form.querySelectorAll('[data-field]').forEach(function (i) {
                body[i.getAttribute('data-field') || ''] = /** @type {HTMLInputElement | HTMLSelectElement} */ (i).value;
            });
            body.hours = Number(body.hours);
            hb.busy(submitBtn, function () {
                return hb.api('/api/platform/support/grants', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.showFieldErrors(form, []);
                form.reset();
                notify(hb.t('js.common.saved'));
                loadGrants();
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        loadGrants();
        loadOpsLog();
    });
})();
