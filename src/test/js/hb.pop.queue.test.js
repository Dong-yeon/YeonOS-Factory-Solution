// hb.pop 오프라인 대기열 단위 테스트 — 실행: node --test src/test/js/*.test.js (docs/25 4.20)
// 다시 보낼 때 서버가 거부한 실적(재고 부족·마감 409 등)이 작업자 모르게 사라지지 않는지
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {makeQueue, retryable} = require('../../main/resources/static/js/common/hb.pop.js');

/** 메모리 저장소 + 가짜 서버(응답을 차례로 꺼낸다: 'ok' | {status, code, message, errors}) */
function fixture(responses) {
    const items = new Map();
    const posted = [];
    let clock = 0;
    const io = {
        put: (it) => { items.set(it.id, structuredClone(it)); return Promise.resolve(); },
        del: (id) => { items.delete(id); return Promise.resolve(); },
        all: () => Promise.resolve([...items.values()].sort((a, b) => a.at - b.at)),
        post: (url, body) => {
            posted.push(body.clientReqId);
            const r = responses.shift();
            return r === 'ok' || r === undefined ? Promise.resolve({data: {ok: true}}) : Promise.reject(r);
        }
    };
    const realNow = Date.now;
    Date.now = () => ++clock;
    const q = makeQueue(io);
    return {q, items, posted, restore: () => { Date.now = realNow; }};
}

const OFFLINE = {status: 0, code: 'NETWORK', message: '끊김'};
const STOCK = {status: 409, code: 'INSUFFICIENT_STOCK', message: '재고가 부족합니다', errors: []};
const CLOSED = {status: 409, code: 'PERIOD_CLOSED', message: '마감된 달입니다', errors: []};

test('다시 보낼 오류: 끊김·타임아웃·5xx·401 / 서버 거부: 400·403·404·409', () => {
    for (const e of [undefined, OFFLINE, {status: 0, code: 'TIMEOUT'}, {status: 500}, {status: 503}, {status: 401}]) {
        assert.equal(retryable(e), true, JSON.stringify(e));
    }
    for (const e of [{status: 400}, {status: 403}, {status: 404}, STOCK]) {
        assert.equal(retryable(e), false, JSON.stringify(e));
    }
});

test('오프라인 확정 → 재고 없음 재전송 → 지우지 않고 거부됨으로 남는다(사유 보관), 다음 건은 계속 보낸다', async () => {
    const f = fixture([OFFLINE, OFFLINE, STOCK, 'ok']);
    try {
        const counts = [];
        f.q.onChange((p, r) => counts.push([p, r]));
        assert.deepEqual(await f.q.send('/r', {clientReqId: 'A', goodQty: '10'}), {queued: true});
        assert.deepEqual(await f.q.send('/r', {clientReqId: 'B', goodQty: '5'}), {queued: true});
        assert.equal(await f.q.count(), 2);
        // 연결 회복: A 는 재고 부족(409) → 거부됨, B 는 보냄
        assert.deepEqual(await f.q.flush(), {sent: 1, rejected: 1});
        assert.deepEqual(f.posted, ['A', 'B', 'A', 'B']);
        assert.equal(await f.q.count(), 0);
        const rej = await f.q.rejected();
        assert.equal(rej.length, 1);
        assert.equal(rej[0].id, 'A');
        assert.equal(rej[0].body.goodQty, '10');
        assert.deepEqual(rej[0].error, {status: 409, code: 'INSUFFICIENT_STOCK', message: '재고가 부족합니다', detail: ''});
        // 거부된 건은 자동 재전송 대상이 아니다(같은 거부를 되풀이하지 않음)
        assert.deepEqual(await f.q.flush(), {sent: 0, rejected: 0});
        assert.deepEqual(counts.at(-1), [0, 1]);
    } finally {
        f.restore();
    }
});

test('작업자가 다시 보내기 → 같은 clientReqId 로, 다시 거부되면 사유 갱신 / 받으면 사라진다', async () => {
    const f = fixture([OFFLINE, CLOSED, CLOSED, STOCK, 'ok']);
    try {
        await f.q.send('/r', {clientReqId: 'A'});
        await f.q.flush();
        assert.equal((await f.q.rejected())[0].error.code, 'PERIOD_CLOSED');
        assert.deepEqual(await f.q.retry('A'), {sent: 0, rejected: 1});
        assert.equal((await f.q.rejected())[0].error.code, 'PERIOD_CLOSED');
        assert.deepEqual(await f.q.retry('A'), {sent: 0, rejected: 1});
        assert.equal((await f.q.rejected())[0].error.code, 'INSUFFICIENT_STOCK');
        assert.deepEqual(await f.q.retry('A'), {sent: 1, rejected: 0});
        assert.deepEqual(f.posted, ['A', 'A', 'A', 'A', 'A']);
        assert.equal((await f.q.rejected()).length, 0);
        assert.equal(f.items.size, 0);
        // 없는 id 다시 보내기는 아무 일 없음
        assert.deepEqual(await f.q.retry('NOPE'), {sent: 0, rejected: 0});
    } finally {
        f.restore();
    }
});

test('버리기는 작업자가 고를 때만, 끊김이면 순서를 지켜 멈춘다, 바로 보낼 때 4xx 는 호출한 쪽 오류', async () => {
    const f = fixture([OFFLINE, OFFLINE, STOCK, OFFLINE, {status: 400, code: 'INVALID_VALUE', message: '값 오류',
        errors: [{field: 'qty', message: '수량 형식'}]}]);
    try {
        await f.q.send('/r', {clientReqId: 'A'});
        await f.q.send('/r', {clientReqId: 'B'});
        // A 거부 → B 는 아직 끊김 → 멈춤(B 남음)
        assert.deepEqual(await f.q.flush(), {sent: 0, rejected: 1});
        assert.equal(await f.q.count(), 1);
        await f.q.discard('A');
        assert.equal((await f.q.rejected()).length, 0);
        assert.equal(await f.q.count(), 1);
        // 바로 보낼 때 400 → 대기열에 남기지 않고 오류(작업자가 화면에서 본다)
        await assert.rejects(f.q.send('/r', {clientReqId: 'C'}), (e) => e.status === 400);
        assert.deepEqual([...f.items.keys()], ['B']);
    } finally {
        f.restore();
    }
});

test('flush 중복 호출은 한 번만 보낸다', async () => {
    const f = fixture([OFFLINE, 'ok']);
    try {
        await f.q.send('/r', {clientReqId: 'A'});
        const [a, b] = await Promise.all([f.q.flush(), f.q.flush()]);
        assert.deepEqual(a, {sent: 1, rejected: 0});
        assert.deepEqual(b, a);
        assert.deepEqual(f.posted, ['A', 'A']);
    } finally {
        f.restore();
    }
});

test('label — 보낸 화면의 요약 글자가 거부·다시 보내기에도 남는다(PDA 창고 건)', async () => {
    const f = fixture([OFFLINE, STOCK, OFFLINE, OFFLINE]);
    try {
        await f.q.send('/api/pop/wh/move', {clientReqId: 'w1', qty: '3'}, {label: '이동 RM-1 3 EA → B-01'});
        assert.equal(f.items.get('w1').label, '이동 RM-1 3 EA → B-01');
        await f.q.flush();                                   // 거부(재고 부족) — 보관
        const [r] = await f.q.rejected();
        assert.equal(r.label, '이동 RM-1 3 EA → B-01');
        await f.q.retry('w1');                               // 다시 대기(끊김) — label 그대로
        assert.equal(f.items.get('w1').label, '이동 RM-1 3 EA → B-01');
        // label 없이 보낸 건(POP 실적)은 null — 화면이 본문으로 그린다
        await f.q.send('/api/pop/procs/1/results', {clientReqId: 'p1', goodQty: '1'});
        assert.equal(f.items.get('p1').label, null);
    } finally {
        f.restore();
    }
});
