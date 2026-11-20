/*
 * 사용량 카운터 (cms-counter) — CMS 1부 C3 (docs/33 4.4).
 * 표(누적·마지막 읽기·수명) → 상세 폼 + 읽기 입력(지금 계기 값 → 증가량 미리보기, 줄면 리셋 확인) + 읽기 기록(마지막 행 무효).
 * 증가량·누적의 정답은 서버(Σ 증가량) — 화면 미리보기는 안내만. 정기 작업이 넣은 행(C10, source AUTO)은 "자동" 으로 보인다.
 */
(function () {
    'use strict';

    var API = '/api/cms/counters';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('counterForm');
    var readForm = document.getElementById('readForm');
    var search = document.getElementById('search');
    var histBody = document.querySelector('#readings tbody');
    var valueInput = document.getElementById('readValue');
    var resetBox = document.getElementById('resetBox');
    var preview = document.getElementById('readPreview');
    var current = null, sp, grid, sels = {}, nums = {};
    var names = {kind: {}};
    /** 종류별 기본 단위·자릿수 — 신규 때만 채운다 */
    var KIND_DEFAULTS = {SHOTS: ['js.cms.counter.unit.SHOTS', 0], CYCLES: ['js.cms.counter.unit.CYCLES', 0],
        RUN_HOURS: ['js.cms.counter.unit.RUN_HOURS', 1], OTHER: [null, 0]};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function rfield(n) { return readForm.querySelector('[data-field="' + n + '"]'); }

    function time(t) { return t ? String(t).substring(0, 16).replace('T', ' ') : ''; }

    function mode() {
        var r = /** @type {HTMLInputElement | null} */ (readForm.querySelector('input[name="mode"]:checked'));
        return r ? r.value : 'GAUGE';
    }

    function lifeName(c) {
        if (c.life === 'OVER') { return hb.t('js.cms.counter.life.OVER', c.lifeShots); }
        if (c.life === 'NEAR') { return hb.t('js.cms.counter.life.NEAR', c.lifeShots); }
        return c.life === 'OK' ? hb.t('js.cms.counter.life.OK', c.lifeShots) : '';
    }

    /** 10진 글자 → 자릿수 정수(BigInt), 되돌리기 — 미리보기 뺄셈용(부동소수 오차 없이) */
    function units(s, scale) {
        var neg = s.charAt(0) === '-';
        var p = (neg ? s.substring(1) : s).split('.');
        var frac = ((p[1] || '') + '000000').substring(0, scale);
        var n = BigInt(p[0] + frac);
        return neg ? -n : n;
    }

    function fromUnits(n, scale) {
        var neg = n < BigInt(0);
        var s = (neg ? -n : n).toString();
        while (s.length <= scale) { s = '0' + s; }
        var out = scale ? s.substring(0, s.length - scale) + '.' + s.substring(s.length - scale) : s;
        return (neg ? '-' : '') + out;
    }

    function unitOf(c) { return c && c.unitNm ? ' ' + c.unitNm : ''; }

    /** 읽기 미리보기: 계기 값이면 직전 값과의 차이, 줄면 리셋 확인 칸을 보인다 */
    function paintPreview() {
        var c = current;
        preview.textContent = '';
        preview.classList.remove('hb-msg-error');
        if (!c) { return; }
        var raw = valueInput.value.trim();
        var isGauge = mode() === 'GAUGE';
        var resetEl = /** @type {HTMLInputElement} */ (rfield('resetYn'));
        if (!isGauge) {
            resetBox.hidden = true;
            resetEl.checked = false;
        }
        if (!raw) {
            preview.textContent = isGauge ? (c.lastGaugeText !== null && c.lastGaugeText !== undefined
                ? hb.t('js.cms.counter.lastGauge', c.lastGaugeText + unitOf(c)) : hb.t('js.cms.counter.firstGauge')) : '';
            if (isGauge && !resetEl.checked) { resetBox.hidden = true; }
            return;
        }
        var p = hb.numbers.parse(raw, {scale: c.scale, allowNegative: false});
        if (!p.ok || p.value === null) {
            preview.textContent = hb.t('js.cms.counter.badNumber', c.scale);
            preview.classList.add('hb-msg-error');
            return;
        }
        if (!isGauge) {
            preview.textContent = hb.t('js.cms.counter.deltaPreview', '+' + p.value + unitOf(c),
                fromUnits(units(c.totalText, c.scale) + units(p.value, c.scale), c.scale) + unitOf(c));
            return;
        }
        var last = c.lastGaugeText;
        var down = last !== null && last !== undefined && hb.numbers.compare(p.value, last) < 0;
        resetBox.hidden = !down && !resetEl.checked;
        if (resetEl.checked) {
            preview.textContent = hb.t('js.cms.counter.resetPreview', '+' + p.value + unitOf(c));
            return;
        }
        if (last === null || last === undefined) {
            preview.textContent = hb.t('js.cms.counter.baseline');
            return;
        }
        if (down) {
            preview.textContent = hb.t('js.cms.counter.decrease', last + unitOf(c));
            preview.classList.add('hb-msg-error');
            return;
        }
        var d = units(p.value, c.scale) - units(last, c.scale);
        preview.textContent = hb.t('js.cms.counter.deltaPreview', '+' + fromUnits(d, c.scale) + unitOf(c),
            fromUnits(units(c.totalText, c.scale) + d, c.scale) + unitOf(c));
    }

    function clearRead() {
        valueInput.value = '';
        rfield('readAt').value = '';
        rfield('remark').value = '';
        /** @type {HTMLInputElement} */ (rfield('resetYn')).checked = false;
        resetBox.hidden = true;
        hb.showFieldErrors(readForm, []);
        paintPreview();
    }

    function show(c) {
        current = c;
        hb.showFieldErrors(form, []);
        ['counterCd', 'counterNm', 'targetCd', 'unitNm', 'remark'].forEach(function (n) { field(n).value = c && c[n] ? c[n] : ''; });
        field('targetType').value = c ? c.targetType : 'EQUIP';
        sels.counterKind.setValue(c ? c.counterKind : 'SHOTS');
        nums.scale.setValue(c ? String(c.scale) : '0');
        field('useYn').value = c ? c.useYn : 'Y';
        ['counterCd', 'targetType', 'targetCd', 'counterKind', 'scale'].forEach(function (n) { field(n).disabled = !!c || !CAN_SAVE; });
        document.getElementById('formMode').textContent = c ? hb.t('js.cms.fail.editing', c.counterCd, c.counterNm)
            : hb.t('js.cms.counter.creating');
        document.getElementById('stateLine').textContent = c ? [
            hb.t('js.cms.counter.totalLine', c.totalText + unitOf(c), c.readingCnt),
            c.lastReadAt ? hb.t('js.cms.counter.lastReadLine', time(c.lastReadAt), c.timeZone) : '',
            lifeName(c), c.planCnt ? hb.t('js.cms.counter.plans', c.planCnt) : ''].filter(Boolean).join(' · ') : '';
        document.getElementById('readSection').hidden = !c || !CAN_SAVE || c.useYn !== 'Y';
        document.getElementById('historySection').hidden = !c;
        clearRead();
        histBody.textContent = '';
        hb.dirty.clear();
        if (c) { loadHistory(); }
    }

    function loadHistory() {
        var c = current;
        return hb.latest('readings', hb.api(API + '/' + c.counterId + '/readings')).then(function (res) {
            histBody.textContent = '';
            if (!res.data.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.counter.noReadings'));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                histBody.appendChild(tr0);
            }
            res.data.forEach(function (r) {
                var tr = hb.el('tr', r.voidYn === 'Y' ? 'hb-tree-dim' : null);
                var gauge = r.source === 'AUTO' ? hb.t('js.cms.counter.auto')
                    : r.gaugeText === null || r.gaugeText === undefined ? hb.t('js.cms.counter.direct')
                    : r.gaugeText + (r.resetYn === 'Y' ? ' ' + hb.t('js.cms.counter.resetMark') : '');
                var delta = r.voidYn === 'Y' ? hb.t('js.cms.counter.voided', r.voidReason || '') : '+' + r.deltaText;
                [time(r.readAt), gauge, delta, r.cumulativeText || '', r.recordedBy + (r.remark ? ' · ' + r.remark : '')]
                    .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                var td = hb.el('td');
                if (CAN_DELETE && r.voidYn === 'N' && r.readingId === c.lastReadingId) {
                    var b = hb.el('button', 'hb-btn-ghost', hb.t('js.cms.counter.void'));
                    b.type = 'button';
                    b.addEventListener('click', function () { voidReading(r, b); });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                histBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    function voidReading(r, btn) {
        cmsUi.reason('js.cms.dlg.void', 'js.cms.counter.voidReason', 'js.cms.counter.void', true).then(function (reason) {
            if (reason === null) { return; }
            var id = current.counterId;
            hb.busy(btn, function () {
                return hb.api(API + '/' + id + '/readings/' + r.readingId + '/void', {method: 'POST', body: {reason: reason}});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.cms.counter.voidedMsg')); });
            }, function (err) {
                var e = err.errors && err.errors[0];
                notify(e ? e.message : err.message, true);
            });
        });
    }

    function body() {
        return {counterCd: field('counterCd').value, counterNm: field('counterNm').value, targetType: field('targetType').value,
            targetCd: field('targetCd').value, counterKind: field('counterKind').value, unitNm: field('unitNm').value,
            scale: nums.scale.value() === null ? null : Number(nums.scale.value()), useYn: field('useYn').value,
            remark: field('remark').value, rowVer: current ? current.rowVer : null};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'useYn', 'targetType'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽) 방금 만든·고친 것은 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.counterId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    kindDefaults();
                    hb.page.openDetail();
                    field('counterCd').focus();
                });
            });
        }
    });

    /** 신규일 때 종류에 맞는 단위·자릿수를 채운다(고칠 수 있음) */
    function kindDefaults() {
        if (current) { return; }
        var d = KIND_DEFAULTS[field('counterKind').value] || [null, 0];
        field('unitNm').value = d[0] ? hb.t(d[0]) : '';
        nums.scale.setValue(String(d[1]));
    }
    field('counterKind').addEventListener('change', kindDefaults);

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.counterId, {method: 'PUT', body: body()}) : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.counterId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    readForm.querySelectorAll('input[name="mode"]').forEach(function (r) {
        r.addEventListener('change', function () {
            var g = mode() === 'GAUGE';
            valueInput.setAttribute('data-field', g ? 'gaugeValue' : 'deltaQty');
            document.getElementById('valueLabel').textContent = hb.t(g ? 'js.cms.counter.mode.GAUGE' : 'js.cms.counter.mode.DELTA');
            hb.showFieldErrors(readForm, []);
            paintPreview();
        });
    });
    valueInput.addEventListener('input', paintPreview);
    rfield('resetYn').addEventListener('change', paintPreview);

    var readBtn = document.getElementById('readBtn');
    readBtn.addEventListener('click', function () {
        var c = current;
        var g = mode() === 'GAUGE';
        var payload = {readAt: rfield('readAt').value || null, gaugeValue: g ? valueInput.value : null, deltaQty: g ? null : valueInput.value,
            resetYn: /** @type {HTMLInputElement} */ (rfield('resetYn')).checked ? 'Y' : 'N', remark: rfield('remark').value,
            lastReadingId: c.lastReadingId || 0};
        hb.busy(readBtn, function () {
            return hb.api(API + '/' + c.counterId + '/readings', {method: 'POST', body: payload});
        }).then(function (res) {
            if (!res) { return; }
            var d = res.data;
            return load(c.counterId).then(function () {
                notify(d.runs ? hb.t('js.cms.counter.readGenerated', '+' + d.deltaText, d.totalText, d.runs, d.workOrders)
                    : hb.t('js.cms.counter.readDone', '+' + d.deltaText, d.totalText));
            });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(readForm, err.errors);
        });
    });

    // 대상 후보(datalist)
    var targetTimer = null;
    field('targetCd').addEventListener('input', function () {
        clearTimeout(targetTimer);
        targetTimer = setTimeout(function () {
            hb.latest('targets', hb.api(API + '/targets?keyword=' + encodeURIComponent(field('targetCd').value))).then(function (res) {
                var dl = document.getElementById('targetList');
                dl.textContent = '';
                res.data.filter(function (t) { return t.targetType === field('targetType').value; }).forEach(function (t) {
                    var o = hb.el('option', null, t.name + (t.classNm ? ' (' + t.classNm + ')' : ''));
                    o.value = t.code;
                    dl.appendChild(o);
                });
            }, function () { /* 후보는 보조 */ });
        }, 250);
    });

    sels.counterKind = hb.select(field('counterKind'));
    nums.scale = hb.number(field('scale'));
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), hb.codeList('CMS_COUNTER_KIND'), sels.counterKind.ready]);
    }).then(function (r) {
        r[1].forEach(function (c) { names.kind[c.code] = c.name; });
        grid = hb.grid('#counterGrid', {
            progCd: 'cms-counter', id: 'main', key: 'counterId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {targetType: 'targetType', useYn: 'useYn'},
            format: {
                lastReadAt: time,                                                   // 대상 공장 현지 시각(서버가 그 시간대로)
                totalText: function (v, row) { return v + unitOf(row); },
                life: function (v, row) { return lifeName(row); }                    // NONE = 수명 없음 → 빈 칸
            },
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {life: {OK: 'neutral', NEAR: 'warning', OVER: 'danger'}, useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.counterId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
