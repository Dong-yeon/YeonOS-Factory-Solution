// hb.help 화면 도움말 모델(순수 함수) — 실행: node --test src/test/js/*.test.js (docs/46)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {model, flatMenu, sentences} = require('../../main/resources/static/js/common/hb.help.js');

const msgs = {
    'help.prd-wo': '작업지시를 만듭니다. 발행하면 스냅샷이 남습니다(공정. 파라미터). 끝',
    'help.prd-wo.helpWhy': '왜',
    'help.prd-wo.helpWhen': '언제',
    'help.prd-wo.helpDo': '[발행] 스냅샷\n  \n[취소] 취소 ',
    'help.prd-wo.helpNote': '발행은 되돌릴 수 없습니다.'
};
const text = (k) => (Object.prototype.hasOwnProperty.call(msgs, k) ? msgs[k] : null);

test('문구: 긴 설명은 문장마다 문단, 동작·주의는 줄마다 항목', () => {
    const m = model('prd-wo', text, {prev: ['sal-order'], next: ['prd-result']});
    assert.deepEqual(m.what, ['작업지시를 만듭니다.', '발행하면 스냅샷이 남습니다(공정. 파라미터).', '끝']);
    assert.deepEqual(m.why, ['왜']);
    assert.deepEqual(m.when, ['언제']);
    assert.deepEqual(m.does, ['[발행] 스냅샷', '[취소] 취소']);
    assert.deepEqual(m.note, ['발행은 되돌릴 수 없습니다.']);
    assert.deepEqual(m.prev, ['sal-order']);
    assert.deepEqual(m.next, ['prd-result']);
});

test('문장 나누기: 줄바꿈이 있으면 줄, 소수점·괄호 안 마침표는 그대로', () => {
    assert.deepEqual(sentences('가.\n나. 다.'), ['가.', '나. 다.']);
    assert.deepEqual(sentences('허용률 1.5% 까지 받습니다. 넘으면 막습니다.'), ['허용률 1.5% 까지 받습니다.', '넘으면 막습니다.']);
    assert.deepEqual(sentences('Release it. Then (e.g. later) edit.'), ['Release it.', 'Then (e.g. later) edit.']);
    assert.deepEqual(sentences(null), []);
});

test('문구가 없으면 빈 배열(화면은 그 칸을 건너뛴다)', () => {
    const m = model('mst-item', text, undefined);
    assert.deepEqual(m, {what: [], why: [], when: [], does: [], note: [], prev: [], next: []});
});

test('앞·뒤 화면: 형식이 틀린 코드·자기 자신·중복은 버리고 5개까지', () => {
    const m = model('prd-wo', text, {
        prev: ['prd-wo', 'x', '../evil', 'sal-order', 'sal-order', 42, null],
        next: ['a1', 'a2', 'a3', 'a4', 'a5', 'a6']
    });
    assert.deepEqual(m.prev, ['sal-order']);
    assert.deepEqual(m.next, ['a1', 'a2', 'a3', 'a4', 'a5']);
    assert.deepEqual(model('prd-wo', text, 'nope').prev, []);
    assert.deepEqual(model('prd-wo', text, {prev: 'sal-order'}).prev, []);
});

test('메뉴 나무 → 화면별 이름(폴더는 건너뛰고 처음 이름)', () => {
    const names = flatMenu([
        {menuId: 1, name: '생산', children: [
            {menuId: 2, name: '작업지시 관리', progCd: 'prd-wo'},
            {menuId: 3, name: '실적', children: [{menuId: 4, name: '실적 조회', progCd: 'prd-result'}]}
        ]},
        {menuId: 5, name: '바로가기', progCd: 'prd-wo'},
        null, 'x'
    ]);
    assert.deepEqual(names, {'prd-wo': '작업지시 관리', 'prd-result': '실적 조회'});
    assert.deepEqual(flatMenu(null), {});
});

test('help-flow.json: 객체 하나, 항목마다 prev·next 배열', () => {
    const file = path.join(__dirname, '../../main/resources/static/js/common/help-flow.json');
    const flow = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(typeof flow, 'object');
    assert.ok(Object.keys(flow).length > 100);
    for (const [prog, f] of Object.entries(flow)) {
        assert.ok(Array.isArray(f.prev) && Array.isArray(f.next), prog);
    }
});
