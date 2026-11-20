/*
 * 오토라벨러 (wms-labeler) — 장비 연동 E3 (docs/65 21장). 보기: 박스(hb.grid + 상세·원장) / 라벨러(마스터 표 + 폼).
 * 판정(SSCC·리젝트·검증)은 서버와 장비가 한다 — 화면은 등록과 조회만. 공통 hb.* 는 고치지 않는다.
 */
(function () {
    'use strict';

    var BOX = '/api/wms/labeler-boxes';
    var LBL = '/api/wms/labelers';
    var PROG = document.body.getAttribute('data-prog') || 'wms-labeler';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var boxForm = document.getElementById('boxForm');
    var lblForm = document.getElementById('lblForm');
    var sp, grid, tab = 'BOX', current = null, qtyInput = null;
    var statusNames = {}, eventNames = {}, reasonNames = {};
    var labelers = [];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function f(form, n) { return form.querySelector('[data-field="' + n + '"]'); }

    function fill(sel, list, blank) {
        sel.textContent = '';
        if (blank) {
            var b = hb.el('option', null, blank);
            b.value = '';
            sel.appendChild(b);
        }
        list.forEach(function (c) {
            var o = hb.el('option', null, c.name);
            o.value = c.code;
            sel.appendChild(o);
        });
    }

    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function line(dl, label, value) {
        dl.appendChild(hb.el('dt', null, label));
        dl.appendChild(hb.el('dd', null, value === null || value === undefined || value === '' ? '-' : String(value)));
    }

    // ---------------------------------------------------------------- 박스

    function showBox(b) {
        current = b;
        var info = document.getElementById('boxInfo');
        info.textContent = '';
        hb.showFieldErrors(boxForm, []);
        var editable = CAN_SAVE && (!b || b.status === 'REGISTERED');
        f(boxForm, 'plantCd').value = b ? b.plantCd : (sp.values().plantCd || f(boxForm, 'plantCd').value);
        f(boxForm, 'boxCd').value = b ? b.boxCd : '';
        f(boxForm, 'itemCd').value = b ? b.itemCd : '';
        qtyInput.setValue(b ? b.qty : '');
        f(boxForm, 'lotNo').value = b ? (b.lotNo || '') : '';
        f(boxForm, 'refNo').value = b ? (b.refNo || '') : '';
        f(boxForm, 'labelSkipYn').value = b ? b.labelSkipYn : 'N';
        boxForm.querySelectorAll('input, textarea').forEach(function (i) { i.readOnly = !editable; });
        boxForm.querySelectorAll('select').forEach(function (s) { s.disabled = !editable; });
        f(boxForm, 'plantCd').disabled = !!b || !CAN_SAVE;
        f(boxForm, 'boxCd').readOnly = !!b || !CAN_SAVE;
        f(boxForm, 'itemCd').readOnly = !!b || !CAN_SAVE;
        var save = document.getElementById('boxSaveBtn');
        if (save) { save.style.display = editable ? '' : 'none'; }
        document.getElementById('boxMode').textContent = b ? hb.t('js.wmsLabeler.boxTitle', b.boxCd, statusNames[b.status] || b.status)
            : hb.t('js.wmsLabeler.newBox');
        var rows = document.getElementById('eventRows');
        rows.textContent = '';
        document.getElementById('eventBox').hidden = !b;
        if (b) {
            line(info, hb.t('js.wmsLabeler.sscc'), b.sscc);
            line(info, hb.t('js.wmsLabeler.attempts'), b.attemptCnt);
            line(info, hb.t('js.wmsLabeler.lastReason'), b.lastReasonCd ? (reasonNames[b.lastReasonCd] || b.lastReasonCd) : '');
            if (b.doneAt) { line(info, hb.t('js.wmsLabeler.doneAt'), hb.fmt.datetime(b.doneAt)); }
            hb.latest('boxEvents', hb.api(BOX + '/' + b.boxId + '/events')).then(function (res) {
                res.data.forEach(function (e) {
                    var tr = hb.el('tr');
                    tr.appendChild(hb.el('td', null, hb.fmt.datetime(e.occurredAt)));
                    tr.appendChild(hb.el('td', null, (eventNames[e.eventCd] || e.eventCd) + (e.labelerCd ? ' · ' + e.labelerCd : '')));
                    tr.appendChild(hb.el('td', null, [e.actionCd, e.reasonCd ? (reasonNames[e.reasonCd] || e.reasonCd) : null, e.detail]
                        .filter(Boolean).join(' · ')));
                    tr.appendChild(hb.el('td', null, e.scanned || ''));
                    tr.appendChild(hb.el('td', 'hb-num', e.elapsedMs === null || e.elapsedMs === undefined ? '' : String(e.elapsedMs)));
                    rows.appendChild(tr);
                });
            }, function (err) { notify(err.message, true); });
        }
        hb.dirty.clear();
    }

    function boxBody() {
        return {plantCd: f(boxForm, 'plantCd').value, boxCd: f(boxForm, 'boxCd').value, itemCd: f(boxForm, 'itemCd').value,
            qty: qtyInput.value() || null, lotNo: f(boxForm, 'lotNo').value, refNo: f(boxForm, 'refNo').value,
            labelSkipYn: f(boxForm, 'labelSkipYn').value, rowVer: current ? current.rowVer : null};
    }

    // ---------------------------------------------------------------- 라벨러

    function loadLabelers(selectId) {
        return hb.api(LBL).then(function (res) {
            labelers = res.data;
            var rows = document.getElementById('lblRows');
            rows.textContent = '';
            labelers.forEach(function (l) {
                var tr = hb.el('tr', l.useYn === 'N' ? 'hb-tree-dim' : null);
                tr.tabIndex = 0;
                [l.plantCd, l.labelerCd, l.labelerNm, l.connMode, l.terminalCd, l.tplCd].forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                tr.appendChild(hb.el('td', 'hb-num', String(l.deadlineMs)));
                tr.appendChild(hb.el('td', null, l.useYn === 'Y' ? hb.t('js.wmsLabeler.on') : hb.t('js.wmsLabeler.off')));
                var pick = function () { confirmLeave().then(function (ok) { if (ok) { showLabeler(l); hb.page.openDetail(); } }); };
                tr.addEventListener('click', pick);
                tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { pick(); } });
                rows.appendChild(tr);
            });
            if (selectId) {
                var hit = labelers.filter(function (x) { return x.labelerId === selectId; })[0];
                if (hit) { showLabeler(hit); }
            }
        });
    }

    function showLabeler(l) {
        current = l;
        hb.showFieldErrors(lblForm, []);
        f(lblForm, 'plantCd').value = l ? l.plantCd : (sp.values().plantCd || f(lblForm, 'plantCd').value);
        f(lblForm, 'labelerCd').value = l ? l.labelerCd : '';
        f(lblForm, 'labelerNm').value = l ? l.labelerNm : '';
        f(lblForm, 'terminalId').value = l ? String(l.terminalId) : (f(lblForm, 'terminalId').options.length ? f(lblForm, 'terminalId').options[0].value : '');
        f(lblForm, 'connMode').value = l ? l.connMode : 'DIRECT';
        f(lblForm, 'tplId').value = l ? String(l.tplId) : (f(lblForm, 'tplId').options.length ? f(lblForm, 'tplId').options[0].value : '');
        f(lblForm, 'deadlineMs').value = String(l ? l.deadlineMs : 2000);
        f(lblForm, 'useYn').value = l ? l.useYn : 'Y';
        f(lblForm, 'remark').value = l ? (l.remark || '') : '';
        lblForm.querySelectorAll('input, textarea').forEach(function (i) { i.readOnly = !CAN_SAVE; });
        lblForm.querySelectorAll('select').forEach(function (s) { s.disabled = !CAN_SAVE; });
        f(lblForm, 'plantCd').disabled = !!l || !CAN_SAVE;
        f(lblForm, 'labelerCd').readOnly = !!l || !CAN_SAVE;
        document.getElementById('lblMode').textContent = l ? hb.t('js.wmsLabeler.editing', l.labelerCd) : hb.t('js.wmsLabeler.newLabeler');
        var rows = document.getElementById('devRows');
        rows.textContent = '';
        document.getElementById('devBox').hidden = !l;
        if (l) {
            hb.latest('devLog', hb.api(LBL + '/' + l.labelerId + '/device-log')).then(function (res) {
                res.data.forEach(function (d) {
                    var tr = hb.el('tr');
                    [hb.fmt.datetime(d.occurredAt), d.eventCd, d.code || '', d.replyCd || ''].forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                    rows.appendChild(tr);
                });
            }, function (err) { notify(err.message, true); });
        }
        hb.dirty.clear();
    }

    function lblBody() {
        var d = f(lblForm, 'deadlineMs').value.trim();
        return {plantCd: f(lblForm, 'plantCd').value, labelerCd: f(lblForm, 'labelerCd').value, labelerNm: f(lblForm, 'labelerNm').value,
            terminalId: f(lblForm, 'terminalId').value ? Number(f(lblForm, 'terminalId').value) : null, connMode: f(lblForm, 'connMode').value,
            tplId: f(lblForm, 'tplId').value ? Number(f(lblForm, 'tplId').value) : null,
            deadlineMs: /^[0-9]{1,5}$/.test(d) ? Number(d) : -1, useYn: f(lblForm, 'useYn').value, remark: f(lblForm, 'remark').value,
            rowVer: current ? current.rowVer : null};
    }

    // ---------------------------------------------------------------- 공통

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    function save(btn, form, base, idKey, payload, after) {
        var editing = !!current;
        var id = editing ? current[idKey] : null;
        hb.busy(btn, function () {
            return editing ? hb.api(base + '/' + id, {method: 'PUT', body: payload}) : hb.api(base, {method: 'POST', body: payload});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            notify(hb.t('js.common.saved'));
            return after(editing ? id : res.data);
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    }

    bind('boxSaveBtn', function (b) {
        save(b, boxForm, BOX, 'boxId', boxBody(), function (id) {
            return grid.reload(false, id).then(function () { return hb.api(BOX + '/' + id); }).then(function (x) { showBox(x.data); });
        });
    });
    bind('lblSaveBtn', function (b) { save(b, lblForm, LBL, 'labelerId', lblBody(), function (id) { return loadLabelers(id); }); });
    bind('newBtn', function () {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            if (tab === 'BOX') { showBox(null); } else { showLabeler(null); }
            hb.page.openDetail();
        });
    });

    function setTab(t) {
        tab = t;
        document.getElementById('boxPane').hidden = t !== 'BOX';
        document.getElementById('lblPane').hidden = t !== 'LBL';
        document.getElementById('boxWrap').hidden = t !== 'BOX';
        document.getElementById('lblWrap').hidden = t !== 'LBL';
        ['status', 'boxCd'].forEach(function (n) { search.querySelector('[name="' + n + '"]').closest('label').style.display = t === 'BOX' ? '' : 'none'; });
        document.getElementById('period').style.display = t === 'BOX' ? '' : 'none';
        current = null;
    }

    search.querySelectorAll('input[name="tab"]').forEach(function (r) {
        r.addEventListener('change', function () {
            confirmLeave().then(function (ok) {
                if (!ok) { search.querySelector('input[name="tab"][value="' + tab + '"]').checked = true; return; }
                setTab(r.value);
                if (r.value === 'BOX') { showBox(null); return grid.reload(true); }
                showLabeler(null);
                return loadLabelers();
            });
        });
    });

    hb.dirty.watch(boxForm);
    hb.dirty.watch(lblForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        qtyInput = hb.number(f(boxForm, 'qty'));
        return Promise.all([hb.loadSession(), hb.api(LBL + '/options'), hb.codes('WMS_LABELER_BOX_STATUS'), hb.codes('WMS_LABELER_EVENT'),
            hb.codes('WMS_LABELER_REASON')]);
    }).then(function (r) {
        var o = r[1].data;
        statusNames = r[2];
        eventNames = r[3];
        reasonNames = r[4];
        var plants = o.plants.map(function (p) { return {code: p.plantCd, name: p.plantCd + ' · ' + p.plantNm}; });
        fill(document.getElementById('plantSel'), plants, hb.t('js.wmsLabeler.allPlants'));
        fill(f(boxForm, 'plantCd'), plants, null);
        fill(f(lblForm, 'plantCd'), plants, null);
        fill(f(lblForm, 'terminalId'), o.devices.map(function (d) {
            return {code: String(d.terminalId), name: d.terminalCd + ' · ' + d.terminalNm};
        }), null);
        fill(f(lblForm, 'tplId'), o.templates.map(function (t) {
            return {code: String(t.tplId), name: t.tplCd + ' · ' + t.tplNm + (t.sizeCd ? ' (' + t.sizeCd + ')' : '')};
        }), null);
        setTab('BOX');
        sp = hb.searchPanel(search, {onSearch: function () {
            return confirmLeave().then(function (ok) {
                if (!ok) { return null; }
                return tab === 'BOX' ? grid.reload(true) : loadLabelers();
            });
        }});
        grid = hb.grid('#boxGrid', {
            progCd: PROG, id: 'box', key: 'boxId', url: BOX + '/grid', columnsUrl: BOX + '/columns', exportUrl: BOX + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            status: {status: {REGISTERED: 'neutral', PREFETCHED: 'info', REQUESTED: 'info', VERIFIED: 'normal', REJECTED: 'danger', SKIPPED: 'neutral'}},
            beforeSelect: function () { return confirmLeave(); },
            onSelect: function (row) {
                hb.latest('box', hb.api(BOX + '/' + row.boxId)).then(function (x) { showBox(x.data); }, function (e) { notify(e.message, true); });
                hb.page.openDetail();
            },
            notify: notify
        });
        showBox(null);
        return Promise.all([sp.ready, grid.ready]).then(function () { return grid.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
