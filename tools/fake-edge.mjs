#!/usr/bin/env node
// 가짜 엣지·장비 어댑터 (장비 연동 E0, docs/65 16장) — 실제 PLC·라벨러 대신 /ext/v1/edge/* 를 실제 HTTP 로 쓴다. 의존성 없음(Node 18+ fetch).
//
//   node tools/fake-edge.mjs --key <API 키> --equip EQ1[,EQ2] [--url http://localhost:8090] [--mode edge|direct] [--listen 8088]
//   node tools/fake-collector.mjs --edge …   (같은 것 — 가짜 수집기를 확장한 진입점)
//
// 두 배포 형태를 같은 코드로:
//   --mode edge   (SaaS)   엣지가 바깥으로만 연결: 하트비트·상태를 보내고 명령은 POST /ext/v1/edge/commands/claim 으로 당겨 간다
//   --mode direct (설치형) 장비 어댑터: --listen 포트에서 서버가 미는 POST /commands 를 받는다(설비 연동의 어댑터 주소 = http://127.0.0.1:<포트>)
//   받는 길(상태·명령 결과)은 둘 다 POST /ext/v1/edge/messages 하나.
//
// 하는 일: 설비별 단조 seq(재기동해도 이어짐 — 상태 파일), birth(기동 때 장비 목록·현재값), 하트비트(--hb 초), PackML 순환
// (IDLE → STARTING → EXECUTE → [HELD → EXECUTE] → COMPLETE → IDLE), 소모품(OK → LOW → EMPTY → OK), 저장 후 전달(못 보낸 메시지는 큐에 두고
// 순서대로 다시, 429 면 Retry-After 만큼 기다림), 명령(엣지 자체 허용 목록·인터록 → 쓰기 → 다시 읽기 → 결과, cmdKey 로 한 번만 쓴다).
//
// 장애 주입 --scenario (쉼표로 여럿):
//   dup297      같은 상태 메시지를 297번(상대 메시지 ID 매번 다름)         reorder   seq n+1 을 n 보다 먼저
//   conflict    같은 seq 에 다른 상태                                     gap       seq 하나를 건너뜀(보내지 않음)
//   skew        봉투 sentAt 을 --skew-sec(기본 600) 만큼 뒤로              drop      --drop-sec(기본 30) 동안 끊김(하트비트·전송 없음, 큐에 쌓음)
//   transition  IDLE → EXECUTE(허용 안 된 전이)                           mismatch  명령을 쓰고 다른 값을 다시 읽음(서버가 실패로 판정)
//   future      occurredAt 을 1시간 뒤로(서버가 FUTURE 로 거부)
// --allow SPEED_SP:0:100:IDLE|STOPPED,RUN:bool  엣지 쓰기 허용 목록(태그:하한:상한:상태들). 서버 목록과 따로 — 서버 값을 믿지 않는다
// --seconds n  n 초 뒤 끝(기본 = Ctrl+C 까지) · --once 시나리오만 보내고 끝 · --dry 보내지 않고 출력
// API 키는 사무 화면 "POP 단말 관리" 에서 유형 "수집 장치" 로 등록하면 한 번만 보인다. 키를 이 파일·저장소에 적지 말 것.

import {createServer} from 'node:http';
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

// ------------------------------------------------------------------ 순수 규칙(시험 대상 — src/test/js/fake-edge.test.js)

/** 서버와 같은 전이 허용표(docs/65 16.5) — 가짜 장비가 정상 흐름에서 허용된 전이만 내는지 시험이 본다 */
export const NEXT = {
    IDLE: ['STARTING', 'STOPPED', 'ABORTED'],
    STARTING: ['EXECUTE', 'HELD', 'STOPPED', 'ABORTED'],
    EXECUTE: ['HELD', 'SUSPENDED', 'STOPPED', 'ABORTED', 'COMPLETE'],
    HELD: ['EXECUTE', 'STOPPED', 'ABORTED'],
    SUSPENDED: ['EXECUTE', 'STOPPED', 'ABORTED'],
    STOPPED: ['IDLE', 'ABORTED'],
    ABORTED: ['STOPPED'],
    COMPLETE: ['IDLE', 'STOPPED', 'ABORTED'],
};

export function allowed(from, to) {
    return !from || from === to || (NEXT[from] || []).includes(to);
}

/** 정상 순환의 다음 상태 — r 은 0~1 난수(EXECUTE 에서 가끔 HELD) */
export function nextState(cur, r) {
    switch (cur) {
        case 'IDLE': return 'STARTING';
        case 'STARTING': return 'EXECUTE';
        case 'EXECUTE': return r < 0.25 ? 'HELD' : 'COMPLETE';
        case 'HELD': return 'EXECUTE';
        case 'SUSPENDED': return 'EXECUTE';
        case 'COMPLETE': return 'IDLE';
        case 'STOPPED': return 'IDLE';
        case 'ABORTED': return 'STOPPED';
        default: return 'IDLE';
    }
}

/** --allow 해석: "SPEED_SP:0:100:IDLE|STOPPED,RUN:bool" → {SPEED_SP: {type, min, max, states}} */
export function parseAllow(spec) {
    const out = {};
    for (const part of String(spec || '').split(',').map((s) => s.trim()).filter(Boolean)) {
        const [tag, a, b, states] = part.split(':');
        if (a === 'bool') {
            out[tag.toUpperCase()] = {type: 'BOOL', states: b ? b.split('|') : []};
        } else {
            out[tag.toUpperCase()] = {type: 'NUM', min: Number(a), max: Number(b), states: states ? states.split('|') : []};
        }
    }
    return out;
}

/** 엣지 2차 검사(서버 값을 믿지 않음) — 통과면 null, 아니면 거부 사유 */
export function checkWrite(allow, tag, value, packState) {
    const rule = allow[tag];
    if (!rule) {
        return 'NOT_ALLOWED';
    }
    if (rule.states.length && !rule.states.includes(packState)) {
        return 'INTERLOCK';
    }
    if (rule.type === 'BOOL') {
        return value === 'true' || value === 'false' ? null : 'TYPE';
    }
    const n = Number(value);
    return Number.isFinite(n) && n >= rule.min && n <= rule.max ? null : 'RANGE';
}

/** 저장 후 전달 큐 — 보낸 만큼만 앞에서 뺀다(순서 유지) */
export function makeQueue(initial = []) {
    const items = [...initial];
    return {
        push: (m) => items.push(m),
        peek: (n) => items.slice(0, n),
        drop: (n) => items.splice(0, n),
        size: () => items.length,
        all: () => [...items],
    };
}

// ------------------------------------------------------------------ 실행

export async function main(argv) {
    const args = parseArgs(argv);
    if (args.help || !args.key || !args.equip) {
        console.log(`사용: node tools/fake-edge.mjs --key <API 키> --equip EQ1[,EQ2] [--url http://localhost:8090] [--mode edge|direct]
  [--listen 8088] [--hb 5] [--step 8] [--scenario dup297,reorder,conflict,gap,skew,drop,transition,mismatch,future]
  [--allow SPEED_SP:0:100:IDLE|STOPPED] [--boot <id>] [--seconds n] [--once] [--dry]`);
        return args.help ? 0 : 2;
    }
    const base = String(args.url || 'http://localhost:8090').replace(/\/$/, '');
    const mode = String(args.mode || 'edge').toLowerCase();
    const equips = String(args.equip).split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
    const hbSec = Math.max(Number(args.hb || 5), 1);
    const stepSec = Math.max(Number(args.step || 8), 1);
    const scenarios = new Set(String(args.scenario || '').split(',').map((s) => s.trim()).filter(Boolean));
    const allow = parseAllow(args.allow || 'SPEED_SP:0:100:IDLE|STOPPED|COMPLETE,RUN:bool');
    const bootId = String(args.boot || 'boot-' + Date.now().toString(36));
    const skewMs = scenarios.has('skew') ? Number(args['skew-sec'] || 600) * 1000 : 0;
    const keyTag = createHash('sha256').update(String(args.key)).digest('hex').slice(0, 12);
    const stateFile = String(args['state-file'] || join(tmpdir(), 'yeonos-fake-edge', keyTag + '.json'));

    // 재기동해도 seq 를 잇고(서버 계약 — 설비별 단조), 못 보낸 큐도 이어 보낸다
    const saved = load(stateFile);
    const st = Object.fromEntries(equips.map((e) => [e, saved.equips?.[e] || {seq: 0, pack: 'STOPPED', at: new Date().toISOString(), cons: 'OK'}]));
    const queue = makeQueue(saved.queue || []);
    const tags = {SPEED_SP: '50', RUN: 'false', ...(saved.tags || {})};
    const doneCmds = new Set(saved.doneCmds || []);
    let online = true;
    let retryUntil = 0;
    const log = (...a) => console.log(new Date().toISOString().substring(11, 19), ...a);
    const persist = () => save(stateFile, {equips: st, queue: queue.all(), tags, doneCmds: [...doneCmds].slice(-500)});

    const sentAt = () => new Date(Date.now() - skewMs).toISOString();
    const stateMsg = (eq, pack, extra = {}) => {
        st[eq].seq += 1;
        st[eq].pack = pack;
        // 발생 시각은 설비별로 늘 앞으로(같은 밀리초 두 상태 = 서버가 순서를 가를 수 없어 늦음으로 본다)
        const prev = Date.parse(st[eq].at) || 0;
        st[eq].at = extra.occurredAt || new Date(Math.max(Date.now(), prev + 1)).toISOString();
        return {route: 'EQUIP_STATE', equipCd: eq, seq: st[eq].seq, packState: pack, occurredAt: st[eq].at, msgId: randomUUID(), ...extra};
    };

    async function call(path, body) {
        if (args.dry) {
            log('DRY', path, JSON.stringify(body).slice(0, 300));
            return {status: 200, body: {data: {}}};
        }
        const res = await fetch(base + path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + args.key},
            body: JSON.stringify(body),
        });
        const json = await res.json().catch(() => ({}));
        return {status: res.status, body: json, retryAfter: Number(res.headers.get('retry-after') || 0)};
    }

    /** 큐를 순서대로 보낸다(묶음 200). 429 = Retry-After 만큼 쉼, 연결 실패 = 큐에 그대로 */
    async function flush() {
        if (!online || Date.now() < retryUntil) {
            return;
        }
        while (queue.size()) {
            const batch = queue.peek(200);
            let r;
            try {
                r = await call('/ext/v1/edge/messages', {sentAt: sentAt(), messages: batch});
            } catch (e) {
                log('보내기 실패(큐 유지)', queue.size(), e.message);
                return;
            }
            if (r.status === 429) {
                retryUntil = Date.now() + (r.retryAfter || 5) * 1000;
                log('429 과부하 — 다시', r.retryAfter, '초 뒤');
                return;
            }
            if (r.status >= 400) {
                // 형식 오류는 데드레터로(다시 보내도 같다) — 큐에서 빼고 남긴다
                log('데드레터', r.status, r.body.code || '', batch.length, '건');
                queue.drop(batch.length);
                continue;
            }
            const d = r.body.data || {};
            const bad = (d.items || []).filter((x) => String(x.result).startsWith('INVALID'));
            log(`보냄 ${batch.length} → 저장 ${d.stored ?? '-'} 중복 ${d.duplicates ?? '-'} 충돌 ${d.conflicts ?? '-'} 형식 ${d.invalid ?? '-'}`
                + (bad.length ? ' ' + bad.map((x) => x.result).join(',') : ''));
            queue.drop(batch.length);
        }
        persist();
    }

    async function heartbeat(birth) {
        if (!online) {
            return;
        }
        const body = {
            bootId, sentAt: sentAt(), birth,
            equips: equips.map((eq) => ({
                equipCd: eq, online: true, seq: st[eq].seq || undefined, packState: st[eq].seq ? st[eq].pack : undefined,
                occurredAt: st[eq].seq ? st[eq].at : undefined, consumables: [{cd: 'RIBBON', level: st[eq].cons}],
            })),
        };
        try {
            const r = await call('/ext/v1/edge/heartbeat', body);
            if (r.status === 200 && (r.body.data?.unknown || []).length) {
                log('서버가 모르는 설비(남의 설비·연동 없음):', r.body.data.unknown.join(','));
            }
            if (r.status !== 200) {
                log('하트비트', r.status, r.body.code || '');
            }
        } catch (e) {
            log('하트비트 실패', e.message);
        }
    }

    /** 명령 한 건 — 2차 검사 → 쓰기 → 다시 읽기 → 결과. cmdKey 로 한 번만(서버가 두 번 보내도) */
    function execute(cmd) {
        if (doneCmds.has(cmd.cmdKey)) {
            log('이미 처리한 명령', cmd.cmdKey);
            return;
        }
        doneCmds.add(cmd.cmdKey);
        const eq = String(cmd.equipCd || '').toUpperCase();
        const pack = st[eq]?.pack;
        const now = () => new Date().toISOString();
        const deny = st[eq] ? checkWrite(allow, String(cmd.tagCd).toUpperCase(), String(cmd.value), pack) : 'NOT_MY_EQUIP';
        if (deny) {
            log('명령 거부', cmd.tagCd, cmd.value, deny);
            queue.push({route: 'CMD_RESULT', cmdKey: cmd.cmdKey, phase: 'RESULT', status: 'REJECTED', reason: deny, occurredAt: now()});
            return;
        }
        tags[cmd.tagCd] = String(cmd.value);
        queue.push({route: 'CMD_RESULT', cmdKey: cmd.cmdKey, phase: 'WRITE', ok: true, value: String(cmd.value), occurredAt: now()});
        const read = scenarios.has('mismatch') ? String(Number(cmd.value) + 1) : tags[cmd.tagCd];
        queue.push({route: 'CMD_RESULT', cmdKey: cmd.cmdKey, phase: 'READBACK', readback: read, occurredAt: now()});
        queue.push({route: 'CMD_RESULT', cmdKey: cmd.cmdKey, phase: 'RESULT', status: 'DONE', readback: read, errors: [], occurredAt: now()});
        log('명령 씀', eq, cmd.tagCd, '=', cmd.value, '다시 읽음', read);
    }

    async function claim() {
        if (mode !== 'edge' || !online || args.dry) {
            return;
        }
        try {
            const r = await call('/ext/v1/edge/commands/claim', {max: 10});
            (r.body.data || []).forEach(execute);
        } catch (e) {
            log('명령 당기기 실패', e.message);
        }
    }

    // 설치형 어댑터: 서버가 미는 명령을 받는다(같은 cmdKey 두 번 = 한 번만 씀)
    let server = null;
    if (mode === 'direct') {
        const port = Number(args.listen || 8088);
        server = createServer((req, res) => {
            if (req.method !== 'POST' || !req.url.startsWith('/commands')) {
                res.writeHead(404).end();
                return;
            }
            let raw = '';
            req.on('data', (c) => { raw += c; });
            req.on('end', () => {
                try {
                    execute(JSON.parse(raw));
                    res.writeHead(202).end();
                    flush();
                } catch {
                    res.writeHead(400).end();
                }
            });
        }).listen(port, '127.0.0.1');
        log(`설치형 어댑터: http://127.0.0.1:${port}/commands 에서 명령을 받는다 — 설비 연동 화면의 어댑터 주소`);
    }

    // ---- 시작: birth(장비 목록·현재값) — 처음이면 STOPPED → IDLE 로 시작
    log(`가짜 ${mode === 'edge' ? '엣지(SaaS)' : '어댑터(설치형)'} ${equips.join(',')} boot=${bootId} → ${base}`
        + (scenarios.size ? ' 시나리오 ' + [...scenarios].join(',') : ''));
    for (const eq of equips) {
        if (!st[eq].seq) {
            queue.push(stateMsg(eq, 'IDLE'));
        }
    }
    await heartbeat(true);
    await flush();
    await injectScenarios();
    if (args.once) {
        persist();
        server?.close();
        return 0;
    }

    const endAt = args.seconds ? Date.now() + Number(args.seconds) * 1000 : Infinity;
    let lastStep = Date.now();
    let tick = 0;
    for (;;) {
        await sleep(hbSec * 1000);
        tick++;
        if (Date.now() > endAt) {
            break;
        }
        if (Date.now() - lastStep >= stepSec * 1000) {
            lastStep = Date.now();
            for (const eq of equips) {
                queue.push(stateMsg(eq, nextState(st[eq].pack, Math.random())));
                // 소모품: 여섯 걸음마다 한 단계(OK → LOW → EMPTY → OK)
                if (tick % 6 === 0) {
                    st[eq].cons = {OK: 'LOW', LOW: 'EMPTY', EMPTY: 'OK'}[st[eq].cons];
                }
            }
        }
        await heartbeat(false);
        await claim();
        await flush();
    }
    persist();
    server?.close();
    return 0;

    async function injectScenarios() {
        const eq = equips[0];
        if (scenarios.has('dup297')) {
            const m = stateMsg(eq, nextState(st[eq].pack, 1));
            for (let i = 0; i < 297; i++) {
                queue.push({...m, msgId: randomUUID()});      // 같은 업무 키(equipCd + seq), 메시지 ID 만 매번 다름
            }
            log('dup297: 같은 상태 297번');
        }
        if (scenarios.has('reorder')) {
            const a = stateMsg(eq, nextState(st[eq].pack, 1));
            const b = stateMsg(eq, nextState(st[eq].pack, 1));
            queue.push(b);
            await flush();
            await sleep(3000);                                   // 서버 처리기가 b 를 먼저 처리한 뒤
            queue.push(a);
            log('reorder: seq', b.seq, '을', a.seq, '보다 먼저 — 서버는 늦은 것을 STALE 로');
        }
        if (scenarios.has('conflict')) {
            const m = stateMsg(eq, nextState(st[eq].pack, 1));
            queue.push(m);
            queue.push({...m, packState: 'STOPPED', msgId: randomUUID()});
            log('conflict: seq', m.seq, '에 다른 상태 — 서버는 충돌·알람');
        }
        if (scenarios.has('gap')) {
            st[eq].seq += 1;                                     // 만들기만 하고 보내지 않는다
            queue.push(stateMsg(eq, nextState(st[eq].pack, 1)));
            log('gap: seq', st[eq].seq - 1, '누락');
        }
        if (scenarios.has('transition')) {
            if (st[eq].pack !== 'IDLE') {
                queue.push(stateMsg(eq, st[eq].pack === 'COMPLETE' || st[eq].pack === 'STOPPED' ? 'IDLE' : 'STOPPED'));
                if (st[eq].pack === 'STOPPED') {
                    queue.push(stateMsg(eq, 'IDLE'));
                }
            }
            queue.push({...stateMsg(eq, 'EXECUTE')});
            st[eq].pack = 'IDLE';                                // 실제 장비는 그대로(서버가 거부)
            log('transition: IDLE → EXECUTE(허용 안 됨)');
        }
        if (scenarios.has('future')) {
            queue.push(stateMsg(eq, nextState(st[eq].pack, 1), {occurredAt: new Date(Date.now() + 3600_000).toISOString()}));
            log('future: 1시간 뒤 시각');
        }
        await flush();
        if (scenarios.has('drop')) {
            const sec = Number(args['drop-sec'] || 30);
            online = false;
            log(`drop: ${sec}초 끊김 — 하트비트 없음, 메시지는 큐에`);
            const until = Date.now() + sec * 1000;
            while (Date.now() < until) {
                await sleep(Math.min(stepSec, sec) * 1000);
                for (const e of equips) {
                    queue.push(stateMsg(e, nextState(st[e].pack, Math.random())));
                }
            }
            online = true;
            log('drop: 다시 연결 — 하트비트(현재값) 후 쌓인', queue.size(), '건을 순서대로');
            await heartbeat(false);
            await flush();
        }
        persist();
    }
}

function sleep(ms) {
    return new Promise((ok) => setTimeout(ok, ms));
}

function load(file) {
    try {
        return JSON.parse(readFileSync(file, 'utf8'));
    } catch {
        return {};
    }
}

function save(file, data) {
    try {
        mkdirSync(join(file, '..'), {recursive: true});
        writeFileSync(file, JSON.stringify(data));
    } catch {
        // 상태 파일은 편의 — 못 써도 계속
    }
}

export function parseArgs(list) {
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

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
    main(process.argv.slice(2)).then((code) => process.exit(code || 0), (e) => {
        console.error('실패:', e.message);
        process.exit(1);
    });
}
