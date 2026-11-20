// 셸 메뉴 묶음 접기 — 저장 값 검증·자동 펼침 경로 (docs/15 4.4). 실행: node --test src/test/js/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {parse, serialize, pathTo, folderIds, search, highlight} = require('../../main/resources/static/js/shell.menufold.js');

const MENU = [
    {menuId: 1, name: '생산', children: [
        {menuId: 2, name: '작업지시', progCd: 'mes-wo'},
        {menuId: 3, name: '실적', children: [{menuId: 4, name: '실적 등록', progCd: 'mes-result'}]}
    ]},
    {menuId: 5, name: '시스템', children: [{menuId: 6, name: '사용자', progCd: 'sys-user'}]},
    {menuId: 7, name: '홈', progCd: 'home-main'}
];

test('묶음 id 는 폴더만', () => {
    assert.deepEqual([...folderIds(MENU)].sort(), ['1', '3', '5']);
});

test('저장 값은 메뉴에 있는 묶음 id 만, 중복·모르는 값·형식 오류는 버린다', () => {
    const known = folderIds(MENU);
    assert.deepEqual(parse('["1","3"]', known), ['1', '3']);
    assert.deepEqual(parse('[1, 3, 3, "5"]', known), ['1', '3', '5']);
    assert.deepEqual(parse('["2", "99", "abc", "-1", "01", null, {}]', known), []);   // 2 는 화면, 99 는 없음
    assert.deepEqual(parse('{"1": true}', known), []);
    assert.deepEqual(parse('not json', known), []);
    assert.deepEqual(parse(null, known), []);
    assert.deepEqual(parse('', known), []);
    assert.deepEqual(parse('["1"' + ',"1"'.repeat(10000) + ']', known), []);   // 너무 긴 값
});

test('저장은 id 형식만', () => {
    assert.equal(serialize(new Set(['1', '3', 'x', '<script>'])), '["1","3"]');
    assert.deepEqual(parse(serialize(['5', '1']), folderIds(MENU)), ['5', '1']);
});

test('연 화면이 든 묶음(조상 전부)을 바깥부터', () => {
    assert.deepEqual(pathTo(MENU, 'mes-result'), ['1', '3']);
    assert.deepEqual(pathTo(MENU, 'mes-wo'), ['1']);
    assert.deepEqual(pathTo(MENU, 'home-main'), []);
    assert.deepEqual(pathTo(MENU, 'nope'), []);
    assert.deepEqual(pathTo([], 'mes-wo'), []);
});

test('검색: 이름·화면 코드 부분 일치, 대소문자·공백 무시, 맞는 화면이 든 묶음 펼침', () => {
    const r = search(MENU, '실적');
    assert.deepEqual(r.progCds, ['mes-result']);
    assert.deepEqual([...r.folders], ['1', '3']);
    assert.deepEqual(search(MENU, ' 실 적  등록 ').progCds, ['mes-result']);   // 공백 무시
    assert.deepEqual(search(MENU, 'SYS-USER').progCds, ['sys-user']);          // 코드·대소문자
    assert.deepEqual([...search(MENU, 'sys').folders], ['5']);
    assert.deepEqual(search(MENU, '작업').progCds, ['mes-wo']);
    assert.deepEqual(search(MENU, '홈').progCds, ['home-main']);               // 묶음 밖 화면
    assert.deepEqual([...search(MENU, '홈').folders], []);
    assert.deepEqual(search(MENU, '생산').progCds, []);                         // 묶음 이름만 맞으면 화면 없음
    assert.deepEqual(search(MENU, 'ㅅㅈ').progCds, []);                         // 초성 검색 없음
    assert.deepEqual(search(MENU, 'zzz').progCds, []);
});

test('검색어가 비면 null(원래 접힘 상태로 돌아간다)', () => {
    assert.equal(search(MENU, ''), null);
    assert.equal(search(MENU, '   '), null);
    assert.equal(search(MENU, null), null);
});

test('강조 구간은 원래 글자 위치(공백 포함)', () => {
    assert.deepEqual(highlight('실적 등록', '적등'), [1, 4]);
    assert.deepEqual(highlight('LOT 추적', 'lot'), [0, 3]);
    assert.deepEqual(highlight('사용자', 'sys-user'), null);   // 코드로만 맞음
    assert.equal(highlight('사용자', ''), null);
});
