// 가짜 엣지 순수 규칙 — 실행: node --test src/test/js/*.test.js (docs/65 16.9)
// 가짜 장비가 정상 흐름에서 서버 전이 허용표(PackMl.java)를 지키는지, 엣지 2차 검사(허용 목록·인터록·범위), 저장 후 전달 큐 순서.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

const load = () => import(pathToFileURL(path.join(__dirname, '../../../tools/fake-edge.mjs')).href);

test('정상 순환은 허용된 전이만 낸다', async () => {
    const {nextState, allowed, NEXT} = await load();
    for (const from of Object.keys(NEXT)) {
        for (const r of [0, 0.1, 0.5, 0.99]) {
            const to = nextState(from, r);
            assert.ok(allowed(from, to), `${from} → ${to}`);
        }
    }
    // 서버와 같은 표: 건너뛰기·비상 정지에서 바로 실행 금지
    assert.equal(allowed('IDLE', 'EXECUTE'), false);
    assert.equal(allowed('ABORTED', 'EXECUTE'), false);
    assert.equal(allowed(null, 'EXECUTE'), true);
});

test('엣지 2차 검사 — 허용 목록·인터록·범위·형식', async () => {
    const {parseAllow, checkWrite} = await load();
    const allow = parseAllow('SPEED_SP:0:100:IDLE|STOPPED,run:bool');
    assert.equal(checkWrite(allow, 'SPEED_SP', '55', 'IDLE'), null);
    assert.equal(checkWrite(allow, 'SPEED_SP', '55', 'EXECUTE'), 'INTERLOCK');
    assert.equal(checkWrite(allow, 'SPEED_SP', '101', 'IDLE'), 'RANGE');
    assert.equal(checkWrite(allow, 'SPEED_SP', 'abc', 'IDLE'), 'RANGE');
    assert.equal(checkWrite(allow, 'RUN', 'true', 'EXECUTE'), null);
    assert.equal(checkWrite(allow, 'RUN', '1', 'EXECUTE'), 'TYPE');
    assert.equal(checkWrite(allow, 'MOTOR_RAW_40001', '1', 'IDLE'), 'NOT_ALLOWED');
});

test('저장 후 전달 큐는 보낸 만큼만 앞에서 뺀다(순서 유지)', async () => {
    const {makeQueue} = await load();
    const q = makeQueue([{seq: 1}]);
    q.push({seq: 2});
    q.push({seq: 3});
    assert.deepEqual(q.peek(2).map((m) => m.seq), [1, 2]);
    q.drop(2);
    q.push({seq: 4});
    assert.deepEqual(q.all().map((m) => m.seq), [3, 4]);
    assert.equal(q.size(), 2);
});
