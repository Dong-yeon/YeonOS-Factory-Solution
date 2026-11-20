// 가짜 WES 순수 규칙 — 실행: node --test src/test/js/*.test.js (docs/65 23.8·25.5)
// 시나리오가 서버 수신 규약(업무 키 = reqKey·lineNo·ctrCd·status, 상대 메시지 ID 는 키 밖)대로 콜백을 내는지,
// 출고(라인 단위 "-", 용기 단위 PICKED·HANDED_OVER)·긴급 먼저 큐·대사 스냅숏 조각.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

const load = () => import(pathToFileURL(path.join(__dirname, '../../../tools/fake-wes.mjs')).href);
const LINE = {lineNo: 1, ctrCd: 'BIN-1', contents: [{slot: 1, itemCd: 'A', qty: 4}, {slot: 2, itemCd: 'A', qty: 1}]};

test('정상 순서와 수량 합', async () => {
    const {callbacks} = await load();
    const cbs = callbacks('normal', 'k1', LINE);
    assert.deepEqual(cbs.map((c) => c.status), ['ACCEPTED', 'IN_PROGRESS', 'ARRIVED', 'STORED']);
    assert.equal(cbs[3].qty, 5);
});

test('297회는 업무 키 하나·메시지 ID 는 매번 다름', async () => {
    const {callbacks, bizKey} = await load();
    const cbs = callbacks('dup297', 'k1', LINE);
    assert.equal(cbs.length, 297);
    assert.equal(new Set(cbs.map(bizKey)).size, 1);
    assert.equal(new Set(cbs.map((c) => c.msgId)).size, 297);
});

test('장애 시나리오 — 늦은 상태·오류 동반 완료·충돌·멈춤, 옵션', async () => {
    const {callbacks, bizKey, parseArgs} = await load();
    assert.deepEqual(callbacks('reorder', 'k', LINE).map((c) => c.status), ['ACCEPTED', 'STORED', 'IN_PROGRESS']);
    assert.ok(callbacks('errors-done', 'k', LINE)[0].errors.length > 0);
    const c = callbacks('conflict', 'k', LINE);
    assert.equal(bizKey(c[0]), bizKey(c[1]));
    assert.notEqual(c[0].qty, c[1].qty);
    assert.deepEqual(callbacks('stuck', 'k', LINE).map((x) => x.status), ['ACCEPTED']);
    assert.throws(() => parseArgs(['--key', 'x']), /셸 기록/);
    assert.throws(() => parseArgs([]), /listen/);
    assert.equal(parseArgs(['--edge']).edge, true);
});

test('출고 — 기억한 용기에서 집고 라인 단위 상태는 "-", short 는 반만 인계', async () => {
    const {rememberIn, planOut} = await load();
    const store = new Map();
    rememberIn(store, LINE);
    rememberIn(store, {lineNo: 1, ctrCd: 'BIN-2', contents: [{slot: 1, itemCd: 'A', qty: 3}]});
    const cbs = planOut(store, 'normal', 'k', {lineNo: 1, itemCd: 'A', qty: 7});
    assert.deepEqual(cbs.map((x) => x.status + ':' + x.ctrCd),
        ['ACCEPTED:-', 'PICKED:BIN-1', 'HANDED_OVER:BIN-1', 'PICKED:BIN-2', 'HANDED_OVER:BIN-2', 'COMPLETED:-']);
    assert.deepEqual(cbs.filter((x) => x.status === 'HANDED_OVER').map((x) => x.qty), [5, 2]);
    assert.deepEqual(store.get('BIN-2'), [{slot: 1, itemCd: 'A', lotNo: null, qty: 1}]);
    const s = planOut(store, 'short', 'k2', {lineNo: 1, itemCd: 'A', qty: 2});
    assert.deepEqual(s.filter((x) => x.status === 'HANDED_OVER').map((x) => x.qty), [1]);
    assert.equal(s[s.length - 1].status, 'COMPLETED');
});

test('긴급·일반이 섞인 큐 — 긴급 먼저, 나머지는 들어온 순서', async () => {
    const {orderQueue} = await load();
    const q = [{reqKey: 'n1', type: 'OUT'}, {reqKey: 'i1', type: 'IN'}, {reqKey: 'u1', type: 'OUT', priority: 'URGENT'}, {reqKey: 'n2', type: 'OUT'},
        {reqKey: 'u2', type: 'OUT', priority: 'URGENT'}];
    assert.deepEqual(orderQueue(q).map((r) => r.reqKey), ['u1', 'u2', 'n1', 'i1', 'n2']);
    assert.deepEqual(orderQueue([{reqKey: 'a'}, {reqKey: 'b'}]).map((r) => r.reqKey), ['a', 'b']);          // 긴급 없으면 그대로
});

test('대사 스냅숏 — 조각 번호·마지막 조각, recon-diff 는 첫 칸 +1', async () => {
    const {rememberIn, snapshotParts} = await load();
    const store = new Map();
    for (let i = 1; i <= 3; i++) { rememberIn(store, {ctrCd: 'B' + i, contents: [{slot: 1, itemCd: 'A', qty: i}]}); }
    const parts = snapshotParts(store, 'S1', 2, false);
    assert.deepEqual(parts.map((p) => [p.partNo, p.lastPart, p.containers.length]), [[1, false, 2], [2, true, 1]]);
    const diff = snapshotParts(store, 'S2', 50, true);
    assert.equal(diff[0].containers[0].contents[0].qty, 2);
    assert.equal(store.get('B1')[0].qty, 1);                                                                       // 기억은 그대로
    assert.deepEqual(snapshotParts(new Map(), 'S3', 50, false).map((p) => p.lastPart), [true]);                   // 빈 창고도 한 조각
});
