#!/usr/bin/env node
// 가짜 오토라벨러 (장비 연동 E3, docs/65 21장) — 실제 Print & Apply 장비 대신 /ext/v1/labeler/* 를 실제 HTTP 로 쓴다. 의존성 없음(Node 18+).
//
//   node tools/fake-labeler.mjs --key-file <키 파일> --boxes BOX-1,BOX-2 [--url http://localhost:8090] [--scenario normal] [--mode direct]
//   YEONOS_AGENT_KEY=<키> node tools/fake-labeler.mjs …    (키는 파일·환경 변수로만 — 명령줄·셸 기록에 남기지 않는다)
//
// 박스 흐름: 앞단 스캔 → request(기한 안에 답) → 인쇄(PRINTED) → 부착(APPLIED) → 검증 스캔(VERIFY: 붙인 라벨을 다시 읽음) → 판정은 서버.
// --mode direct (설치형) 박스마다 서버에 바로 묻는다.
// --mode edge   (SaaS)   prefetch 로 오늘 박스를 SSCC 와 함께 미리 받고, 현장에서 혼자 답한 뒤 request(answeredLocally)·result 로 올린다.
//                         쓰지 않은 미리 받기는 release(결번 — 다시 쓰지 않음).
//
// 장애 주입 --scenario:
//   normal       정상                         no-read      앞단 못 읽음(빈 값) → 서버 REJECT NO_READ
//   unregistered 등록 안 된 박스 코드          late         ageMs 를 기한보다 크게 → 서버 REJECT LATE(늦은 정답 = 오답)
//   wrong-label  검증 스캔에 다른 박스 SSCC     empty-label  검증 스캔 못 읽음(빈 라벨) → 둘 다 리젝트 → 재라벨(같은 SSCC)
//   print-fail   장비 인쇄 실패 보고(PRINT_FAIL) reject-full 리젝트 라인 만재 → 서버가 정책 지시(WAIT·ALARM·STOP)
//   dup-request  같은 requestId 로 두 번 물음 → 서버가 처음 답 그대로(replay)
// 키는 사무 화면 시스템관리 > 단말·장치에서 "수집 장치"로 등록하면 한 번만 보인다. 키를 이 파일·저장소에 적지 말 것.

import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

// ------------------------------------------------------------------ 순수 규칙(시험 대상 — src/test/js/fake-labeler.test.js)

export const SCENARIOS = ['normal', 'no-read', 'unregistered', 'late', 'wrong-label', 'empty-label', 'print-fail', 'reject-full',
    'dup-request'];

/** 앞단 스캐너가 읽은 값 — 시나리오에 따라 비우거나 바꾼다 */
export function frontScan(scenario, boxCd) {
    if (scenario === 'no-read') { return ''; }
    if (scenario === 'unregistered') { return boxCd + '-X'; }
    return boxCd;
}

/** 장비가 박스를 읽은 뒤 지난 시간(ms) — late 면 기한을 넘긴다 */
export function ageMs(scenario, deadlineMs = 2000) {
    return scenario === 'late' ? deadlineMs + 500 : 50;
}

/** 검증 스캐너가 읽은 값 — GS1-128 심볼 식별자 + AI 00 + SSCC. wrong-label 은 남의 SSCC, empty-label 은 못 읽음 */
export function verifyScan(scenario, sscc, otherSscc) {
    if (scenario === 'empty-label') { return ''; }
    if (scenario === 'wrong-label') { return ']C100' + (otherSscc || '000000000000000000'); }
    return ']C100' + sscc;
}

/** 한 박스에 보낼 결과 단계(서버 결과 API 의 phase) */
export function resultPhases(scenario) {
    if (scenario === 'print-fail') { return [{phase: 'REJECTED', reasonCd: 'PRINT_FAIL'}]; }
    return [{phase: 'PRINTED'}, {phase: 'APPLIED'}, {phase: 'VERIFY'}];
}

/** 서버 답이 다음 동작을 부르는가 — PRINT 만 인쇄, SKIP 은 통과, REJECT 는 리젝트 라인 */
export function nextMove(answer) {
    if (!answer) { return 'REJECT'; }
    return answer.action === 'PRINT' ? 'PRINT' : answer.action === 'SKIP' ? 'PASS' : 'REJECT';
}

export function parseArgs(list) {
    const o = {url: 'http://localhost:8090', scenario: 'normal', mode: 'direct', boxes: [], keyFile: null, prefetch: 20, relabel: true};
    for (let i = 0; i < list.length; i++) {
        const a = list[i];
        const v = () => list[++i];
        switch (a) {
            case '--url': o.url = v(); break;
            case '--key-file': o.keyFile = v(); break;
            case '--scenario': o.scenario = v(); break;
            case '--mode': o.mode = v(); break;
            case '--boxes': o.boxes = v().split(',').map((s) => s.trim()).filter(Boolean); break;
            case '--prefetch': o.prefetch = Number(v()); break;
            case '--no-relabel': o.relabel = false; break;
            case '--key': throw new Error('--key 는 받지 않습니다 — 셸 기록에 남습니다. --key-file 또는 YEONOS_AGENT_KEY 를 쓰세요');
            default: throw new Error('모르는 옵션: ' + a);
        }
    }
    if (!SCENARIOS.includes(o.scenario)) { throw new Error('모르는 시나리오: ' + o.scenario); }
    if (o.mode !== 'direct' && o.mode !== 'edge') { throw new Error('--mode 는 direct|edge'); }
    return o;
}

// ------------------------------------------------------------------ 실행

export async function main(argv) {
    const o = parseArgs(argv);
    const key = (o.keyFile ? readFileSync(o.keyFile, 'utf8') : process.env.YEONOS_AGENT_KEY || '').trim();
    if (!key) { throw new Error('키가 없습니다 — --key-file 또는 YEONOS_AGENT_KEY'); }

    async function call(path, body) {
        const res = await fetch(o.url + '/ext/v1/labeler' + path, {
            method: 'POST', headers: {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'}, body: JSON.stringify(body || {}),
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch { /* 본문 없음 */ }
        if (!res.ok) { throw new Error(path + ' → ' + res.status + ' ' + (json && json.code ? json.code : text.slice(0, 200))); }
        return json && json.data;
    }

    const req = () => 'fl-' + randomUUID().slice(0, 12);
    let lastSscc = null;

    async function finish(reqId, sscc, scenario) {
        for (const p of resultPhases(scenario)) {
            const scanned = p.phase === 'VERIFY' ? verifyScan(scenario, sscc, lastSscc) : undefined;
            const r = await call('/result', {requestId: reqId, phase: p.phase, scanned, reasonCd: p.reasonCd});
            console.log(`  [${p.phase}] → ${r.outcome} · 박스 ${r.boxStatus}${r.reasonCd ? ' · ' + r.reasonCd : ''}`);
            if (r.boxStatus === 'REJECTED') { return false; }
        }
        return true;
    }

    if (o.scenario === 'reject-full') {
        const r = await call('/event', {type: 'REJECT_FULL', code: 'REJECT_LINE_FULL', detail: '리젝트 컨베이어 만재'});
        console.log(`[event] 리젝트 만재 → 서버 지시 ${r.reply}`);
        return;
    }

    if (o.mode === 'direct') {
        for (const boxCd of o.boxes) {
            const reqId = req();
            const a = await call('/request', {requestId: reqId, scanned: frontScan(o.scenario, boxCd), ageMs: ageMs(o.scenario)});
            console.log(`[box ${boxCd}] ${a.action}${a.reasonCd ? ' ' + a.reasonCd : ''}${a.sscc ? ' SSCC ' + a.sscc : ''} (${a.elapsedMs}ms)`);
            if (o.scenario === 'dup-request') {
                const again = await call('/request', {requestId: reqId, scanned: boxCd, ageMs: 10});
                console.log(`  같은 요청 다시 → replay=${again.replay}, 같은 SSCC=${again.sscc === a.sscc}`);
            }
            if (nextMove(a) !== 'PRINT') { continue; }
            const ok = await finish(reqId, a.sscc, o.scenario);
            lastSscc = a.sscc;
            if (!ok && o.relabel) {
                // 리젝트 → 디버터 → 재라벨: 같은 박스, 서버는 같은 SSCC 를 준다
                const r2 = req();
                const a2 = await call('/request', {requestId: r2, scanned: boxCd, ageMs: 50});
                console.log(`  [재라벨] ${a2.action} SSCC ${a2.sscc} (같은 번호=${a2.sscc === a.sscc})`);
                if (nextMove(a2) === 'PRINT') { await finish(r2, a2.sscc, 'normal'); }
            }
        }
        return;
    }

    // --- SaaS 엣지: 미리 받기 → 현장에서 답 → 나중에 올림
    const pre = await call('/prefetch', {max: o.prefetch});
    console.log(`[prefetch] ${pre.length}건 미리 받음`);
    const byBox = new Map(pre.map((p) => [p.boxCd, p]));
    const used = new Set();
    for (const boxCd of o.boxes) {
        const scanned = frontScan(o.scenario, boxCd);
        const hit = byBox.get(scanned);
        const reqId = req();
        if (!hit) {
            // 현장 판정: 못 읽음·미리 받지 않은 박스 → 리젝트(서버에는 나중에 올림)
            const a = await call('/request', {requestId: reqId, scanned, answeredLocally: true, action: 'REJECT',
                reasonCd: scanned ? 'UNREGISTERED' : 'NO_READ'});
            console.log(`[box ${boxCd}] 현장 REJECT → 올림 ${a.action} ${a.reasonCd}`);
            continue;
        }
        used.add(boxCd);
        const a = await call('/request', {requestId: reqId, scanned, answeredLocally: true, action: 'PRINT', sscc: hit.sscc});
        console.log(`[box ${boxCd}] 현장 PRINT SSCC ${hit.sscc} → 올림 ${a.action}`);
        await finish(reqId, hit.sscc, o.scenario);
        lastSscc = hit.sscc;
    }
    const unused = pre.map((p) => p.boxCd).filter((b) => !used.has(b));
    if (unused.length) {
        const r = await call('/release', {boxCds: unused});
        console.log(`[release] 안 쓴 미리 받기 ${r.voided}건 결번(다시 쓰지 않음)`);
    }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
    main(process.argv.slice(2)).catch((e) => { console.error(e.message || e); process.exit(1); });
}
