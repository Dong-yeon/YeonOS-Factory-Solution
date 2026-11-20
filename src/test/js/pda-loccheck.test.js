// 창고 PDA 정위치 점검 흐름 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/55 — 온라인만, 상위 검토 2026-10-29)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeLocCheck} = require('../../main/resources/static/js/views/wms/pda-loccheck.js');

function fixture(online, responses) {
    const posted = [];
    const fb = [];
    const rendered = [];
    let seq = 0;
    const state = {online};
    const lc = makeLocCheck({
        post: (url, body) => { posted.push({url, body}); const r = responses.shift(); return r && r.code ? Promise.reject(r) : Promise.resolve(r); },
        online: () => state.online,
        render: (v) => rendered.push(v),
        feedback: (kind, key) => fb.push(kind + ':' + key),
        newReqId: () => 'req-' + (++seq),
        now: () => '2026-10-29T10:00:00+09:00'
    });
    return {lc, posted, fb, rendered, state};
}

const view = (status) => ({data: {head: {checkId: 7, path: 'P1/WH/A-01', status, accurateYn: 'N', missingCnt: 1}, lines: []}});

test('오프라인이면 위치 스캔도 보내지 않는다(대기열 없음)', async () => {
    const f = fixture(false, []);
    assert.equal(await f.lc.start('LOC:P1:A-01'), null);
    assert.deepEqual(f.posted, []);
    assert.deepEqual(f.fb, ['error:js.pdaWh.lcOffline']);
    assert.equal(f.lc.canAct(), false);
});

test('점검 중 연결이 끊기면 물건 스캔·완료를 보내지 않고, 버튼은 비활성(canAct = false)', async () => {
    const f = fixture(true, [view('OPEN')]);
    await f.lc.start('LOC:P1:A-01');
    assert.equal(f.lc.canAct(), true);
    f.state.online = false;
    assert.equal(f.lc.canAct(), false);
    assert.equal(await f.lc.scan('LOT:L1'), null);
    assert.equal(await f.lc.done(), null);
    assert.equal(f.posted.length, 1);                       // 시작 한 번뿐
    assert.deepEqual(f.fb.slice(-2), ['error:js.pdaWh.lcOffline', 'error:js.pdaWh.lcOffline']);
});

test('온라인 물건 스캔은 스캔마다 새 요청 id·단말 시각, 같은 줄이 돌아오면 목록에 한 번만', async () => {
    const line = {lineId: 11, result: 'MISPLACED', itemCd: 'FG-L'};
    const f = fixture(true, [view('OPEN'), {data: {head: {checkId: 7, status: 'OPEN'}, line, duplicate: false}},
        {data: {head: {checkId: 7, status: 'OPEN'}, line, duplicate: true}}]);
    await f.lc.start('LOC:P1:A-01');
    await f.lc.scan('LOT:L1');
    await f.lc.scan('LOT:L1');
    assert.deepEqual(f.posted.slice(1).map((p) => p.body.clientReqId), ['req-1', 'req-2']);
    assert.equal(f.posted[1].url, '/api/pop/wh/loc-checks/7/lines');
    assert.equal(f.posted[1].body.scannedAt, '2026-10-29T10:00:00+09:00');
    assert.equal(f.lc.view().lines.length, 1);
    assert.deepEqual(f.fb.slice(-2), ['error:js.pdaWh.lcResult.MISPLACED', 'error:js.pdaWh.lcDuplicate']);
});

test('완료 뒤에는 canAct = false, 다음 스캔은 새 위치 시작으로', async () => {
    const f = fixture(true, [view('OPEN'), view('DONE'), view('OPEN')]);
    await f.lc.start('LOC:P1:A-01');
    await f.lc.done();
    assert.equal(f.lc.canAct(), false);
    await f.lc.scan('LOC:P1:A-02');
    assert.equal(f.posted[2].url, '/api/pop/wh/loc-checks/scan');
});

test('날짜가 지난 열린 점검(409 WMS_LOC_CHECK_STALE)은 버리고, 다음 스캔은 위치 시작으로', async () => {
    const f = fixture(true, [view('OPEN'), {code: 'WMS_LOC_CHECK_STALE', message: 'stale'}, view('OPEN')]);
    await f.lc.start('LOC:P1:A-01');
    assert.equal(await f.lc.scan('LOT:L1'), null);
    assert.equal(f.lc.view(), null);
    assert.equal(f.rendered[f.rendered.length - 1], null);
    assert.equal(f.lc.canAct(), false);
    await f.lc.scan('LOC:P1:A-01');
    assert.equal(f.posted[2].url, '/api/pop/wh/loc-checks/scan');
});
