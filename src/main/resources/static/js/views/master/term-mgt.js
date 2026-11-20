// @ts-check
/* 용어 사전 (mst-term) — P1 6단계 (docs/21). 왼쪽 전역 용어 hb.grid(읽기 전용, UI 단계 3 ① 빠른 찾기·페이지), 오른쪽 고른 용어의 회사 동의어 추가·사용 중지 */
/**
 * @typedef {{termCd: string, category: string, termKo: string, termEn: string, defKo: string, defEn: string,
 *   aliasCnt: number, [field: string]: any}} TermRow
 * @typedef {{aliasId: number, alias: string, lang: string, useYn: string, rowVer: number, [field: string]: any}} TermAlias
 */
(function () {
    'use strict';

    var API = '/api/master/terms';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var EN = (document.documentElement.lang || 'ko') === 'en';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var abody = /** @type {HTMLElement} */ (document.querySelector('#aliases tbody'));
    var aform = /** @type {HTMLFormElement | null} */ (document.getElementById('aliasForm'));   // SAVE 권한 없으면 없음
    /** 고른 용어 @type {TermRow | null} */
    var current = null;
    /** @type {Record<string, string>} */
    var catNames = {};
    /** @type {Record<string, string>} */
    var langNames = {};
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** 사용 여부 칸을 hb-status 배지로(docs/40 ⑤) — 방금 붙인 칸 @param {HTMLElement} tr @param {string} useYn */
    function ynCell(tr, useYn) {
        var td = /** @type {HTMLElement} */ (tr.lastElementChild);
        td.textContent = '';
        td.appendChild(hb.status(useYn === 'Y' ? 'normal' : 'neutral', useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')));
    }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 목록 다시 읽기 — 동의어 수가 바뀐다. keepCd 가 이 페이지에 있으면 다시 골라 onSelect 가 동의어를 다시 읽는다 @param {string} keepCd */
    function loadTerms(keepCd) {
        return grid ? grid.reload(false, keepCd) : Promise.resolve();
    }

    /** @param {TermRow} t */
    function show(t) {
        current = t;
        /** @type {HTMLElement} */ (document.getElementById('formMode')).hidden = true;
        /** @type {HTMLElement} */ (document.getElementById('termBox')).hidden = false;
        /** @type {HTMLElement} */ (document.getElementById('termTitle')).textContent = (EN ? t.termEn : t.termKo) + ' (' + t.termCd + ')';
        /** @type {HTMLElement} */ (document.getElementById('termDef')).textContent = EN ? t.defEn : t.defKo;
        /** @type {HTMLElement} */ (document.getElementById('termDefEn')).textContent = EN ? t.defKo : t.defEn;
        if (aform) { hb.showFieldErrors(aform, []); }
        return loadAliases(t.termCd);
    }

    /** @param {string} termCd */
    function loadAliases(termCd) {
        return hb.latest('aliases', hb.api(API + '/' + encodeURIComponent(termCd) + '/aliases')).then(function (res) {
            /** @type {TermAlias[]} */
            var list = res.data;
            abody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.term.noAlias'));
                td.colSpan = 4;
                tr.appendChild(td);
                abody.appendChild(tr);
            }
            list.forEach(function (a) {
                var tr = hb.el('tr', a.useYn === 'Y' ? null : 'hb-tree-dim');
                [a.alias, langNames[a.lang] || a.lang, a.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                ynCell(tr, a.useYn);
                var td = hb.el('td');
                if (CAN_SAVE) {
                    var b = hb.el('button', 'hb-btn-secondary hb-btn-small', a.useYn === 'Y' ? hb.t('js.term.disable') : hb.t('js.term.enable'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(API + '/aliases/' + a.aliasId, {method: 'PUT',
                                body: {alias: a.alias, lang: a.lang, useYn: a.useYn === 'Y' ? 'N' : 'Y', rowVer: a.rowVer}});
                        }).then(function (res) {
                            if (!res) { return; }
                            // 이 동의어의 용어로 마무리(저장 중에 다른 용어를 골라도)
                            return loadTerms(termCd).then(function () { notify(hb.t('js.common.saved')); });
                        }, function (err) { notify(err.message, true); });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                abody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    if (aform) {
        var addForm = aform;
        addForm.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!current) { return; }
            var termCd = current.termCd;   // 저장 중에 다른 용어를 골라도 이 용어로 마무리
            var btn = /** @type {HTMLButtonElement} */ (document.getElementById('addAliasBtn'));
            var input = /** @type {HTMLInputElement} */ (addForm.querySelector('[data-field="alias"]'));
            var lang = /** @type {HTMLSelectElement} */ (addForm.querySelector('[data-field="lang"]'));
            var body = {termCd: termCd, alias: input.value, lang: lang.value};
            hb.busy(btn, function () { return hb.api(API + '/aliases', {method: 'POST', body: body}); }).then(function (res) {
                if (!res) { return; }
                input.value = '';
                hb.showFieldErrors(addForm, []);
                return loadTerms(termCd).then(function () { notify(hb.t('js.common.saved')); input.focus(); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(addForm, err.errors);
            });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () {
        hb.page.init();
        catNames = {KPI: hb.t('js.term.cat.KPI'), QUALITY: hb.t('js.term.cat.QUALITY'), PROD: hb.t('js.term.cat.PROD'),
            EQUIP: hb.t('js.term.cat.EQUIP')};
        langNames = {ko: hb.t('js.term.lang.ko'), en: hb.t('js.term.lang.en'), vi: hb.t('js.term.lang.vi')};
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        grid = hb.grid('#termGrid', {
            progCd: PROG, id: 'main', key: 'termCd',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            format: {category: function (v) { return catNames[v] || v; }},
            onSelect: function (row) { show(/** @type {TermRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
