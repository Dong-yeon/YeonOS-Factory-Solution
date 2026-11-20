// @ts-check
/*
 * 정책 (sys-policy) — 시스템관리 2단계 (docs/36 9.2). 정의 목록(hb.grid, UI 단계 3 ① docs/36 17.2 — 조건·정렬·쪽은 서버) + 오른쪽: 설명·형식·기본값 + 범위(회사/공장) + 값 편집.
 * 값 칸은 정의 형식대로 만든다(BOOL·ENUM = 선택, INT = 숫자, STRING·IP_LIST = 글자). 검증은 서버(PolicyService).
 * 보안 정책(관리자만)은 편집 칸을 잠근다 — 판정은 서버.
 */
/**
 * @typedef {{plantCd: string, policyKey: string, policyValue: string, rowVer: number, updatedAt?: string, updatedBy?: string}} SysPolicyValue
 * @typedef {{policyKey: string, category: string, desc: string, valueType: string, defaultValue: string,
 *   allowedValues: string[], minValue: number | null, maxValue: number | null, plantScope: boolean, security: boolean,
 *   editable: boolean, values: SysPolicyValue[]}} SysPolicy
 */
(function () {
    'use strict';

    var API = '/api/admin/policies';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var COMPANY = '*';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('policyForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var info = /** @type {HTMLElement} */ (document.getElementById('info'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {HB.Grid | null} */
    var grid = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** 저장 뒤 같은 정책을 다시 고를 때 보일 범위(그리드 onSelect 가 쓴다) */
    var nextPlant = '';
    var scopeSel = /** @type {HTMLSelectElement} */ (document.getElementById('scopeSel'));
    var valueBox = /** @type {HTMLElement} */ (document.getElementById('valueBox'));
    var scopeNote = /** @type {HTMLElement} */ (document.getElementById('scopeNote'));
    var catSel = /** @type {HTMLSelectElement} */ (document.getElementById('catSel'));
    /** @type {string[]} */
    var plants = [];
    /** @type {SysPolicy | null} */
    var current = null;
    /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | null} */
    var valueEl = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {SysPolicy} p @param {string} plant */
    function valueAt(p, plant) {
        return p.values.filter(function (v) { return v.plantCd === plant; })[0] || null;
    }

    /** 정의 형식대로 값 칸 @param {SysPolicy} p @param {string} value */
    function makeValue(p, value) {
        if (valueEl) { valueEl.remove(); }
        /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */
        var el;
        if (p.valueType === 'BOOL' || p.valueType === 'ENUM') {
            var s = hb.el('select');
            (p.valueType === 'BOOL' ? ['Y', 'N'] : p.allowedValues).forEach(function (a) {
                var o = hb.el('option', null, p.valueType === 'BOOL' ? (a === 'Y' ? hb.t('js.sysPolicy.yes') : hb.t('js.sysPolicy.no')) : a);
                o.value = a;
                s.appendChild(o);
            });
            el = s;
        } else if (p.valueType === 'INT') {
            var i = hb.el('input');
            i.type = 'text';
            i.inputMode = 'numeric';
            i.maxLength = 11;
            el = i;
        } else {
            var t = hb.el('textarea');
            t.rows = p.valueType === 'IP_LIST' ? 3 : 2;
            t.maxLength = 2000;
            el = t;
        }
        el.value = value;
        el.setAttribute('data-field', 'value');
        el.setAttribute('aria-label', p.policyKey);
        el.disabled = !CAN_SAVE || !p.editable;
        valueBox.appendChild(el);
        valueEl = el;
    }

    /** @param {SysPolicy} p @param {string} plant */
    function show(p, plant) {
        current = p;
        hb.showFieldErrors(form, []);
        form.hidden = false;
        formMode.textContent = p.policyKey;
        info.textContent = '';
        /** @type {[string, string][]} */
        var rows = [[hb.t('js.sysPolicy.desc'), p.desc], [hb.t('js.sysPolicy.type'), p.valueType],
            [hb.t('js.sysPolicy.default'), p.defaultValue]];
        if (p.allowedValues.length) { rows.push([hb.t('js.sysPolicy.allowed'), p.allowedValues.join(', ')]); }
        if (p.minValue !== null || p.maxValue !== null) {
            rows.push([hb.t('js.sysPolicy.range'), (p.minValue === null ? '' : p.minValue) + ' ~ ' + (p.maxValue === null ? '' : p.maxValue)]);
        }
        if (p.security) { rows.push([hb.t('js.sysPolicy.security'), hb.t('js.sysPolicy.securityNote')]); }
        rows.forEach(function (r) {
            info.appendChild(hb.el('dt', null, r[0]));
            info.appendChild(hb.el('dd', null, r[1]));
        });
        scopeSel.textContent = '';
        var co = hb.el('option', null, hb.t('js.sysPolicy.company'));
        co.value = COMPANY;
        scopeSel.appendChild(co);
        if (p.plantScope) {
            plants.forEach(function (pc) {
                var o = hb.el('option', null, hb.t('js.sysPolicy.plant', pc));
                o.value = pc;
                scopeSel.appendChild(o);
            });
        }
        scopeSel.value = plant;
        shownScope = plant;
        scopeSel.disabled = !p.plantScope;
        var v = valueAt(p, plant);
        var company = valueAt(p, COMPANY);
        // 값이 없으면 지금 실제로 쓰이는 값(공장 → 회사 → 기본값)을 채워 둔다
        makeValue(p, v ? v.policyValue : (company ? company.policyValue : p.defaultValue));
        scopeNote.textContent = v ? hb.t('js.sysPolicy.setBy', v.updatedBy || '', v.updatedAt ? hb.fmt.datetime(v.updatedAt) : '')
            : (plant === COMPANY ? hb.t('js.sysPolicy.usingDefault') : hb.t('js.sysPolicy.usingCompany'));
        var resetBtn = document.getElementById('resetBtn');
        if (resetBtn) { resetBtn.hidden = !v || !p.editable; }
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !p.editable; }
        hb.dirty.clear();
    }

    /** 다시 읽고 그 정책을 그 범위로 다시 고른다 — 이 쪽 밖(조건·정렬)이면 폼을 닫는다 @param {string} [keepKey] @param {string} [keepPlant] */
    function load(keepKey, keepPlant) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        nextPlant = keepPlant || COMPANY;
        return g.reload(false, keepKey).then(function () {
            nextPlant = '';
            if (!g.selected()) { current = null; form.hidden = true; }
        });
    }

    var shownScope = '';   // 지금 보이는 범위 — 이동을 취소하면 고르기 칸을 되돌린다
    scopeSel.addEventListener('change', function () {
        if (!current) { return; }
        var p = current;
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { scopeSel.value = shownScope; return; }
            show(p, scopeSel.value);
        });
    });

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    bind('saveBtn', function (btn) {
        if (!current || !valueEl) { return; }
        var p = current;   // 저장 중 다른 정책을 골라도 이 정책으로 마무리
        var plant = scopeSel.value;
        var v = valueAt(p, plant);
        var body = {plantCd: plant, value: valueEl.value, rowVer: v ? v.rowVer : null};
        hb.busy(btn, function () {
            return hb.api(API + '/' + encodeURIComponent(p.policyKey), {method: 'PUT', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(p.policyKey, plant).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    bind('resetBtn', function (btn) {
        if (!current) { return; }
        var p = current;
        var plant = scopeSel.value;
        var v = valueAt(p, plant);
        if (!v) { return; }
        var rowVer = v.rowVer;
        sysUi.ask('js.sysPolicy.dlg.reset', hb.t('js.sysPolicy.confirmReset', p.policyKey), 'js.sysPolicy.dlg.resetOk').then(function (ok) {
            if (!ok) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + encodeURIComponent(p.policyKey) + '/reset', {method: 'POST', body: {plantCd: plant, rowVer: rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(p.policyKey, plant).then(function () { notify(hb.t('js.sysPolicy.resetDone')); });
            }, function (err) { notify(err.message, true); });
        });
    });

    // 값 칸만 본다(범위 선택을 바꾸는 것은 입력이 아니다 — 폼 전체를 보면 범위 변경이 "저장 안 함" 확인을 띄운다)
    hb.dirty.watch(valueBox);
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.api(API)]);
    }).then(function (r) {
        plants = r[1].list || [];
        // 분류 고르기 칸 = 정의 키 앞부분(전역 정의 목록 한 번)
        /** @type {string[]} */
        var cats = [];
        /** @type {SysPolicy[]} */ (r[2].list || []).forEach(function (p) { if (cats.indexOf(p.category) < 0) { cats.push(p.category); } });
        cats.forEach(function (c) {
            var o = hb.el('option', null, c);
            o.value = c;
            catSel.appendChild(o);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return sysUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#policyGrid', {
            progCd: 'sys-policy', id: 'main', key: 'policyKey',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            rowClass: function (row) { return row.changed ? null : 'hb-tree-dim'; },   // 바꾸지 않은(기본값 그대로) 정책은 흐리게
            beforeSelect: sysUi.beforeSelect,
            onSelect: function (row) {
                show(/** @type {SysPolicy} */ (row.view), nextPlant || COMPANY);
                hb.page.openDetail();
            },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
