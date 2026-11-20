// @ts-check
/*
 * BOM 네 화면(관리·전개·역전개·일괄 대체) 공용 — 화면 사이 바로가기와 링크 조회 (docs/60 X2·X3·X5).
 *   bomUi.open('mst-bom-where', {itemCd: 'A'})   다른 BOM 화면을 그 조건(q.이름)으로 — 셸 탭(이미 열려 있으면 조건만 바꿔 다시 조회),
 *                                                셸 밖이면 그 화면으로 이동(srm-ui openProg 와 같은 길)
 *   bomUi.linked(sp, 'itemCd')                   링크(?q.itemCd=)로 새로 열렸으면 바로 조회 — 패널은 링크 값을 칸에 채우기만 한다
 *   bomUi.errorText(err, field)                  "없는 품목" 칸 오류를 품목 코드가 들어간 문구로(그 밖은 서버 문구 그대로)
 */
(function (global) {
    'use strict';

    /** @type {Record<string, string>} 화면 → 탭 제목 문구 키 */
    var TAB = {
        'mst-bom': 'js.bomUi.tab.mgt',
        'mst-bom-tree': 'js.bomUi.tab.tree',
        'mst-bom-where': 'js.bomUi.tab.where',
        'mst-bom-replace': 'js.bomUi.tab.replace'
    };

    /** @param {string} progCd @param {Record<string, string>} params */
    function open(progCd, params) {
        if (!TAB[progCd]) { return; }
        var title = hb.t(TAB[progCd]);
        var top = /** @type {any} */ (window.top);
        var shell = top && top !== window ? top.hbShell : null;
        if (shell && shell !== true) {
            shell.open(progCd, title, params);
            return;
        }
        var q = Object.keys(params).filter(function (k) { return params[k]; })
            .map(function (k) { return encodeURIComponent('q.' + k) + '=' + encodeURIComponent(params[k]); }).join('&');
        window.location.href = '/page/' + encodeURIComponent(progCd) + (q ? '?' + q : '');
    }

    /**
     * 주소에 링크 조건(q.필드)이 있고 그 칸이 채워졌으면 조회 — 조회 버튼을 누른 것과 같다
     * @param {HB.SearchPanel} sp @param {string} field
     */
    function linked(sp, field) {
        if (new URLSearchParams(window.location.search).has('q.' + field) && sp.values()[field]) {
            return sp.search();
        }
        return null;
    }

    /** @param {HB.ApiError} err @param {string} field @param {string | null | undefined} typed */
    function errorText(err, field, typed) {
        var nf = (err.errors || []).some(function (/** @type {HB.FieldError} */ e) { return e.field === field && e.code === 'NOT_FOUND'; });
        return nf && typed ? hb.t('js.bomUi.noItem', typed) : err.message;
    }

    /** @type {any} */ (global).bomUi = {open: open, linked: linked, errorText: errorText};
})(window);
