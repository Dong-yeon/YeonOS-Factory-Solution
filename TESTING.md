# TESTING.md — SmartHappy 테스트 규칙과 기록

> 원칙·체크리스트(A~K) 원본은 dev-playbook `playbook/testing/abuse-testing.md`, 유형별 실제 사례는 같은 폴더 `cases.md`. 여기는 이 프로젝트 전용: 실행 방법·테스트 데이터 규칙·실행 기록.
> 사용자가 허용으로 정한 동작은 "정책"으로 기록하고 버그로 막지 않는다.

## 실행

| 종류 | 방법 |
|---|---|
| 자동 테스트 | `./gradlew check` — Testcontainers 가 일회용 PostgreSQL(postgres:16-alpine)을 띄우고 로컬과 같은 init 스크립트·Flyway 를 적용한다. 개발 DB 를 건드리지 않는다 |
| 앱 수동 확인 | `docker compose up -d --wait` → `./gradlew bootRun` → `http://localhost:8090/actuator/health` |

- **반영 확인**: 서버 코드·매퍼는 재빌드·재시작 후 반영 — 빌드 산출물 타임스탬프가 수정 시각 이후인지 본다. 화면 JS 는 새로고침.
- 사용자가 띄운 서버는 죽이지 않는다. 화면 검증은 겹치지 않는 포트로 띄우고 끝나면 종료한다.
- 한글 JSON 은 파일이나 스크립트로 보낸다(셸 인코딩 400 은 도구 문제).
- 클라우드 세션 컨테이너에서 `check` 도중 Docker 엔진이 죽으면 남은 시험이 한꺼번에 `NoClassDefFoundError`·`DockerClientProviderStrategy` 로 실패한다(코드 원인 아님) → `rm -f /var/run/docker.pid` 후 `dockerd` 를 **백그라운드 작업이 아닌 일반 셸에서** `setsid nohup` 으로 다시 띄우고 `check` 를 다시 돌린다. 오래 걸리는 `check` 는 백그라운드 작업으로(포그라운드 10분 제한) (2026-10-23).
- 클라우드 세션 컨테이너의 Playwright Chromium 은 `LANG` 이 비어 있으면(C 로캘) **한글 내려받기 파일 이름을 "download" 로** 바꾼다 — 서버 헤더(`filename*=UTF-8''…`)·`hb.download` 가 맞아도 그렇다(코드 원인 아님). 내려받기를 확인하는 스크립트는 `LANG=C.UTF-8 LC_ALL=C.UTF-8 node …` 로 돌린다. 사용자 PC 크롬·엣지는 해당 없음 (2026-11-02).
- 다시 띄운 `dockerd` 가 `containerd is still running`·`timeout waiting for containerd to start` 로 곧 멈추면 죽은 프로세스를 가리키는 `/var/run/docker/containerd/containerd.pid` 가 남은 것이다 → 이 파일도 지우고 다시 띄운다 (2026-10-24).

## 이상 사용 체크리스트 A~K (요약 — 원본은 dev-playbook)

클라우드 QA 세션은 이 PC 의 dev-playbook 을 못 본다(docs/24 6장, 결정 A). **QA 세션은 이 절을 기준으로** 해당 항목을 고르고, 보고서 "해 본 것" 표에 항목 글자(A~K)를 붙인다.
세부 기준·사례는 원본 `playbook/testing/abuse-testing.md`·`cases.md` 가 우선 — 이 요약은 docs/24 에서 쓴 범위로 적었으므로, 원본 제목과 다르면 원본에 맞춰 고친다.

| 항목 | 한 줄 요약 |
|---|---|
| A | 화면 크기·배율 — 좁은 창(775·390), 브라우저 배율 125~150%, 버튼 가림·서랍 전환 경계 |
| B | 입력값 경계 — 길이·형식·빈 값·공백만·유니코드(전각·합자·제로폭·NFD)·제어 문자 |
| C | 동시성·연타 — 같은 버전 동시 저장, 버튼 연타, **조건·행을 빠르게 바꿀 때 늦은 응답**이 다른 자리에 그려지는지 |
| D | 기준정보 변경 — 쓰는 중인 기준(품목·공정·코드)을 사용 중지·변경했을 때, 다시 사용(N→Y) |
| E | 권한 — 조회만·저장만·엑셀만 조합, 다른 화면 권한으로 API, **A사로 B사 데이터**, 화면 안 비밀번호 승인 잠금 |
| F | 흐름 우회 — 완료·확정 문서 수정, 상태 요청값 조작, 낡은 화면(rowVer) 저장, 진행 중 상위 기준 변경, 화면 계산 ≠ 서버 판정 |
| G | 파일·번호 — 첨부 대상 바꿔치기·주인 없는 파일, **드롭 위치**·여러 파일, 마지막 번호 삭제 후 재등록 |
| H | 숫자·문자 한계 — 천단위·전각·지수·자릿수, 9999년·계산 오버플로, LIKE 와일드카드, 긴 검색어, 엑셀 수식 주입, NUL |
| I | 세션·브라우저 — 기본 조회 기간, **세션 만료 중 저장**, 뒤로 가기·새로고침 경고, 저장형 XSS |
| J | 입력 부담 — 대표 업무의 누른 횟수·거친 화면 수(기준선을 남긴다) |
| K | 현장 단말 — 실제 단말 크기·누르는 크기·글씨, 키보드 없이, 연타, 두 단말 동시, 순서 실수, 이상값, 세션, 자재 LOT (P2 POP 부터) |

## 테스트 데이터 규칙

- 자동 테스트는 테스트마다 새 회사코드(`TA{n}`, `TB{n}`)를 만들어 서로 간섭하지 않게 한다.
- 로컬 개발 DB 에서 수동 테스트할 때는 회사코드 접두어 `ZT` 를 쓰고 끝나면 지운다.
  - 예외(2026-10-22, 대량 화면 테스트): 테스트 회사는 운영자 콘솔(`/ops`, TOTP)에서만 만들 수 있어 세션이 만들지 못한다 → 회사 `DEMO` 에 **코드·이름 접두어 `ZT-{모듈}-`**(`ZT-MST-`·`ZT-SAL-`·`ZT-MES-` …)로 만들고, 데모 행·다른 접두어 행은 고치지 않는다. 대량 데이터는 목록·엑셀 속도 확인용으로 남겨 두고, 지울 때는 접두어로(`LIKE 'ZT-MST-%' ESCAPE`).
- **실제 샘플·데모 행으로 스모크를 돌리지 않는다**(UPDATE 로 덮어쓴 사고 사례). "있으면 기존 행으로 진행" 분기를 두지 않는다.
- 정리 SQL 의 `LIKE` 에서 `_` 는 와일드카드다 → `ESCAPE` 를 쓴다. 목록 전체를 깨는 데이터(오버플로 값)는 그 케이스 직후 지운다.
- 비밀번호·토큰은 `.env` 에만 둔다(커밋 금지). 테스트 코드의 비밀번호는 컨테이너 전용 값.

## 여러 세션 화면 테스트 (2026-10-22)

모듈별 세션이 같은 로컬 서버(8090)·DB 를 화면으로 동시에 시험할 때. 근거는 2026-10-22 대량 화면 테스트(docs/testing/master.md·mes.md 같은 날 절).

- **동시 3세션까지**(16GB PC). 11개를 한꺼번에 돌리자 메모리가 바닥나 Docker 엔진 → WSL 서비스까지 멈췄고, 직전에 화면이 "저장했습니다"를 보인 거래처 7건이 재시작 뒤 없었다(추정: VM 비정상 종료로 쓰기 유실). 세션마다 브라우저 탭 1개, 서버를 따로 띄우지 않는다, health 감시 루프를 길게 돌리지 않는다, 서버가 죽으면 기다리지 말고 멈추고 보고.
- **로그인은 호스트 단위 쿠키를 공유한다** — 한 세션이 다른 계정으로 로그인(결재자 등)하거나 POP 단말을 활성화(`/api/pop/activate`, 현재 HTTP 세션 무효화)하면 같은 호스트로 연 다른 세션의 admin 로그인이 풀린다. 계정 전환·POP 시험은 다른 세션과 시간을 나누거나 호스트 이름을 나눈다(`localhost:8090` / `127.0.0.1:8090`).
- 세션의 `.env` 읽기는 권한 정책에 막힐 수 있다 → 그 세션 브라우저 창에서 사용자가 로그인. 비밀번호를 메시지로 넘기지 않는다.
- 대량 입력은 화면의 엑셀 올리기(MST_ITEM·MST_CUST·MST_BOM·SAL_ORDER·SYS_USER 등)가 화면 검증을 그대로 거쳐 빠르다. DB 직접 INSERT 는 쓰지 않는다.
- DB 볼륨이 이미 있는데 `.env` 를 새로 만들면 DB 계정 비밀번호는 바뀌지 않는다(`POSTGRES_PASSWORD`·init 스크립트는 볼륨을 처음 만들 때만) → 기동 시 Flyway `28P01`. 데이터를 지키려면 컨테이너 안에서 `ALTER ROLE … PASSWORD`, 버려도 되면 `docker compose down -v`. 개발 계정(admin)은 `LocalDevSeeder` 가 기동 때 `.env` 값으로 맞춘다.

## 기록 위치 (2026-10-22 나눔)

테스트 기록은 모듈별 파일에 시간순으로 덧붙인다 — 병합 때 한 파일에 몰려 충돌하던 것을 없앴다. 예전 문서·주석의 "TESTING.md "P1 4a" 표" 같은 참조는 아래 파일의 같은 제목 절을 보면 된다.

| 모듈 | 파일 | 옛 제목 머리 |
|---|---|---|
| P0 플랫폼·공통 화면 | [docs/testing/platform.md](docs/testing/platform.md) | "기록"(P0 1~9단계)·화면 공통·화면 JS·문구 |
| P1 기준정보 | [docs/testing/master.md](docs/testing/master.md) | P1 |
| P2 MES(영업·생산·POP) | [docs/testing/mes.md](docs/testing/mes.md) | P2 · MES |
| P3 QMS | [docs/testing/qms.md](docs/testing/qms.md) | P3 QMS |
| P4 WMS | [docs/testing/wms.md](docs/testing/wms.md) | P4 · WMS |
| CMS 설비관리 | [docs/testing/cms.md](docs/testing/cms.md) | CMS |
| SRM 구매 | [docs/testing/srm.md](docs/testing/srm.md) | SRM |
| PMS 프로젝트 | [docs/testing/pms.md](docs/testing/pms.md) | PMS |
| PLM 설계 변경 | [docs/testing/plm.md](docs/testing/plm.md) | PLM |
| APS 생산계획 | [docs/testing/aps.md](docs/testing/aps.md) | APS |
| 홈화면 | [docs/testing/home.md](docs/testing/home.md) | 홈화면 |
| 시스템관리 | [docs/testing/sys.md](docs/testing/sys.md) | 시스템관리 · 결재 위임 · 운영자 인증 |

**QA 세션 결과**는 `docs/qa/<날짜>_<주제>.md`(분석), 시험 코드는 `src/test/java/io/smarthappy/qa/`.
