/*
 * 설비 프로필 (cms-equip-profile) — CMS 1부 C1 (docs/33 D15).
 * 설비 표(P1 설비 + 프로필) → 상세: 중요도·핵심 설비·보전 담당 + 설비 이력 카드(설치 자산, 최근 1년 작업지시).
 * 프로필 행이 없으면 rowVer 가 null — 처음 저장할 때 서버가 만든다.
 */
(function () {
    'use strict';

    var API = '/api/cms/equip-profiles';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('profileForm');
    var search = document.getElementById('search');
    var formMode = document.getElementById('formMode');
    var card = document.getElementById('card');
    var current = null, sp, grid, critSel;
    var names = {type: {}, status: {}, asset: {}};

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

    function time(t) { return t ? String(t).substring(0, 16).replace('T', ' ') : ''; }

    function emptyRow(body, cols, key) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', hb.t(key));
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    function show(p) {
        current = p;
        hb.showFieldErrors(form, []);
        document.getElementById('profileBox').hidden = !p;   // .hb-form 은 display:grid 라 hidden 속성이 안 먹는다 — 감싼 div 로
        card.hidden = !p;
        formMode.textContent = p ? hb.t('js.cms.profile.editing', p.equipCd, p.equipNm) : hb.t('js.cms.profile.select');
        if (p) {
            critSel.setValue(p.criticality);
            field('keyEquipYn').value = p.keyEquipYn;
            var os = field('ownerLoginId');
            os.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
            os.value = p.ownerLoginId || '';
            if (p.ownerLoginId && os.value !== p.ownerLoginId) {
                option(os, p.ownerLoginId, (p.ownerNm || p.ownerLoginId) + ' — ' + hb.t('js.cms.inactive'));
                os.lastChild.setAttribute('data-stale', '');
                os.value = p.ownerLoginId;
            }
            field('idealCtSec').value = p.idealCtSec == null ? '' : String(p.idealCtSec);
            field('remark').value = p.remark || '';
            // 단독 화면(C17) — 점검·예비품·카운터까지, 기간 선택. 여기 카드는 최근 1년 작업지시만
            document.getElementById('cardLink').setAttribute('href', '/page/cms-equip-card?equip=' + encodeURIComponent(p.equipCd));
            loadCard(p.equipId);
        }
        hb.dirty.clear();
    }

    function loadCard(equipId) {
        var inst = document.querySelector('#installed tbody');
        var hist = document.querySelector('#history tbody');
        var to = hb.dates.todayIn(hb.session().timeZone);
        var from = hb.dates.add(to, -365);
        return Promise.all([hb.latest('installed', hb.api(API + '/' + equipId + '/installed')),
            hb.latest('history', hb.api(API + '/' + equipId + '/mwos?from=' + from + '&to=' + to))]).then(function (r) {
            inst.textContent = '';
            hist.textContent = '';
            if (!r[0].data.length) { emptyRow(inst, 3, 'js.cms.profile.noInstalled'); }
            r[0].data.forEach(function (a) {
                var tr = hb.el('tr');
                [a.assetCd + ' · ' + a.assetNm, names.asset[a.assetType] || a.assetType, time(a.installedAt)]
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                inst.appendChild(tr);
            });
            if (!r[1].data.length) { emptyRow(hist, 5, 'js.cms.profile.noHistory'); }
            var bm = 0, downMin = 0;
            r[1].data.forEach(function (m) {
                if (m.mwoType === 'BM' && m.status !== 'CANCELLED') { bm++; }
                if (m.downStartAt && m.downEndAt) {
                    downMin += Math.round((Date.parse(m.downEndAt) - Date.parse(m.downStartAt)) / 60000);
                }
                var tr = hb.el('tr', m.status === 'CANCELLED' ? 'hb-tree-dim' : null);
                cmsUi.cells(tr, [m.mwoNo, names.type[m.mwoType] || m.mwoType, m.title,
                    cmsUi.badge('mwo', m.status, names.status[m.status] || m.status), time(m.reportedAt)]);
                hist.appendChild(tr);
            });
            // 건수·정지 시간 합만 — MTBF/MTTR 은 2부 상태 원장에서 한 번만 정의한다(docs/33 D10)
            document.getElementById('historySum').textContent = hb.t('js.cms.profile.historySum', r[1].data.length, bm, downMin);
        }, function (err) { notify(err.message, true); });
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['plantCd', 'keyword', 'criticality', 'keyOnly'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
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
            if (selectId && (!sel || sel.equipId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            if (!current) { return; }
            var body = {criticality: field('criticality').value, keyEquipYn: field('keyEquipYn').value,
                ownerLoginId: field('ownerLoginId').value, idealCtSec: field('idealCtSec').value, remark: field('remark').value,
                rowVer: current.rowVer};
            var id = current.equipId;
            hb.busy(saveBtn, function () {
                return hb.api(API + '/' + id, {method: 'PUT', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    critSel = hb.select(field('criticality'));
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/options'), hb.codeList('CMS_MWO_TYPE'), hb.codeList('CMS_MWO_STATUS'),
            hb.codeList('PHYS_ASSET_TYPE'), critSel.ready]);
    }).then(function (r) {
        var ps = document.getElementById('plantSearch');
        option(ps, '', hb.t('js.cms.allPlants'));
        r[1].data.plants.forEach(function (p) { option(ps, p.code, p.code + ' · ' + p.name); });
        var os = field('ownerLoginId');
        option(os, '', hb.t('js.cms.noOwner'));
        r[1].data.users.forEach(function (u) { option(os, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        r[2].forEach(function (c) { names.type[c.code] = c.name; });
        r[3].forEach(function (c) { names.status[c.code] = c.name; });
        r[4].forEach(function (c) { names.asset[c.code] = c.name; });
        grid = hb.grid('#equipGrid', {
            progCd: 'cms-equip-profile', id: 'main', key: 'equipId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {plantCd: 'plantCd', criticality: 'criticality'},
            format: {keyEquipYn: function (v) { return v === 'Y' ? hb.t('js.cms.profile.keyMark') : ''; }},   // 위치 없음(보관)은 빈 칸 — grid 는 null 을 format 에 넘기지 않는다
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {
                keyEquipYn: function (v) { return v === 'Y' ? 'info' : null; },   // 핵심 설비만 배지
                openMwoCnt: function (v) { return v > 0 ? 'normal' : null; }       // 진행 중 작업 있음
            },
            onSelect: function (row) { openRow(row.equipId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
