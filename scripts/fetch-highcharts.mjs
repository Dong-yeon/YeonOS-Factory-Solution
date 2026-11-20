#!/usr/bin/env node
/*
 * Highcharts 내려받기 (docs/61 11장) — 파일은 저장소에 커밋하지 않는다(.gitignore src/main/resources/static/vendor-local/).
 *
 *   node scripts/fetch-highcharts.mjs           # 받기 → 검증 → vendor-local/highcharts/<버전>/ 에 풀기
 *   node scripts/fetch-highcharts.mjs --check   # 이미 받은 파일의 SHA-256 만 대조(받지 않음)
 *
 * - 버전 고정: npm 레지스트리 tarball(내용이 바뀌지 않는 주소) + tarball SHA-256 + 꺼낸 파일마다 SHA-256.
 *   하나라도 어긋나면 아무것도 쓰지 않고 실패한다(공급망 — docs/05, vendor 규칙과 같은 생각).
 * - 버전을 바꿀 때: VERSION·TARBALL_SHA256·FILES 의 해시와 hb.chart.js 의 HC_VERSION 을 같이 고친다(ChartLibraryGuardTest 가 대조).
 * - Node 18+ 만 있으면 된다(의존성 없음, tar 는 아래 몇 줄로 직접 푼다). Windows·Linux 같은 명령.
 * - 라이선스: Highcharts 는 비상업(개인·학습·포트폴리오) 무료, 상업·납품은 유료(Highsoft). 이 저장소 용도는 비상업이다.
 *   납품·SaaS 로 가면 라이선스를 사거나 hb.chart 안쪽만 다른 라이브러리로 바꾼다(화면은 hb.chart 만 부른다).
 */
import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, writeFileSync, existsSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';

const VERSION = '13.1.1';
const TARBALL = `https://registry.npmjs.org/highcharts/-/highcharts-${VERSION}.tgz`;
const TARBALL_SHA256 = 'a5738d8c6cef098b26a661e25f9202c5a756f15c440c9c9b6ea503ac45f66109';
/** tarball 안 경로(package/ 뒤) → SHA-256. 이 파일들만 꺼낸다 */
const FILES = {
    'highcharts.js': 'd8eff087665964775f068b2ebc7d54f23458e7a4dc18c519697365b1800f5732',
    'highcharts-more.js': '44bf22f252be6e12279ce7922d544ee16720a3fe5dc79909edae7eb8ebb4c56f',
    'modules/solid-gauge.js': 'c6baab776f09de61dcdad0d0823c6575f594a853d9352c9055a01710a396e979',
    'modules/accessibility.js': '951eb02055cfc9e61db04eabba6cdfe258c8eb80998209640dea30c0613f7c87',
    'css/highcharts.css': '03251d5e9670970333468997a796890bf9a64abd566a97d0579089b14347a48b',
    'LICENSE.txt': '46db1c6758029ffeb4e66bde22655fa23910ab6f10c366b501bf6cee96b32406',
};

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEST = join(ROOT, 'src/main/resources/static/vendor-local/highcharts', VERSION);

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

/** ustar 를 푼다 — 일반 파일만, 경로 → Buffer */
function untar(tar) {
    const out = new Map();
    let pos = 0;
    let longName = null;
    while (pos + 512 <= tar.length) {
        const h = tar.subarray(pos, pos + 512);
        if (h.every((b) => b === 0)) { break; }
        const str = (a, b) => h.subarray(a, b).toString('utf8').replace(/\0.*$/s, '');
        const size = parseInt(str(124, 136).trim() || '0', 8);
        const type = String.fromCharCode(h[156] || 48);
        const prefix = str(345, 500);
        let name = longName || (prefix ? prefix + '/' : '') + str(0, 100);
        longName = null;
        const body = tar.subarray(pos + 512, pos + 512 + size);
        if (type === 'L') {
            longName = body.toString('utf8').replace(/\0.*$/s, '');
        } else if (type === 'x') {
            const m = /\d+ path=([^\n]*)\n/.exec(body.toString('utf8'));
            if (m) { longName = m[1]; }
        } else if (type === '0' || type === '\0') {
            out.set(name, Buffer.from(body));
        }
        pos += 512 + Math.ceil(size / 512) * 512;
    }
    return out;
}

function check() {
    const bad = [];
    for (const [rel, want] of Object.entries(FILES)) {
        const f = join(DEST, rel);
        if (!existsSync(f)) { bad.push('없음: ' + rel); continue; }
        if (sha256(readFileSync(f)) !== want) { bad.push('체크섬 불일치: ' + rel); }
    }
    return bad;
}

async function fetchAndExtract() {
    console.log(`Highcharts ${VERSION} 받는 중: ${TARBALL}`);
    const res = await fetch(TARBALL);
    if (!res.ok) { throw new Error(`내려받기 실패 HTTP ${res.status}`); }
    const tgz = Buffer.from(await res.arrayBuffer());
    const got = sha256(tgz);
    if (got !== TARBALL_SHA256) { throw new Error(`tarball SHA-256 불일치\n  기대 ${TARBALL_SHA256}\n  실제 ${got}`); }
    const entries = untar(gunzipSync(tgz));
    const picked = [];
    for (const [rel, want] of Object.entries(FILES)) {
        const buf = entries.get('package/' + rel);
        if (!buf) { throw new Error('tarball 안에 없음: ' + rel); }
        if (sha256(buf) !== want) { throw new Error('파일 SHA-256 불일치: ' + rel); }
        picked.push([rel, buf]);
    }
    // 전부 맞을 때만 쓴다
    for (const [rel, buf] of picked) {
        const f = join(DEST, rel);
        mkdirSync(dirname(f), {recursive: true});
        writeFileSync(f, buf);
    }
    console.log(`완료: ${DEST} (${picked.length}개 파일)`);
}

const checkOnly = process.argv.includes('--check');
try {
    if (!checkOnly) { await fetchAndExtract(); }
    const bad = check();
    if (bad.length) {
        console.error('Highcharts 파일 확인 실패:\n  ' + bad.join('\n  ') + '\n  → node scripts/fetch-highcharts.mjs');
        process.exit(1);
    }
    console.log(`Highcharts ${VERSION} 파일 ${Object.keys(FILES).length}개 SHA-256 일치.`);
    console.log('라이선스: 비상업(개인·학습·포트폴리오)만 무료 — 상업·납품 시 Highsoft 라이선스 구매 또는 hb.chart 안쪽 교체(docs/61 11장).');
} catch (e) {
    console.error('실패: ' + (e instanceof Error ? e.message : e));
    process.exit(1);
}
