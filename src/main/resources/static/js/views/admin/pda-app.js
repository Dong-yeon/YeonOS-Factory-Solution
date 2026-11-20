// @ts-check
/*
 * 현장 단말 앱 (sys-pda-app) — docs/50 4장. 보기 전용: 서버가 운영자가 놓은 파일에서 계산한 버전·크기·SHA-256 을 보이고,
 * 준비된 때만 내려받기 링크를 연다(링크 = 브라우저가 바로 받는다 — 수십 MB 를 화면 메모리에 올리지 않는다).
 */
/**
 * @typedef {{state: 'OK' | 'MISSING' | 'INVALID', version?: string, minVersion?: string, releasedAt?: string,
 *   notes?: string | null, sizeBytes?: number | null, sha256?: string | null}} PdaAppInfo
 */
(function () {
    'use strict';

    var state = /** @type {HTMLElement} */ (document.getElementById('state'));
    var info = /** @type {HTMLElement} */ (document.getElementById('info'));
    var link = /** @type {HTMLAnchorElement} */ (document.getElementById('downloadLink'));
    /** 상태 → 문구 키(키를 이어 붙이지 않는다 — I18nKeyGuardTest) @type {Record<string, string>} */
    var STATE_KEYS = {OK: 'js.sysPdaApp.state.OK', MISSING: 'js.sysPdaApp.state.MISSING', INVALID: 'js.sysPdaApp.state.INVALID'};

    /** @param {string} id @param {string | null | undefined} text */
    function put(id, text) {
        /** @type {HTMLElement} */ (document.getElementById(id)).textContent = text || '-';
    }

    /** 줄바꿈을 살려 글자로만 넣는다 @param {string | null | undefined} text */
    function putLines(text) {
        var dd = /** @type {HTMLElement} */ (document.getElementById('notes'));
        dd.textContent = '';
        (text || '-').split('\n').forEach(function (line, i) {
            if (i > 0) { dd.appendChild(document.createElement('br')); }
            dd.appendChild(document.createTextNode(line));
        });
    }

    /** @param {number | null | undefined} n */
    function size(n) {
        if (n === null || n === undefined) { return '-'; }
        return hb.fmt.num(n / (1024 * 1024), 1, 1) + ' MB (' + hb.fmt.num(n) + ' B)';
    }

    /** @param {PdaAppInfo} d */
    function show(d) {
        var ok = d.state === 'OK';
        state.textContent = hb.t(STATE_KEYS[d.state] || 'js.sysPdaApp.state.MISSING');
        state.classList.toggle('hb-msg-error', !ok);
        info.hidden = !d.version;
        link.hidden = !ok;
        put('version', d.version);
        put('minVersion', d.minVersion);
        put('releasedAt', d.releasedAt ? hb.fmt.date(d.releasedAt) : null);
        put('size', size(d.sizeBytes));
        put('sha256', d.sha256);
        putLines(d.notes);
    }

    put('serverUrl', window.location.origin);
    // 상태 문구(js.*)가 키로 보이지 않게 문구를 먼저 받는다
    Promise.all([hb.loadMessages(), hb.api('/api/platform/pda-app')]).then(function (r) { show(r[1].data); }, function (err) {
        state.textContent = err.message;
        state.classList.add('hb-msg-error');
    });
})();
