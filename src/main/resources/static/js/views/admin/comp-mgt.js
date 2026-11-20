// @ts-check
/*
 * 회사 정보 (sys-comp) — 시스템관리 2단계 (docs/36 9.5). 폼 하나. 시간대를 바꾸면 확인 칸이 나타나고(필수),
 * 관리자 그룹이 아니면 시간대 칸을 잠근다(편의 — 판정은 서버).
 */
/**
 * @typedef {{compCd: string, compNm: string, compEnNm?: string | null, bizNo?: string | null, ceoNm?: string | null,
 *   telNo?: string | null, addr?: string | null, timeZone: string, localeCd: string, rowVer: number,
 *   updatedAt?: string, updatedBy?: string, [field: string]: any}} SysComp
 */
(function () {
    'use strict';

    var API = '/api/admin/company';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var FIELDS = ['compNm', 'compEnNm', 'bizNo', 'ceoNm', 'telNo', 'addr', 'localeCd', 'timeZone'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('compForm'));
    var tzRow = /** @type {HTMLElement} */ (document.getElementById('tzConfirmRow'));
    var tzConfirm = /** @type {HTMLInputElement} */ (document.getElementById('confirmTz'));
    /** @type {SysComp | null} */
    var current = null;
    var canTz = false;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {SysComp} c */
    function show(c) {
        current = c;
        hb.showFieldErrors(form, []);
        /** @type {HTMLElement} */ (document.getElementById('compCd')).textContent = c.compCd;
        FIELDS.forEach(function (n) {
            var v = c[n];
            field(n).value = n === 'bizNo' ? hb.fmt.bizNo(v) : (v === null || v === undefined ? '' : v);
            field(n).disabled = !CAN_SAVE || (n === 'timeZone' && !canTz);
        });
        tzRow.hidden = true;
        tzConfirm.checked = false;
        /** @type {HTMLElement} */ (document.getElementById('tzNote')).textContent = canTz ? '' : hb.t('js.sysComp.tzAdminOnly');
        /** @type {HTMLElement} */ (document.getElementById('updated')).textContent =
            hb.t('js.sysComp.updated', c.updatedBy || '', c.updatedAt ? hb.fmt.datetime(c.updatedAt) : '');
        hb.dirty.clear();
    }

    function load() {
        return hb.api(API).then(function (res) {
            canTz = !!res.data.canChangeTimeZone;
            show(res.data.comp);
        }, function (err) { notify(err.message, true); });
    }

    field('timeZone').addEventListener('change', function () {
        tzRow.hidden = !current || field('timeZone').value === current.timeZone;
        tzConfirm.checked = false;
    });

    var saveBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtnEl) {
        var saveBtn = saveBtnEl;
        saveBtn.addEventListener('click', function () {
            if (!current) { return; }
            /** @type {Record<string, unknown>} */
            var body = {rowVer: current.rowVer, confirmTimeZone: tzConfirm.checked};
            FIELDS.forEach(function (n) { body[n] = field(n).value; });
            hb.busy(saveBtn, function () {
                return hb.api(API, {method: 'PUT', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load().then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/time-zones')]);
    }).then(function (r) {
        var sel = /** @type {HTMLSelectElement} */ (field('timeZone'));
        /** @type {string[]} */ (r[1].list || []).forEach(function (z) {
            var o = hb.el('option', null, z);
            o.value = z;
            sel.appendChild(o);
        });
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
