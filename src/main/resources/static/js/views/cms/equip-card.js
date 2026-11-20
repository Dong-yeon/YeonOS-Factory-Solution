/*
 * 설비 이력 카드 (cms-equip-card) — CMS C17 (docs/33 13.5). 조회·엑셀만.
 * 설비 한 대 × 기간 → 요약(서버 집계) + 이력 한 표(hb.grid, 작업지시·점검·예비품·카운터 리셋).
 * 고른 행에 작업지시가 있으면 "작업지시 열기"(화살표 키로 행을 옮길 때 화면이 바뀌지 않게 버튼으로).
 * grid 는 첫 조회 때 만든다 — 설비 없이 읽으면 400 이라.
 */
(function () {
    'use strict';

    var API = '/api/cms/equip-card';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var equipSel = /** @type {HTMLSelectElement} */ (document.getElementById('equipSel'));
    var equipKw = /** @type {HTMLInputElement} */ (document.getElementById('equipKw'));
    var names = {asset: {}, kind: {}};
    var sp;
    var grid = null;
    var openMwo = /** @type {HTMLButtonElement} */ (document.getElementById('openMwo'));
    var picked = null;

    openMwo.addEventListener('click', function () {
        if (picked && picked.mwoId) { window.location.href = '/page/cms-mwo?mwo=' + encodeURIComponent(String(picked.mwoId)); }
    });

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function time(t) { return t ? String(t).substring(0, 16).replace('T', ' ') : ''; }

    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    /** 설비 고르기 — 검색어로 다시 채운다(사용 중지 설비는 표시). 값 = 설비 코드, keep = 남길 설비 코드 */
    function fillEquips(keyword, keep) {
        return hb.latest('equips', hb.api(API + '/equips' + (keyword ? '?keyword=' + encodeURIComponent(keyword) : ''))).then(function (r) {
            equipSel.textContent = '';
            var o0 = hb.el('option', null, hb.t('js.cms.card.pickEquip'));
            o0.value = '';
            equipSel.appendChild(o0);
            r.data.forEach(function (e) {
                var o = hb.el('option', null, e.equipCd + ' · ' + e.equipNm + (e.useYn === 'N' ? ' — ' + hb.t('js.cms.inactive') : ''));
                o.value = e.equipCd;
                equipSel.appendChild(o);
            });
            if (keep && r.data.some(function (e) { return e.equipCd === keep; })) {
                equipSel.value = keep;
            } else if (r.data.length === 1) {
                equipSel.value = r.data[0].equipCd;
            }
            return r.data;
        });
    }

    var kwTimer = 0;
    equipKw.addEventListener('input', function () {
        clearTimeout(kwTimer);
        kwTimer = setTimeout(function () {
            fillEquips(equipKw.value.trim(), equipSel.value).then(null, function (err) { if (!err.stale) { notify(err.message, true); } });
        }, 300);
    });
    equipKw.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); }   // 검색어 칸 Enter 는 설비 목록만 — 조회는 조회 버튼
    });

    /** 조회 조건 — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['equipCd', 'from', 'to', 'kind'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function renderSummary(d) {
        var p = d.profile;
        document.getElementById('sumTitle').textContent = p.equipCd + ' · ' + p.equipNm;
        document.getElementById('profileLine').textContent = hb.t('js.cms.card.profileLine', p.plantCd, p.classCd || '', p.criticality,
            p.keyEquipYn === 'Y' ? hb.t('js.cms.profile.keyMark') : '-', p.ownerNm || '-', d.from, d.to, d.timeZone)
            + (p.equipUseYn === 'N' ? ' · ' + hb.t('js.cms.inactive') : '');
        var counts = document.querySelector('#counts tbody');
        counts.textContent = '';
        var m = d.mwo, c = d.chk;
        var tr = hb.el('tr');
        cmsUi.cells(tr, [hb.t('js.cms.card.mwoLine', m.total, m.bm, m.cm, m.pm)]);
        [m.bm, m.open, m.downMin].forEach(function (x) { tr.appendChild(hb.el('td', 'hb-num', String(x))); });
        tr.appendChild(hb.el('td', null, hb.t('js.cms.card.chkLine', c.total, c.ok, c.ng, c.skipped, c.open)));
        var od = hb.el('td', 'hb-num', String(c.overdue));
        if (c.overdue) { od.appendChild(document.createTextNode(' ')); od.appendChild(cmsUi.badge('due', 'OVERDUE', hb.t('js.cms.state.OVERDUE'))); }
        tr.appendChild(od);
        tr.appendChild(hb.el('td', null, hb.t('js.cms.card.partLine', d.part.lines, d.part.items)));
        tr.appendChild(hb.el('td', 'hb-num', String(d.resets)));
        counts.appendChild(tr);

        var inst = document.querySelector('#installed tbody');
        inst.textContent = '';
        if (!d.installed.length) { emptyRow(inst, 3, hb.t('js.cms.profile.noInstalled')); }
        d.installed.forEach(function (a) {
            var r = hb.el('tr');
            [a.assetCd + ' · ' + a.assetNm, names.asset[a.assetType] || a.assetType, time(a.installedAt)]
                .forEach(function (v) { r.appendChild(hb.el('td', null, v)); });
            inst.appendChild(r);
        });
        var cnt = document.querySelector('#counters tbody');
        cnt.textContent = '';
        if (!d.counters.length) { emptyRow(cnt, 3, hb.t('js.cms.card.noCounters')); }
        d.counters.forEach(function (k) {
            var r = hb.el('tr');
            [k.counterCd + ' · ' + k.counterNm, k.targetCd].forEach(function (v) { r.appendChild(hb.el('td', null, v)); });
            r.appendChild(hb.el('td', 'hb-num', k.totalText + (k.unitNm ? ' ' + k.unitNm : '')));
            cnt.appendChild(r);
        });
        document.getElementById('summary').hidden = false;
    }

    function load() {
        hb.showFieldErrors(search, []);
        var q = params();
        if (!q.equipCd) {
            hb.showFieldErrors(search, [{field: 'equipCd', code: 'REQUIRED', message: hb.t('js.common.required')}]);
            return Promise.resolve();
        }
        var qs = ['equipCd', 'from', 'to'].map(function (k) { return k + '=' + encodeURIComponent(q[k] || ''); }).join('&');
        return hb.latest('summary', hb.api(API + '/summary?' + qs)).then(function (r) {
            notify('');
            document.getElementById('pick').hidden = true;
            renderSummary(r.data);
            document.getElementById('historyBox').hidden = false;
            if (!grid) {
                grid = hb.grid('#histGrid', {
                    progCd: 'cms-equip-card', id: 'main', key: 'rowKey',
                    url: API + '/history/grid', columnsUrl: API + '/history/columns', exportUrl: API + '/history/export',
                    actions: ACTIONS, params: params,
                    filters: {kind: 'kind'},
                    onFilter: function (param, value) { sp.setValue(param, value); },
                    rowClass: function (row) { return row.kind === 'MWO' && row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
                    onSelect: function (row) {
                        picked = row;
                        openMwo.disabled = !(row && row.mwoId);
                    },
                    notify: notify
                });
                return grid.ready;
            }
            picked = null;
            openMwo.disabled = true;
            return grid.reload(true);
        }, function (err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
            hb.showFieldErrors(search, err.errors);
        });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codeList('PHYS_ASSET_TYPE')]);
    }).then(function (r) {
        r[1].forEach(function (c) { names.asset[c.code] = c.name; });
        var today = hb.dates.todayIn(hb.session().timeZone);
        document.getElementById('toIn').value = today;
        document.getElementById('fromIn').value = hb.dates.add(today, -364);
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        var fixed = new URLSearchParams(window.location.search).get('equip');   // 설비 프로필 "이력 카드 열기" — 설비 코드
        if (fixed) { equipKw.value = fixed; }
        return Promise.all([sp.ready, fillEquips(fixed || '', null)]);
    }).then(function (r) {
        var fixed = equipKw.value;
        if (fixed) {
            var hit = r[1].filter(function (e) { return e.equipCd === fixed.toUpperCase(); })[0];
            if (hit) {
                equipSel.value = hit.equipCd;
                return load();
            }
        }
    }).then(null, function (err) { notify(err && err.message, true); });
})();
