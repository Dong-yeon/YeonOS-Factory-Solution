#!/usr/bin/env node
// 가짜 자동창고 WES (장비 연동 E4, docs/65 23.8·25.5) — 실제 WES·로봇 대신 요청을 받아 라인 → 용기 콜백을 낸다. 의존성 없음(Node 18+).
//
//   설치형(DIRECT): node tools/fake-wes.mjs --listen 8099 --key-file <키 파일> [--url http://localhost:8090] [--scenario normal]
//                   자동창고 마스터 WES 주소 = http://127.0.0.1:8099/wes  (서버가 POST /wes/requests 로 민다)
//   SaaS(EDGE):     node tools/fake-wes.mjs --edge --key-file <키 파일> …   (엣지가 /ext/v1/wes/requests/claim 으로 당기고 /ext/v1/edge/messages 로 올림)
//   대사:           --snapshot 을 주면 큐를 비울 때마다 기억한 용기 전체를 /ext/v1/wes/snapshot 으로 보낸다(조각 --part-size, 기본 50)
//   키는 파일·환경 변수(YEONOS_AGENT_KEY)로만 — 명령줄·셸 기록에 남기지 않는다.
//
// 이 WES 는 자기 재고를 기억한다: 입고에서 STORED 한 용기 내용 → 출고 때 그 용기를 골라 PICKED → HANDED_OVER → COMPLETED.
// 큐는 긴급(priority URGENT) 먼저, 나머지는 들어온 순서(우선순위 칸이 있는 것만 앞으로 — 일반 출고는 칸이 없다, Q-E4-4).
//
// 장애 주입 --scenario:
//   normal       입고 ACCEPTED → IN_PROGRESS → ARRIVED → STORED / 출고 ACCEPTED → PICKED → HANDED_OVER → COMPLETED
//   dup297       입고 STORED 를 297번(상대 메시지 ID 매번 다름 — 현장 실측) → 서버 원문 1행·DUP 296
//   reorder      STORED 뒤에 IN_PROGRESS 를 늦게 → 서버 STALE
//   errors-done  STORED + errors(완료 + 오류 = 실패) → 서버 라인 FAILED, 원장 없음
//   conflict     같은 STORED 를 다른 qty 로 한 번 더 → 서버 CONFLICT
//   stuck        ACCEPTED 만 보내고 멈춤(로봇이 닿지 못한 용기 — 오류 없이 NOT_STARTED) → 서버 오래 머문 요청 경보
//   short        출고를 반만 인계하고 COMPLETED → 서버 라인 SHORT(남은 수량 재요청은 사람)
//   recon-diff   스냅숏에서 첫 칸 수량을 1 늘려 보냄 → 서버 대사 차이 + 용기 격리
//   urgent-mix   (큐 순서 확인용) normal 과 같고, 처리 순서를 로그로 남긴다

import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

// ------------------------------------------------------------------ 순수 규칙(시험 대상 — src/test/js/fake-wes.test.js)

export const SCENARIOS = ['normal', 'dup297', 'reorder', 'errors-done', 'conflict', 'stuck', 'short', 'recon-diff', 'urgent-mix'];

/** 입고 한 라인(용기)에 보낼 콜백 목록 — 서버 키(reqKey·lineNo·ctrCd·status)·내용(qty·errors) 규칙대로 */
export function callbacks(scenario, reqKey, line) {
    const base = (status, extra = {}) => ({reqKey, lineNo: line.lineNo, ctrCd: line.ctrCd, status, msgId: randomUUID(), ...extra});
    const qty = (line.contents || []).reduce((s, c) => s + Number(c.qty || 0), 0);
    switch (scenario) {
        case 'dup297': return Array.from({length: 297}, () => base('STORED', {qty}));
        case 'reorder': return [base('ACCEPTED'), base('STORED', {qty}), base('IN_PROGRESS')];
        case 'errors-done': return [base('STORED', {qty, errors: [{code: 'E17', text: 'shelf blocked'}]})];
        case 'conflict': return [base('STORED', {qty}), base('STORED', {qty: qty + 1})];
        case 'stuck': return [base('ACCEPTED')];
        default:
            if (!SCENARIOS.includes(scenario)) { throw new Error('모르는 시나리오: ' + scenario); }
            return [base('ACCEPTED'), base('IN_PROGRESS'), base('ARRIVED'), base('STORED', {qty})];
    }
}

/** 업무 키 — 서버와 같은 칸(상대 메시지 ID 는 넣지 않는다) */
export function bizKey(cb) {
    return [cb.reqKey, cb.lineNo, cb.ctrCd, cb.status].join('|');
}

/** 긴급 먼저, 나머지는 들어온 순서(안정 정렬) — 우선순위 칸은 긴급에만 있다 */
export function orderQueue(reqs) {
    return reqs.map((r, i) => ({r, i})).sort((x, y) => {
        const ux = x.r.priority === 'URGENT' ? 0 : 1;
        const uy = y.r.priority === 'URGENT' ? 0 : 1;
        return ux - uy || x.i - y.i;
    }).map((x) => x.r);
}

/** 입고 STORED 뒤 WES 가 기억하는 용기 내용 */
export function rememberIn(store, line) {
    store.set(line.ctrCd, (line.contents || []).map((c) => ({slot: c.slot, itemCd: c.itemCd, lotNo: c.lotNo || null, qty: Number(c.qty)})));
}

/**
 * 출고 한 라인 — 기억한 용기에서 그 품목(지정 LOT)을 골라 PICKED·HANDED_OVER, 끝에 COMPLETED. 라인 단위 상태의 용기 칸 = "-".
 * short 면 라인 수량의 반(내림)만 인계. 기억(store)에서 뺀다.
 */
export function planOut(store, scenario, reqKey, line) {
    const out = [];
    const base = (status, ctrCd, extra = {}) => ({reqKey, lineNo: line.lineNo, ctrCd, status, msgId: randomUUID(), ...extra});
    out.push(base('ACCEPTED', '-'));
    let need = scenario === 'short' ? Math.floor(Number(line.qty) / 2) : Number(line.qty);
    for (const [ctrCd, cs] of store) {
        if (need <= 0) { break; }
        let took = 0;
        let slot = null;
        for (const c of cs) {
            if (need <= 0) { break; }
            if (c.itemCd !== line.itemCd || (line.lotNo && c.lotNo !== line.lotNo) || c.qty <= 0) { continue; }
            const t = Math.min(c.qty, need);
            c.qty -= t;
            need -= t;
            took += t;
            slot = slot === null ? c.slot : slot;
        }
        if (took > 0) {
            store.set(ctrCd, cs.filter((c) => c.qty > 0));
            out.push(base('PICKED', ctrCd));
            out.push(base('HANDED_OVER', ctrCd, {qty: took}));
        }
    }
    out.push(base('COMPLETED', '-'));
    return out;
}

/** 기억한 용기 전체를 조각으로 — recon-diff 면 첫 칸 수량을 1 늘린다(대사 차이 주입) */
export function snapshotParts(store, snapKey, partSize, alter) {
    const ctrs = [...store].map(([ctrCd, cs]) => ({ctrCd, contents: cs.map((c) => ({...c}))}));
    if (alter && ctrs.length && ctrs[0].contents.length) { ctrs[0].contents[0].qty += 1; }
    const parts = [];
    for (let i = 0; i < Math.max(1, ctrs.length); i += partSize) { parts.push(ctrs.slice(i, i + partSize)); }
    return parts.map((containers, i) => ({snapKey, partNo: i + 1, lastPart: i === parts.length - 1, containers}));
}

export function parseArgs(list) {
    const o = {url: 'http://localhost:8090', scenario: 'normal', listen: 0, edge: false, keyFile: null, delayMs: 300, snapshot: false, partSize: 50};
    for (let i = 0; i < list.length; i++) {
        const a = list[i];
        const v = () => list[++i];
        switch (a) {
            case '--url': o.url = v(); break;
            case '--key-file': o.keyFile = v(); break;
            case '--scenario': o.scenario = v(); break;
            case '--listen': o.listen = Number(v()); break;
            case '--edge': o.edge = true; break;
            case '--delay-ms': o.delayMs = Number(v()); break;
            case '--snapshot': o.snapshot = true; break;
            case '--part-size': o.partSize = Math.max(1, Number(v())); break;
            case '--key': throw new Error('--key 는 받지 않습니다 — 셸 기록에 남습니다. --key-file 또는 YEONOS_AGENT_KEY 를 쓰세요');
            default: throw new Error('모르는 옵션: ' + a);
        }
    }
    if (!SCENARIOS.includes(o.scenario)) { throw new Error('모르는 시나리오: ' + o.scenario); }
    if (!o.edge && !o.listen) { throw new Error('--listen <포트>(설치형) 또는 --edge(SaaS)'); }
    return o;
}

// ------------------------------------------------------------------ 실행

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function main(argv) {
    const o = parseArgs(argv);
    const key = (o.keyFile ? readFileSync(o.keyFile, 'utf8') : process.env.YEONOS_AGENT_KEY || '').trim();
    if (!key) { throw new Error('키가 없습니다 — --key-file 또는 YEONOS_AGENT_KEY'); }
    const seen = new Set();                                         // 같은 reqKey 를 두 번 처리하지 않는다(서버 멱등 키)
    const store = new Map();                                        // WES 자기 재고(용기 → 칸 내용)
    const queue = [];
    let snapNo = 0;

    async function post(path, body) {
        const res = await fetch(o.url + path, {method: 'POST', headers: {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'},
            body: JSON.stringify(body)});
        const text = await res.text();
        if (res.status === 429) { throw Object.assign(new Error('429'), {retryAfter: Number(res.headers.get('retry-after') || 5)}); }
        if (!res.ok) { throw new Error(path + ' → ' + res.status + ' ' + text.slice(0, 200)); }
        return JSON.parse(text).data;
    }

    async function emit(cb) {
        await sleep(o.delayMs);
        const r = o.edge ? await post('/ext/v1/edge/messages', {sentAt: new Date().toISOString(), messages: [{route: 'WES_CALLBACK', ...cb}]})
            : await post('/ext/v1/wes/callback', cb);
        console.log(`  [${cb.status}] ${cb.ctrCd}${cb.qty !== undefined ? ' ' + cb.qty : ''} → ${JSON.stringify(r).slice(0, 200)}`);
    }

    async function run(req) {
        console.log(`[wes] ${req.reqKey} ${req.type}${req.priority ? ' ' + req.priority : ''} 라인 ${req.lines.length}`);
        for (const line of req.lines) {
            const cbs = req.type === 'OUT' ? planOut(store, o.scenario, req.reqKey, line) : callbacks(o.scenario, req.reqKey, line);
            for (const cb of cbs) { await emit(cb); }
            if (req.type === 'IN' && cbs.some((c) => c.status === 'STORED' && !c.errors)) { rememberIn(store, line); }
        }
    }

    async function drain() {
        while (queue.length) {
            const next = orderQueue(queue)[0];
            queue.splice(queue.indexOf(next), 1);
            await run(next);
        }
        if (o.snapshot || o.scenario === 'recon-diff') {
            const parts = snapshotParts(store, 'FAKE-' + Date.now() + '-' + (++snapNo), o.partSize, o.scenario === 'recon-diff');
            for (const p of parts) { console.log(`[snapshot] ${p.snapKey} #${p.partNo} → ${JSON.stringify(await post('/ext/v1/wes/snapshot', p))}`); }
        }
    }

    function enqueue(req) {
        if (seen.has(req.reqKey)) { console.log(`[wes] ${req.reqKey} 이미 받음 — 무시`); return false; }
        seen.add(req.reqKey);
        queue.push(req);
        return true;
    }

    if (o.edge) {
        console.log(`가짜 WES(SaaS 엣지) · ${o.url} · 시나리오 ${o.scenario}`);
        for (;;) {
            try {
                let any = false;
                for (const req of await post('/ext/v1/wes/requests/claim', {max: 10})) { any = enqueue(req) || any; }
                if (any) { await drain(); }
            } catch (e) {
                console.error('[오류]', e.message);
                await sleep((e.retryAfter || 3) * 1000);
            }
            await sleep(2000);
        }
    }
    let busy = false;
    createServer((req, res) => {
        if (req.method !== 'POST' || !req.url.endsWith('/requests')) { res.writeHead(404).end(); return; }
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
            res.writeHead(202).end();
            try {
                if (enqueue(JSON.parse(body)) && !busy) {
                    busy = true;
                    // 잠깐 모아서 처리 — 그사이 들어온 긴급이 앞으로 간다
                    sleep(o.delayMs).then(drain).catch((e) => console.error('[오류]', e.message)).finally(() => { busy = false; });
                }
            } catch (e) {
                console.error('[오류] 본문', e.message);
            }
        });
    }).listen(o.listen, '127.0.0.1', () => console.log(`가짜 WES(설치형) 127.0.0.1:${o.listen}/wes · 시나리오 ${o.scenario}`));
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
    main(process.argv.slice(2)).catch((e) => { console.error(e.message || e); process.exit(1); });
}
