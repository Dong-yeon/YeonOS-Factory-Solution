// Jenkins junit 단계용: Gradle 시험 결과 XML 에서 <system-out>·<system-err>(시험별 Spring 로그)만 뺀 사본을 만든다(docs/71 6장).
// 원본은 1.5GB(시험 3,280건, 큰 파일 하나 122MB) — Jenkins 본체가 그대로 읽으면 Java heap space(2026-11-10 merge-c).
// 실패 메시지·스택(<failure>·<error>)과 시험 이름·시간은 그대로 남는다. 로그 전문은 실패 때 보관하는 HTML 보고서에 있다.
// 사용: node deploy/ci/strip-junit-stdout.mjs <입력 폴더> <출력 폴더>
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [src, dst] = process.argv.slice(2);
if (!src || !dst) {
    console.error('사용: node strip-junit-stdout.mjs <입력 폴더> <출력 폴더>');
    process.exit(2);
}
mkdirSync(dst, { recursive: true });
const BLOCK = /<(system-out|system-err)>[\s\S]*?<\/\1>|<(system-out|system-err)\/>/g;
let files = 0, before = 0, after = 0;
for (const name of readdirSync(src)) {
    if (!name.endsWith('.xml')) continue;
    const xml = readFileSync(join(src, name), 'utf8');
    const out = xml.replace(BLOCK, '');
    writeFileSync(join(dst, name), out);
    files++; before += Buffer.byteLength(xml); after += Buffer.byteLength(out);
}
const mb = (n) => (n / 1048576).toFixed(1);
console.log(`junit 사본 ${files}개: ${mb(before)}MB → ${mb(after)}MB (system-out·err 제거)`);
