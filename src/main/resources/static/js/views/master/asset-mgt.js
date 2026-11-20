// @ts-check
/*
 * 금형·지그·게이지 (mst-asset) — P1 4b. hb.grid + 상세 폼 + 설치 이력(원장: 설치·해체·무효).
 * 시각은 회사 시간대 기준 datetime-local 값을 그대로 보낸다(서버가 회사 시간대로 해석, 미래 금지).
 */
/**
 * @typedef {{assetId: number, assetCd: string, rowVer: number, assetType?: string, lifeShots?: string | number | null,
 *   calibCycleDays?: number | null, [field: string]: any}} MstAsset
 * @typedef {{installId: number, equipCd: string, equipNm: string, nodePath?: string | null, installedAt: string,
 *   removedAt?: string | null, timeZone?: string | null, voidYn: string, voidReason?: string | null}} AssetInstall
 * @typedef {{equipCd: string, equipNm: string, nodePath?: string | null}} AssetTarget
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/master/assets';
    var TEXT = ['assetCd', 'assetNm', 'serialNo', 'itemCd', 'lastCalibDate', 'useYn', 'remark'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('assetForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 자산 @type {MstAsset | null} */
    var current = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** 수명 타수 hb.number @type {HB.NumberControl} */
    var shots;
    /** 교정 주기 hb.number @type {HB.NumberControl} */
    var cycle;
    /** 유형 hb.select @type {HB.SelectControl} */
    var typeSel;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {MstAsset | null} row */
    function show(row) {
        current = row;
        hb.showFieldErrors(form, []);
        TEXT.forEach(function (n) { field(n).value = row && row[n] !== null && row[n] !== undefined ? row[n] : ''; });
        typeSel.setValue(row ? row.assetType : '');
        shots.setValue(row ? row.lifeShots : null);
        cycle.setValue(row ? row.calibCycleDays : null);
        if (!row) { field('useYn').value = 'Y'; }
        formMode.textContent = row ? hb.t('js.asset.editing', row.assetCd) : hb.t('js.asset.creating');
        /** @type {HTMLElement} */ (document.getElementById('installSection')).hidden = !row;
        hb.dirty.clear();
        if (row) { loadInstalls(row.assetId); }
    }

    /** 설치 이력 — 자산을 빨리 바꾸면 늦게 온 이전 자산 이력은 hb.latest 가 버린다 @param {number} assetId */
    function loadInstalls(assetId) {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#installs tbody'));
        hb.latest('installs', hb.api(API + '/' + assetId + '/installs')).then(function (res) {
            /** @type {AssetInstall[]} */
            var list = res.data;
            tbody.textContent = '';
            list.forEach(function (i) {
                var tr = hb.el('tr', i.voidYn === 'Y' ? 'hb-tree-dim' : null);
                tr.appendChild(hb.el('td', null, i.equipCd + ' · ' + i.equipNm));
                tr.appendChild(hb.el('td', null, i.nodePath || ''));
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(i.installedAt, i.timeZone)));
                tr.appendChild(hb.el('td', null, i.removedAt ? hb.fmt.datetime(i.removedAt, i.timeZone) : ''));
                tr.appendChild(hb.el('td', null, i.voidYn === 'Y' ? hb.t('js.asset.voided', i.voidReason)
                    : i.removedAt ? hb.t('js.asset.removed') : hb.t('js.asset.installedNow')));
                var td = hb.el('td');
                if (i.voidYn !== 'Y' && !i.removedAt && CAN_SAVE) {
                    td.appendChild(action(hb.t('js.asset.remove'), function (b) {
                        hb.dialog.prompt({title: hb.t('js.asset.dlg.remove'), message: hb.t('js.asset.removePrompt'),
                            label: hb.t('js.asset.removedAtLabel'), required: false, maxLength: 40,
                            ok: hb.t('js.asset.dlg.removeOk')}).then(function (at) {
                            if (at === null) { return; }
                            run(b, API + '/installs/' + i.installId + '/remove', {removedAt: at.trim() || null}, hb.t('js.asset.removedMsg'));
                        });
                    }));
                }
                if (i.voidYn !== 'Y' && CAN_DELETE) {
                    td.appendChild(action(hb.t('js.asset.void'), function (b) {
                        hb.dialog.prompt({title: hb.t('js.asset.dlg.void'), message: hb.t('js.asset.voidPrompt'),
                            label: hb.t('js.asset.reason'), required: true, maxLength: 500,
                            ok: hb.t('js.asset.dlg.voidOk'), danger: true}).then(function (reason) {
                            if (reason === null) { return; }
                            run(b, API + '/installs/' + i.installId + '/void', {reason: reason}, hb.t('js.asset.voidedMsg'));
                        });
                    }));
                }
                tr.appendChild(td);
                tbody.appendChild(tr);
            });
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.asset.noInstalls'));
                td.colSpan = 6;
                tr.appendChild(td);
                tbody.appendChild(tr);
            }
        }, function (err) { notify(err.message, true); });
    }

    /** @param {string} text @param {(b: HTMLButtonElement) => void} fn */
    function action(text, fn) {
        var b = hb.el('button', 'hb-btn-secondary hb-btn-small', text);
        b.type = 'button';
        b.addEventListener('click', function () { fn(b); });
        return b;
    }

    /**
     * 설치·해체·무효 — 끝나면 누른 시점의 자산을 다시 읽는다
     * @param {HTMLButtonElement} btn @param {string} url @param {Record<string, unknown>} payload @param {string} done
     */
    function run(btn, url, payload, done) {
        if (!current) { return; }
        var id = current.assetId;   // 처리 중에 다른 행·신규를 골라도 이 자산으로 마무리
        hb.busy(btn, function () { return hb.api(url, {method: 'POST', body: payload}); }).then(function (res) {
            if (!res) { return; }
            return after(id).then(function () { notify(done); });
        }, function (err) {
            notify(err.message + (err.errors && err.errors.length
                ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : ''), true);
        });
    }

    /** 목록을 다시 읽고 그 행을 다시 고른다(조회 조건 밖이거나 그리드 준비 전이면 상세만 다시) @param {number} id */
    function after(id) {
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () {
            if (!g || !g.selected()) { return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { show(r.data); }); }
        });
    }

    function body() {
        /** @type {Record<string, unknown>} */
        var b = {assetType: typeSel.value(), lifeShots: shots.value(), calibCycleDays: cycle.numberValue(),
            rowVer: current ? current.rowVer : null};
        TEXT.forEach(function (n) { b[n] = field(n).value; });
        return b;
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function newAsset() {
        confirmLeave().then(function (ok) { if (ok) { show(null); hb.page.openDetail(); field('assetCd').focus(); } });
    }
    bind('newBtn', newAsset);
    bind('newBtnList', newAsset);
    bind('saveBtn', function (btn) {
        var editId = current ? current.assetId : null;   // 저장 중에 다른 행·신규를 골라도 이 자산으로 마무리
        var b = body();
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return after(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) { notify(err.message, true); hb.showFieldErrors(form, err.errors); });
    });
    bind('disableBtn', function (btn) {
        if (!current) { return; }
        var asset = current;   // 처리 중에 다른 행·신규를 골라도 이 자산으로 마무리
        hb.dialog.confirm({title: hb.t('js.asset.dlg.disable', asset.assetCd), message: hb.t('js.asset.confirmDisable', asset.assetCd),
            ok: hb.t('js.asset.dlg.disableOk'), danger: true}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/delete', {method: 'POST', body: {items: [{id: asset.assetId, rowVer: asset.rowVer}]}});
            }).then(function (res) {
                if (!res) { return; }
                return after(asset.assetId).then(function () { notify(hb.t('js.equip.disabled')); });
            }, function (err) { notify(err.message, true); hb.showFieldErrors(form, err.errors); });
        });
    });
    bind('installBtn', function (btn) {
        if (!current) { return; }
        // 설치 칸은 installBtn 과 같은 th:if 블록 — 버튼이 있으면 칸도 있다
        var eq = /** @type {HTMLSelectElement} */ (document.getElementById('installEquip')).value;
        if (!eq) { notify(hb.t('js.asset.pickEquip'), true); return; }
        var at = /** @type {HTMLInputElement} */ (document.getElementById('installAt')).value;
        run(btn, API + '/' + current.assetId + '/install', {equipCd: eq, installedAt: at || null},
            hb.t('js.asset.installedMsg'));
    });

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        shots = hb.number(/** @type {HTMLInputElement} */ (field('lifeShots')));
        cycle = hb.number(/** @type {HTMLInputElement} */ (field('calibCycleDays')));
        typeSel = hb.select(/** @type {HTMLSelectElement} */ (field('assetType')));
        if (!CAN_SAVE) {
            /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>} */ (form.querySelectorAll('input, select, textarea'))
                .forEach(function (i) { i.disabled = true; });
        }
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            var g = grid;
            return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
        }});
        return Promise.all([hb.loadSession(), sp.ready, typeSel.ready, hb.api(API + '/targets')]);
    }).then(function (r) {
        var equipSel = document.getElementById('installEquip');   // SAVE 권한 없으면 없음
        if (equipSel) {
            var sel = equipSel;
            var b0 = hb.el('option', null, hb.t('js.common.choose'));
            b0.value = '';
            sel.appendChild(b0);
            /** @type {AssetTarget[]} */
            var targets = r[3].data;
            targets.forEach(function (e) {
                var o = hb.el('option', null, e.equipCd + ' · ' + e.equipNm + (e.nodePath ? ' (' + e.nodePath + ')' : ''));
                o.value = e.equipCd;
                sel.appendChild(o);
            });
        }
        grid = hb.grid('#assetGrid', {
            progCd: PROG, id: 'main', key: 'assetId',
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_phys_asset',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {assetType: 'assetType', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) {
                hb.latest('detail', hb.api(API + '/' + row.assetId)).then(function (x) { show(x.data); }, function (e) { notify(e.message, true); });
                hb.page.openDetail();
            },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
