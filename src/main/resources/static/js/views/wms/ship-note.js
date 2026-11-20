// @ts-check
/* 출하 특이사항 (wms-ship-note) — docs/56. 왼쪽 규칙 hb.grid, 오른쪽 편집(고객·품목군·품목 비우면 전체 / 제목·내용·확인 필수·사용·순서).
   저장 = 추가(새) 또는 수정(rowVer — 남이 먼저 고치면 409). 삭제 없음 = 사용 끔. 공통 hb.* 는 고치지 않는다. */
/**
 * @typedef {{noteId: number, custCd?: string | null, custNm?: string | null, classCd?: string | null, classNm?: string | null,
 *   itemCd?: string | null, itemNm?: string | null, title: string, body?: string | null, ackRequiredYn: string, useYn: string,
 *   sortNo: number, rowVer: number, updatedAt?: string | null, updatedBy?: string | null, [field: string]: any}} ShipNoteRow
 */
(function () {
    'use strict';

    var API = '/api/wms/ship-notes';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('noteForm'));
    var mode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var meta = /** @type {HTMLElement} */ (document.getElementById('noteMeta'));
    var classSel = /** @type {HTMLSelectElement} */ (document.getElementById('classSel'));
    /** 고친 규칙(새면 null) @type {ShipNoteRow | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    /** 편집 칸 채우기 — null = 새 규칙 @param {ShipNoteRow | null} n */
    function edit(n) {
        current = n;
        mode.hidden = true;
        form.hidden = false;
        hb.showFieldErrors(form, []);
        field('custCd').value = n && n.custCd ? n.custCd : '';
        field('classCd').value = n && n.classCd ? n.classCd : '';
        field('itemCd').value = n && n.itemCd ? n.itemCd : '';
        field('title').value = n ? n.title : '';
        field('body').value = n && n.body ? n.body : '';
        field('ackRequiredYn').value = n ? n.ackRequiredYn : 'N';
        field('useYn').value = n ? n.useYn : 'Y';
        field('sortNo').value = String(n ? n.sortNo : 0);
        meta.textContent = n ? hb.t('js.wmsShipNote.meta', n.updatedBy || '', n.updatedAt ? hb.fmt.datetime(n.updatedAt) : '') : hb.t('js.wmsShipNote.newHint');
        form.querySelectorAll('input, select, textarea').forEach(function (el) {
            /** @type {HTMLInputElement} */ (el).disabled = !CAN_SAVE;
        });
        field('title').focus();
    }

    /** @param {number} id */
    function open(id) {
        hb.latest('note', hb.api(API + '/' + id)).then(function (/** @type {any} */ res) {
            edit(/** @type {ShipNoteRow} */ (res.data));
        }, function (/** @type {any} */ err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
        });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (!CAN_SAVE) { return; }
        var btn = /** @type {HTMLButtonElement} */ (document.getElementById('saveBtn'));
        var body = {noteId: current ? current.noteId : null, rowVer: current ? current.rowVer : null,
            custCd: field('custCd').value, classCd: field('classCd').value, itemCd: field('itemCd').value,
            title: field('title').value, body: field('body').value, ackRequiredYn: field('ackRequiredYn').value,
            useYn: field('useYn').value, sortNo: field('sortNo').value === '' ? null : Number(field('sortNo').value)};
        hb.busy(btn, function () { return hb.api(API, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var saved = /** @type {ShipNoteRow} */ (res.data);
            edit(saved);
            notify(hb.t('js.common.saved'));
            return grid ? grid.reload(false, String(saved.noteId)) : null;
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    var newBtn = document.getElementById('newBtn');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            edit(null);
            hb.page.openDetail();
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        return Promise.all([hb.loadSession(), hb.api(API + '/classes'), sp.ready]);
    }).then(function (r) {
        /** @type {{classCd: string, classNm: string}[]} */ (r[1].data).forEach(function (c) {
            var o = hb.el('option', null, c.classCd + ' · ' + c.classNm);
            o.setAttribute('value', c.classCd);
            classSel.appendChild(o);
        });
        grid = hb.grid('#noteGrid', {
            progCd: PROG, id: 'main', key: 'noteId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 사용 끔 흐리게
            onSelect: function (row) { open(Number(row.noteId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
