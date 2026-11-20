#!/usr/bin/env node
// 가짜 수집기 (MES P2 6단계, docs/25 4.8 — D7 "스키마 + 수신 API + 가짜 수집기").
// 실제 설비 대신 표준 이벤트(STATE·CONN·PARAM·METER_READING·COUNT)를 실제 HTTP 로 /ext/v1/equip-events 에 보낸다. 의존성 없음(Node 18+ fetch).
//
//   node tools/fake-collector.mjs --key <API 키> --equip EQ1[,EQ2] [--url http://localhost:8090] [--hours 8] [--live]
//
// - 기본(되돌려 보내기): 지금부터 --hours 시간 전까지의 하루치를 시간 순서대로 만들어 보낸다 — 타임라인 데모용.
//   설비마다 생산(PRD) ↔ 대기(SBY) ↔ 고장(UDT, 사유 --reason) 순환, 한 번은 통신 끊김(CONN OFFLINE → ONLINE = UNKNOWN 구간),
//   --skip 번째마다 seq 를 건너뛰어(보내지 않음) 수집 누락(GAP)을 만든다. --param 이름의 파라미터, --meter 계측기의 15분 사용량.
// - --live: 몇 초마다 지금 시각으로 상태를 바꿔 보낸다(Ctrl+C 로 끝).
// API 키는 사무 화면 "POP 단말 관리" 에서 유형 "수집 장치" 로 등록하면 한 번만 보인다. 키를 이 파일·저장소에 적지 말 것.
//
// --edge: 장비 연동 E0(docs/65 16장) 가짜 엣지·어댑터로 — 하트비트·PackML 상태·seq·저장 후 전달·명령(쓰기 → 다시 읽기 → 결과)·장애 주입.
//   node tools/fake-collector.mjs --edge --key <API 키> --equip EQ1 [--mode edge|direct] [--scenario dup297,reorder,…]  (자세한 것은 tools/fake-edge.mjs)

if (process.argv.includes('--edge')) {
    const {main} = await import('./fake-edge.mjs');
    process.exit(await main(process.argv.slice(2).filter((a) => a !== '--edge')));
}

const args = parseArgs(process.argv.slice(2));
if (args.help || !args.key || !args.equip) {
    console.log(`사용: node tools/fake-collector.mjs --key <API 키> --equip EQ1[,EQ2]
  --url <주소>       기본 http://localhost:8090
  --hours <n>        되돌려 보낼 시간(기본 8, 최대 23)
  --step <분>        상태 하나의 평균 길이(기본 20)
  --skip <n>         n 번째 seq 마다 하나 건너뛰기(수집 누락, 기본 40, 0 = 안 함)
  --param <코드>     PARAM 이벤트 파라미터 코드(기본 WELD_CURRENT, 진행 중 공정이 없으면 NO_RUNNING_PROC 로 거부됨)
  --meter <코드>     에너지 계측기 코드(기본 KWH-<첫 설비>)
  --reason <코드>    고장(UDT) 사유 코드(회사 사유 마스터에 있어야 함, 없으면 사유 없이)
  --seq <n>          시작 seq(기본 1)
  --live             지금 시각으로 계속 보내기
  --dry              보내지 않고 이벤트만 출력`);
    process.exit(args.help ? 0 : 2);
}

const url = (args.url || 'http://localhost:8090').replace(/\/$/, '') + '/ext/v1/equip-events';
const equips = String(args.equip).split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
const hours = Math.min(Math.max(Number(args.hours || 8), 1), 23);
const stepMin = Math.max(Number(args.step || 20), 1);
const skipEvery = Number(args.skip ?? 40);
const paramCd = String(args.param || 'WELD_CURRENT');
const meterCd = String(args.meter || 'KWH-' + equips[0]);
const reasonCd = args.reason ? String(args.reason) : null;
const runId = Math.random().toString(36).slice(2, 10);
let seq = Number(args.seq || 1);
let counter = 0;

// 결정적이지 않아도 되는 데모용 난수 — 같은 실행 안에서만 쓴다
const rnd = (a, b) => a + Math.random() * (b - a);
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

function event(type, equipCd, at, metrics, extra = {}) {
    counter += 1;
    const ev = {eventId: `fc-${runId}-${counter}`, type, occurredAt: new Date(at).toISOString(), seq, metrics, ...extra};
    if (equipCd) {
        ev.equipCd = equipCd;
    }
    seq += 1;
    // 수집 누락 흉내: 이 seq 는 만들기만 하고 보내지 않는다
    if (skipEvery > 0 && counter % skipEvery === 0) {
        return null;
    }
    return ev;
}

const state = (equipCd, at, cat, reason) =>
    event('STATE', equipCd, at, [{name: 'state', value: cat}, ...(reason ? [{name: 'reason', value: reason}] : [])]);
const conn = (equipCd, at, online) => event('CONN', equipCd, at, [{name: 'online', value: online}]);

/** 되돌려 보내기용 이벤트 — 시간 순서(설비별 순서가 지켜져야 LATE 가 안 난다) */
function history() {
    const now = Date.now();
    const start = now - hours * 3600_000;
    const out = [];
    for (const [i, eq] of equips.entries()) {
        let t = start + i * 60_000;
        let cat = 'PRD';
        let dropped = false;
        while (t < now - 60_000) {
            out.push(state(eq, t, cat, cat === 'UDT' ? reasonCd : null));
            // 생산 중엔 파라미터 몇 개
            if (cat === 'PRD') {
                for (let k = 1; k <= 2; k++) {
                    const pt = t + k * 4 * 60_000;
                    if (pt < now) {
                        out.push(event('PARAM', eq, pt, [{name: paramCd, value: rnd(8.6, 9.6).toFixed(2)}]));
                        out.push(event('COUNT', eq, pt, [{name: 'count', value: Math.round(rnd(5, 15))}]));
                    }
                }
            }
            const len = rnd(stepMin * 0.5, stepMin * 1.5) * 60_000;
            // 하루에 한 번 통신 끊김(가운데쯤) — 끊긴 동안은 UNKNOWN, 다시 연결 뒤 상태 신호
            if (!dropped && t > start + (now - start) / 2) {
                dropped = true;
                const off = t + len / 2;
                const on = off + rnd(5, 15) * 60_000;
                if (on < now - 60_000) {
                    out.push(conn(eq, off, false));
                    out.push(conn(eq, on, true));
                    t = on + 30_000;
                    cat = 'SBY';
                    continue;
                }
            }
            t += len;
            cat = cat === 'PRD' ? pick(['SBY', 'PRD', 'UDT']) : cat === 'UDT' ? 'SBY' : 'PRD';
        }
    }
    // 에너지 15분 구간(첫 설비 라인 계측기)
    for (let p = start - (start % 900_000) + 900_000; p + 900_000 <= now; p += 900_000) {
        out.push(event('METER_READING', null, p + 900_000, [{name: meterCd, value: rnd(3, 9).toFixed(3), unit: 'KWH'}],
            {periodStart: new Date(p).toISOString()}));
    }
    return out.filter(Boolean).sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));
}

async function post(events) {
    if (args.dry) {
        events.forEach((e) => console.log(JSON.stringify(e)));
        return {accepted: events.length, duplicates: 0, rejected: []};
    }
    const res = await fetch(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + args.key},
        body: JSON.stringify(events),
    });
    const body = await res.json().catch(() => ({}));
    if (res.status !== 202) {
        throw new Error(`HTTP ${res.status} ${body.code || ''} ${body.message || ''}`.trim());
    }
    return body.data;
}

async function replay() {
    const all = history();
    const sum = {accepted: 0, duplicates: 0, rejected: {}};
    for (let i = 0; i < all.length; i += 200) {
        const r = await post(all.slice(i, i + 200));
        sum.accepted += r.accepted;
        sum.duplicates += r.duplicates;
        r.rejected.forEach((x) => { sum.rejected[x.code] = (sum.rejected[x.code] || 0) + 1; });
    }
    console.log(`보냄 ${all.length}건 → 받음 ${sum.accepted} · 중복 ${sum.duplicates} · 거부 ${JSON.stringify(sum.rejected)}`
        + ` (건너뛴 seq ${skipEvery > 0 ? Math.floor(counter / skipEvery) : 0}개 = 수집 누락)`);
}

async function live() {
    const cats = Object.fromEntries(equips.map((e) => [e, 'PRD']));
    console.log(`라이브: ${equips.join(',')} → ${url} (Ctrl+C 로 끝)`);
    for (;;) {
        const evs = [];
        for (const eq of equips) {
            cats[eq] = Math.random() < 0.3 ? pick(['PRD', 'SBY', 'UDT']) : cats[eq];
            evs.push(state(eq, Date.now(), cats[eq], cats[eq] === 'UDT' ? reasonCd : null));
            if (cats[eq] === 'PRD') {
                evs.push(event('PARAM', eq, Date.now(), [{name: paramCd, value: rnd(8.6, 9.6).toFixed(2)}]));
            }
        }
        const r = await post(evs.filter(Boolean));
        console.log(new Date().toISOString(), equips.map((e) => `${e}=${cats[e]}`).join(' '),
            `받음 ${r.accepted} 거부 ${r.rejected.map((x) => x.code).join(',') || '-'}`);
        await new Promise((ok) => setTimeout(ok, Number(args.interval || 5) * 1000));
    }
}

function parseArgs(list) {
    const o = {};
    for (let i = 0; i < list.length; i++) {
        const a = list[i];
        if (!a.startsWith('--')) {
            continue;
        }
        const k = a.slice(2);
        const next = list[i + 1];
        if (next === undefined || next.startsWith('--')) {
            o[k] = true;
        } else {
            o[k] = next;
            i++;
        }
    }
    return o;
}

(args.live ? live() : replay()).catch((e) => {
    console.error('실패:', e.message);
    process.exit(1);
});
