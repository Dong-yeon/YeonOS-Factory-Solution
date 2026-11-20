// @ts-check
/*
 * 공지 관리 (sys-notice) — 시스템관리 2b (docs/36 11.4). 표준 화면 모양: 조회 패널 + hb.grid + 오른쪽 상세 + 첨부.
 * 본문은 글자만(서식 없음). 판정(지난 게시 시작·기간 순서)은 서버, 첨부는 저장한 뒤에만.
 */
/**
 * @typedef {{noticeId: number, catCd: string, title: string, body?: string, pinYn: string, postFrom: string,
 *   postTo?: string | null, useYn: string, stateCd: string, fileCnt: number, updatedBy: string, updatedAt: string,
 *   rowVer: number, [field: string]: any}} SysNotice
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/admin/notices';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('noticeForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var fileSection = /** @type {HTMLElement} */ (document.getElementById('fileSection'));
    /** 고치는 공지 또는 null(새로) @type {SysNotice | null} */
    var current = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (
            form.querySelector('[data-field="' + name + '"]'));
    }

    /** @param {SysNotice | null} n */
    function showDetail(n) {
        current = n;
        hb.showFieldErrors(form, []);
        field('title').value = n ? n.title : '';
        field('catCd').value = n ? n.catCd : 'NOTICE';
        field('pinYn').value = n ? n.pinYn : 'N';
        field('postFrom').value = n ? n.postFrom : hb.dates.today();
        field('postTo').value = n && n.postTo ? n.postTo : '';
        field('useYn').value = n ? n.useYn : 'Y';
        field('body').value = n && n.body ? n.body : '';
        ['title', 'postFrom', 'postTo', 'body'].forEach(function (k) {
            /** @type {HTMLInputElement} */ (field(k)).readOnly = !CAN_SAVE;
        });
        ['catCd', 'pinYn', 'useYn'].forEach(function (k) { field(k).disabled = !CAN_SAVE; });
        formMode.textContent = n ? hb.t('js.sysNotice.editing', n.updatedBy, hb.fmt.datetime(n.updatedAt))
            : hb.t('js.sysNotice.creating');
        fileSection.hidden = !n;
        loadFiles();
        hb.dirty.clear(form);
    }

    /** @param {number} id @param {boolean} [keepMsg] */
    function loadDetail(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { showDetail(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** 첨부 목록 — 공지를 빨리 바꾸면 늦게 온 앞 목록은 hb.latest 가 버린다 */
    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        var n = current;
        if (!n) { return; }
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + n.noticeId)).then(function (res) {
            (res.list || []).forEach(function (/** @type {{fileId: number, name: string, size: number}} */ f) {
                var li = hb.el('li');
                var a = hb.el('a', null, f.name);
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (CAN_SAVE) {
                    var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        sysUi.ask('js.sysNotice.dlg.fileDelete', hb.t('js.sysNotice.confirmFileDelete', f.name), 'js.sysNotice.dlg.fileDeleteOk', true)
                            .then(function (ok) {
                                if (!ok) { return; }
                                hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                    .then(loadFiles, function (err) { notify(err.message, true); });
                            });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));   // SAVE 권한 없으면 없음
    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            var n = current;
            if (!file || !n) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(n.noticeId));
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                loadFiles();
            }, function (err) {
                upInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function newNotice() {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            notify('');
            showDetail(null);
            hb.page.openDetail();
            field('title').focus();
        });
    }
    bind('newBtn', newNotice);
    bind('newBtnList', newNotice);

    bind('saveBtn', function (btn) {
        var editId = current ? current.noticeId : null;   // 저장 중 다른 행을 골라도 이 공지로 마무리
        var body = {title: field('title').value, catCd: field('catCd').value, pinYn: field('pinYn').value,
            postFrom: field('postFrom').value || null, postTo: field('postTo').value || null,
            useYn: field('useYn').value, body: field('body').value, rowVer: current ? current.rowVer : null};
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            var id = editId !== null ? editId : /** @type {number} */ (res.data);
            return (grid ? grid.reload(false, id) : Promise.resolve()).then(function () {
                if (!grid || !grid.selected()) { return loadDetail(id, true); }
            }).then(function () {
                notify(editId === null ? hb.t('js.sysNotice.created') : hb.t('js.common.saved'));
            });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return sysUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([sp.ready, hb.select(/** @type {HTMLSelectElement} */ (field('catCd'))).ready]);
    }).then(function () {
        showDetail(null);
        grid = hb.grid('#noticeGrid', {
            progCd: PROG, id: 'main', key: 'noticeId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'sys_notice',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {catCd: 'catCd', stateCd: 'stateCd'},
            format: {pinYn: function (v) { return v === 'Y' ? hb.t('js.sysInbox.pinned') : ''; }},
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {stateCd: sysUi.TONES.notice},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            beforeSelect: sysUi.beforeSelect,
            onSelect: function (row) { loadDetail(row.noticeId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
