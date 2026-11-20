// hb.text 보이는 글자 판정 — 실행: node --test src/test/js/*.test.js (QA R4-a, 서버 VisibleTextTest 와 같은 경우)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const text = require('../../main/resources/static/js/common/hb.text.js');

const INVISIBLE = ['\u200B', '\uFEFF', '\u00AD', '\u00A0', '\u3000', '\u2060', '\u200E', '\u202E', '\u180E', '\u2028',
    '\t', '\n', '\r', ' ', '\u0085', '\u3164', '\u115F', '\u1160', '\uFFA0', '\u2800', '\u200B\u200B', '\u00A0\u00A0',
    ' \u200B\u3000\uFEFF\u2060 '];

test('보이지 않는 글자뿐이면 빈 값', () => {
    for (const s of INVISIBLE) {
        const hex = [...s].map((c) => 'U+' + c.codePointAt(0).toString(16)).join(' ');
        assert.equal(text.isBlank(s), true, hex);
        assert.equal(text.visibleLength(s), 0, hex);
        assert.equal(text.strip(s), '', hex);
    }
    assert.equal(text.isBlank(null), true);
    assert.equal(text.isBlank(undefined), true);
    assert.equal(text.isBlank(''), true);
});

test('보이는 글자 수는 코드 포인트 — 이모지·보조 평면 한자 1, ZWJ 는 세지 않음', () => {
    assert.equal(text.visibleLength('오류'), 2);
    assert.equal(text.visibleLength('\u200B오\u00A0류\uFEFF'), 2);
    assert.equal(text.visibleLength('\u{1F600}'), 1);
    assert.equal(text.visibleLength('\u{20BB7}'), 1);
    assert.equal(text.visibleLength('\u{1F468}\u200D\u{1F469}'), 2);
});

test('앞뒤만 걷어내고 가운데는 그대로', () => {
    assert.equal(text.strip('\u200B\u00A0 설비 고장 \u3000\uFEFF'), '설비 고장');
    assert.equal(text.strip('\u{1F468}\u200D\u{1F469}'), '\u{1F468}\u200D\u{1F469}');
    assert.equal(text.strip('a\u200Bb'), 'a\u200Bb');
    assert.equal(text.strip('첫 줄\n둘째 줄'), '첫 줄\n둘째 줄');
    assert.equal(text.strip('\u{1F600}\u200B'), '\u{1F600}');
});

test('hb.dialog.prompt 필수 칸: hb.text 가 있으면 보이지 않는 글자만 넣은 사유는 REQUIRED', () => {
    const {check, submitValue} = require('../../main/resources/static/js/common/hb.dialog.js');
    const saved = globalThis.hb;
    globalThis.hb = /** @type {any} */ ({text});
    try {
        assert.deepEqual(check('\u200B', {required: true}, null), {ok: false, code: 'REQUIRED'});
        assert.deepEqual(check('\u00A0\u00A0', {required: true}, null), {ok: false, code: 'REQUIRED'});
        assert.deepEqual(check('\u200B 사유 \uFEFF', {required: true}, null), {ok: true, value: '사유'});
        assert.deepEqual(submitValue('\u200B', {}, null), {ok: true, value: ''});   // 선택 칸 = 비움
    } finally {
        globalThis.hb = saved;
    }
});
