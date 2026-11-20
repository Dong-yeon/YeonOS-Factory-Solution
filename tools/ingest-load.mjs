// 설비 이벤트 수신 부하 측정 (docs/70 4장) — /ext/v1/equip-events 에 COUNT 이벤트를 정해진 속도로 보내고 처리량·지연을 잰다.
// 의존성 없음(Node 18+ fetch). 측정 전용 — 운영 서버에 쓰지 말 것(원장에 행이 쌓인다).
//
//   node tools/ingest-load.mjs --keys <키1,키2,…> --equips <설비1,설비2,…> [--url http://localhost:8095]
//        [--rate 1000] [--seconds 20] [--batch 50] [--seq] [--inflight 4]
//
// - 장치(키)마다 설비 하나(같은 순서). 장치마다 동시에 보내는 요청은 --inflight 개까지(실제 수집기는 보통 1).
// - --rate = 전체 목표 이벤트/초. 묶음(--batch) 단위로 장치들에 고르게 나눠 보낸다(열린 고리 — 서버가 느리면 밀린 만큼 "못 보냄"으로 센다).
// - --seq: 이벤트에 장치별 seq 를 붙인다(수집 누락 추적 경로까지 — 실제 수집기와 같음).
// - 출력: 보낸·받아들인·중복·거부 수, 실제 이벤트/초, 요청 지연 p50/p95/p99(ms), HTTP 오류 수.
// API 키는 이 파일·저장소에 적지 않는다(측정용 DB 에서 만든 시험 키만).

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
    if (a.startsWith('--')) {
        const next = all[i + 1];
        acc.push([a.slice(2), next === undefined || next.startsWith('--') ? true : next]);
    }
    return acc;
}, []));
if (!args.keys || !args.equips) {
    console.log('사용: node tools/ingest-load.mjs --keys k1,k2 --equips EQ1,EQ2 [--url …] [--rate 1000] [--seconds 20] [--batch 50] [--seq] [--inflight 4]');
    process.exit(2);
}
const url = (args.url || 'http://localhost:8095') + '/ext/v1/equip-events';
const keys = String(args.keys).split(',');
const equips = String(args.equips).split(',');
if (keys.length !== equips.length) {
    console.error('--keys 와 --equips 개수가 같아야 한다');
    process.exit(2);
}
const rate = Number(args.rate || 1000);
const seconds = Number(args.seconds || 20);
const batch = Math.min(500, Number(args.batch || 50));
const inflightMax = Number(args.inflight || 4);
const withSeq = Boolean(args.seq);

const devices = keys.map((key, i) => ({ key, equip: equips[i], seq: 1, inflight: 0 }));
const stats = { sent: 0, accepted: 0, dup: 0, rejected: 0, httpErr: 0, skipped: 0, lat: [] };
const run = Math.random().toString(36).slice(2, 8);
let n = 0;

function events(d, count) {
    const now = new Date().toISOString();
    const list = [];
    for (let i = 0; i < count; i++) {
        const ev = { eventId: `load-${run}-${n++}`, type: 'COUNT', equipCd: d.equip, occurredAt: now, metrics: [{ name: 'goodCount', value: '1' }] };
        if (withSeq) {
            ev.seq = d.seq++;
        }
        list.push(ev);
    }
    return list;
}

async function send(d) {
    if (d.inflight >= inflightMax) {
        stats.skipped += batch;   // 서버가 못 따라옴 — 보내지 않고 센다
        return;
    }
    d.inflight++;
    const body = JSON.stringify(events(d, batch));
    const t = performance.now();
    try {
        const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${d.key}` }, body });
        const ms = performance.now() - t;
        stats.lat.push(ms);
        stats.sent += batch;
        if (res.status !== 202) {
            stats.httpErr++;
            await res.text();
            return;
        }
        const j = await res.json();
        stats.accepted += j.data.accepted;
        stats.dup += j.data.duplicates;
        stats.rejected += j.data.rejected.length;
    } catch (e) {
        stats.httpErr++;
    } finally {
        d.inflight--;
    }
}

const batchesPerSec = rate / batch;
const intervalMs = 1000 / batchesPerSec;
const start = performance.now();
let k = 0;
const pending = [];
await new Promise((resolve) => {
    const tick = () => {
        const elapsed = performance.now() - start;
        if (elapsed >= seconds * 1000) {
            resolve();
            return;
        }
        // 지금까지 보냈어야 할 묶음 수만큼 따라잡기(타이머 오차 보정)
        const due = Math.floor(elapsed / intervalMs) + 1;
        while (k < due) {
            pending.push(send(devices[k % devices.length]));
            k++;
        }
        setTimeout(tick, Math.max(1, Math.min(20, intervalMs)));
    };
    tick();
});
await Promise.all(pending);
const total = (performance.now() - start) / 1000;
const sorted = stats.lat.sort((a, b) => a - b);
const pct = (p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(1) : '-');
console.log(JSON.stringify({
    target: rate, seconds, batch, devices: devices.length, inflight: inflightMax, seq: withSeq,
    sent: stats.sent, accepted: stats.accepted, duplicates: stats.dup, rejected: stats.rejected, httpErrors: stats.httpErr,
    notSent: stats.skipped, eventsPerSec: Math.round(stats.accepted / total),
    latencyMs: { p50: pct(0.5), p95: pct(0.95), p99: pct(0.99) },
}));
