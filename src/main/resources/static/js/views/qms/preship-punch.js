// @ts-check
/* 검수 미결 (qms-preship-punch) — docs/78. hb.grid 목록 + 상세(내용·사진·닫기·조건부 출하 허용·NCR 연결). 닫힌 미결은 이력 */
/**
 * @typedef {{punchId: number, inspId: number, inspNo: string, inspStatus: string, shipId: number, shipNo: string, shipStatus: string,
 *   resultId: number, itemCd: string, itemNm: string, grpNm: string | null, checkText: string, photoReqYn: string, status: string,
 *   issueText: string, actionText: string | null, closedBy: string | null, shipAllowYn: string, allowReason: string | null,
 *   allowedBy: string | null, ncrNo: string | null, fileCnt: number, rowVer: number, [field: string]: any}} PreshipPunchRow
 * @typedef {{fileId: number, name: string}} PreshipPunchFile
 */
(function () {
    'use strict';

    var API = '/api/qms/preship/punches';
    var FILES = '/api/platform/files';
    /** 사진은 검수 실행 화면 대상으로 붙는다(올리기 권한 = 검수 실행 SAVE) */
    var PHOTO_TARGET = 'qms-preship';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var PROG = document.body.getAttribute('data-prog') || '';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var info = /** @type {HTMLElement} */ (document.getElementById('punchInfo'));
    var view = /** @type {HTMLElement} */ (document.getElementById('punchView'));
    var photoBox = /** @type {HTMLElement} */ (document.getElementById('photoBox'));
    var closeForm = /** @type {HTMLFormElement} */ (document.getElementById('closeForm'));
    var allowForm = /** @type {HTMLFormElement} */ (document.getElementById('allowForm'));
    var ncrForm = /** @type {HTMLFormElement} */ (document.getElementById('ncrForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {PreshipPunchRow | null} */
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

    /** @param {HB.ApiError} err */
    function errorText(err) {
        if (err && err.errors && err.errors.length) {
            return err.message + ' ' + err.errors.map(function (x) { return x.message; }).join(' / ');
        }
        return err && err.message;
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function input(f, n) {
        return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} n @param {string} text */
    function setView(n, text) {
        var el = view.querySelector('[data-view="' + n + '"]');
        if (el) { el.textContent = text; }
    }

    /** @param {PreshipPunchRow} p @param {boolean} canUpload */
    function photos(p, canUpload) {
        photoBox.textContent = '';
        var list = hb.el('ul');
        photoBox.appendChild(hb.el('strong', null, hb.t('js.qms.preship.photos')));
        photoBox.appendChild(list);
        function refresh() {
            return hb.latest('punch-files', hb.api(FILES + '?targetType=' + PHOTO_TARGET + '&targetId=' + p.resultId)).then(function (res) {
                list.textContent = '';
                /** @type {PreshipPunchFile[]} */ (res.list || []).forEach(function (f) {
                    var li = hb.el('li');
                    var a = hb.el('a', null, f.name);
                    a.href = FILES + '/' + f.fileId;
                    li.appendChild(a);
                    list.appendChild(li);
                });
            }, function (err) { if (!err.stale) { notify(err.message, true); } });
        }
        if (canUpload) {
            var lab = hb.el('label', 'hb-check');
            lab.appendChild(hb.el('span', null, hb.t('js.qms.preship.addPhoto')));
            var fileInput = hb.el('input');
            fileInput.type = 'file';
            fileInput.accept = 'image/*';
            lab.appendChild(fileInput);
            photoBox.appendChild(lab);
            fileInput.addEventListener('change', function () {
                if (!fileInput.files || !fileInput.files.length) { return; }
                var fd = new FormData();
                fd.append('targetType', PHOTO_TARGET);
                fd.append('targetId', String(p.resultId));
                fd.append('file', fileInput.files[0]);
                hb.upload(FILES, fd).then(function () {
                    fileInput.value = '';
                    notify(hb.t('js.qms.preship.photoAdded'));
                    return refresh();
                }, function (err) { notify(err.message, true); });
            });
        }
        refresh();
    }

    /** @param {PreshipPunchRow | null} p */
    function show(p) {
        current = p;
        view.hidden = !p;
        [closeForm, allowForm, ncrForm].forEach(function (f) { hb.showFieldErrors(f, []); });
        if (!p) {
            info.textContent = hb.t('js.qms.preship.selectPunch');
            closeForm.hidden = allowForm.hidden = ncrForm.hidden = true;
            photoBox.textContent = '';
            return;
        }
        var open = p.status === 'OPEN';
        info.textContent = p.shipNo + ' · ' + p.inspNo + ' · ' + p.itemCd + ' ' + p.itemNm;
        setView('checkText', (p.grpNm ? '[' + p.grpNm + '] ' : '') + p.checkText + (p.photoReqYn === 'Y' ? ' · ' + hb.t('js.qms.preship.photoReq') : ''));
        setView('issueText', p.issueText);
        setView('allow', p.shipAllowYn === 'Y' ? hb.t('js.qms.preship.allowedBy', p.allowedBy || '', p.allowReason || '') : hb.t('js.qms.preship.notAllowed'));
        setView('action', p.actionText ? p.actionText + ' (' + (p.closedBy || '') + ')' : '');
        closeForm.hidden = !open || !CAN_SAVE;
        allowForm.hidden = !open || p.shipAllowYn === 'Y' || p.inspStatus !== 'OPEN' || !CAN_APPROVE;
        ncrForm.hidden = !CAN_SAVE;
        input(closeForm, 'actionText').value = '';
        input(allowForm, 'reason').value = '';
        input(ncrForm, 'ncrNo').value = p.ncrNo || '';
        photos(p, open && CAN_SAVE);
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('punch', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {PreshipPunchRow} */ (res.data));
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} id @param {HTMLFormElement} f @param {string} path @param {() => Record<string, unknown>} body @param {string} doneKey */
    function action(id, f, path, body, doneKey) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (!b) { return; }
        var button = b;
        button.addEventListener('click', function () {
            if (!current) { return; }
            var punchId = current.punchId;
            var payload = Object.assign({rowVer: current.rowVer}, body());
            hb.busy(button, function () {
                return hb.api(API + '/' + punchId + '/' + path, {method: 'POST', body: payload});
            }).then(function (res) {
                if (!res) { return; }
                notify(hb.t(doneKey));
                return (grid ? grid.reload(false, punchId) : Promise.resolve()).then(function () { show(/** @type {PreshipPunchRow} */ (res.data)); });
            }, function (err) {
                notify(errorText(err), true);
                hb.showFieldErrors(f, err.errors);
            });
        });
    }

    action('closeBtn', closeForm, 'close', function () { return {actionText: input(closeForm, 'actionText').value}; }, 'js.qms.preship.punchClosed');
    action('allowBtn', allowForm, 'allow', function () { return {reason: input(allowForm, 'reason').value}; }, 'js.qms.preship.punchAllowedDone');
    action('ncrBtn', ncrForm, 'ncr', function () { return {ncrNo: input(ncrForm, 'ncrNo').value}; }, 'js.qms.preship.ncrLinked');

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        sp.setDefault('status', 'OPEN');
        sp.setValue('status', 'OPEN');
        show(null);
        grid = hb.grid('#punchGrid', {
            progCd: PROG, id: 'main', key: 'punchId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            status: {status: {OPEN: 'danger', CLOSED: 'normal'}, shipAllowYn: {Y: 'warning', N: 'neutral'}},
            rowClass: function (row) { return row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {PreshipPunchRow} */ (row).punchId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
