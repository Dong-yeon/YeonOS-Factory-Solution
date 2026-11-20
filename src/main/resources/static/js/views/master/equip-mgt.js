// @ts-check
/*
 * 설비 관리 (mst-equip) — P1 4b. hb.grid + 상세 폼. 위치 선택지 = 고른 공장의 사용 중 라인·셀(경로).
 * 클래스는 대화상자에서 추가·수정(사용 중 설비가 있으면 사용 중지 불가 — 서버 판정).
 */
/**
 * @typedef {{equipId: number, equipCd: string, plantCd: string, classId: number | null, nodeId?: number | null,
 *   nodePath?: string | null, rowVer: number, [field: string]: any}} MstEquip
 * @typedef {{nodeId: number, path: string, nodeNm: string}} EquipPlace
 * @typedef {{plantCd: string, plantNm: string}} EquipPlant
 * @typedef {{classId: number | null, classCd: string, classNm: string, useYn: string, equipCnt: number,
 *   rowVer: number | null}} EquipClass  대화상자의 추가 행은 classId·rowVer 가 null
 * @typedef {{classId: number | null, props: HB.ClassProp[], attrs: Record<string, unknown>}} EquipAttrView
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/master/equips';
    var FIELDS = ['equipCd', 'equipNm', 'maker', 'model', 'serialNo', 'assetNo', 'installDate', 'useYn', 'remark'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('equipForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 설비 @type {MstEquip | null} */
    var current = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {EquipPlant[]} */
    var plants = [];
    /** @type {EquipClass[]} */
    var classes = [];

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** 상세 폼 select 칸 @param {string} n */
    function selectField(n) {
        return /** @type {HTMLSelectElement} */ (field(n));
    }

    /**
     * select 선택지를 다시 채운다(고른 값은 유지). blankText 가 null 이면 빈 선택지 없음
     * @template T
     * @param {HTMLSelectElement} sel @param {T[]} list @param {(x: T) => unknown} valueOf @param {(x: T) => string} textOf
     * @param {string | null} blankText
     */
    function options(sel, list, valueOf, textOf, blankText) {
        var keep = sel.value;
        sel.textContent = '';
        if (blankText !== null) {
            var b = hb.el('option', null, blankText);
            b.value = '';
            sel.appendChild(b);
        }
        list.forEach(function (x) {
            var o = hb.el('option', null, textOf(x));
            o.value = String(valueOf(x));
            sel.appendChild(o);
        });
        sel.value = keep;
    }

    /**
     * 선택지는 사용 중 라인·셀뿐 — 지금 위치(row)가 사용 중지 노드면 그 값을 선택지로 남긴다(없으면 저장 때 위치가 지워진다)
     * @param {string} plantCd @param {unknown} value @param {MstEquip | null} [row]
     */
    function loadPlaces(plantCd, value, row) {
        var sel = selectField('nodeId');
        if (!plantCd) { options(sel, [], String, String, hb.t('js.equip.noPlace')); return Promise.resolve(); }
        return hb.latest('places', hb.api(API + '/places?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            /** @type {EquipPlace[]} */
            var places = res.data;
            options(sel, places, function (n) { return n.nodeId; }, function (n) { return n.path + ' · ' + n.nodeNm; },
                hb.t('js.equip.noPlace'));
            var v = value === null || value === undefined ? '' : String(value);
            if (v && !places.some(function (n) { return String(n.nodeId) === v; })) {
                var o = hb.el('option', null, hb.t('js.equip.placeInactive', row && row.nodePath ? row.nodePath : v));
                o.value = v;
                sel.appendChild(o);
            }
            sel.value = v;
        });
    }

    // ---------- 클래스 속성 (P1 7b) ----------
    var attrForm = /** @type {HTMLFormElement} */ (document.getElementById('attrForm'));
    var attrHint = /** @type {HTMLElement} */ (document.getElementById('attrHint'));
    /** 시작 때 만든다 @type {HB.Attrs | null} */
    var attrUi = null;
    /** 속성 저장 때 보낼 클래스(폼에서 바꾸면 새 클래스) @type {number | null} */
    var attrClassId = null;
    /** @type {Record<string, unknown>} */
    var attrValues = {};

    function loadAttrs() {
        /** @type {HTMLElement} */ (document.getElementById('attrSection')).hidden = !current;
        attrHint.hidden = true;
        if (!current || !attrUi) { return Promise.resolve(); }
        var ui = attrUi;
        return hb.latest('attrs', hb.api(API + '/' + current.equipId + '/attrs')).then(function (res) {
            /** @type {EquipAttrView} */
            var view = res.data;
            hb.showFieldErrors(attrForm, []);
            attrClassId = view.classId;
            attrValues = view.attrs || {};
            ui.render(view.props, attrValues);
        }, function (err) { notify(err.message, true); });
    }

    /**
     * 설비 폼에서 클래스를 바꾸면 속성 칸을 새 클래스로 다시 그린다 — [속성 저장] 이 클래스와 새 값을 한 번에 저장한다.
     * 필수 속성 값이 있는 설비를 그 속성이 없는 클래스로 옮길 길(7단계 QA 결함 #3). 같은 코드의 값은 옮겨 둔다
     */
    function classChanged() {
        if (!current || !attrUi) { return; }
        var ui = attrUi;
        var id = field('classId').value ? Number(field('classId').value) : null;
        if (!id || id === current.classId) { loadAttrs(); return; }
        hb.latest('attrs', hb.api(API + '/class-props?classId=' + id)).then(function (res) {
            /** @type {HB.ClassProp[]} */
            var props = res.data;
            hb.showFieldErrors(attrForm, []);
            attrClassId = id;
            /** @type {Record<string, unknown>} */
            var carry = {};
            props.forEach(function (p) { if (attrValues[p.propCd] !== undefined) { carry[p.propCd] = attrValues[p.propCd]; } });
            ui.render(props, carry);
            attrHint.hidden = false;
            hb.dirty.mark();
        }, function (err) { notify(err.message, true); });
    }

    bind('saveAttrsBtn', function (btn) {
        if (!current || !attrUi || !attrUi.validate()) { return; }
        var equip = current;   // 저장 중에 다른 행·신규를 골라도 이 설비로 마무리
        var attrs = attrUi.values();
        hb.busy(btn, function () {
            return hb.api(API + '/' + equip.equipId + '/attrs', {method: 'PUT',
                body: {classId: attrClassId, attrs: attrs, rowVer: equip.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            // 설비 버전이 올랐다 — 다시 읽어 다음 저장이 409 가 되지 않게
            return after(equip.equipId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(attrForm, err.errors);
        });
    });

    /** @param {MstEquip | null} row */
    function show(row) {
        current = row;
        if (attrUi) { loadAttrs(); }
        hb.showFieldErrors(form, []);
        FIELDS.forEach(function (n) { field(n).value = row && row[n] !== null && row[n] !== undefined ? row[n] : ''; });
        if (!row) { field('useYn').value = 'Y'; }
        field('plantCd').value = row ? row.plantCd : (sp.values().plantCd || (plants[0] && plants[0].plantCd) || '');
        field('classId').value = row ? String(row.classId) : '';
        /** @type {HTMLInputElement} */ (field('equipCd')).readOnly = !CAN_SAVE;
        formMode.textContent = row ? hb.t('js.equip.editing', row.equipCd) : hb.t('js.equip.creating');
        hb.dirty.clear();
        return loadPlaces(field('plantCd').value, row ? row.nodeId : null, row);
    }

    field('plantCd').addEventListener('change', function () { loadPlaces(field('plantCd').value, null); });
    field('classId').addEventListener('change', classChanged);

    function body() {
        /** @type {Record<string, unknown>} */
        var b = {plantCd: field('plantCd').value, classId: field('classId').value ? Number(field('classId').value) : null,
            nodeId: field('nodeId').value ? Number(field('nodeId').value) : null, rowVer: current ? current.rowVer : null};
        FIELDS.forEach(function (n) { b[n] = field(n).value; });
        return b;
    }

    function loadClasses() {
        return hb.api('/api/master/equip-classes').then(function (res) {
            classes = res.data;
            var active = classes.filter(function (c) { return c.useYn === 'Y'; });
            options(/** @type {HTMLSelectElement} */ (document.getElementById('classSel')), active, function (c) { return c.classId; },
                function (c) { return c.classNm; }, hb.t('js.common.all'));
            options(selectField('classId'), current ? classes : active, function (c) { return c.classId; },
                function (c) { return c.classCd + ' · ' + c.classNm; }, hb.t('js.common.choose'));
        });
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

    /** 목록을 다시 읽고 그 행을 다시 고른다(조회 조건 밖이거나 그리드 준비 전이면 상세만 다시) @param {number} id */
    function after(id) {
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () {
            if (!g || !g.selected()) { return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { return show(r.data); }); }
        });
    }

    function newEquip() {
        confirmLeave().then(function (ok) { if (ok) { show(null); hb.page.openDetail(); field('equipCd').focus(); } });
    }
    bind('newBtn', newEquip);
    bind('newBtnList', newEquip);
    bind('saveBtn', function (btn) {
        var editId = current ? current.equipId : null;   // 저장 중에 다른 행·신규를 골라도 이 설비로 마무리
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
        var equip = current;   // 처리 중에 다른 행·신규를 골라도 이 설비로 마무리
        hb.dialog.confirm({title: hb.t('js.equip.dlg.disable', equip.equipCd), message: hb.t('js.equip.confirmDisable', equip.equipCd),
            ok: hb.t('js.equip.dlg.disableOk'), danger: true}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/delete', {method: 'POST', body: {items: [{id: equip.equipId, rowVer: equip.rowVer}]}});
            }).then(function (res) {
                if (!res) { return; }
                return after(equip.equipId).then(function () { notify(hb.t('js.equip.disabled')); });
            }, function (err) { notify(err.message, true); hb.showFieldErrors(form, err.errors); });
        });
    });

    // ---------- 클래스 관리 대화상자 ----------
    bind('classBtn', function () {
        var d = hb.el('dialog', 'hb-dialog');
        d.appendChild(hb.el('h2', null, hb.t('js.equip.classTitle')));
        var bodyEl = hb.el('div', 'hb-dialog-body');
        var t = hb.el('table', 'hb-cmp-table');
        var err = hb.el('p', 'hb-msg-error');
        function render() {
            t.textContent = '';
            var h = hb.el('tr');
            [hb.t('js.equip.classCd'), hb.t('js.equip.classNm'), hb.t('js.equip.classCnt'), hb.t('js.equip.classUse'), '']
                .forEach(function (x) { h.appendChild(hb.el('th', null, x)); });
            t.appendChild(h);
            classes.concat([{classId: null, classCd: '', classNm: '', useYn: 'Y', equipCnt: 0, rowVer: null}]).forEach(function (c) {
                var tr = hb.el('tr');
                var cd = hb.el('input');
                cd.value = c.classCd;
                cd.maxLength = 30;
                cd.setAttribute('aria-label', hb.t('js.equip.classCd'));
                var nm = hb.el('input');
                nm.value = c.classNm;
                nm.maxLength = 100;
                nm.setAttribute('aria-label', hb.t('js.equip.classNm'));
                var use = hb.el('select');
                [['Y', hb.t('js.yn.Y')], ['N', hb.t('js.yn.N')]].forEach(function (o) {
                    var op = hb.el('option', null, o[1]);
                    op.value = o[0];
                    use.appendChild(op);
                });
                use.value = c.useYn;
                use.setAttribute('aria-label', hb.t('js.equip.classUse'));
                [cd, nm].forEach(function (x) { var td = hb.el('td'); td.appendChild(x); tr.appendChild(td); });
                tr.appendChild(hb.el('td', 'hb-num', c.classId ? String(c.equipCnt) : ''));
                var tdu = hb.el('td');
                tdu.appendChild(use);
                tr.appendChild(tdu);
                var save = hb.el('button', 'hb-btn-secondary hb-btn-small', c.classId ? hb.t('js.equip.classSave') : hb.t('js.equip.classAdd'));
                save.type = 'button';
                save.disabled = !CAN_SAVE;
                save.addEventListener('click', function () {
                    err.textContent = '';
                    var payload = {classCd: cd.value, classNm: nm.value, useYn: use.value, rowVer: c.rowVer};
                    hb.busy(save, function () {
                        return c.classId ? hb.api('/api/master/equip-classes/' + c.classId, {method: 'PUT', body: payload})
                            : hb.api('/api/master/equip-classes', {method: 'POST', body: payload});
                    }).then(function (res) {
                        if (!res) { return; }
                        return loadClasses().then(render);
                    }, function (e) {
                        err.textContent = e.message + (e.errors ? ' ' + e.errors.map(/** @param {HB.FieldError} x */ function (x) { return x.message; }).join(' ') : '');
                    });
                });
                var tds = hb.el('td');
                tds.appendChild(save);
                tr.appendChild(tds);
                t.appendChild(tr);
            });
        }
        render();
        bodyEl.appendChild(t);
        bodyEl.appendChild(err);
        d.appendChild(bodyEl);
        var bar = hb.el('div', 'hb-dialog-bar');
        var close = hb.el('button', 'hb-btn-secondary', hb.t('js.common.close'));
        close.type = 'button';
        close.addEventListener('click', function () { d.close(); });
        bar.appendChild(close);
        d.appendChild(bar);
        d.addEventListener('close', function () { d.remove(); });
        document.body.appendChild(d);
        d.showModal();
    });

    hb.dirty.watch(form);
    hb.dirty.watch(attrForm);       // 속성 칸 입력도 이탈 확인 대상(7단계 QA 결함 #11)
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), loadClasses()]);
    }).then(function (r) {
        plants = r[1].data;
        attrUi = hb.attrs(/** @type {HTMLElement} */ (document.getElementById('attrBox')), {editable: CAN_SAVE});
        options(/** @type {HTMLSelectElement} */ (document.getElementById('plantSel')), plants, function (p) { return p.plantCd; },
            function (p) { return p.plantCd + ' · ' + p.plantNm; }, hb.t('js.common.all'));
        options(selectField('plantCd'), plants, function (p) { return p.plantCd; }, function (p) { return p.plantCd + ' · ' + p.plantNm; }, null);
        if (!CAN_SAVE) {
            /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>} */ (form.querySelectorAll('input, select, textarea'))
                .forEach(function (i) { i.disabled = true; });
        }
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            var g = grid;
            return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
        }});
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#equipGrid', {
            progCd: PROG, id: 'main', key: 'equipId',
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_equip',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) {
                hb.latest('detail', hb.api(API + '/' + row.equipId)).then(function (r) { return show(r.data); }, function (e) { notify(e.message, true); });
                hb.page.openDetail();
            },
            notify: notify
        });
        return grid.ready.then(function () { return show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
