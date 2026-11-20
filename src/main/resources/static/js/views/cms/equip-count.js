/* 수집 카운트 조회·정정 (cms-equip-count) — docs/25 4.36. 공장·기간·설비·구분 → grid(서버 페이징·정렬) → 상세 + [정정].
   정정 = 역분개 행 하나(사유 필수, 시각은 원 행 그대로). 정정된 행·역분개 행은 흐리게, 두 번 정정은 서버가 막는다(409). */
(function () {
    'use strict';

    var API = '/api/cms/equip-counts';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = document.getElementById('msg');
    var plantSel = document.getElementById('plantSel');
    var equipSel = document.getElementById('equipSel');
    var facts = document.getElementById('facts');
    var guide = document.getElementById('detailGuide');
    var correctBtn = document.getElementById('correctBtn');   // SAVE 권한 없으면 없음
    var grid = null, sp = null, current = null;

    /** 상태 → 배지 톤(docs/40 부록 A) — 그대로 = 정상(색 없음), 정정됨·역분개 = 사람이 고른 결과(neutral) */
    var STATE_TONE = {NORMAL: 'normal', CORRECTED: 'neutral', REVERSAL: 'neutral'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    var STATE_TEXT = {NORMAL: 'js.cmsCount.state.NORMAL', CORRECTED: 'js.cmsCount.state.CORRECTED', REVERSAL: 'js.cmsCount.state.REVERSAL'};
    var METRIC_TEXT = {GOOD: 'js.cmsCount.metric.GOOD', SCRAP: 'js.cmsCount.metric.SCRAP'};

    function stateText(v) { return STATE_TEXT[v] ? hb.t(STATE_TEXT[v]) : v || ''; }
    function metricText(v) { return METRIC_TEXT[v] ? hb.t(METRIC_TEXT[v]) : v || ''; }
    function qty(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    function loadEquips(plantCd) {
        equipSel.textContent = '';
        option(equipSel, '', hb.t('js.cmsCount.allEquip'));
        if (!plantCd) { return Promise.resolve(); }
        return hb.latest('equips', hb.api(API + '/equips?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            res.data.forEach(function (e) {
                option(equipSel, String(e.equipId), e.equipCd + ' · ' + e.equipNm + (e.useYn === 'Y' ? '' : ' (' + hb.t('js.cmsCount.unused') + ')'));
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function params() {
        var v = sp.values(), q = {};
        ['from', 'to', 'plantCd', 'equipId', 'metricCd'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function show(row) {
        current = row;
        facts.textContent = '';
        guide.hidden = !!row;
        if (correctBtn) { correctBtn.hidden = !row || row.state !== 'NORMAL'; }
        if (!row) { facts.hidden = true; return; }
        [[hb.t('js.cmsCount.f.state'), stateText(row.state)],
            [hb.t('js.cmsCount.f.equip'), row.equipCd + ' · ' + row.equipNm],
            [hb.t('js.cmsCount.f.metric'), metricText(row.metricCd)],
            [hb.t('js.cmsCount.f.qty'), qty(row.qty)],
            [hb.t('js.cmsCount.f.occurredAt'), hb.fmt.datetime(row.occurredAt)],
            [hb.t('js.cmsCount.f.recordedAt'), hb.fmt.datetime(row.recordedAt)],
            [hb.t('js.cmsCount.f.collector'), row.collectorCd || ''],
            [hb.t('js.cmsCount.f.eventId'), row.eventId || ''],
            [hb.t('js.cmsCount.f.recordedBy'), row.recordedBy || ''],
            [hb.t('js.cmsCount.f.reason'), row.reason || ''],
            [hb.t('js.cmsCount.f.reversalOf'), row.reversalOfId ? '#' + row.reversalOfId : ''],
            [hb.t('js.cmsCount.f.reversedBy'), row.reversedById ? '#' + row.reversedById : '']
        ].forEach(function (p) {
            if (!p[1]) { return; }
            facts.appendChild(hb.el('dt', null, p[0]));
            facts.appendChild(hb.el('dd', null, p[1]));
        });
        facts.hidden = false;
    }

    if (correctBtn) {
        correctBtn.addEventListener('click', function () {
            var row = current;
            if (!row) { return; }
            // 화면 안 대화상자(docs/40 V-7) — 사유 필수(서버도 REQUIRED), 위험 톤
            hb.dialog.prompt({title: hb.t('js.cmsCount.dlg.title'), label: hb.t('js.cmsCount.dlg.reason'),
                message: hb.t('js.cmsCount.dlg.message', row.equipCd, metricText(row.metricCd), qty(row.qty), hb.fmt.datetime(row.occurredAt)),
                ok: hb.t('js.cmsCount.correct'), required: true, maxLength: 500, danger: true}).then(function (reason) {
                if (reason === null) { return; }
                hb.busy(correctBtn, function () {
                    return hb.api(API + '/' + row.countId + '/correct', {method: 'POST', body: {reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.cmsCount.done', row.equipCd));
                    return grid.reload(false, row.countId).then(function () {
                        // 그 행이 지금 쪽에 없으면 onSelect 가 안 불린다 — 상세는 서버에서 다시
                        return hb.api(API + '/' + row.countId).then(function (r) { show(r.data); });
                    });
                }, function (err) {
                    notify(err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message, true);
                });
            });
        });
    }

    plantSel.addEventListener('change', function () { loadEquips(plantSel.value); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) { option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
        return loadEquips(plantSel.value);
    }).then(function () {
        sp = hb.searchPanel(document.getElementById('search'), {onSearch: function () {
            show(null);
            return grid ? grid.reload(true) : null;
        }});
        return sp.ready;
    }).then(function () {
        show(null);
        grid = hb.grid('#countGrid', {
            progCd: PROG, id: 'main', key: 'countId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            format: {state: stateText, metricCd: metricText},
            status: {state: STATE_TONE},
            rowClass: function (row) { return row.state === 'NORMAL' ? null : 'hb-tree-dim'; },
            onSelect: function (row) { show(row); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
