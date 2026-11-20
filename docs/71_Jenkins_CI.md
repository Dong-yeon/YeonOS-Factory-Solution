# 71. Jenkins 자동 빌드 (시스템관리, 2026-11-09)

> 사용자 결정(2026-11-09): "Jenkins 쪽으로 바꿔보자". 지원한 회사가 Jenkins 로 자동 빌드를 하므로 같은 도구로 바꾸고,
> 설계 판단을 면접에서 설명할 수 있게 남긴다. 파일: `Jenkinsfile`, `deploy/ci/jenkins/`(compose·Dockerfile·plugins.txt·JCasC),
> 설치·운영은 `deploy/ci/README.md`. 시험 기록은 `docs/testing/sys.md` 끝 절.

## 1. 결론 먼저

| 항목 | 정한 것 | 이유 한 줄 |
|---|---|---|
| 어디서 | 이 PC(Windows 11 + Docker Desktop)의 Jenkins 컨테이너 1대 | 무료, Actions 사용 시간과 무관 |
| 무엇을 | Multibranch 잡 `yeonos` — `main`·`claude/*` 가지, 저장소 루트 `Jenkinsfile` | 하위 세션 가지와 상위 병합 묶음(`claude/merge-*`)이 같은 규칙으로 검증 |
| 언제 | **폴링** 5분(가지 훑기) — webhook 없음 | PC 를 인터넷에 열지 않는다 |
| 몇 개 동시에 | **실행기 1** + 가지 안 `disableConcurrentBuilds` | 오늘 check 5~8개 동시 → 시험 DB 가 죽어 "연결 거부" 88건(docs/29 7.10). 줄 세우기가 이 작업의 핵심 |
| 검증 내용 | `npm ci` → `./gradlew check -Phb.requireNode=true --no-daemon` → JUnit·JS 보고서 보관(실패해도) | `ci.yml` 과 같은 관문(SBOM·OSV 는 제외 — 7장) |
| 시험 DB | Testcontainers 가 Docker Desktop 소켓으로 **형제 컨테이너**를 띄우고 `host.docker.internal` 로 접속 | 4장 시험 |
| 메모리 | 컨테이너 상한 **4GB**(Jenkins 힙 512m + Gradle 빌드 JVM + 시험 JVM 힙 1536m), 시험 DB 는 상한 밖 형제 컨테이너 | 같은 PC 에서 다른 세션 check 가 메모리 부족으로 죽은 일(2026-11-09) — 6GB 에서 낮춤 |
| 보안 | 127.0.0.1 바인딩, 관리자 1명(JCasC + `.env`), 익명 읽기 없음, GitHub 토큰은 Jenkins 자격증명 저장소에만, 플러그인 9개(+의존성) 버전 고정 | 5장 |

**남는 운영 규칙(결정 질문 Q1)**: Jenkins 는 *자기 안의* 빌드만 줄 세운다. 하위 세션이 자기 worktree 에서 전체 check 를 또 돌리면
동시 실행 문제는 그대로다 → 하위 세션은 **바뀐 범위 시험만** 돌리고 push, 전체 check 는 Jenkins 결과를 병합 근거로 삼는다.

## 2. 구성·트리거

```
GitHub(비공개 Dong-yeon/YeonOS)
   ▲  git ls-remote / fetch (5분마다, HTTPS + 자격증명 github-yeonos)
   │
[yeonos-jenkins 컨테이너]  127.0.0.1:8095 ── 브라우저(이 PC 만)
   │  컨트롤러 = 실행기 1 (빌드도 여기서)
   │  JDK 25(Temurin, 이미지 기본) · Node 22.23.5 · git
   │  /var/jenkins_home  ← 볼륨 yeonos-jenkins-home (잡·빌드 기록·Gradle·npm 캐시)
   │  /var/jenkins_casc  ← deploy/ci/jenkins/casc (읽기 전용)
   │  /var/run/docker.sock ← Docker Desktop 소켓
   ▼
[Docker Desktop VM]  postgres:16-alpine(시험 DB) · testcontainers/ryuk  ← Jenkins 의 "형제" 컨테이너
```

- **폴링을 고른 이유**: webhook 은 GitHub 가 이 PC 로 들어와야 한다(공인 주소·포트 열기·터널). 1인 PC 를 인터넷에 여는 위험이 5분 지연보다 크다.
  Multibranch 의 "주기적 훑기(periodicFolderTrigger 5m)"가 `git ls-remote` 로 가지 끝 커밋을 비교해 바뀐 가지만 빌드한다 — GitHub API 를 쓰지 않아
  API 한도와 무관하고 `github-branch-source` 플러그인(GitHub App·API 토큰 필요)도 필요 없다.
- **가지 거르기**: `main claude/*`(wildcard). `archive/…`, `pr/…` 등은 보지 않는다. 사라진 가지의 잡은 14일·30개까지 남긴다.
- **설정은 코드로**(JCasC + Job DSL): 관리자·권한·실행기 수·잡 정의가 `casc/jenkins.yaml` 에 있다. 화면에서 바꾼 설정은 재시작 때 되돌아간다 —
  Jenkins 를 지웠다 다시 만들어도 같은 상태(자격증명과 빌드 기록만 볼륨에).

## 3. 실행기 수

| 실행기 | 장점 | 단점 | 판단 |
|---|---|---|---|
| **1 (채택)** | 시험 DB·힙이 한 벌만 — 연결 거부·heap 부족 재발 없음. 결과 해석이 단순 | 가지 3개가 몰리면 마지막은 2시간 뒤 | 하위 세션 push 가 하루 수 회 → 충분 |
| 2 | 줄이 반으로 | 이 PC 메모리·CPU 를 다른 세션과 나눔(Jenkins 컨테이너 상한 4GB = check 1개분 — 2로 늘리면 상한도 7~8GB 로). 오늘 실패가 5개 이상 동시였으니 2는 안전권이지만, 다른 세션이 로컬 check 를 돌리면 다시 위험 | 줄이 길어지면(Q2) `numExecutors: 2` 한 줄 |

같은 가지에 커밋이 연달아 오면 `disableConcurrentBuilds()` 가 앞 빌드를 끝까지 돌리고 다음을 돈다(취소하지 않음 — 중간 커밋 결과도 근거가 된다).
멈춘 빌드가 줄을 막지 않게 `timeout 90분`(전체 check 40~45분의 두 배).

## 4. Testcontainers 를 Jenkins 안에서 — 시험으로 확인한 방법

### 4.1 고른 방식: Docker 소켓 공유(docker-outside-of-docker)

| 방식 | 판단 |
|---|---|
| **소켓 공유 `/var/run/docker.sock` (채택)** | Testcontainers 가 Docker Desktop 에 바로 시험 DB 를 띄운다. 이미지에 Docker CLI 도 필요 없다(Testcontainers 는 소켓 API 직접) |
| Docker-in-Docker(`docker:dind`, privileged) | 컨테이너 안에 Docker 를 또 — privileged 필요, 이미지 캐시 이중, Windows 에서 이득 없음 |
| Jenkins 를 Windows 에 직접 설치(서비스) | 소켓 문제는 없지만 설정이 PC 에 흩어지고(서비스 계정·PATH·JDK) 지웠다 다시 만들기 어렵다. 리눅스 컨테이너면 CI 결과가 Actions(ubuntu)·데모 VM 과 같은 OS |

### 4.2 시험 결과(2026-11-09, Docker Desktop 29.9.2)

| # | 확인 | 결과 |
|---|---|---|
| T1 | 컨테이너 안 소켓 권한 | `srw-rw---- 0:0`(root:root 660) → jenkins(uid 1000)는 못 연다. compose `group_add: ["0"]` 로 해결 |
| T2 | 형제 컨테이너 포트로 접속 — `host.docker.internal`(192.168.65.254) | 접속됨 |
| T3 | 같은 시험 — 기본 경로(컨테이너 기본 게이트웨이 172.17.0.1) | 접속됨(Testcontainers 기본 동작도 되긴 한다) |
| T4 | `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` 로 고정 | Docker Desktop 문서화된 이름 — compose 네트워크가 바뀌어도 같다. 채택 |
| T5 | 시험 기반 `PostgresTestBase` 의 init 스크립트 | `withCopyFileToContainer`(API 로 복사)라 **경로 매핑 문제 없음** — 바인드 마운트였으면 Jenkins 볼륨 경로가 VM 에 없어 실패했을 것 |
| T6 | 실제 전체 check | 6장 기록 |

### 4.3 첫 훑기 폭주 막기 — 시험으로 고친 것

GitHub 를 처음 연결하면 첫 훑기가 이미 있는 `claude/*` 가지 32개를 모두 "새 가지"로 보고 빌드를 줄 세운다(45분 × 32 ≈ 24시간).

1. 처음 시도: `basic-branch-build-strategies` 의 `skipInitialBuildOnFirstBranchIndexing`. 로컬 미러 시험에서 **첫 훑기 뒤 새로 push 한 가지도
   "No automatic build triggered"** — 가지마다 첫 발견을 건너뛰는 전략이라 하위 세션의 첫 push 가 검증되지 않는다. 버림(플러그인도 뺌).
2. 채택: `Jenkinsfile` 첫 단계에서 **가지 훑기로 시작된 빌드(`BranchIndexingCause`) + 마지막 커밋이 3일보다 오래됨 → `NOT_BUILT` 로 바로 끝**(체크아웃만, 수 초).
   새 가지 첫 push·새 커밋은 커밋이 최근이라 그대로 돈다. 사람이 "지금 빌드"를 누르면 언제나 돈다.
   시험: 10일 전 커밋 가지 → `NOT_BUILT`(설명 "옛 가지(마지막 커밋 10일 전)"), 방금 만든 가지 → 전 단계 실행, 샌드박스 승인 요청 없음.

## 5. 보안

| 위협 | 대책 |
|---|---|
| 밖에서 Jenkins 화면·API 접근 | 포트를 `127.0.0.1:8095` 에만. 인바운드 에이전트 포트 닫음(`slaveAgentPort: -1`). webhook 없음 |
| 이 PC 다른 사용자·브라우저 확장의 무단 사용 | 관리자 1명(`local` 보안 영역, 가입 끔), **익명 읽기 없음**(시험: 비로그인 `/api/json` → 403), CSRF crumb 켬, "로그인 유지" 끔 |
| 관리자 비밀번호 유출 | 비밀번호는 `deploy/ci/jenkins/.env`(커밋 금지 — 루트 `.gitignore` 의 `.env`)에서 JCasC 가 읽는다. 문서·저장소에 값 없음 |
| GitHub 토큰 유출 | 토큰은 **Jenkins 자격증명 저장소에만**(화면에서 등록, Jenkins 가 암호화해 볼륨에 둠). JCasC·`.env`·Jenkinsfile 에 없다. 권한은 fine-grained PAT **이 저장소 1개 · Contents 읽기만**(또는 읽기 전용 deploy key). Jenkins 는 push·상태 쓰기를 하지 않는다 |
| 플러그인 취약점 | 직접 고른 9개(configuration-as-code·job-dsl·workflow-multibranch·pipeline-model-definition·workflow-durable-task-step·workflow-basic-steps·git·junit·timestamper) + 끌려온 의존성 = 67개, **전부 정확한 버전 고정**(`plugins.txt`). github·docker·blueocean·matrix-auth 는 넣지 않았다. 갱신은 이미지 다시 만들기로만(화면 플러그인 관리자에서 설치하지 않는다) |
| 빌드 스크립트가 Docker 를 장악 | 소켓 = Docker VM root 와 같다. `claude/*` 를 push 할 수 있는 사람(사용자·사용자의 세션)이 곧 이 PC 의 Docker 를 쓸 수 있다는 뜻 — 비공개 저장소·1인이라 받아들인다. 남의 PR·포크는 빌드하지 않는다(plain git 소스, PR 발견 없음). Job DSL 은 JCasC 파일에서만(script security 켬) |
| 빌드가 컨트롤러에서 돈다 | Jenkins 권장은 "컨트롤러 실행기 0 + 에이전트". 1인·관리자 1명·자기 저장소만이라 컨트롤러 1대로 단순화. 사람이 늘거나 외부 코드를 빌드하면 에이전트 컨테이너로 나눈다(Q4) |

## 6. 실제 빌드 기록

| 날짜 | 가지·커밋 | 결과 | 시간 | 비고 |
|---|---|---|---|---|
| 2026-11-09 | 로컬 미러 `claude/jenkins-probe` | SUCCESS | 수 초 | check 단계만 echo 로 바꾼 탐침 — 준비·npm ci·보고서 단계 확인 |
| 2026-11-09 | 로컬 미러 `claude/jenkins-stale`(10일 전 커밋) | NOT_BUILT | 수 초 | 4.3 |
| 2026-11-09 | 로컬 미러 `claude/sys-jenkins` #1·#2 | ABORTED | — | 다른 세션 check 가 몰려 사람이 멈춤(#2 는 Testcontainers 시험 DB 가 Jenkins 안에서 뜨고 :test 진행까지 확인) |
| 2026-11-10 | 볼륨·이미지·컨테이너가 사라져 `docker compose up -d --build` 로 다시 만듦 | — | 약 1분 | JCasC 로 관리자·잡·실행기·플러그인 67개 복원. 볼륨에만 있던 것(자격증명·기록·캐시)은 README 5.6 |
| 2026-11-10 | 로컬 미러 `claude/merge-c` #1 (816d24f9 = main + sys-jenkins + 장비 E1·E2 + APS 3차) | **check 통과**, Jenkins 표시 FAILURE | 60분 43초(Gradle 1시간 0분 28초) | 시험 3,280 · 실패 0 · 건너뜀 15, JS tsc 오류 0 · node 시험 177. 컨테이너 메모리 최대 4,032MiB(상한 4GiB, 쓰기 캐시 포함). FAILURE = post 의 junit 이 1.5GB XML 을 읽다 Jenkins 힙 OOM → 6.1 |
| 2026-11-10 | 로컬 미러 `claude/merge-d` #1 (76e7d8ec = merge-c + QMS 4차 + 공통코드 Q1~Q6 + 보안) | FAILURE(실제 실패 2) | Gradle 1시간 2분 59초 | 3,333 중 실패 2: `ImagePinGuardTest`(이 작업의 Jenkins 이미지 태그만 — 20a0dc9a 로 다이제스트 고정), `CodeOptionGuardTest`(라벨 화면 — 공통코드 세션). post junit OOM 도 겹침 |
| 2026-11-10 | 로컬 미러 `claude/merge-d` #2 (91a813ee = #1 + aad0201e·20a0dc9a·9f12d5dd + 23f74c66) | **SUCCESS** | 46분 55초(Gradle 46분 42초) | Gradle 시험 3,333(통과 3,318 · 건너뜀 15 · 실패 0) + JS node 177, tsc 오류 0. 결과 XML 2.5MB(사본 0.6MB), 작업 공간 정리 뒤 62MB, 빌드 기록 2.3MB. 메모리 최대 docker stats 3,926MiB · **프로세스 RSS 합 3,843MiB** |
| 2026-11-10 | 로컬 미러 `claude/merge-e` #1 (bf727dbe = main + 장비 E3 + APS 4차) | **SUCCESS** | 53분 47초(Gradle 53분 28초) | Jenkins 집계 통과 3,518 · 건너뜀 15 · 실패 0(JS node 시험 포함), tsc 오류 0, XML 2.6MB. 메모리 최대 docker stats 3,975MiB · **RSS 합 3,962MiB**(상한까지 134MiB) |
| 2026-11-10 | 로컬 미러 `claude/merge-g` #1 (2fb3277f = merge-f + QMS 현장 검사 계약 + POP 화면) | FAILURE(실제 실패 2) | 43분 26초(Gradle 42분 53초) | 3,413 중 실패 2 — `IntegrityCheckTest` 둘: 감사 예외 목록이 `RetentionTest` 에는 `aps_mrp_elem/exc/item/peg` 4개가 있고 `AuditCoverage.EXEMPT` 에는 없음(APS 5차 MRP, merge-f 쪽) → 점검 결과 OK 대신 FAIL. tsc 오류 0. RSS 합 최대 3,836MiB, 컨테이너 OOM 없음 |
| 2026-11-10 | 로컬 미러 `claude/merge-g` #2 (e2692012 = #1 + AuditCoverage 맞춤 7dbb8254) | **SUCCESS** | 44분 13초(Gradle 44분 6초) | Jenkins 집계 통과 3,584 · 건너뜀 15 · 실패 0(JS 포함), tsc 오류 0. 메모리 최대 docker stats 3,909MiB(상한까지 187MiB) · RSS 합 3,628MiB(468MiB) |
| 2026-11-12 | 로컬 미러 `claude/merge-h` #1 (27cab4bf = main + 그리드 엑셀 + 코어 확장점 + 모바일 웹앱) | **SUCCESS** | 57분 8초(Gradle 56분 33초) | Jenkins 집계 3,648 · 건너뜀 15 · 실패 0(JS 포함). merge-g 보다 약 13분 길다 — 원인은 재지 않음(추정: 내보내기 API 56개를 부르는 시험 추가) |
| 2026-11-13 | 로컬 미러 `claude/merge-i` #1 (9bdf44ae = main + 메뉴 개편) | **SUCCESS** | 47분 19초(Gradle 46분 58초) | Jenkins 집계 3,652 · 건너뜀 15 · 실패 0(JS 포함). merge-h(57분)보다 10분 짧다 — merge-h 가 길었던 것은 시험 수보다 그때 PC 부하(다른 세션 여럿) 쪽일 수 있다(추정, 재지 않음) |
| 2026-11-16 | 로컬 미러 `claude/merge-j` #1 (cca7d829 = main + 수집 묶음 쓰기) | FAILURE(실제 실패 2) | 60분 15초(Gradle 59분 53초) | 3,659 중 실패 2 — `IntegrityCheckTest` 둘: `cms_equip_inbox` 가 `RetentionTest` 에만 |
| 2026-11-16 | 로컬 미러 `claude/merge-k` #1 (5abd121e = j + 시리얼 구성 + 출하 전 검수 + 의존성) | FAILURE(실제 실패 3) | 97분 21초(Gradle 51분 49초 + 대기) | 3,691 중 실패 3 — 위 2 + `GridSweepQaTest` `/api/production/serial-trace/grid` 기준 응답 400(필수 입력). k #2·l #1 은 고치기 전 커밋이라 사용자가 중지(ABORTED) |
| 2026-11-16 | 로컬 미러 `claude/merge-l` #2 (52dad60c = k + AuditCoverage·GridSweep 시험 수정 + 사용 모듈 + 화면 정리 + 문서 지도) | **SUCCESS** | 57분 24초(Gradle 57분 8초) | Jenkins 집계 3,708 · 건너뜀 15 · 실패 0(JS 포함) |

### 6.1 시험 결과 XML 이 1.5GB — junit 단계 OOM (2026-11-10)

- 원인: Gradle 이 시험별 stdout(Spring 기동·SQL 로그)을 결과 XML 에 CDATA 로 넣는다 — 467개 1.5GB, 가장 큰 `LabelE1Test` 122MB. HTML 보고서도 757MB. Jenkins junit 플러그인은 이 XML 을 **본체 JVM(힙 512m)** 에서 읽는다.
- 고침: post 에서 `deploy/ci/strip-junit-stdout.mjs` 로 `<system-out>`·`<system-err>` 만 뺀 사본(733.6MB → 0.6MB, 6초, testcase 3,280 그대로)을 junit 에 넘김. HTML 보고서는 **실패한 빌드만** 보관, 작업 공간 결과는 cleanup 에서 지움(가지마다 약 2GB).
- (정정) merge-c 의 메모리 4,032MiB 를 파일 캐시 탓으로 추정했으나, merge-d #2 에서 프로세스 RSS 합을 따로 재니 **3,843MiB** — 캐시가 아니라 실제 JVM 사용량이다(6.3).
- 후속 후보(범위 밖, build.gradle 공유 파일): `test { reports.junitXml.includeSystemOutLog = false }` 류로 원본부터 줄이면 로컬 check 디스크도 준다 — 상위 세션 판단.

### 6.2 시험 로그 수준 DEBUG → INFO (9f12d5dd)

- 결과 XML 769MB 중 766MB(99.6%)가 `io.smarthappy` DEBUG 줄 — `TenantAwareTransactionManager`(트랜잭션 시작·참여마다)와 MyBatis SQL·인자. `LabelE1Test` 는 프린터 상태 확인이 시험 DB 에 쌓인 회사 전부를 돌아 약 28,800번 쿼리(122MB).
- `application-test.yml` 을 INFO 로. 자세히 볼 때만 환경 변수 `LOGGING_LEVEL_IO_SMARTHAPPY=DEBUG`. 로그를 잡는 시험(`HomeCardTest` WARN, `ApsDemoFlowTest`·`DemoHistoryFlowTest` 는 수준을 직접 바꿈) 영향 없음 — merge-d #2 전체 통과로 확인.
- 효과(merge-d #1 → #2): 결과 XML 1.5GB → 2.5MB, Gradle 1시간 2분 59초 → 46분 42초(약 −26%, 시험 2건·공통코드 수정 차이는 있음).

### 6.3 메모리 — 상한 4GiB 에 여유 250MB

| 안 | 내용 | 판단 |
|---|---|---|
| A 그대로 | 상한 4g · Jenkins 힙 512m · 시험 힙 1536m | RSS 3,843 → 3,962MiB(merge-d #2 → merge-e) / 4,096MiB — 시험이 조금만 늘어도 컨테이너 OOM kill 위험 |
| **B 상한 5g (추천)** | `mem_limit: 5g` 한 줄 | WSL 상한 10GB 중 Jenkins 5 + 다른 세션 5. 하위 세션이 로컬 전체 check 를 안 돌리는 운영(Q1)이면 충분 |
| C 힙 줄이기 | Jenkins 512m → 384m, Gradle 빌드 JVM(기본 512m) 그대로 | 아껴야 150MB 안팎. 시험 힙 1536m 는 heap space 전례(2026-11-03)로 줄이지 않는다 |

GitHub 실제 연결(토큰 등록 뒤 첫 훑기)은 사용자가 토큰을 등록해야 시험할 수 있다 — 위 시험은 같은 저장소의 **로컬 bare 미러**(`file://`)를 잡 원격으로
잠시 바꿔서 했다(검증 전용 compose 덮어쓰기, 커밋 안 함). 미러와 GitHub 의 차이는 인증뿐이다.

## 7. 대안 비교

| 대안 | 비용 | 줄 세우기 | 인터넷 노출 | 설정을 코드로 | 면접 설명 | 판단 |
|---|---|---|---|---|---|---|
| **Jenkins(채택)** | 0 | 실행기 수로 | 없음(폴링) | JCasC + Jenkinsfile | 지원 회사 도구 그대로 | 채택 |
| GitHub Actions(호스트) | 비공개 저장소 분 단위 과금 — 이미 소진(2026-11-02) | `concurrency` | 없음 | `ci.yml` 있음 | 흔함 | 비용 때문에 꺼 둠. `ci.yml` 은 수동 실행용으로 남긴다 |
| Actions self-hosted runner(이 PC) | 0(자체 실행기는 분 과금 없음) | runner 1개 = 1줄 | 없음(runner 가 바깥으로 긴 폴링) | `ci.yml` 재사용, PR·커밋 화면에 결과가 붙음 | Actions 경험 | 기술적으로는 가장 적은 일. 다만 비공개 저장소 runner 는 GitHub 계정·토큰에 묶인 상시 프로세스이고, 사용자가 **Jenkins 경험**을 원함 |
| 로컬 줄 세우기 스크립트(파일 잠금으로 check 하나씩) | 0 | 잠금 | 없음 | 스크립트 | 약함 | 가장 가볍지만 기록·화면·보고서 보관이 없다 |

- `ci.yml` 의 SBOM(CycloneDX)·OSV 취약점 검사는 Jenkinsfile 에 넣지 않았다 — 첫 판은 "줄 세운 check" 하나에 집중. 필요하면 `cyclonedxBom` 은 같은 gradle 줄에 붙이면 된다(Q5).

## 8. 결정 질문 (사용자)

| # | 질문 | 추천 |
|---|---|---|
| Q1 | 하위 세션 완료 보고의 check 를 "바뀐 범위 시험 + Jenkins 결과"로 바꿀까? (지금 규칙: 하위 세션이 자기 컨테이너에서 전체 check) | **예** — 그래야 동시 실행이 사라진다. 상위 세션 병합 묶음도 `claude/merge-bN` 으로 push 하면 Jenkins 가 같은 줄에서 돈다 |
| Q2 | 실행기 1 → 2? | 1 로 시작, 줄이 하루 넘게 밀리면 2 |
| Q3 | 결과를 어디서 볼까? A 사용자가 Jenkins 화면 / B GitHub 커밋 상태로 올리기(github 플러그인 + `statuses` 쓰기 토큰) / C 세션이 로컬 API 로 읽기(읽기용 계정 필요 → matrix-auth 추가) | **A** 로 시작(토큰 권한 최소). 세션이 직접 결과를 읽어야 하면 C |
| Q4 | 빌드를 에이전트 컨테이너로 나눌까? | 지금은 아니오(5장 마지막 줄) |
| Q5 | SBOM·OSV 도 Jenkins 에서? | 나중에 — 의존성을 바꾸는 작업이 생길 때 |
| Q6 | Actions `ci.yml` 을 지울까? | 남긴다(수동 실행 — PC 가 꺼져 있을 때 비상용) |
| Q7 | 같은 PC 의 다른 저장소도 이 Jenkins 줄(실행기 1)에 세울까, 세운다면 잡을 어떻게 더할까? | 세운다면 **그 저장소의 GitHub 원격 + 그 저장소 전용 읽기 자격증명**으로 Multibranch 잡 하나 더(`file://` 로컬 클론 + `ALLOW_LOCAL_CHECKOUT` 완화는 쓰지 않음). 잡 정의를 YeonOS 밖에 두려면 추가 JCasC 폴더 선택 마운트(`CASC_JENKINS_CONFIG` 여러 경로)는 선택 — 사용자 확인 뒤 후속 작업. **어긋남(사용자 확인 필요)**: 갈래 세션은 "그 저장소는 원격 없는 로컬 전용(사용자 결정)"이라 하고, 상위 세션은 "비공개 GitHub 원격이 있다"고 했다. 로컬 전용이면 대안: ⓐ 같은 compose 네트워크에 **읽기 전용 git 서버 컨테이너**(`git daemon --export-all`, 저장소 폴더 `:ro` 마운트, 포트를 호스트에 열지 않음) → 잡 원격 `git://<이름>/…` — `ALLOW_LOCAL_CHECKOUT` 완화가 필요 없고 노출은 Docker 내부망뿐(인증 없음이라 같은 망 컨테이너는 읽을 수 있음) ⓑ `file://` 마운트 + `ALLOW_LOCAL_CHECKOUT` — 가장 단순하지만 Jenkins 보안 완화. 추천은 ⓐ, 최종 선택은 사용자 |

## 9. 검토 (채택 결과)

- (사용자 검토 뒤 채운다)
