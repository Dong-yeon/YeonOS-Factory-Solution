// @ts-check
/*
 * 품목관리 (mst-item) — P1 1단계 (docs/17 3장). 구조는 거래처관리(cust-mgt.js)와 같고 디테일 목록이 없다.
 *   마스터 = hb.grid(조회, 열 맞춤) + 오른쪽 상세 폼 / 첨부(도면·성적서) / 엑셀 / 변경 이력
 * 입력 컴포넌트: 조회 조건 hb.searchPanel, 유형·단위 hb.select, 조달 세그먼트, LOT 관리 토글, 순질량 hb.number.
 * 회사·작성자·일시는 서버가 채운다(보내지 않는다). 서버 데이터는 textContent·value 로만 넣는다.
 */
/**
 * @typedef {{itemId: number, itemCd: string, itemNm: string, unitCd: string, rowVer: number, [field: string]: any}} MstItem
 * @typedef {{mapId: number, systemCd: string, systemNm: string, externalId: string, externalNm?: string | null,
 *   useYn: string, rowVer: number}} ItemExtId
 * @typedef {{classId: number | null, props: HB.ClassProp[], attrs: Record<string, unknown>}} ItemAttrView
 * @typedef {{rowKey: string, convId?: number, rowVer?: number, fromUnit?: string, factor?: string | null,
 *   state: 'new' | 'old' | 'deleted', dirty: boolean}} ItemConv
 * @typedef {{classId: number, classCd: string, classNm: string}} ItemClass
 * @typedef {{systemCd: string, systemNm: string}} ItemExtSystem
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var API = '/api/master/items';
    var FIELDS = ['itemCd', 'itemNm', 'spec', 'itemType', 'procType', 'unitCd', 'lotCtrlYn', 'netMassKg', 'hsCd',
        'stdPrice', 'remark', 'useYn'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('itemForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 품목 행 @type {MstItem | null} */
    var current = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** 순질량 hb.number @type {HB.NumberControl} */
    var mass;
    /** 표준단가 hb.number (docs/29_P3 29장 — 비우면 단가 없음) @type {HB.NumberControl} */
    var price;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    // ---------- 상세 폼 (세그먼트·토글·숫자 칸도 같은 get/set) ----------
    /** 상세 폼 칸 @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    /** @param {string} name */
    function getValue(name) {
        if (name === 'procType') {
            var on = /** @type {HTMLInputElement | null} */ (form.querySelector('[data-field="procType"]:checked'));
            return on ? on.value : '';
        }
        if (name === 'lotCtrlYn') { return /** @type {HTMLInputElement} */ (field(name)).checked ? 'Y' : 'N'; }
        if (name === 'netMassKg') { return mass.value(); }
        if (name === 'stdPrice') { return price.value(); }
        return field(name).value;
    }

    /** @param {string} name @param {unknown} v */
    function setValue(name, v) {
        var text = v === null || v === undefined ? '' : String(v);
        if (name === 'procType') {
            /** @type {NodeListOf<HTMLInputElement>} */ (form.querySelectorAll('[data-field="procType"]'))
                .forEach(function (r) { r.checked = r.value === text; });
        } else if (name === 'lotCtrlYn') {
            /** @type {HTMLInputElement} */ (field(name)).checked = text !== 'N';
        } else if (name === 'netMassKg') {
            mass.setValue(text);
        } else if (name === 'stdPrice') {
            price.setValue(text);
        } else if (name === 'itemType' || name === 'unitCd') {
            hb.select(/** @type {HTMLSelectElement} */ (field(name))).setValue(text);
        } else {
            field(name).value = text;
        }
    }

    /** @param {MstItem | null} item */
    function setForm(item) {
        hb.showFieldErrors(form, []);
        FIELDS.forEach(function (n) { setValue(n, item ? item[n] : ''); });
        if (!item) {
            setValue('useYn', 'Y');
            setValue('procType', 'MAKE');
            setValue('lotCtrlYn', 'Y');
        }
        hb.dirty.clear();
    }

    function formBody() {
        /** @type {Record<string, unknown>} */
        var b = {};
        FIELDS.forEach(function (n) { b[n] = getValue(n); });
        if (current) {
            b.rowVer = current.rowVer;
        } else {
            b.erpItemCd = erpCd.value;                     // ERP 품번은 신규 때만 함께 저장(수정은 아래 외부 품번 칸)
            if (cdMode === 'AUTO') { b.itemCd = ''; }       // 서버가 채번 — 코드를 보내면 AUTO_NUMBERED
        }
        return b;
    }

    // ---------- 품목코드 = 시스템 코드 (docs/17 9장 — 만든 뒤 고정, 정책 ITEM_CD_MODE=AUTO 면 서버 채번) ----------
    /** 정책 ITEM_CD_MODE (설정 API) */
    var cdMode = 'MANUAL';
    var erpCd = /** @type {HTMLInputElement} */ (document.getElementById('erpItemCd'));
    var cdFixedHint = /** @type {HTMLElement} */ (document.getElementById('cdFixedHint'));
    var cdAutoHint = /** @type {HTMLElement} */ (document.getElementById('cdAutoHint'));

    /** 코드 칸: 기존 품목은 읽기 전용(고정), AUTO 신규는 비운 채 읽기 전용 + "(자동)". ERP 품번 칸: 신규만 입력, 기존은 보기 */
    function applyCdMode() {
        var cd = /** @type {HTMLInputElement} */ (field('itemCd'));
        var auto = !current && cdMode === 'AUTO';
        cd.readOnly = !!current || auto || erpOwned.indexOf('itemCd') >= 0;
        cd.required = !current && !auto;
        cd.placeholder = auto ? hb.t('js.mstItem.autoCd') : '';
        cdFixedHint.hidden = !current;
        cdAutoHint.hidden = !auto;
        erpCd.value = current && current.erpItemCd ? current.erpItemCd : '';
        erpCd.readOnly = !!current;
    }

    /** 외부 품번을 바꾼 뒤 상세의 ERP 품번 칸만 다시 — 그 사이 다른 품목을 골랐으면 그리지 않는다 */
    function refreshErpCd() {
        if (!current) { return Promise.resolve(); }
        var item = current;
        return hb.api(API + '/' + item.itemId).then(function (res) {
            if (current !== item) { return; }
            item.erpItemCd = res.data.erpItemCd;
            erpCd.value = item.erpItemCd || '';
        });
    }

    // ---------- ERP 주인 필드 잠금 (P1 7a, docs/22 — 정책 ERP_ITEM_SYNC_MODE=PULL) ----------
    /** @type {string[]} */
    var erpOwned = [];

    function applyErpLock() {
        var locked = erpOwned.length > 0;
        /** @type {HTMLElement} */ (document.getElementById('erpHint')).hidden = !locked;
        erpOwned.forEach(function (n) {
            /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>} */ (form.querySelectorAll('[data-field="' + n + '"]'))
                .forEach(function (el) {
                    if (el instanceof HTMLSelectElement || el.type === 'radio') { el.disabled = true; } else { el.readOnly = true; }
                });
        });
        // 품목은 ERP 에서 들어온다 — 신규 버튼을 숨긴다(서버도 409 ERP_OWNED)
        ['newBtn', 'newBtnList'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b && locked) { b.hidden = true; }
        });
    }

    // ---------- 외부 품번 (P1 7a) ----------
    var extBody = /** @type {HTMLElement} */ (document.querySelector('#extIds tbody'));
    var extForm = /** @type {HTMLFormElement | null} */ (document.getElementById('extForm'));   // SAVE 권한 없으면 없음

    /** 외부 품번 목록 — 품목을 빨리 바꾸면 늦게 온 이전 품목 목록은 hb.latest 가 버린다 */
    function loadExtIds() {
        if (!current) { return Promise.resolve(); }
        return hb.latest('extIds', hb.api(API + '/' + current.itemId + '/ext-ids')).then(function (res) {
            /** @type {ItemExtId[]} */
            var list = res.data;
            extBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.mstItem.ext.none'));
                td.colSpan = 5;
                tr.appendChild(td);
                extBody.appendChild(tr);
            }
            list.forEach(function (m) {
                var tr = hb.el('tr', m.useYn === 'Y' ? null : 'hb-tree-dim');
                [m.systemCd + ' · ' + m.systemNm, m.externalId, m.externalNm || '', m.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]
                    .forEach(function (v, i) { tr.appendChild(hb.el('td', i < 3 ? 'hb-wrap' : null, v)); });
                var td = hb.el('td');
                if (ACTIONS.indexOf('SAVE') >= 0) {
                    var b = hb.el('button', 'hb-btn-secondary hb-btn-small', m.useYn === 'Y' ? hb.t('js.mstItem.ext.disable') : hb.t('js.mstItem.ext.enable'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(API + '/ext-ids/' + m.mapId, {method: 'PUT', body: {externalId: m.externalId,
                                externalNm: m.externalNm, useYn: m.useYn === 'Y' ? 'N' : 'Y', rowVer: m.rowVer}});
                        }).then(function (res) {
                            if (!res) { return; }
                            return Promise.all([loadExtIds(), refreshErpCd()]).then(function () { notify(hb.t('js.common.saved')); });
                        }, function (err) { notify(err.message, true); });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                extBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    if (extForm) {
        var ext = extForm;
        ext.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!current) { return; }
            var itemId = current.itemId;
            /** @param {string} n */
            function extField(n) {
                return /** @type {HTMLInputElement | HTMLSelectElement} */ (ext.querySelector('[data-field="' + n + '"]'));
            }
            /** @type {Record<string, string>} */
            var body = {};
            ['systemCd', 'externalId', 'externalNm'].forEach(function (n) {
                body[n] = extField(n).value;
            });
            hb.busy(/** @type {HTMLButtonElement} */ (document.getElementById('addExtBtn')), function () {
                return hb.api(API + '/' + itemId + '/ext-ids', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                extField('externalId').value = '';
                extField('externalNm').value = '';
                hb.showFieldErrors(ext, []);
                hb.dirty.clear(ext);
                return Promise.all([loadExtIds(), refreshErpCd()]).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(ext, err.errors);
            });
        });
    }

    // ---------- 클래스 속성 (P1 7b) ----------
    var attrForm = /** @type {HTMLFormElement} */ (document.getElementById('attrForm'));
    var classSel = /** @type {HTMLSelectElement} */ (document.getElementById('itemClass'));
    /** 시작 때 만든다 @type {HB.Attrs | null} */
    var attrUi = null;
    /** 마지막으로 읽은 품목 속성 @type {ItemAttrView | null} */
    var attrView = null;

    function loadAttrs() {
        if (!current || !attrUi) { return Promise.resolve(); }
        var ui = attrUi;
        return hb.latest('attrs', hb.api(API + '/' + current.itemId + '/attrs')).then(function (res) {
            /** @type {ItemAttrView} */
            var view = res.data;
            attrView = view;
            classSel.value = view.classId === null ? '' : String(view.classId);
            if (view.classId !== null && classSel.value !== String(view.classId)) {
                // 사용 중지된 클래스 — 선택지에 없으니 그대로 보여 준다
                var o = hb.el('option', null, hb.t('js.attrs.inactiveClass'));
                o.value = String(view.classId);
                classSel.appendChild(o);
                classSel.value = String(view.classId);
            }
            hb.showFieldErrors(attrForm, []);
            ui.render(view.props, view.attrs);
        }, function (err) { notify(err.message, true); });
    }

    classSel.addEventListener('change', function () {
        if (!attrUi) { return; }
        var ui = attrUi;
        hb.showFieldErrors(attrForm, []);
        if (!classSel.value) { ui.render([], {}); return; }
        // 클래스를 바꾸면 그 클래스의 속성으로 다시 그린다 — 같은 코드의 값은 이어받는다(저장 전까지 화면에서만)
        var keep = ui.values();
        var item = current, cls = classSel.value;   // 응답 전에 다른 품목·클래스로 바뀌면 그리지 않는다
        hb.latest('classProps', hb.api(API + '/class-props?classId=' + cls)).then(function (res) {
            if (current !== item || classSel.value !== cls) { return; }
            ui.render(res.data, attrView && String(attrView.classId) === cls ? attrView.attrs : keep);
        }, function (err) { notify(err.message, true); });
    });

    /** @param {MstItem | null} item */
    function showDetail(item) {
        current = item;
        setForm(item);
        applyErpLock();
        applyCdMode();
        /** @type {HTMLElement} */ (document.getElementById('extSection')).hidden = !item;
        /** @type {HTMLElement} */ (document.getElementById('attrSection')).hidden = !item;
        if (extForm) { hb.showFieldErrors(extForm, []); }
        formMode.textContent = item ? hb.t('js.mstItem.editing', item.itemCd) : hb.t('js.mstItem.creating');
        /** @type {HTMLElement} */ (document.getElementById('fileSection')).hidden = !item;
        /** @type {HTMLElement} */ (document.getElementById('convSection')).hidden = !item;
        convs = [];
        renderConvs();
        if (item) {
            loadFiles();
            loadConvs();
            loadExtIds();
            loadAttrs();
        }
    }

    /** @param {number} itemId @param {boolean} [keepMsg] */
    function loadDetail(itemId, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + itemId)).then(function (res) { showDetail(res.data); },
            function (err) { notify(err.message, true); });
    }

    /** 미저장(폼·단위 환산 행)이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        var convDirty = convs.some(function (c) { return c.dirty || c.state !== 'old'; });
        if (!(hb.dirty.isDirty() || convDirty)) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    // ---------- 단위 환산 (P1 3a — 1 다른 단위 = factor × 기본 단위, 일괄 저장 규약) ----------
    /** @type {ItemConv[]} */
    var convs = [];
    var convSeq = 0;
    var convBox = /** @type {HTMLElement} */ (document.getElementById('convs'));

    function loadConvs() {
        if (!current) { return; }
        hb.latest('convs', hb.api(API + '/' + current.itemId + '/unit-conv')).then(function (res) {
            convs = (res.data || []).map(/** @param {ItemConv} c */ function (c) {
                return Object.assign({rowKey: 'c' + (++convSeq), state: 'old', dirty: false}, c);
            });
            renderConvs();
        }, function (err) { notify(err.message, true); });
    }

    function renderConvs() {
        convBox.textContent = '';
        var canSave = ACTIONS.indexOf('SAVE') >= 0;
        var visible = convs.filter(function (c) { return c.state !== 'deleted'; });
        if (current && !visible.length) {
            convBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstItem.noConvs')));
        }
        visible.forEach(function (c) {
            var row = hb.el('div', 'hb-conv-row');
            row.setAttribute('data-row-key', c.rowKey);
            row.appendChild(hb.el('span', null, '1'));
            var unit = hb.el('select');
            unit.setAttribute('data-field', 'fromUnit');
            unit.setAttribute('aria-label', hb.t('js.mstItem.convFrom'));
            unit.disabled = !canSave;
            hb.select(unit, {code: 'UNIT', blank: 'select'}).ready.then(function () {
                unit.value = c.fromUnit || '';
            });
            unit.addEventListener('change', function () { c.fromUnit = unit.value; c.dirty = true; });
            row.appendChild(unit);
            row.appendChild(hb.el('span', null, '='));
            var f = hb.el('input');
            f.setAttribute('data-field', 'factor');
            f.setAttribute('aria-label', hb.t('js.mstItem.convFactor'));
            f.setAttribute('data-scale', '9');
            f.setAttribute('data-pad', 'false');
            f.setAttribute('data-min', '0.000000001');
            f.readOnly = !canSave;
            var num = hb.number(f);
            num.setValue(c.factor);
            f.addEventListener('blur', function () { c.factor = num.value(); });
            f.addEventListener('input', function () { c.dirty = true; });
            row.appendChild(f);
            row.appendChild(hb.el('span', null, current ? current.unitCd : ''));
            if (canSave) {
                var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    if (c.state === 'new') { convs.splice(convs.indexOf(c), 1); } else { c.state = 'deleted'; }
                    renderConvs();
                });
                row.appendChild(del);
            }
            convBox.appendChild(row);
        });
    }

    /** 서버로 보낼 환산(화면 상태 state·dirty 제외) @param {ItemConv} c */
    function convItem(c) {
        return {rowKey: c.rowKey, convId: c.convId, rowVer: c.rowVer, fromUnit: c.fromUnit, factor: c.factor};
    }

    /** @param {HTMLButtonElement} btn */
    function saveConvs(btn) {
        if (!current) { return; }
        var item = current;   // 저장 중에 다른 품목·신규를 골라도 이 품목으로 마무리
        convBox.querySelectorAll('input').forEach(function (i) { if (i.hbNumber) { i.hbNumber.validate(); } });
        var batch = {
            created: convs.filter(function (c) { return c.state === 'new'; }).map(convItem),
            updated: convs.filter(function (c) { return c.state === 'old' && c.dirty; }).map(convItem),
            deleted: convs.filter(function (c) { return c.state === 'deleted'; }).map(convItem)
        };
        if (!batch.created.length && !batch.updated.length && !batch.deleted.length) {
            notify(hb.t('js.common.noChanges'));
            return;
        }
        hb.busy(btn, function () {
            return hb.api(API + '/' + item.itemId + '/unit-conv/batch?rowVer=' + item.rowVer, {method: 'POST', body: batch});
        }).then(function (res) {
            if (!res) { return; }
            // 품목 버전이 올라갔다 — 상세를 다시 읽는다
            return loadDetail(item.itemId, true).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            convBox.querySelectorAll('.hb-field-error').forEach(function (e) { e.remove(); });
            convBox.querySelectorAll('[aria-invalid]').forEach(function (e) { e.removeAttribute('aria-invalid'); });
            (err.errors || []).forEach(/** @param {HB.FieldError} er */ function (er) {
                var row = convBox.querySelector('[data-row-key="' + er.rowKey + '"]');
                if (!row) { return; }
                var t = er.field ? row.querySelector('[data-field="' + er.field + '"]') : null;
                (t || row).setAttribute('aria-invalid', 'true');
                row.appendChild(hb.el('span', 'hb-field-error', er.message));
            });
        });
    }

    // ---------- 첨부 ----------
    /** 첨부 목록 — 품목을 빨리 바꾸면 늦게 온 이전 품목 목록은 hb.latest 가 버린다 */
    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!current) { return; }
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + current.itemId)).then(function (res) {
            (res.list || []).forEach(function (f) {
                var li = hb.el('li');
                var a = hb.el('a', null, f.name);
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (ACTIONS.indexOf('SAVE') >= 0) {
                    var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        hb.dialog.confirm({title: hb.t('js.mstItem.dlg.fileDelete'), message: hb.t('js.mstItem.confirmFileDelete', f.name),
                            ok: hb.t('js.mstItem.dlg.fileDeleteOk'), danger: true}).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                .then(loadFiles, function (err) { notify(err.message, true); });
                        });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
        }, function (err) { notify(err.message, true); });
    }

    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));   // SAVE 권한 없으면 없음
    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            if (!file || !current) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(current.itemId));
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

    // ---------- 버튼 ----------
    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    /** 목록 다시 읽기 — 그리드가 아직 준비 전이면(첫 조회 전 저장) 건너뛴다 @param {unknown} [keepId] */
    function reloadGrid(keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }

    function newItem() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            showDetail(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    }
    bind('newBtn', newItem);
    bind('newBtnList', newItem);
    bind('addConvBtn', function () {
        convs.push({rowKey: 'n' + (++convSeq), state: 'new', dirty: true, fromUnit: '', factor: null});
        renderConvs();
    });
    bind('saveConvsBtn', saveConvs);
    bind('importBtn', function () {
        // 반영되면 목록을 다시 읽는다(검증만 하고 닫으면 그대로)
        hb.importDialog({templateCd: 'MST_ITEM', onDone: function () { if (grid) { grid.reload(true); } }});
    });

    bind('saveBtn', function (btn) {
        // 숫자 칸 화면 검사 먼저 — 형식 오류면 보내지 않는다(서버도 다시 검사)
        if (!mass.validate().ok) {
            field('netMassKg').focus();
            return;
        }
        if (!price.validate().ok) {
            field('stdPrice').focus();
            return;
        }
        var body = formBody();
        var editId = current ? current.itemId : null;   // 저장 중에 다른 행·신규를 골라도 이 품목으로 마무리
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            var id = editId !== null ? editId : res.data;
            hb.dirty.clear();
            // 그리드가 같은 행을 다시 고르면 onSelect 가 상세를 다시 읽는다(그때 문구를 지움) → 다 읽은 뒤 알린다
            return reloadGrid(id).then(function () {
                if (!grid || !grid.selected()) { return loadDetail(id, true); }   // 조회 조건 밖이면 상세만 다시
            }).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    bind('saveAttrsBtn', function (btn) {
        if (!current || !attrUi || !attrUi.validate()) { return; }
        var item = current;   // 저장 중에 다른 품목·신규를 골라도 이 품목으로 마무리
        var body = {classId: classSel.value ? Number(classSel.value) : null, attrs: attrUi.values(), rowVer: item.rowVer};
        hb.busy(btn, function () {
            return hb.api(API + '/' + item.itemId + '/attrs', {method: 'PUT', body: body});
        }).then(function (res) {
            if (!res) { return; }
            // 품목 버전이 올랐다 — 상세를 다시 읽어 다음 저장이 409 가 되지 않게
            return loadDetail(item.itemId, true).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(attrForm, err.errors);
        });
    });

    bind('disableBtn', function (btn) {
        if (!current) { return; }
        var item = current;
        hb.dialog.confirm({title: hb.t('js.mstItem.dlg.disable', item.itemNm), message: hb.t('js.mstItem.confirmDisable', item.itemNm),
            ok: hb.t('js.mstItem.dlg.disableOk'), danger: true}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/delete', {method: 'POST', body: {items: [{id: item.itemId, rowVer: item.rowVer}]}});
            }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.mstItem.disabled'));
                showDetail(null);
                reloadGrid();
            }, function (err) { notify(err.message, true); });
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    // 속성·외부 품번 칸 입력도 이탈 확인 대상(7단계 QA 결함 #11)
    if (attrForm) { hb.dirty.watch(attrForm); }
    if (extForm) { hb.dirty.watch(extForm); }
    search.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        mass = hb.number(/** @type {HTMLInputElement} */ (field('netMassKg')));
        price = hb.number(/** @type {HTMLInputElement} */ (field('stdPrice')));
        return Promise.all([hb.loadSession(), sp.ready, hb.select(/** @type {HTMLSelectElement} */ (field('itemType'))).ready,
            hb.select(/** @type {HTMLSelectElement} */ (field('unitCd'))).ready, hb.api(API + '/settings'), hb.api(API + '/ext-systems'),
            hb.api(API + '/classes')]);
    }).then(function (r) {
        attrUi = hb.attrs(/** @type {HTMLElement} */ (document.getElementById('attrBox')), {editable: ACTIONS.indexOf('SAVE') >= 0});
        var blank = hb.el('option', null, hb.t('js.attrs.noClass'));
        blank.value = '';
        classSel.appendChild(blank);
        /** @type {ItemClass[]} */
        var itemClasses = r[6].data;
        itemClasses.forEach(function (c) {
            var o = hb.el('option', null, c.classCd + ' · ' + c.classNm);
            o.value = String(c.classId);
            classSel.appendChild(o);
        });
        classSel.disabled = ACTIONS.indexOf('SAVE') < 0;
        erpOwned = r[4].data.erpOwnedFields || [];
        cdMode = r[4].data.itemCdMode || 'MANUAL';
        var extSel = document.getElementById('extSystem');   // SAVE 권한 없으면 없음(외부 품번 추가 폼)
        if (extSel) {
            var sel = extSel;
            /** @type {ItemExtSystem[]} */
            var systems = r[5].data;
            systems.forEach(function (s) {
                var o = hb.el('option', null, s.systemCd + ' · ' + s.systemNm);
                o.value = s.systemCd;
                sel.appendChild(o);
            });
        }
        setForm(null);
        applyErpLock();
        applyCdMode();
        grid = hb.grid('#itemGrid', {
            progCd: PROG, id: 'main', key: 'itemId',
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_item',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {itemType: 'itemType', procType: 'procType', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) { loadDetail(row.itemId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
