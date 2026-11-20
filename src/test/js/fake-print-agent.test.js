// 가짜 인쇄 에이전트 순수 규칙 — 실행: node --test src/test/js/*.test.js (docs/65 19.5)
// 시나리오가 서버가 받는 결과 상태만 내는지, 흉내 프린터의 odometer(서버 판정식의 입력)가 용지 없음 동안 멈췄다 이어지는지, 키를 명령줄로 받지 않는지.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

const load = () => import(pathToFileURL(path.join(__dirname, '../../../tools/fake-print-agent.mjs')).href);

test('시나리오는 서버가 받는 결과 상태만 낸다', async () => {
    const {SCENARIOS, RESULT_STATES, planResults} = await load();
    for (const s of SCENARIOS) {
        for (const step of planResults(s)) {
            assert.ok(RESULT_STATES.includes(step.state), `${s}: ${step.state}`);
        }
    }
    assert.deepEqual(planResults('crash-after-claim'), []);                        // 보고 없음 → 서버 lease 만료
    assert.ok(planResults('late', {leaseSec: 30})[0].delaySec > 30);               // lease 를 넘겨 늦게
    assert.equal(planResults('dup-result').filter((x) => x.state === 'PRINTED').length, 2);
    assert.throws(() => planResults('nope'));
});

test('흉내 프린터 — 용지 없음 동안 odometer 멈춤, 넣으면 밀린 장', async () => {
    const {makePrinter} = await load();
    const p = makePrinter(100);
    p.receive(2);
    assert.equal(p.state().odometer, 102);
    p.setPaper(false);
    p.receive(3);
    assert.equal(p.state().odometer, 102);
    assert.deepEqual(p.state().errors, ['MEDIA_OUT']);
    p.setPaper(true);
    assert.equal(p.state().odometer, 105);
    assert.deepEqual(p.state().errors, []);
});

test('옵션 — 키는 명령줄로 받지 않는다, 모르는 시나리오 거부', async () => {
    const {parseArgs, settled} = await load();
    assert.throws(() => parseArgs(['--key', 'COMP.1.secret']), /셸 기록/);
    assert.throws(() => parseArgs(['--scenario', 'boom']));
    const o = parseArgs(['--key-file', 'k.txt', '--scenario', 'paper-out', '--wait', '3']);
    assert.equal(o.keyFile, 'k.txt');
    assert.equal(o.wait, 3);
    assert.ok(settled('DUP') && settled('STALE') && !settled('ERROR'));
});
