// 가짜 오토라벨러 순수 규칙 — 실행: node --test src/test/js/*.test.js (docs/65 21.6)
// 시나리오가 서버 계약(결과 단계·검증 스캔 형식)을 지키는지, 리젝트·늦음·빈 라벨을 의도대로 만드는지.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

const load = () => import(pathToFileURL(path.join(__dirname, '../../../tools/fake-labeler.mjs')).href);

test('검증 스캔은 GS1-128 (00) SSCC — 다른 박스·빈 라벨 시나리오', async () => {
    const {verifyScan} = await load();
    const sscc = '088012340000000017';
    assert.equal(verifyScan('normal', sscc), ']C100' + sscc);
    assert.equal(verifyScan('wrong-label', sscc, '088012340000000024'), ']C100088012340000000024');
    assert.equal(verifyScan('empty-label', sscc), '');
});

test('앞단 스캔·기한·결과 단계', async () => {
    const {frontScan, ageMs, resultPhases, SCENARIOS} = await load();
    assert.equal(frontScan('no-read', 'B1'), '');
    assert.notEqual(frontScan('unregistered', 'B1'), 'B1');
    assert.ok(ageMs('late', 2000) > 2000);
    assert.ok(ageMs('normal', 2000) < 2000);
    const phases = ['PRINTED', 'APPLIED', 'VERIFY', 'REJECTED'];
    for (const s of SCENARIOS) {
        for (const p of resultPhases(s)) { assert.ok(phases.includes(p.phase), `${s}: ${p.phase}`); }
    }
    assert.deepEqual(resultPhases('print-fail'), [{phase: 'REJECTED', reasonCd: 'PRINT_FAIL'}]);
});

test('답 → 다음 동작 · 옵션', async () => {
    const {nextMove, parseArgs} = await load();
    assert.equal(nextMove({action: 'PRINT'}), 'PRINT');
    assert.equal(nextMove({action: 'SKIP'}), 'PASS');
    assert.equal(nextMove({action: 'REJECT'}), 'REJECT');
    assert.equal(nextMove(null), 'REJECT');
    assert.throws(() => parseArgs(['--key', 'x']), /셸 기록/);
    assert.throws(() => parseArgs(['--mode', 'cloud']));
    assert.deepEqual(parseArgs(['--boxes', 'A, B,,C']).boxes, ['A', 'B', 'C']);
});
