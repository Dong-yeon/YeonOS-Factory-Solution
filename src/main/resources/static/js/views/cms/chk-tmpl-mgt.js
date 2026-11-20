/*
 * 점검표 (cms-chk-tmpl) — CMS 1부 C2 (docs/33 4.3, D8).
 * 표 → 머리 폼 + 항목 블록(추가·삭제, 한 벌 저장 — 머리 rowVer). 칸 오류는 행 키(보낸 순서)로 그 블록에 표시.
 */
(function () {
    'use strict';

    var API = '/api/cms/chk-tmpls';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('tmplForm');
    var search = document.getElementById('search');
    var itemBody = document.getElementById('items');
    var current = null, sp, grid;
    var kindName = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 칸 하나 = 라벨 + 입력 (항목 블록 안 .hb-form 의 칸) */
    function cell(box, labelKeyText, control, wide) {
        var l = hb.el('label', wide ? 'hb-wide' : null);
        l.appendChild(hb.el('span', null, labelKeyText));
        l.appendChild(control);
        box.appendChild(l);
        return control;
    }

    function input(name, value, attrs) {
        var i = hb.el('input');
        i.setAttribute('data-field', name);
        i.value = value === null || value === undefined ? '' : String(value);
        Object.keys(attrs || {}).forEach(function (k) { i.setAttribute(k, attrs[k]); });
        i.disabled = !CAN_SAVE;
        return i;
    }

    function select(name, value, opts) {
        var s = hb.el('select');
        s.setAttribute('data-field', name);
        opts.forEach(function (o) { option(s, o[0], o[1]); });
        s.value = value;
        s.disabled = !CAN_SAVE;
        return s;
    }

    /**
     * 항목 하나 = 작은 폼 블록(상세 패널이 좁아 표로 두면 칸이 한 글자로 줄었다). 판정이 수치가 아니면 하한·상한·자릿수를 끈다.
     * 블록 순서 = 보낸 순서 = 서버 칸 오류의 행 키
     */
    function itemRow(it) {
        var wrap = hb.el('div', 'hb-sub');
        wrap.setAttribute('data-item', '');
        if (it && it.itemId) { wrap.setAttribute('data-item-id', String(it.itemId)); }
        var box = hb.el('div', 'hb-form');
        wrap.appendChild(box);
        cell(box, hb.t('js.cms.tmpl.seq'), input('seq', it ? it.seq : nextSeq(), {inputmode: 'numeric'}));
        var judge = cell(box, hb.t('js.cms.tmpl.judge'), select('judgeType', it ? it.judgeType : 'OKNG',
            [['OKNG', hb.t('js.cms.tmpl.judge.OKNG')], ['NUM', hb.t('js.cms.tmpl.judge.NUM')], ['TEXT', hb.t('js.cms.tmpl.judge.TEXT')]]));
        cell(box, hb.t('js.cms.tmpl.item'), input('itemNm', it ? it.itemNm : '', {maxlength: '200'}), true);
        var lsl = cell(box, hb.t('js.cms.tmpl.lsl'), input('lsl', it ? it.lslText : '', {inputmode: 'decimal'}));
        var usl = cell(box, hb.t('js.cms.tmpl.usl'), input('usl', it ? it.uslText : '', {inputmode: 'decimal'}));
        cell(box, hb.t('js.cms.tmpl.unit'), input('unitNm', it ? it.unitNm : '', {maxlength: '20'}));
        var scale = cell(box, hb.t('js.cms.tmpl.scale'), input('scale', it ? it.scale : 2, {inputmode: 'numeric'}));
        cell(box, hb.t('js.cms.tmpl.required'), select('requiredYn', it ? it.requiredYn : 'Y', [['Y', hb.t('js.cms.tmpl.mustYes')], ['N', hb.t('js.cms.tmpl.optional')]]));
        if (CAN_SAVE) {
            var bar = hb.el('div', 'hb-toolbar');
            var del = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.tmpl.removeItem'));
            del.type = 'button';
            del.addEventListener('click', function () { wrap.remove(); });
            bar.appendChild(del);
            box.appendChild(bar);
        }
        cell(box, hb.t('js.cms.tmpl.method'), input('methodText', it ? it.methodText : '', {maxlength: '500'}), true);
        function sync() {
            var num = judge.value === 'NUM';
            [lsl, usl, scale].forEach(function (x) { x.disabled = !CAN_SAVE || !num; if (!num) { x.value = x === scale ? '2' : ''; } });
        }
        judge.addEventListener('change', sync);
        sync();
        itemBody.appendChild(wrap);
        return wrap;
    }

    function nextSeq() {
        var max = 0;
        itemBody.querySelectorAll('[data-field="seq"]').forEach(function (i) { max = Math.max(max, Number(i.value) || 0); });
        return max + 10;
    }

    function loadItems() {
        return hb.latest('items', hb.api(API + '/' + current.tmplId + '/items')).then(function (res) {
            itemBody.textContent = '';
            res.data.forEach(itemRow);
        }, function (err) { notify(err.message, true); });
    }

    function show(t) {
        current = t;
        hb.showFieldErrors(form, []);
        field('tmplCd').value = t ? t.tmplCd : '';
        field('tmplNm').value = t ? t.tmplNm : '';
        field('targetKind').value = t ? t.targetKind : 'EQUIP';
        var cs = field('equipClassCd');
        cs.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        cs.value = t && t.equipClassCd ? t.equipClassCd : '';
        if (t && t.equipClassCd && cs.value !== t.equipClassCd) {
            option(cs, t.equipClassCd, t.equipClassCd + ' — ' + hb.t('js.cms.inactive'));
            cs.lastChild.setAttribute('data-stale', '');
            cs.value = t.equipClassCd;
        }
        field('useYn').value = t ? t.useYn : 'Y';
        field('remark').value = t && t.remark ? t.remark : '';
        field('tmplCd').readOnly = !!t || !CAN_SAVE;
        field('targetKind').disabled = !!t || !CAN_SAVE;
        document.getElementById('formMode').textContent = t ? hb.t('js.cms.fail.editing', t.tmplCd, t.tmplNm) : hb.t('js.cms.tmpl.creating');
        document.getElementById('itemSection').hidden = !t;
        itemBody.textContent = '';
        if (t) { loadItems(); }
        hb.dirty.clear();
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다(grid 행 = 목록 행이라 그대로 보인다) */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (!selectId || !sel || sel.tmplId !== selectId) { show(null); }
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    hb.page.openDetail();
                    field('tmplCd').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var body = {tmplCd: field('tmplCd').value, tmplNm: field('tmplNm').value, targetKind: field('targetKind').value,
                equipClassCd: field('equipClassCd').value, useYn: field('useYn').value, remark: field('remark').value,
                rowVer: current ? current.rowVer : null};
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.tmplId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.tmplId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    var addItemBtn = document.getElementById('addItemBtn');
    if (addItemBtn) {
        addItemBtn.addEventListener('click', function () { itemRow(null).querySelector('[data-field="itemNm"]').focus(); });
    }

    function showRowErrors(errors) {
        var rows = itemBody.querySelectorAll('[data-item]');
        itemBody.querySelectorAll('[aria-invalid]').forEach(function (el) { el.removeAttribute('aria-invalid'); el.title = ''; });
        (errors || []).forEach(function (er) {
            var tr = rows[Number(er.rowKey)];
            var el = tr && tr.querySelector('[data-field="' + er.field + '"]');
            if (el) {
                el.setAttribute('aria-invalid', 'true');
                el.title = er.message;
            }
        });
    }

    var saveItemsBtn = document.getElementById('saveItemsBtn');
    if (saveItemsBtn) {
        saveItemsBtn.addEventListener('click', function () {
            var rows = [];
            itemBody.querySelectorAll('[data-item]').forEach(function (tr) {
                var f = function (n) { return tr.querySelector('[data-field="' + n + '"]').value; };
                var id = tr.getAttribute('data-item-id');
                rows.push({itemId: id ? Number(id) : null, seq: f('seq') === '' ? null : Number(f('seq')), itemNm: f('itemNm'),
                    judgeType: f('judgeType'), lsl: f('lsl'), usl: f('usl'), unitNm: f('unitNm'),
                    scale: f('scale') === '' ? null : Number(f('scale')), requiredYn: f('requiredYn'), methodText: f('methodText')});
            });
            hb.busy(saveItemsBtn, function () {
                return hb.api(API + '/' + current.tmplId + '/items?rowVer=' + current.rowVer, {method: 'PUT', body: rows});
            }).then(function (res) {
                if (!res) { return; }
                return load(current.tmplId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                showRowErrors(err.errors);
            });
        });
    }

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/classes')]);
    }).then(function (r) {
        kindName = {EQUIP: hb.t('js.cms.kind.EQUIP'), ASSET: hb.t('js.cms.kind.ASSET')};
        var cs = field('equipClassCd');
        option(cs, '', hb.t('js.cms.fail.allClasses'));
        r[1].data.forEach(function (c) { option(cs, c.code, c.code + ' · ' + c.name); });
        grid = hb.grid('#tmplGrid', {
            progCd: 'cms-chk-tmpl', id: 'main', key: 'tmplId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {targetKind: function (v) { return kindName[v] || v; }},
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(row); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
