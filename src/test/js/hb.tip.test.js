// hb.tip 버튼 툴팁 문구·자리 — 실행: node --test src/test/js/*.test.js (docs/36 20장)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const tip = require('../../main/resources/static/js/common/hb.tip.js');

const MSG = {
    'js.tip.common.saveBtn': '입력한 내용을 저장합니다.',
    'js.tip.common.search': '조회 조건으로 다시 불러옵니다.',
    'js.tip.common.detailClose': '상세를 닫습니다.',
    'js.tip.sys-code.saveBtn': '회사 코드를 저장합니다. 코드는 저장 뒤 바꿀 수 없습니다.',
    'js.tip.custom': '키로 단 설명'
};
const t = (k) => (Object.prototype.hasOwnProperty.call(MSG, k) ? MSG[k] : k);

test('설명이 없으면 버튼 이름 — aria-label 이 보이는 글자보다 먼저, 공백은 한 칸으로', () => {
    const r = tip.resolve({text: '\n   엑셀  내려받기 \n'}, t);
    assert.deepEqual(r, {text: '엑셀 내려받기', name: '엑셀 내려받기', source: 'name', extra: false});
    assert.equal(tip.resolve({ariaLabel: '닫기', text: '×'}, t).text, '닫기');
    assert.equal(tip.resolve({value: '보내기'}, t).text, '보내기');
});

test('찾는 순서: data-tip-key → data-tip → 화면별 키 → title → 공통 id → 이름', () => {
    const all = {tipKey: 'js.tip.custom', tip: '글', prog: 'sys-code', id: 'saveBtn', title: '원래 title', text: '저장'};
    assert.equal(tip.resolve(all, t).source, 'key');
    assert.equal(tip.resolve({...all, tipKey: null}, t).source, 'tip');
    assert.equal(tip.resolve({...all, tipKey: null, tip: ''}, t).text, MSG['js.tip.sys-code.saveBtn']);
    assert.equal(tip.resolve({...all, tipKey: null, tip: null, prog: 'other'}, t).text, '원래 title');
    const common = tip.resolve({prog: 'other', id: 'saveBtn', text: '저장'}, t);
    assert.equal(common.source, 'common');
    assert.equal(common.extra, true);
    assert.equal(tip.resolve({id: 'unknownBtn', text: '확정'}, t).source, 'name');
});

test('없는 키는 건너뛴다 — 키 글자가 툴팁에 새지 않는다', () => {
    const r = tip.resolve({tipKey: 'js.tip.nope', prog: 'x', id: 'y', text: '실행'}, t);
    assert.equal(r.text, '실행');
    assert.equal(r.source, 'name');
});

test('관례 id 가 아닌 id 는 공통 키를 보지 않는다(뜻이 화면마다 다를 수 있어서)', () => {
    const t2 = (k) => (k === 'js.tip.common.releaseBtn' ? '공통 설명' : k);
    assert.equal(tip.resolve({id: 'releaseBtn', text: '확정'}, t2).source, 'name');
});

test('역할: 조회 버튼·상세 닫기', () => {
    assert.equal(tip.resolve({role: 'search', text: '조회'}, t).text, MSG['js.tip.common.search']);
    assert.equal(tip.resolve({role: 'detailClose', text: '닫기'}, t).text, MSG['js.tip.common.detailClose']);
});

test('설명과 이름이 같으면 extra=false(두 번 읽히지 않게), 아무 글자도 없으면 빈 문구', () => {
    assert.equal(tip.resolve({tip: '저장', text: '저장'}, t).extra, false);
    assert.deepEqual(tip.resolve({text: '   '}, t), {text: '', name: '', source: 'name', extra: false});
});

test('글자 주입 — 문구는 글자 그대로 돌려준다(그리기는 textContent)', () => {
    const r = tip.resolve({tip: '<img src=x onerror=alert(1)>'}, t);
    assert.equal(r.text, '<img src=x onerror=alert(1)>');
});

test('자리: 아래 가운데, 아래가 모자라면 위, 좌우는 화면 안 4px', () => {
    const view = {width: 390, height: 800};
    assert.deepEqual(tip.place({left: 100, top: 100, width: 80, height: 28}, {width: 120, height: 30}, view),
        {left: 80, top: 134, place: 'below'});
    assert.deepEqual(tip.place({left: 100, top: 760, width: 80, height: 28}, {width: 120, height: 30}, view),
        {left: 80, top: 724, place: 'above'});
    assert.equal(tip.place({left: 0, top: 10, width: 20, height: 20}, {width: 200, height: 30}, view).left, 4);
    assert.equal(tip.place({left: 370, top: 10, width: 20, height: 20}, {width: 200, height: 30}, view).left, 186);
    // 툴팁이 화면보다 넓으면 왼쪽 4px 에 붙인다(오른쪽은 CSS max-width 가 자른다)
    assert.equal(tip.place({left: 10, top: 10, width: 20, height: 20}, {width: 500, height: 30}, view).left, 4);
    // 위·아래 모두 모자라면 아래(위로 올려 화면 밖으로 나가지 않게)
    assert.equal(tip.place({left: 10, top: 10, width: 20, height: 20}, {width: 100, height: 900}, view).place, 'below');
});
