// 의존성 라이선스 확인 (docs/50 S1) — package-lock.json 만 읽는다(설치 불필요).
// 앱에 들어가는 것(runtime) = MIT·Apache-2.0·0BSD 만, 빌드 도구(dev, @capacitor/cli 아래) = 허용 목록의 permissive 만.
import { readFileSync } from 'node:fs';

const RUNTIME = new Set(['MIT', 'Apache-2.0', '0BSD']);
const DEV = new Set([...RUNTIME, 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'BlueOak-1.0.0', 'Unlicense']);

const lock = JSON.parse(readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'));
const bad = [];
const count = {};
for (const [path, p] of Object.entries(lock.packages)) {
    if (!path) continue;
    const lic = p.license || '(없음)';
    const key = (p.dev ? 'dev ' : 'runtime ') + lic;
    count[key] = (count[key] || 0) + 1;
    if (!(p.dev ? DEV : RUNTIME).has(lic)) bad.push(`${path}@${p.version} ${lic}${p.dev ? ' (dev)' : ''}`);
}
console.log(count);
if (bad.length) {
    console.error('허용 밖 라이선스:\n' + bad.join('\n'));
    process.exit(1);
}
