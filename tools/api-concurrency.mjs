// 화면 API 동시 요청 측정 (docs/70 2.3) — 로그인 세션 하나로 GET <경로> 를 동시 C 개로 S 초 계속(닫힌 고리). 측정 전용.
//   node tools/api-concurrency.mjs <JSESSIONID> <동시 수> <초> [기본 http://127.0.0.1:8197/api/home/cards]
// 세션 ID 는 이 파일·저장소에 적지 않는다(측정용 DB 의 시험 계정으로 로그인해 얻은 값만).
const [sid, cArg, sArg, urlArg] = process.argv.slice(2);
const C = Number(cArg), S = Number(sArg);
const url = urlArg || 'http://127.0.0.1:8197/api/home/cards';
const lat = []; let ok = 0, err = 0; const codes = {};
const end = performance.now() + S * 1000;
async function worker() {
    while (performance.now() < end) {
        const t = performance.now();
        try {
            const r = await fetch(url, { headers: { Cookie: `JSESSIONID=${sid}` } });
            await r.arrayBuffer();
            codes[r.status] = (codes[r.status] || 0) + 1;
            if (r.status === 200) { ok++; lat.push(performance.now() - t); } else { err++; }
        } catch (e) { err++; codes.net = (codes.net || 0) + 1; }
    }
}
const t0 = performance.now();
await Promise.all(Array.from({ length: C }, worker));
const sec = (performance.now() - t0) / 1000;
lat.sort((a, b) => a - b);
const p = (x) => lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * x))].toFixed(0) : '-';
console.log(JSON.stringify({ concurrency: C, reqPerSec: Math.round(ok / sec), ok, err, codes, p50: p(0.5), p95: p(0.95), p99: p(0.99) }));
