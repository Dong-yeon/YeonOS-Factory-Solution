/* POP 단말 관리 (sys-terminal) — P2 5a (docs/25 4.6). 목록 → 상세(등록·수정) + 등록 코드 다시 발급·폐기.
   등록 코드는 만든 응답에서 한 번만 보인다(서버는 해시만 저장).
   유형 COLLECTOR(수집 장치, P2 6단계 docs/25 4.8)는 등록 코드 대신 API 키(Bearer)를 한 번만 보여 준다. 수집 장치 ↔ POP·PDA 는 못 바꾼다. */
(function () {
    'use strict';

    var API = '/api/production/terminals';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('form');
    var formMode = document.getElementById('formMode');
    var facts = document.getElementById('facts');
    var codeBox = document.getElementById('codeBox');
    var plantSel = document.getElementById('plantSel');
    var nodeSel = document.getElementById('nodeSel');
    var reissueBtn = document.getElementById('reissueBtn');
    var revokeBtn = document.getElementById('revokeBtn');
    var current = null, grid = null, keepCode = null;
    var COLLECTOR = 'COLLECTOR';
    var codeTitle = document.getElementById('codeTitle');
    var codeTitleText = codeTitle.textContent;
    var reissueText = reissueBtn ? reissueBtn.textContent : '';

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

    function when(iso) { return iso ? hb.fmt.datetime(iso) : ''; }

    function state(t) {
        if (t.revokedAt) { return hb.t('js.terminal.revoked'); }
        if (t.keyIssued) { return hb.t('js.terminal.active'); }
        if (t.terminalType === COLLECTOR) { return hb.t('js.terminal.keyMissing'); }
        return t.activationExpiresAt && new Date(t.activationExpiresAt) > new Date()
            ? hb.t('js.terminal.waiting') : hb.t('js.terminal.expired');
    }

    /** 단말 상태(서버 state 코드) → 배지 톤(docs/40 부록 A)·이름 — 사용 중 normal, 등록 대기 info, 만료·키 없음 warning, 폐기 neutral */
    var STATE_TONE = {ACTIVE: 'normal', WAITING: 'info', EXPIRED: 'warning', KEY_MISSING: 'warning', REVOKED: 'neutral'};
    var STATE_TEXT = {ACTIVE: 'js.terminal.active', WAITING: 'js.terminal.waiting', EXPIRED: 'js.terminal.expired',
        KEY_MISSING: 'js.terminal.keyMissing', REVOKED: 'js.terminal.revoked'};

    function loadNodes(plantCd, selected) {
        nodeSel.textContent = '';
        option(nodeSel, '', hb.t('js.terminal.wholePlant'));
        if (!plantCd) { return Promise.resolve(); }
        return hb.latest('nodes', hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            res.data.forEach(function (n) {
                option(nodeSel, String(n.nodeId), n.nodeCd + ' · ' + n.nodeNm + ' (' + n.nodeType + ')');
            });
            if (selected) {
                nodeSel.value = String(selected);
                if (nodeSel.value !== String(selected)) {
                    option(nodeSel, String(selected), hb.t('js.terminal.nodeInactive'));
                    nodeSel.value = String(selected);
                }
            }
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function info(dl, pairs) {
        dl.textContent = '';
        pairs.forEach(function (p) {
            if (!p[1]) { return; }
            dl.appendChild(hb.el('dt', null, p[0]));
            dl.appendChild(hb.el('dd', null, p[1]));
        });
        dl.hidden = !dl.children.length;
    }

    function show(t, code) {
        current = t;
        hb.showFieldErrors(form, []);
        ['terminalCd', 'terminalNm', 'remark'].forEach(function (n) { field(n).value = t ? (t[n] || '') : ''; });
        field('terminalType').value = t ? t.terminalType : 'POP';
        plantSel.value = t ? t.plantCd : (plantSel.options.length ? plantSel.options[0].value : '');
        field('terminalCd').readOnly = !!t || !CAN_SAVE;
        var locked = !CAN_SAVE || !!(t && t.revokedAt);
        form.querySelectorAll('input, select').forEach(function (i) {
            if (i.getAttribute('data-field') !== 'terminalCd') { i.disabled = locked; }
        });
        // 수정 때는 같은 계열로만(POP ↔ PDA) — 수집 장치와 서로 바꾸지 않는다
        var isCollector = !!(t && t.terminalType === COLLECTOR);
        Array.prototype.forEach.call(field('terminalType').options, function (o) {
            o.disabled = !!t && ((o.value === COLLECTOR) !== isCollector);
        });
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = locked; }
        formMode.textContent = t ? hb.t('js.terminal.editing', t.terminalCd, state(t)) : hb.t('js.terminal.creating');
        if (reissueBtn) {
            reissueBtn.hidden = !t || !!t.revokedAt;
            reissueBtn.textContent = isCollector ? hb.t('js.terminal.reissueKey') : reissueText;
        }
        if (revokeBtn) {
            revokeBtn.hidden = !t || !!t.revokedAt;
            /** @type {HTMLElement} */ (revokeBtn.parentElement).hidden = revokeBtn.hidden; // 떨어진 줄도 같이(빈 줄 여백 방지)
        }
        info(facts, t ? [[hb.t('js.terminal.activatedAt'), when(t.activatedAt)], [hb.t('js.terminal.codeUntil'),
            t.keyIssued || isCollector ? '' : when(t.activationExpiresAt)], [hb.t('js.terminal.lastSeen'), when(t.lastSeenAt)],
            [hb.t('js.terminal.revokedAt'), t.revokedAt ? when(t.revokedAt) + ' — ' + t.revokeReason : ''],
            [hb.t('js.terminal.updated'), when(t.updatedAt) + ' · ' + t.updatedBy]] : []);
        codeBox.hidden = !code;
        if (code) {
            // 수집 장치 = API 키(만료 없음, 길다), 그 밖 = 등록 코드(24시간)
            var isKey = !code.expiresAt;
            var actCode = document.getElementById('actCode');
            actCode.textContent = code.code;
            actCode.classList.toggle('hb-code-big', !isKey);
            actCode.classList.toggle('hb-code-key', isKey);
            codeTitle.textContent = isKey ? hb.t('js.terminal.keyTitle') : codeTitleText;
            document.getElementById('actGuide').textContent = isKey ? hb.t('js.terminal.keyGuide')
                : hb.t('js.terminal.codeGuide', when(code.expiresAt));
        }
        hb.dirty.clear();
        return loadNodes(plantSel.value, t ? t.nodeId : null);
    }

    /** 목록 다시 읽기(페이지 그대로) + 그 단말 상세(code = 방금 받은 등록 코드·키, 한 번만 보임) */
    function list(selectId, code) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        keepCode = code || null;
        return g.reload(false, selectId).then(function () {
            var c = keepCode;
            keepCode = null;
            var sel = g.selected();
            // 지금 페이지에 없으면(다른 페이지·새 단말) 상세만 다시 읽어 연다
            if (selectId && (!sel || sel.terminalId !== selectId)) {
                return hb.api(API + '/' + selectId).then(function (res) { return show(res.data, c); });
            }
        });
    }

    plantSel.addEventListener('change', function () { loadNodes(plantSel.value, null); });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            var body = {terminalCd: field('terminalCd').value, terminalNm: field('terminalNm').value, plantCd: plantSel.value,
                nodeId: nodeSel.value ? Number(nodeSel.value) : null, terminalType: field('terminalType').value,
                remark: field('remark').value, rowVer: current ? current.rowVer : null};
            hb.busy(saveBtn, function () {
                return current ? hb.api(API + '/' + current.terminalId, {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                var id = current ? current.terminalId : res.data.terminalId;
                hb.dirty.clear();
                return list(id, current ? null : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (reissueBtn) {
        reissueBtn.addEventListener('click', function () {
            if (!current) { return; }
            var key = current.terminalType === COLLECTOR;
            // 지금 쓰는 단말·수집기가 바로 끊긴다 — 위험 확인(기본 포커스 = 취소)
            mesUi.ask('js.mes.dlg.termReissue', key ? hb.t('js.terminal.confirmReissueKey', current.terminalCd)
                : hb.t('js.terminal.confirmReissue', current.terminalCd), 'js.mes.dlg.termReissueOk', true).then(function (ok) {
                if (!ok || !current) { return; }
                hb.busy(reissueBtn, function () {
                    return hb.api(API + '/' + current.terminalId + '/reissue', {method: 'POST', body: {rowVer: current.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return list(current.terminalId, res.data).then(function () {
                        notify(key ? hb.t('js.terminal.reissuedKey') : hb.t('js.terminal.reissued'));
                    });
                }, function (err) { notify(err.message, true); });
            });
        });
    }

    if (revokeBtn) {
        revokeBtn.addEventListener('click', function () {
            if (!current) { return; }
            hb.dialog.prompt({title: hb.t('js.mes.dlg.termRevoke'), message: hb.t('js.terminal.revokeReason', current.terminalCd),
                label: hb.t('js.mes.dlg.reason'), ok: hb.t('js.mes.dlg.termRevokeOk'), required: true, maxLength: 500, danger: true})
                .then(function (reason) {
                    if (reason === null || !current) { return; }
                    hb.busy(revokeBtn, function () {
                        return hb.api(API + '/' + current.terminalId + '/revoke', {method: 'POST', body: {reason: reason, rowVer: current.rowVer}});
                    }).then(function (res) {
                        if (!res) { return; }
                        return list(current.terminalId).then(function () { notify(hb.t('js.terminal.revokedMsg')); });
                    }, function (err) { notify(err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message, true); });
                });
        });
    }

    var newBtn = document.getElementById('newBtn');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            mesUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                show(null);
                hb.page.openDetail();
                field('terminalCd').focus();
            });
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) { option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        show(null);
        // 주 목록 = hb.grid(UI 단계 3 ①) — 서버 페이징·정렬, 상태 칸 배지, 폐기 행 흐리게, 미저장이면 옮기기 전에 묻는다
        grid = hb.grid('#terminalGrid', {
            progCd: PROG, id: 'main', key: 'terminalId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS,
            format: {
                state: function (v) { return STATE_TEXT[v] ? hb.t(STATE_TEXT[v]) : v || ''; },
                nodeRef: function (v) { return v || hb.t('js.terminal.wholePlant'); }
            },
            beforeSelect: function () { return mesUi.leaveOk(); },
            status: {state: STATE_TONE},
            rowClass: function (row) { return row.revokedAt ? 'hb-tree-dim' : null; },
            onSelect: function (row) {
                var c = keepCode;
                keepCode = null;
                show(row, c);
                hb.page.openDetail();
            },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
