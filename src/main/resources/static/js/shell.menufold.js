// @ts-check
/*
 * 셸 메뉴 묶음 접기·펼치기의 순수 계산 (docs/15 4.4) — DOM 없음, node --test 로 시험(src/test/js/shell.menufold.test.js).
 * - 접은 묶음은 이 기기 localStorage `hb.shell.folders` 에 menuId 목록(JSON 배열)으로. 모르는 값·형식은 버린다(기본 = 모두 펼침)
 * - 지금 연 화면이 든 묶음(조상 전부)은 펼친다
 * - 메뉴 검색: 이름·화면 코드 부분 일치(대소문자·공백 무시, 한글 그대로 — 초성 검색 없음), 맞는 화면이 든 묶음은 펼쳐 보인다
 */
(function (global) {
    'use strict';

    var MAX_IDS = 500;
    var ID = /^[1-9][0-9]{0,18}$/;

    /**
     * 저장 값 → 접은 묶음 id 목록. 메뉴에 있는 묶음(known)만, 중복 없이
     * @param {string | null | undefined} raw @param {Set<string>} known @returns {string[]}
     */
    function parse(raw, known) {
        if (!raw || raw.length > 20000) { return []; }
        /** @type {unknown} */
        var v;
        try { v = JSON.parse(raw); } catch (e) { return []; }
        if (!Array.isArray(v)) { return []; }
        /** @type {string[]} */
        var out = [];
        for (var i = 0; i < v.length && out.length < MAX_IDS; i++) {
            var s = typeof v[i] === 'number' ? String(v[i]) : v[i];
            if (typeof s === 'string' && ID.test(s) && known.has(s) && out.indexOf(s) < 0) { out.push(s); }
        }
        return out;
    }

    /** @param {Iterable<string>} ids @returns {string} */
    function serialize(ids) {
        return JSON.stringify(Array.from(ids).filter(function (s) { return ID.test(s); }).slice(0, MAX_IDS));
    }

    /**
     * progCd 가 든 묶음 id(바깥 → 안). 없으면 빈 배열
     * @param {{menuId?: number, progCd?: string, children?: any[]}[]} nodes @param {string} progCd @returns {string[]}
     */
    function pathTo(nodes, progCd) {
        /** @type {string[]} */
        var found = [];
        (function walk(/** @type {any[]} */ list, /** @type {string[]} */ path) {
            for (var i = 0; i < list.length && found.length === 0; i++) {
                var n = list[i];
                if (n.progCd === progCd) {
                    found = path.slice();
                    return;
                }
                if (!n.progCd && n.children) {
                    walk(n.children, n.menuId != null ? path.concat(String(n.menuId)) : path);
                }
            }
        })(nodes || [], []);
        return found;
    }

    /** 묶음 id 전부 @param {{menuId?: number, progCd?: string, children?: any[]}[]} nodes @returns {Set<string>} */
    function folderIds(nodes) {
        /** @type {Set<string>} */
        var ids = new Set();
        (function walk(/** @type {any[]} */ list) {
            list.forEach(function (n) {
                if (!n.progCd) {
                    if (n.menuId != null) { ids.add(String(n.menuId)); }
                    walk(n.children || []);
                }
            });
        })(nodes || []);
        return ids;
    }

    /** 비교용: 소문자 + 공백 제거 @param {string} s */
    function norm(s) {
        return String(s || '').toLowerCase().replace(/\s+/g, '');
    }

    /**
     * 검색 — 맞는 화면(progCd)과 펼쳐 보일 묶음 id. 검색어가 비면 null(= 원래 상태로)
     * @param {{menuId?: number, name?: string, progCd?: string, children?: any[]}[]} nodes @param {string} query
     * @returns {{progCds: string[], folders: Set<string>} | null}
     */
    function search(nodes, query) {
        var q = norm(query);
        if (!q) { return null; }
        /** @type {string[]} */
        var progCds = [];
        /** @type {Set<string>} */
        var folders = new Set();
        (function walk(/** @type {any[]} */ list, /** @type {string[]} */ path) {
            list.forEach(function (n) {
                if (n.progCd) {
                    if (norm(n.name).indexOf(q) >= 0 || norm(n.progCd).indexOf(q) >= 0) {
                        progCds.push(n.progCd);
                        path.forEach(function (id) { folders.add(id); });
                    }
                } else {
                    walk(n.children || [], n.menuId != null ? path.concat(String(n.menuId)) : path);
                }
            });
        })(nodes || [], []);
        return {progCds: progCds, folders: folders};
    }

    /**
     * 이름에서 검색어가 맞는 구간 [시작, 끝) — 원래 글자 위치(공백 포함). 이름에 없으면(코드로만 맞음) null
     * @param {string} name @param {string} query @returns {[number, number] | null}
     */
    function highlight(name, query) {
        var q = norm(query);
        if (!q || !name) { return null; }
        /** @type {number[]} 정규화 글자 i → 원래 위치 */
        var map = [];
        var flat = '';
        for (var i = 0; i < name.length; i++) {
            if (!/\s/.test(name.charAt(i))) {
                flat += name.charAt(i).toLowerCase();
                map.push(i);
            }
        }
        var at = flat.indexOf(q);
        if (at < 0) { return null; }
        return [map[at], map[at + q.length - 1] + 1];
    }

    var api = {parse: parse, serialize: serialize, pathTo: pathTo, folderIds: folderIds, search: search, highlight: highlight};
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        /** @type {any} */ (global).hbMenuFold = api;
    }
})(typeof window !== 'undefined' ? window : globalThis);
