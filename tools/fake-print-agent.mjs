#!/usr/bin/env node
// 가짜 인쇄 에이전트 (장비 연동 E2, docs/65 19장) — 공장 PC 의 pull 인쇄 에이전트 대신 /ext/v1/print-agent/* 를 실제 HTTP 로 쓴다.
// 의존성 없음(Node 18+ fetch).
//
//   node tools/fake-print-agent.mjs --key-file <키 파일> [--url http://localhost:8090] [--scenario normal] [--seconds 60]
//   YEONOS_AGENT_KEY=<키> node tools/fake-print-agent.mjs …     (키를 명령줄·셸 기록에 남기지 않게 파일·환경 변수로만)
//
// SaaS 서버는 공장 안 프린터로 연결을 열 수 없다 → 에이전트가 바깥으로 붙어 당겨 간다:
//   하트비트(5초, 프린터 상태·odometer) → claim(롱폴링 --wait 초) → 프린터로 보냄 → 결과 보고(SENT → PRINTED)
// 프린터: 기본은 흉내(장 수만큼 odometer 증가, 용지 흉내). --send 면 받은 host:port(9100)로 ZPL 을 실제로 보낸다.
//
// 장애 주입 --scenario:
//   normal             보냄 → 찍힘 보고
//   fail               보내기 실패(FAILED, 사유 CONNECT)
//   paper-out          용지 없음: PRINTER_ERROR(MEDIA_OUT) + 하트비트 오류 비트 → --fix-sec 뒤 용지 넣음 → 이어서 찍힘(odometer)
//   crash-after-claim  가져간 뒤 아무 보고 없이 끝(서버 lease 만료 → 결과 불명, 자동 재인쇄 없음)
//   dup-result         같은 결과를 두 번(서버 DUP)
//   late               lease 를 넘겨 늦게 PRINTED(서버: UNKNOWN → PRINTED 로 받음)
//   odometer-only      결과는 SENT 까지만, 찍힘은 하트비트 odometer 로만(서버 판정식이 PRINTED)
// 키는 사무 화면 시스템관리 > 단말·장치에서 유형 "수집 장치"로 등록하면 한 번만 보인다. 키를 이 파일·저장소에 적지 말 것.

import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {connect} from 'node:net';
import {pathToFileURL} from 'node:url';

// ------------------------------------------------------------------ 순수 규칙(시험 대상 — src/test/js/fake-print-agent.test.js)

export const SCENARIOS = ['normal', 'fail', 'paper-out', 'crash-after-claim', 'dup-result', 'late', 'odometer-only'];

/** 서버와 같은 결과 상태(PrintAgentService.RESULT_STATES) */
export const RESULT_STATES = ['SENT', 'PRINTED', 'PRINTER_ERROR', 'FAILED'];

/**
 * 시나리오 → 이 작업에 보낼 결과 순서. 각 항목 = {state, reasonCd?, delaySec?}. 빈 목록 = 아무 보고 없음
 * @param {string} scenario @param {{leaseSec?:number}} [opt]
 */
export function planResults(scenario, opt = {}) {
    const lease = opt.leaseSec || 60;
    switch (scenario) {
        case 'normal': return [{state: 'SENT'}, {state: 'PRINTED'}];
        case 'fail': return [{state: 'FAILED', reasonCd: 'CONNECT'}];
        case 'paper-out': return [{state: 'SENT'}, {state: 'PRINTER_ERROR', reasonCd: 'MEDIA_OUT'}];
        case 'crash-after-claim': return [];
        case 'dup-result': return [{state: 'SENT'}, {state: 'PRINTED'}, {state: 'PRINTED'}];
        case 'late': return [{state: 'PRINTED', delaySec: lease + 15}];
        case 'odometer-only': return [{state: 'SENT'}];
        default: throw new Error('모르는 시나리오: ' + scenario);
    }
}

/**
 * 흉내 프린터 하나 — 용지가 있으면 받은 장 수만큼 odometer 가 오른다(서버 판정식: 증가량으로 PRINTED).
 * 용지가 없으면 받기만(버퍼) 하고, 용지를 넣으면 밀린 장을 찍는다.
 */
export function makePrinter(start = 1000) {
    let odometer = start;
    let pending = 0;
    let paper = true;
    return {
        receive(labels) {
            if (paper) { odometer += labels; } else { pending += labels; }
        },
        setPaper(on) {
            paper = on;
            if (on) { odometer += pending; pending = 0; }
        },
        state() {
            return {odometer, errors: paper ? [] : ['MEDIA_OUT'], warnings: []};
        },
    };
}

/** 서버 응답의 결과 판정(outcome) 이 에이전트가 기대한 것인지 — DUP·STALE 은 재전송하지 않아도 된다 */
export function settled(outcome) {
    return outcome === 'MOVED' || outcome === 'DUP' || outcome === 'STALE';
}

export function parseArgs(list) {
    const o = {url: 'http://localhost:8090', scenario: 'normal', wait: 10, max: 5, hb: 5, seconds: 0, fixSec: 20, send: false, keyFile: null};
    for (let i = 0; i < list.length; i++) {
        const a = list[i];
        const v = () => list[++i];
        switch (a) {
            case '--url': o.url = v(); break;
            case '--key-file': o.keyFile = v(); break;
            case '--scenario': o.scenario = v(); break;
            case '--wait': o.wait = Number(v()); break;
            case '--max': o.max = Number(v()); break;
            case '--hb': o.hb = Number(v()); break;
            case '--seconds': o.seconds = Number(v()); break;
            case '--fix-sec': o.fixSec = Number(v()); break;
            case '--send': o.send = true; break;
            case '--key': throw new Error('--key 는 받지 않습니다 — 셸 기록에 남습니다. --key-file 또는 YEONOS_AGENT_KEY 를 쓰세요');
            default: throw new Error('모르는 옵션: ' + a);
        }
    }
    if (!SCENARIOS.includes(o.scenario)) { throw new Error('모르는 시나리오: ' + o.scenario); }
    return o;
}

// ------------------------------------------------------------------ 실행

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function main(argv) {
    const o = parseArgs(argv);
    const key = (o.keyFile ? readFileSync(o.keyFile, 'utf8') : process.env.YEONOS_AGENT_KEY || '').trim();
    if (!key) { throw new Error('키가 없습니다 — --key-file 또는 YEONOS_AGENT_KEY'); }
    const bootId = 'fpa-' + randomUUID().slice(0, 8);
    const printers = new Map();          // printerId → 흉내 프린터
    const until = o.seconds > 0 ? Date.now() + o.seconds * 1000 : Infinity;
    let leaseSec = 60;

    async function call(path, body) {
        const res = await fetch(o.url + '/ext/v1/print-agent' + path, {
            method: 'POST',
            headers: {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'},
            body: JSON.stringify(body || {}),
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch { /* 본문 없음 */ }
        if (!res.ok) { throw new Error(path + ' → ' + res.status + ' ' + (json && json.code ? json.code : text.slice(0, 200))); }
        return json && json.data;
    }

    async function heartbeat() {
        const list = [...printers.entries()].map(([id, p]) => ({printerId: id, online: true, ...p.state()}));
        const r = await call('/heartbeat', {bootId, printers: list});
        for (const p of r.printers) {
            if (!printers.has(p.printerId)) { printers.set(p.printerId, makePrinter()); }
        }
        if (r.unknown && r.unknown.length) { console.log('[hb] 서버가 모르는 프린터', r.unknown); }
        return r;
    }

    function sendTcp(host, port, zpl, copies) {
        return new Promise((resolve, reject) => {
            const s = connect({host, port, timeout: 3000}, () => {
                for (let i = 0; i < copies; i++) { s.write(zpl); }
                s.end();
            });
            s.on('close', () => resolve());
            s.on('timeout', () => { s.destroy(); reject(new Error('timeout')); });
            s.on('error', reject);
        });
    }

    async function handle(job) {
        console.log(`[claim] job ${job.jobId} → ${job.printerCd} (${job.labels}장, lease ${job.leaseUntil})`);
        const plan = planResults(o.scenario, {leaseSec});
        const p = printers.get(job.printerId) || makePrinter();
        printers.set(job.printerId, p);
        if (o.scenario === 'paper-out') {
            p.setPaper(false);
            setTimeout(() => { p.setPaper(true); console.log('[printer] 용지 넣음 → 밀린 장 찍힘'); }, o.fixSec * 1000);
        }
        if (o.scenario !== 'fail' && o.scenario !== 'crash-after-claim') {
            if (o.send) {
                try {
                    await sendTcp(job.host, job.port, job.zpl, job.copies);
                } catch (e) {
                    await report(job, {state: 'FAILED', reasonCd: 'CONNECT', detail: String(e.message || e)});
                    return;
                }
            }
            p.receive(job.labels);
        }
        for (const step of plan) {
            if (step.delaySec) { await sleep(step.delaySec * 1000); }
            await report(job, step);
        }
        if (o.scenario === 'crash-after-claim') {
            console.log('[crash] 보고 없이 끝냅니다 — 서버는 lease 만료 뒤 "결과 불명"으로 두고 다시 찍지 않습니다');
            process.exit(0);
        }
    }

    async function report(job, step) {
        const r = await call('/jobs/' + job.jobId + '/result', {state: step.state, reasonCd: step.reasonCd, detail: step.detail,
            occurredAt: new Date().toISOString()});
        console.log(`[result] job ${job.jobId} ${step.state} → ${r.stateCd} (${r.outcome})`);
        if (!settled(r.outcome)) { throw new Error('예상 밖 응답 ' + JSON.stringify(r)); }
    }

    console.log(`가짜 인쇄 에이전트 ${bootId} · ${o.url} · 시나리오 ${o.scenario}`);
    let lastHb = 0;
    while (Date.now() < until) {
        try {
            if (Date.now() - lastHb >= o.hb * 1000) {
                const r = await heartbeat();
                lastHb = Date.now();
                if (r.queued) { console.log(`[hb] 기다리는 작업 ${r.queued}건`); }
            }
            const got = await call('/claim', {max: o.max, waitSec: o.wait});
            for (const job of got) { await handle(job); }
        } catch (e) {
            console.error('[오류]', e.message || e);
            await sleep(3000);          // 서버·망 끊김 — 잠깐 쉬고 다시(에이전트가 서버를 두드리지 않게)
        }
    }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
    main(process.argv.slice(2)).catch((e) => { console.error(e.message || e); process.exit(1); });
}
