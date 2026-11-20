/*
 * 예방점검 계획 (cms-pm-plan) — CMS 1부 C2·C3 (docs/33 4.3·4.4).
 * 표(다음 예정·상태) → 상세 폼 + 예정 미리보기(저장 전 규칙으로 12회) + 클래스 일괄 + "지금 생성"(정기 작업과 같은 함수).
 * 주기 종류: 달력(날짜) / 사용량(대상 카운터의 누적) — 종류에 맞는 칸만 보인다. 종류·카운터는 만든 뒤 고정.
 * 달력 보기(C27, docs/33 13.14): 검색 패널의 공장 × 월 — 생성된 예정(실선)·예상(점선), 항목을 누르면 계획 상세. 사용량 기준은 생성된 예정만.
 */
(function () {
    'use strict';

    var API = '/api/cms/pm-plans';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('planForm');
    var search = document.getElementById('search');
    var current = null, sp, grid, sels = {}, nums = {};
    var names = {mode: {}, unit: {}, basis: {}, cycle: {}, run: {}};
    var counterSel = document.getElementById('counterSel');

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

    function stateName(s) {
        return {UPCOMING: hb.t('js.cms.state.UPCOMING'), DUE: hb.t('js.cms.state.DUE'), OVERDUE: hb.t('js.cms.state.OVERDUE')}[s] || '';
    }

    function usageUnit(p) { return p.counterUnit ? ' ' + p.counterUnit : ''; }

    /** 주기 글자(행 값으로) — grid 주기 칸 */
    function cycle(p) {
        if (p.cycleType === 'USAGE') {
            return hb.t('js.cms.plan.usageCycle', p.usageIntervalText + usageUnit(p), p.counterCd, names.basis[p.scheduleBasis] || p.scheduleBasis);
        }
        return hb.t('js.cms.plan.cycle', p.calInterval, names.unit[p.calUnit] || p.calUnit, names.basis[p.scheduleBasis] || p.scheduleBasis);
    }

    /** 다음 예정 글자 — 사용량 주기는 예정 누적 · 지금 누적 */
    function nextDue(p) {
        return p.cycleType === 'USAGE' ? hb.t('js.cms.plan.nextUsage', p.nextDueUsageText + usageUnit(p), p.counterTotalText + usageUnit(p))
            : p.nextDueDate;
    }

    /** 주기 종류에 맞는 칸만 보인다 */
    function toggleCycle() {
        var c = field('cycleType').value || 'CAL';
        form.querySelectorAll('[data-cycle]').forEach(function (el) { el.hidden = el.getAttribute('data-cycle') !== c; });
    }

    /** 대상의 사용 중 카운터 — 사용량 주기 고르기. keep = 지금 값(목록 밖이어도 보인다) */
    function loadCounters(keep, keepText) {
        var type = field('targetType').value;
        var cd = field('targetCd').value.trim();
        counterSel.textContent = '';
        option(counterSel, '', hb.t('js.common.choose'));
        if (!cd) {
            stale(counterSel, keep, keepText);
            return Promise.resolve();
        }
        return hb.latest('counters', hb.api(API + '/counters?targetType=' + encodeURIComponent(type) + '&targetCd=' + encodeURIComponent(cd)))
            .then(function (res) {
                res.data.forEach(function (k) {
                    option(counterSel, k.counterCd, k.counterCd + ' · ' + k.counterNm + ' (' + hb.t('js.cms.plan.counterTotal', k.totalText
                        + (k.unitNm ? ' ' + k.unitNm : '')) + ')');
                });
                stale(counterSel, keep, keepText);
            }, function () { stale(counterSel, keep, keepText); });
    }

    function stale(sel, value, text) {
        sel.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        sel.value = value || '';
        if (value && sel.value !== value) {
            option(sel, value, text + ' — ' + hb.t('js.cms.inactive'));
            sel.lastChild.setAttribute('data-stale', '');
            sel.value = value;
        }
    }

    function show(p) {
        current = p;
        hb.showFieldErrors(form, []);
        ['planCd', 'planNm', 'targetCd', 'anchorDate', 'remark'].forEach(function (n) { field(n).value = p && p[n] ? p[n] : ''; });
        field('targetType').value = p ? p.targetType : 'EQUIP';
        sels.cycleType.setValue(p ? p.cycleType : 'CAL');
        field('usageInterval').value = p && p.usageIntervalText ? p.usageIntervalText : '';
        field('usageLead').value = p && p.usageLeadText ? p.usageLeadText : '';
        field('anchorUsage').value = p && p.anchorUsageText ? p.anchorUsageText : '';
        loadCounters(p ? p.counterCd : null, p && p.counterCd ? p.counterCd + ' · ' + p.counterNm : '');
        stale(field('tmplCd'), p && p.tmplCd, p ? p.tmplCd + ' · ' + p.tmplNm : '');
        sels.execMode.setValue(p ? p.execMode : 'WO');
        sels.scheduleBasis.setValue(p ? p.scheduleBasis : 'FIXED');
        sels.calUnit.setValue(p && p.calUnit ? p.calUnit : 'MONTH');
        nums.calInterval.setValue(p && p.calInterval ? String(p.calInterval) : '1');
        nums.leadDays.setValue(p && p.leadDays !== null && p.leadDays !== undefined ? String(p.leadDays) : null);
        nums.estMinutes.setValue(p && p.estMinutes ? String(p.estMinutes) : null);
        stale(field('assigneeLoginId'), p && p.assigneeLoginId, p ? (p.assigneeNm || p.assigneeLoginId) : '');
        field('useYn').value = p ? p.useYn : 'Y';
        ['planCd', 'targetType', 'targetCd', 'cycleType', 'counterCd'].forEach(function (n) { field(n).disabled = !!p || !CAN_SAVE; });
        toggleCycle();
        document.getElementById('formMode').textContent = p ? hb.t('js.cms.fail.editing', p.planCd, p.planNm) : hb.t('js.cms.plan.creating');
        document.getElementById('stateLine').textContent = p ? [p.cycleType === 'USAGE'
            ? hb.t('js.cms.plan.nextUsageLine', p.nextDueUsageText + usageUnit(p), stateName(p.state), p.counterTotalText + usageUnit(p))
            : hb.t('js.cms.plan.next', p.nextDueDate, stateName(p.state), p.effLeadDays),
            p.lastDoneDate ? hb.t('js.cms.plan.lastDone', p.lastDoneDate) : '',
            p.lastDoneUsageText ? hb.t('js.cms.plan.lastDoneUsage', p.lastDoneUsageText + usageUnit(p)) : '',
            p.openRunId ? hb.t('js.cms.plan.open') : '']
            .filter(Boolean).join(' · ') : '';
        document.getElementById('preview').textContent = '';
        hb.dirty.clear();
    }

    function body() {
        return {planCd: field('planCd').value, planNm: field('planNm').value, targetType: field('targetType').value,
            targetCd: field('targetCd').value, tmplCd: field('tmplCd').value, execMode: field('execMode').value,
            calUnit: field('calUnit').value, calInterval: nums.calInterval.value() === null ? null : Number(nums.calInterval.value()),
            scheduleBasis: field('scheduleBasis').value, anchorDate: field('anchorDate').value,
            leadDays: nums.leadDays.value() === null ? null : Number(nums.leadDays.value()),
            assigneeLoginId: field('assigneeLoginId').value,
            estMinutes: nums.estMinutes.value() === null ? null : Number(nums.estMinutes.value()),
            useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null,
            cycleType: field('cycleType').value || 'CAL', counterCd: field('counterCd').value, usageInterval: field('usageInterval').value,
            usageLead: field('usageLead').value, anchorUsage: field('anchorUsage').value};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['plantCd', 'keyword', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
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
            if (selectId && (!sel || sel.planId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ------------------------------------------------------------------ 달력 보기 (C27)

    var calBox = document.getElementById('calBox');
    var calToggle = document.getElementById('calToggle');
    var calMonth = document.getElementById('calMonth');
    var calBody = document.querySelector('#calTable tbody');
    var calList = document.getElementById('calList');
    var calOn = false;
    var CELL_MAX = 4;   // 한 칸에 보이는 항목 수 — 넘으면 "+n" (목록·계획 grid 에서 모두 보인다)

    function shiftMonth(ym, delta) {
        var y = Number(ym.substring(0, 4)), m = Number(ym.substring(5, 7)) - 1 + delta;
        y += Math.floor(m / 12);
        m = ((m % 12) + 12) % 12;
        return y + '-' + (m < 9 ? '0' : '') + (m + 1);
    }

    /** 항목 버튼 — 계획 코드 + 배지(끝난 예정 = 실행 상태, 열린 예정·예상 = 예정 상태) */
    function calItem(it) {
        var b = hb.el('button', 'pmcal-item' + (it.kind === 'FORECAST' ? ' pmcal-forecast' : ''));
        b.type = 'button';
        var what = it.kind === 'FORECAST' ? hb.t('js.cms.cal.forecast')
            : it.cycleType === 'USAGE' ? hb.t('js.cms.cal.usageRun', it.dueUsageText) : hb.t('js.cms.cal.run');
        b.title = [it.planCd, it.planNm, it.targetCd, what].filter(Boolean).join(' · ');
        b.appendChild(hb.el('span', 'pmcal-code', it.planCd));
        if (it.state) {
            b.appendChild(cmsUi.badge('due', it.state, stateName(it.state)));
        } else if (it.status) {
            b.appendChild(cmsUi.badge('run', it.status, names.run[it.status] || it.status));
        }
        b.addEventListener('click', function () {
            cmsUi.leaveOk().then(function (ok) { if (ok) { openRow(it.planId); } });
        });
        return b;
    }

    function renderCal(v) {
        calBody.textContent = '';
        calList.textContent = '';
        var byDay = {};
        v.items.forEach(function (it) { (byDay[it.date] = byDay[it.date] || []).push(it); });
        var y = Number(v.month.substring(0, 4)), m = Number(v.month.substring(5, 7));
        var lead = (hb.dates.weekday(v.month + '-01') + 6) % 7;   // 월요일 시작
        var tr = hb.el('tr');
        for (var i = 0; i < lead; i++) { tr.appendChild(hb.el('td', 'pmcal-out')); }
        for (var d = 1; d <= hb.dates.daysInMonth(y, m); d++) {
            if (tr.children.length === 7) {
                calBody.appendChild(tr);
                tr = hb.el('tr');
            }
            var day = v.month + '-' + (d < 10 ? '0' : '') + d;
            var td = hb.el('td', day === v.today ? 'pmcal-today' : null);
            td.appendChild(hb.el('span', 'pmcal-day', String(d)));
            var its = byDay[day] || [];
            its.slice(0, CELL_MAX).forEach(function (it) { td.appendChild(calItem(it)); });
            if (its.length > CELL_MAX) { td.appendChild(hb.el('span', 'pmcal-more', hb.t('js.cms.cal.more', its.length - CELL_MAX))); }
            tr.appendChild(td);
            if (its.length) {
                var li = hb.el('li');
                li.appendChild(hb.el('span', 'pmcal-date', day + (day === v.today ? ' · ' + hb.t('js.cms.cal.today') : '')));
                its.forEach(function (it) { li.appendChild(calItem(it)); });
                calList.appendChild(li);
            }
        }
        while (tr.children.length < 7) { tr.appendChild(hb.el('td', 'pmcal-out')); }
        calBody.appendChild(tr);
        if (!v.items.length) { calList.appendChild(hb.el('li', 'hb-muted', hb.t('js.cms.cal.empty'))); }
        var info = [hb.t('js.cms.cal.count', v.items.length)];
        if (v.usagePlans) { info.push(hb.t('js.cms.cal.usagePlans', v.usagePlans)); }
        if (v.truncated) { info.push(hb.t('js.cms.cal.truncated')); }
        document.getElementById('calInfo').textContent = info.join(' · ');
    }

    /** 달 다시 읽기 — 공장은 검색 패널 값(필수), 달은 비우면 서버가 공장 오늘의 달 */
    function loadCal() {
        var plant = sp.values().plantCd;
        if (!plant) {
            calBody.textContent = '';
            calList.textContent = '';
            document.getElementById('calInfo').textContent = hb.t('js.cms.cal.choosePlant');
            return Promise.resolve();
        }
        var q = '?plantCd=' + encodeURIComponent(plant) + (calMonth.value ? '&month=' + encodeURIComponent(calMonth.value) : '');
        return hb.latest('calendar', hb.api(API + '/calendar' + q)).then(function (res) {
            calMonth.value = res.data.month;
            renderCal(res.data);
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function setCal(on) {
        calOn = on;
        calToggle.setAttribute('aria-pressed', on ? 'true' : 'false');
        calToggle.textContent = hb.t(on ? 'js.cms.cal.toList' : 'js.cms.cal.toCal');
        calBox.hidden = !on;
        document.getElementById('planGrid').hidden = on;
        if (on) { loadCal(); }
    }

    calToggle.addEventListener('click', function () { setCal(!calOn); });
    document.getElementById('calPrev').addEventListener('click', function () {
        if (calMonth.value) { calMonth.value = shiftMonth(calMonth.value, -1); }
        loadCal();
    });
    document.getElementById('calNext').addEventListener('click', function () {
        if (calMonth.value) { calMonth.value = shiftMonth(calMonth.value, 1); }
        loadCal();
    });
    calMonth.addEventListener('change', loadCal);

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    field('anchorDate').value = hb.dates.todayIn(hb.session().timeZone);
                    hb.page.openDetail();
                    field('planCd').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.planId, {method: 'PUT', body: body()}) : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.planId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    var previewBtn = document.getElementById('previewBtn');
    previewBtn.addEventListener('click', function () {
        var b = body();
        hb.busy(previewBtn, function () {
            return hb.api(API + '/preview', {method: 'POST', body: {scheduleBasis: b.scheduleBasis, calUnit: b.calUnit,
                calInterval: b.calInterval, anchorDate: b.anchorDate, from: current ? current.nextDueDate : null,
                cycleType: b.cycleType, counterCd: b.counterCd, usageInterval: b.usageInterval, anchorUsage: b.anchorUsage,
                fromUsage: current ? current.nextDueUsageText : null}});
        }).then(function (res) {
            if (res) { document.getElementById('preview').textContent = res.data.join(', '); }
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    var genBtn = document.getElementById('genBtn');
    if (genBtn) {
        genBtn.addEventListener('click', function () {
            hb.busy(genBtn, function () {
                return hb.api(API + '/generate', {method: 'POST', body: {}});
            }).then(function (res) {
                if (!res) { return; }
                return load(current ? current.planId : null).then(function () {
                    notify(hb.t('js.cms.plan.generated', res.data.runs, res.data.workOrders));
                });
            }, function (err) { notify(err.message, true); });
        });
    }

    var bulkBtn = document.getElementById('bulkBtn');
    if (bulkBtn) {
        bulkBtn.addEventListener('click', function () {
            var b = body();
            var payload = {classCd: document.getElementById('bulkClass').value, tmplCd: b.tmplCd, execMode: b.execMode, calUnit: b.calUnit,
                calInterval: b.calInterval, scheduleBasis: b.scheduleBasis, anchorDate: b.anchorDate, leadDays: b.leadDays,
                assigneeLoginId: b.assigneeLoginId};
            hb.busy(bulkBtn, function () {
                return hb.api(API + '/bulk', {method: 'POST', body: payload});
            }).then(function (res) {
                if (!res) { return; }
                return load().then(function () { notify(hb.t('js.cms.plan.bulkDone', res.data.created, res.data.skipped)); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
                hb.showFieldErrors(document.getElementById('bulkForm'), err.errors);
            });
        });
    }

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

    // 대상이 바뀌면 카운터 목록을 다시 — 고른 카운터가 새 목록에도 있으면 그대로 둔다(같은 값으로 change 가 다시 와도 지우지 않게)
    function reloadCounters() {
        if (current) { return; }
        var keep = counterSel.value;
        loadCounters(null, '').then(function () {
            if (keep && counterSel.querySelector('option[value="' + CSS.escape(keep) + '"]')) { counterSel.value = keep; }
        });
    }
    field('targetCd').addEventListener('change', reloadCounters);
    field('targetType').addEventListener('change', reloadCounters);
    field('cycleType').addEventListener('change', toggleCycle);

    ['execMode', 'scheduleBasis', 'calUnit', 'cycleType'].forEach(function (n) { sels[n] = hb.select(field(n)); });
    ['calInterval', 'leadDays', 'estMinutes'].forEach(function (n) { nums[n] = hb.number(field(n)); });
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load().then(function () { return calOn ? loadCal() : null; }) : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/options'), hb.codeList('CMS_EXEC_MODE'), hb.codeList('CMS_CAL_UNIT'),
            hb.codeList('CMS_SCHED_BASIS'), sels.execMode.ready, sels.scheduleBasis.ready, sels.calUnit.ready, sels.cycleType.ready,
            hb.codeList('CMS_RUN_STATUS')]);
    }).then(function (r) {
        r[2].forEach(function (c) { names.mode[c.code] = c.name; });
        r[3].forEach(function (c) { names.unit[c.code] = c.name; });
        r[4].forEach(function (c) { names.basis[c.code] = c.name; });
        r[9].forEach(function (c) { names.run[c.code] = c.name; });
        var o = r[1].data;
        var ps = document.getElementById('plantSearch');
        option(ps, '', hb.t('js.cms.allPlants'));
        o.plants.forEach(function (p) { option(ps, p.code, p.code + ' · ' + p.name); });
        var ts = field('tmplCd');
        option(ts, '', hb.t('js.common.choose'));
        o.tmpls.forEach(function (t) { option(ts, t.tmplCd, t.tmplCd + ' · ' + t.tmplNm + (t.equipClassCd ? ' [' + t.equipClassCd + ']' : '')); });
        var as = field('assigneeLoginId');
        option(as, '', hb.t('js.cms.noOwner'));
        o.users.forEach(function (u) { option(as, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        var bc = document.getElementById('bulkClass');
        if (bc) {
            option(bc, '', hb.t('js.common.choose'));
            o.classes.forEach(function (c) { option(bc, c.code, c.code + ' · ' + c.name); });
        }
        grid = hb.grid('#planGrid', {
            progCd: 'cms-pm-plan', id: 'main', key: 'planId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {useYn: 'useYn'},
            format: {
                state: stateName,
                nextDueText: function (v, row) { return nextDue(row); },
                cycleType: function (v, row) { return cycle(row); }
            },
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {state: cmsUi.TONES.due, useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.planId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
