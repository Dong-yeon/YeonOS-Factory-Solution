// @ts-check
/* 설비 연동 (cms-equip-link) — 장비 연동 E0 (docs/65 16장).
   설비 목록(연결 방식·장치·연결 상태 판정·PackML·분류·상위 상태) → 상세: 연동 설정 · 논리 태그 · 명령 4단 · 연동 사건 / 아래 수신 원문(격리·충돌).
   연결 상태는 서버가 판정(EquipLinkJudge)한 값 그대로 — 화면이 시간으로 다시 계산하지 않는다. 명령은 FN1 권한, 요청 키(X-Idempotency-Key)를 붙인다. */
/**
 * @typedef {{equipId: number, equipCd: string, equipNm: string, plantCd: string, nodeCd?: string | null, connMode?: string | null,
 *   terminalCd?: string | null, adapterUrl?: string | null, hbSec: number, writeEnabledYn: string, useYn: string, lastSeenAt?: string | null,
 *   lastSeq?: number | null, packState?: string | null, packMode?: string | null, lostAt?: string | null, rowVer?: number | null,
 *   judge: string, machineryState: string, stateCat?: string | null, openCmdCnt: number, openAlarmCnt: number, remark?: string | null}} ElLink
 * @typedef {{tagId: number, tagCd: string, tagNm: string, dataType: string, minValue?: number | null, maxValue?: number | null,
 *   unit?: string | null, allowedStates: string, writableYn: string, useYn: string, rowVer: number}} ElTag
 * @typedef {{phase: string, okYn?: string | null, stepValue?: string | null, detail?: string | null, occurredAt: string}} ElStep
 * @typedef {{cmdId: number, tagCd: string, cmdValue: string, status: string, resultCd?: string | null, readback?: string | null,
 *   requestedAt: string, steps?: ElStep[]}} ElCmd
 * @typedef {{eventCd: string, seqFrom?: number | null, seqTo?: number | null, detail?: string | null, occurredAt: string}} ElLog
 * @typedef {{consCd: string, levelCd: string}} ElCons
 */
(function () {
    'use strict';

    var API = '/api/cms/equip-links';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var linkForm = /** @type {HTMLFormElement} */ (document.getElementById('linkForm'));
    var tagForm = /** @type {HTMLFormElement | null} */ (document.getElementById('tagForm'));
    var cmdForm = /** @type {HTMLFormElement | null} */ (document.getElementById('cmdForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var linkBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#links tbody'));
    var tagBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#tags tbody'));
    var cmdBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#cmds tbody'));
    var logBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#logs tbody'));
    var inBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#inbound tbody'));
    var consLine = /** @type {HTMLElement} */ (document.getElementById('consLine'));
    /** @type {Record<string, Record<string, string>>} */
    var names = {mode: {}, pack: {}, cmd: {}, event: {}, inbound: {}, cat: {}};
    /** @type {ElLink | null} */
    var current = null;
    /** @type {ElTag | null} */
    var currentTag = null;
    /** @type {HB.SearchPanel} */
    var sp;

    /** @type {Record<string, HB.StatusTone>} */
    var JUDGE_TONE = {ONLINE: 'success', LOST: 'danger', NEVER: 'neutral', OFF: 'neutral'};
    /** @type {Record<string, HB.StatusTone>} */
    var CAT_TONE = {PRD: 'success', SBY: 'warning', ENG: 'info', SDT: 'info', UDT: 'danger', NST: 'neutral', UNKNOWN: 'neutral'};
    /** @type {Record<string, HB.StatusTone>} */
    var CMD_TONE = {REQUESTED: 'neutral', SENT: 'normal', CLAIMED: 'normal', WRITTEN: 'normal', DONE: 'success', FAILED: 'danger',
        REJECTED: 'danger', EXPIRED: 'neutral', UNKNOWN: 'warning'};
    /** @type {Record<string, HB.StatusTone>} */
    var IN_TONE = {NEW: 'neutral', DONE: 'success', STALE: 'neutral', REJECTED: 'warning', QUARANTINE: 'danger', CONFLICT: 'danger'};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement} form @param {string} n */
    function field(form, n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {unknown} t */
    function time(t) { return t ? hb.fmt.datetime(t) : ''; }

    /** @param {HTMLTableRowElement} tr @param {Array<unknown>} values */
    function cells(tr, values) {
        values.forEach(function (v) {
            var td = hb.el('td');
            if (v instanceof Node) { td.appendChild(v); } else { td.textContent = v === null || v === undefined ? '' : String(v); }
            tr.appendChild(td);
        });
    }

    /** @param {Record<string, HB.StatusTone>} tones @param {string | null | undefined} code @param {string | null | undefined} text */
    function badge(tones, code, text) {
        return hb.status(code && tones[code] ? tones[code] : 'neutral', text || code || '-');
    }

    /** 연결 상태 판정 이름(문구 키는 리터럴로 — I18nKeyGuardTest) @param {string} j */
    function judgeName(j) {
        switch (j) {
            case 'ONLINE': return hb.t('js.cms.link.judge.ONLINE');
            case 'LOST': return hb.t('js.cms.link.judge.LOST');
            case 'NEVER': return hb.t('js.cms.link.judge.NEVER');
            default: return hb.t('js.cms.link.judge.OFF');
        }
    }

    // ------------------------------------------------------------------ 목록

    function load() {
        var v = sp.values();
        var q = [];
        if (v.plantCd) { q.push('plantCd=' + encodeURIComponent(v.plantCd)); }
        if (v.keyword) { q.push('keyword=' + encodeURIComponent(v.keyword)); }
        return hb.latest('list', hb.api(API + (q.length ? '?' + q.join('&') : ''))).then(function (r) {
            /** @type {ElLink[]} */
            var rows = r.data || [];
            linkBody.textContent = '';
            if (!rows.length) { hb.emptyRow(linkBody); }
            rows.forEach(function (l) {
                var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr', l.connMode ? null : 'hb-tree-dim'));
                tr.tabIndex = 0;
                var pack = l.packState ? (names.pack[l.packState] || l.packState) + (l.packMode && l.packMode !== 'PRODUCTION' ? ' · ' + l.packMode : '') : '';
                cells(tr, [l.equipCd + ' · ' + l.equipNm, l.nodeCd || '', l.connMode ? names.mode[l.connMode] || l.connMode : hb.t('js.cms.link.none'),
                    l.terminalCd || '', badge(JUDGE_TONE, l.judge, judgeName(l.judge)), pack,
                    l.stateCat ? badge(CAT_TONE, l.stateCat, names.cat[l.stateCat] || l.stateCat) : '',
                    l.machineryState, time(l.lastSeenAt), l.openCmdCnt + ' · ' + l.openAlarmCnt]);
                tr.addEventListener('click', function () { open(l); });
                tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { open(l); } });
                linkBody.appendChild(tr);
            });
            if (current) {
                var again = rows.filter(function (x) { return current && x.equipId === current.equipId; })[0];
                if (again) { open(again); }
            }
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    // ------------------------------------------------------------------ 상세

    /** @param {ElLink} l */
    function open(l) {
        current = l;
        hb.showFieldErrors(linkForm, []);
        /** @type {HTMLElement} */ (document.getElementById('linkBox')).hidden = false;
        formMode.textContent = hb.t('js.cms.link.editing', l.equipCd, l.equipNm);
        field(linkForm, 'connMode').value = l.connMode || 'EDGE';
        field(linkForm, 'terminalCd').value = l.terminalCd || '';
        field(linkForm, 'adapterUrl').value = l.adapterUrl || '';
        field(linkForm, 'hbSec').value = String(l.hbSec || 5);
        field(linkForm, 'writeEnabledYn').value = l.writeEnabledYn || 'N';
        field(linkForm, 'useYn').value = l.connMode ? l.useYn : 'Y';
        field(linkForm, 'remark').value = '';
        hb.dirty.clear(linkForm);
        var has = !!l.connMode;
        ['tagsBox', 'cmdBox', 'logBox'].forEach(function (id) { /** @type {HTMLElement} */ (document.getElementById(id)).hidden = !has; });
        hb.page.openDetail();
        if (has) { loadDetail(l.equipId); }
    }

    /** @param {number} equipId */
    function loadDetail(equipId) {
        return Promise.all([hb.latest('detail', hb.api(API + '/' + equipId)), hb.latest('cmds', hb.api(API + '/' + equipId + '/commands'))])
            .then(function (r) {
                var d = r[0].data || {};
                if (d.link && d.link.remark) { field(linkForm, 'remark').value = d.link.remark; }
                drawTags(d.tags || []);
                drawLogs(d.logs || [], d.consumables || []);
                drawCmds(r[1].data || []);
            }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {ElTag[]} tags */
    function drawTags(tags) {
        tagBody.textContent = '';
        if (!tags.length) { hb.emptyRow(tagBody); }
        var sel = /** @type {HTMLSelectElement | null} */ (document.getElementById('cmdTag'));
        if (sel) { sel.textContent = ''; }
        tags.forEach(function (g) {
            var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr', g.useYn === 'Y' ? null : 'hb-tree-dim'));
            var range = g.dataType === 'NUM' ? (g.minValue + ' ~ ' + g.maxValue + (g.unit ? ' ' + g.unit : '')) : 'true / false';
            cells(tr, [g.tagCd + ' · ' + g.tagNm, g.dataType, range, g.allowedStates || hb.t('js.cms.link.anyState'), g.useYn]);
            if (tagForm) { tr.addEventListener('click', function () { editTag(g); }); }
            tagBody.appendChild(tr);
            if (sel && g.useYn === 'Y' && g.writableYn === 'Y') {
                var o = hb.el('option', null, g.tagCd + ' · ' + g.tagNm);
                o.value = g.tagCd;
                sel.appendChild(o);
            }
        });
        editTag(null);
    }

    /** @param {ElTag | null} g */
    function editTag(g) {
        currentTag = g;
        if (!tagForm) { return; }
        hb.showFieldErrors(tagForm, []);
        field(tagForm, 'tagCd').value = g ? g.tagCd : '';
        /** @type {HTMLInputElement} */ (field(tagForm, 'tagCd')).readOnly = !!g;
        field(tagForm, 'tagNm').value = g ? g.tagNm : '';
        field(tagForm, 'dataType').value = g ? g.dataType : 'NUM';
        /** @type {HTMLSelectElement} */ (field(tagForm, 'dataType')).disabled = !!g;
        field(tagForm, 'minValue').value = g && g.minValue !== null && g.minValue !== undefined ? String(g.minValue) : '';
        field(tagForm, 'maxValue').value = g && g.maxValue !== null && g.maxValue !== undefined ? String(g.maxValue) : '';
        field(tagForm, 'unit').value = g && g.unit ? g.unit : '';
        field(tagForm, 'allowedStates').value = g ? g.allowedStates : '';
        field(tagForm, 'useYn').value = g ? g.useYn : 'Y';
    }

    /** @param {ElLog[]} logs @param {ElCons[]} cons */
    function drawLogs(logs, cons) {
        consLine.textContent = cons.length ? hb.t('js.cms.link.consumables', cons.map(function (c) { return c.consCd + '=' + c.levelCd; }).join(', ')) : '';
        logBody.textContent = '';
        if (!logs.length) { hb.emptyRow(logBody); }
        logs.forEach(function (g) {
            var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr'));
            var seq = g.seqFrom !== null && g.seqFrom !== undefined ? ' seq ' + g.seqFrom + '~' + g.seqTo : '';
            cells(tr, [time(g.occurredAt), names.event[g.eventCd] || g.eventCd, (g.detail || '') + seq]);
            logBody.appendChild(tr);
        });
    }

    /** @param {ElCmd[]} cmds */
    function drawCmds(cmds) {
        cmdBody.textContent = '';
        if (!cmds.length) { hb.emptyRow(cmdBody); }
        cmds.forEach(function (c) {
            var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr'));
            var steps = (c.steps || []).map(function (s) { return s.phase + (s.okYn === 'N' ? '✕' : s.okYn === 'Y' ? '✓' : ''); }).join(' → ');
            var status = badge(CMD_TONE, c.status, (names.cmd[c.status] || c.status) + (c.resultCd ? ' (' + c.resultCd + ')' : ''));
            /** @type {Node | string} */
            var act = '';
            if (c.status === 'UNKNOWN' && cmdForm) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.cms.link.resolve')));
                b.type = 'button';
                b.addEventListener('click', function () { resolve(c); });
                act = b;
            }
            cells(tr, [time(c.requestedAt), c.tagCd, c.cmdValue, status, c.readback || '', steps, act]);
            cmdBody.appendChild(tr);
        });
    }

    /** 결과 불명 확정 — 현장에서 본 결과와 메모 @param {ElCmd} c */
    function resolve(c) {
        if (!current) { return; }
        var equipId = current.equipId;
        hb.dialog.prompt({title: hb.t('js.cms.link.resolveTitle'), label: hb.t('js.cms.link.resolveLabel'), ok: hb.t('js.cms.link.resolveDone'),
            required: true, maxLength: 500}).then(function (note) {
            if (note === null) { return; }
            return hb.dialog.confirm({title: hb.t('js.cms.link.resolveWritten'), message: hb.t('js.cms.link.resolveWrittenMsg'),
                ok: hb.t('js.cms.link.resolveDone')}).then(function (written) {
                return hb.api(API + '/' + equipId + '/commands/' + c.cmdId + '/resolve',
                    {method: 'POST', body: {status: written ? 'DONE' : 'FAILED', note: note}});
            }).then(function () { notify(hb.t('js.common.saved')); return loadDetail(equipId); });
        }).then(null, function (err) { notify(err && err.message, true); });
    }

    // ------------------------------------------------------------------ 수신 원문

    function loadInbound() {
        var checked = /** @type {HTMLInputElement | null} */ (document.querySelector('input[name="inboundStatus"]:checked'));
        var st = checked ? checked.value : '';
        return hb.latest('inbound', hb.api(API + '/inbound' + (st ? '?status=' + encodeURIComponent(st) : ''))).then(function (r) {
            /** @type {Array<Record<string, any>>} */
            var rows = r.data || [];
            inBody.textContent = '';
            if (!rows.length) { hb.emptyRow(inBody); }
            rows.forEach(function (m) {
                var tr = /** @type {HTMLTableRowElement} */ (hb.el('tr'));
                /** @type {Node | string} */
                var act = '';
                if (m.status === 'QUARANTINE' && CAN_SAVE) {
                    var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.cms.link.requeue')));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () { return hb.api(API + '/inbound/' + m.inbound_id + '/requeue', {method: 'POST'}); })
                            .then(function (res) { if (res) { notify(hb.t('js.cms.link.requeued')); loadInbound(); } },
                                function (err) { notify(err.message, true); });
                    });
                    act = b;
                }
                var result = (m.result_cd || '') + (m.last_error ? ' ' + m.last_error : '') + (m.conflict_of_id ? ' ≠ #' + m.conflict_of_id : '');
                cells(tr, [m.inbound_id, m.route_cd, m.biz_key, badge(IN_TONE, m.status, names.inbound[m.status] || m.status), result,
                    m.dup_count, time(m.received_at), act]);
                inBody.appendChild(tr);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    // ------------------------------------------------------------------ 저장·명령

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            if (!current) { return; }
            var l = current;
            var body = {connMode: field(linkForm, 'connMode').value, terminalCd: field(linkForm, 'terminalCd').value,
                adapterUrl: field(linkForm, 'adapterUrl').value, hbSec: Number(field(linkForm, 'hbSec').value) || null,
                writeEnabledYn: field(linkForm, 'writeEnabledYn').value, useYn: field(linkForm, 'useYn').value,
                remark: field(linkForm, 'remark').value, rowVer: l.connMode ? l.rowVer : null};
            hb.busy(sb, function () { return hb.api(API + '/' + l.equipId, {method: 'PUT', body: body}); }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear(linkForm);
                notify(hb.t('js.common.saved'));
                return load();
            }, function (err) { notify(err.message, true); hb.showFieldErrors(linkForm, err.errors); });
        });
    }

    var tagNewBtn = document.getElementById('tagNewBtn');
    if (tagNewBtn) { tagNewBtn.addEventListener('click', function () { editTag(null); }); }
    var tagSaveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('tagSaveBtn'));
    if (tagSaveBtn && tagForm) {
        var tb = tagSaveBtn;
        var tf = tagForm;
        tb.addEventListener('click', function () {
            if (!current) { return; }
            var equipId = current.equipId;
            var states = field(tf, 'allowedStates').value.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
            var body = {tagId: currentTag ? currentTag.tagId : null, tagCd: field(tf, 'tagCd').value, tagNm: field(tf, 'tagNm').value,
                dataType: field(tf, 'dataType').value, minValue: field(tf, 'minValue').value, maxValue: field(tf, 'maxValue').value,
                unit: field(tf, 'unit').value, allowedStates: states, writableYn: 'Y', useYn: field(tf, 'useYn').value,
                rowVer: currentTag ? currentTag.rowVer : null};
            hb.busy(tb, function () { return hb.api(API + '/' + equipId + '/tags', {method: 'POST', body: body}); }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return loadDetail(equipId);
            }, function (err) { notify(err.message, true); hb.showFieldErrors(tf, err.errors); });
        });
    }

    var cmdBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('cmdBtn'));
    if (cmdBtn && cmdForm) {
        var cb = cmdBtn;
        var cf = cmdForm;
        cb.addEventListener('click', function () {
            if (!current) { return; }
            var equipId = current.equipId;
            var body = {tagCd: field(cf, 'tagCd').value, value: field(cf, 'value').value, reason: field(cf, 'reason').value};
            hb.dialog.confirm({title: hb.t('js.cms.link.sendTitle'), message: hb.t('js.cms.link.sendMsg', current.equipCd, body.tagCd, body.value),
                ok: hb.t('js.cms.link.send'), danger: true}).then(function (ok) {
                if (!ok) { return null; }
                // 장비에 쓰는 일 — 같은 요청이 두 번 가지 않게 요청 키(재전송·연타는 서버가 409)
                var key = 'cmd-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
                return hb.busy(cb, function () {
                    return hb.api(API + '/' + equipId + '/commands', {method: 'POST', body: body, headers: {'X-Idempotency-Key': key}});
                });
            }).then(function (res) {
                if (!res) { return; }
                hb.showFieldErrors(cf, []);
                field(cf, 'value').value = '';
                field(cf, 'reason').value = '';
                notify(hb.t('js.cms.link.sent'));
                return loadDetail(equipId);
            }, function (err) { notify(err.message, true); hb.showFieldErrors(cf, err.errors); });
        });
    }

    if (!CAN_SAVE) {
        linkForm.querySelectorAll('input, select').forEach(function (i) { /** @type {HTMLInputElement} */ (i).disabled = true; });
    }
    document.querySelectorAll('input[name="inboundStatus"]').forEach(function (r) { r.addEventListener('change', function () { loadInbound(); }); });

    hb.dirty.watch(linkForm);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return Promise.all([load(), loadInbound()]); }});
        return Promise.all([hb.loadSession(), hb.api(API + '/options'), hb.codeNames('EQUIP_CONN_MODE'), hb.codeNames('PACKML_STATE'),
            hb.codeNames('EQUIP_CMD_STATUS'), hb.codeNames('EQUIP_LINK_EVENT'), hb.codeNames('INBOUND_STATUS'), hb.codeNames('STATE_CAT')]);
    }).then(function (r) {
        var ps = /** @type {HTMLSelectElement} */ (document.getElementById('plantSearch'));
        var all = hb.el('option', null, hb.t('js.cms.allPlants'));
        all.value = '';
        ps.appendChild(all);
        (r[1].data.plants || []).forEach(/** @param {{plantCd: string, plantNm: string}} p */ function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            ps.appendChild(o);
        });
        var ts = /** @type {HTMLSelectElement} */ (document.getElementById('terminalSel'));
        (r[1].data.collectors || []).forEach(/** @param {{terminalCd: string, terminalNm: string, plantCd: string}} t */ function (t) {
            var o = hb.el('option', null, t.terminalCd + ' · ' + t.terminalNm + ' (' + t.plantCd + ')');
            o.value = t.terminalCd;
            ts.appendChild(o);
        });
        names.mode = r[2];
        names.pack = r[3];
        names.cmd = r[4];
        names.event = r[5];
        names.inbound = r[6];
        names.cat = r[7];
        return Promise.all([load(), loadInbound()]);
    }).then(null, function (err) { notify(err && err.message, true); });
})();
