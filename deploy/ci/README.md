# YeonOS Jenkins 자동 빌드 — 설치·운영 (docs/71)

이 PC(Windows 11 + Docker Desktop)에서 Jenkins 컨테이너 하나가 `main`·`claude/*` 가지를 5분마다 폴링해 `./gradlew check` 를
**한 번에 하나씩** 돌린다. 설계 판단은 [docs/71](../../docs/71_Jenkins_CI.md). 비밀번호·토큰 값은 이 문서에 쓰지 않는다.

| 파일 | 내용 |
|---|---|
| `jenkins/compose.yml` | 컨테이너(127.0.0.1:8095), 볼륨 `yeonos-jenkins-home`, Docker 소켓 |
| `jenkins/Dockerfile` | `jenkins/jenkins:2.580.2-lts-jdk25` + Node 22.23.5(둘 다 다이제스트 고정) + 플러그인. 만든 이미지 이름은 compose 기본 `yeonos-ci-jenkins` |
| `jenkins/plugins.txt` | 플러그인 전부 정확한 버전 |
| `jenkins/casc/jenkins.yaml` | 관리자·권한·실행기 1·Multibranch 잡 `yeonos`(JCasC + Job DSL) |
| `jenkins/.env.example` | `.env`(커밋 금지) 견본 — 관리자 비밀번호 |
| `../../Jenkinsfile` | 빌드 단계 |

## 1. 설치

준비: Docker Desktop 켜짐(WSL2 엔진, 메모리 8GB 이상 권장), 저장소 checkout.

```bash
cd deploy/ci/jenkins
cp .env.example .env
```

`.env` 의 `JENKINS_ADMIN_PASSWORD` 를 16자 이상 무작위 값으로 바꾼다(비밀번호 관리자에 저장). 그다음:

```bash
docker compose up -d --build
```

```bash
docker logs -f yeonos-jenkins
```

`Jenkins is fully up and running` 이 보이면 브라우저로 **http://127.0.0.1:8095/** → 아이디 `admin`(또는 `.env` 의 `JENKINS_ADMIN_ID`) + `.env` 의 비밀번호.
설정 마법사는 나오지 않는다(JCasC 가 관리자·플러그인·잡을 이미 만든다).

## 2. GitHub 토큰 만들기 (사용자가 직접)

권한을 가장 좁게 — Jenkins 는 **읽기만** 한다.

1. GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token
2. Token name `yeonos-jenkins`, Expiration 90일 안팎(만료 전 5.4 절차로 교체)
3. Repository access: **Only select repositories → `Dong-yeon/YeonOS`**
4. Permissions → Repository permissions → **Contents: Read-only**(Metadata: Read-only 는 자동). 그 밖은 모두 No access
5. 만든 토큰은 다음 3장에서 Jenkins 에 한 번 붙여 넣고 끝 — 파일·메모·채팅에 남기지 않는다

> 대안: 저장소 Settings → Deploy keys 에 **읽기 전용** SSH 키(쓰기 허용 끔). 이때 3장의 종류는 "SSH Username with private key",
> `.env` 의 `YEONOS_GIT_URL` 은 `git@github.com:Dong-yeon/YeonOS.git`, 그리고 Jenkins 의 known_hosts 설정이 더 필요하다 — 처음에는 PAT 를 권한다.

## 3. 자격증명 등록 (사용자가 직접, 한 번)

http://127.0.0.1:8095/manage/credentials/store/system/domain/_/newCredentials

| 칸 | 값 |
|---|---|
| Kind | Username with password |
| Scope | Global |
| Username | GitHub 사용자 이름(`Dong-yeon`) |
| Password | 2장의 토큰 |
| **ID** | **`github-yeonos`** (잡이 이 ID 를 찾는다 — 정확히) |
| Description | YeonOS 읽기 전용 PAT (만료일 적어 두기) |

등록 뒤 http://127.0.0.1:8095/job/yeonos/ → **Scan Multibranch Pipeline Now**. 훑기 기록(Scan Multibranch Pipeline Log)에 가지 목록이 나오면 연결 성공.

- 첫 훑기는 `main`·`claude/*` 가지를 모두 새로 본다. 마지막 커밋이 3일보다 오래된 가지는 `NOT_BUILT` 로 수 초 만에 끝나고(Jenkinsfile '준비' 단계),
  최근 가지만 실제 check 를 줄 서서 돈다(가지당 40~45분).
- Multibranch 잡은 JCasC 가 만든다 — **화면에서 잡을 새로 만들지 않는다**(재시작하면 JCasC 정의로 돌아감). 잡을 바꾸려면 `casc/jenkins.yaml` 을 고치고 5.1.

## 4. 쓰는 법

| 하고 싶은 것 | 방법 |
|---|---|
| 검증 받기 | 가지를 origin 에 push 하면 끝. 5분 안에 훑기 → 줄에 선다 |
| 지금 바로 | 잡 → 가지 → **Build Now**(옛 가지 건너뛰기 규칙도 무시하고 돈다) |
| 결과 | 가지 → 빌드 번호 → **Tests**(실패한 시험·stdout), **Artifacts** `build/reports/tests/test/index.html`(Gradle 보고서), `build/reports/js/typecheck.txt` |
| 줄 보기 | 첫 화면 왼쪽 Build Queue — 실행기 1개라 한 번에 하나 |
| 상위 병합 검증 | 병합 묶음을 `claude/merge-bN` 가지로 push → 같은 줄에서 돈다 |

### 4.1 병합 흐름 (하위 세션 → 상위 세션 → main)

1. 하위 세션: 자기 가지(`claude/<모듈>-<주제>`)에 커밋 → origin push. 로컬에서는 **바뀐 범위 시험만**(전체 check 는 Jenkins 가 — 같은 PC 에서 여러 개 동시에 돌면 시험 DB·메모리가 모자란다, docs/71 1장). 보고에 가지 이름 + 커밋 해시
2. 상위 세션: main 위에 묶음을 만들어(병합·재번호) `claude/merge-bN` 으로 push → Jenkins 가 줄에 세워 빌드
3. 병합 근거 = **그 `claude/merge-bN` 빌드의 SUCCESS + 빌드 화면의 커밋 해시가 main 에 올릴 커밋과 같음**. 다른 점이 있으면(빌드 뒤 커밋 추가·충돌 정리) 다시 push 해서 새 빌드를 받는다
4. 사용자 승인 뒤 상위 세션이 그 커밋을 main 에 push → main 빌드가 한 번 더 돈다(같은 트리라 확인용)

전제: GitHub 자격증명 `github-yeonos`(3장)가 등록돼 있어야 Jenkins 가 origin 을 본다. 결과는 사람이 Jenkins 화면에서 본다(세션이 API 로 읽는 방식은 docs/71 Q3).

결과 판정: `SUCCESS` = check 통과, `FAILURE` = 시험·컴파일·JS 검사 실패, `NOT_BUILT` = 옛 가지 폴링 건너뜀, `ABORTED` = 90분 초과 또는 사람이 멈춤.

## 5. 운영

### 5.1 설정 바꾸기

`casc/jenkins.yaml` 을 고친 뒤 → Jenkins 관리 → Configuration as Code → **Reload existing configuration**, 또는 `docker compose restart`.
화면에서 바꾼 시스템 설정은 재시작 때 이 파일 값으로 돌아간다.

### 5.2 로그·디스크

```bash
docker logs --tail 200 yeonos-jenkins
```

```bash
docker system df -v
```

- 볼륨 `yeonos-jenkins-home` 에 빌드 기록(가지마다 20개, 보고서 5개), Gradle 캐시(`.gradle`, 수 GB), npm 캐시가 쌓인다.
- Gradle 캐시가 너무 크면 Jenkins 가 빌드 중이 아닐 때: `docker exec yeonos-jenkins rm -rf /var/jenkins_home/.gradle/caches` (다음 빌드가 다시 받는다).
- 사라진 가지의 잡은 14일 뒤 지워진다.
- 시험 DB 컨테이너는 Testcontainers 의 Ryuk 가 빌드 끝에 지운다. 빌드를 강제로 죽여 남으면 Ryuk 가 약 10초 뒤 정리한다.

### 5.3 업그레이드 (Jenkins·플러그인)

1. `Dockerfile` 의 `jenkins/jenkins:<새 LTS>-lts-jdk25@sha256:…`(와 Node) 태그를 바꾸고 다이제스트를 `docker buildx imagetools inspect 이미지:태그` 의 Digest 로 바꾼다(ImagePinGuardTest — 태그만 쓰면 check 실패)
2. `plugins.txt` 에서 **직접 고른 9개만** 버전을 지우고 의존성 절은 통째로 지운 뒤 `docker compose build`
3. 나온 버전으로 `plugins.txt` 를 다시 쓴다(이미지 안 `/usr/share/jenkins/ref/plugins/*.jpi` 의 `Plugin-Version`) → 차이를 검토해 커밋
4. `docker compose up -d` → 로그에 `SEVERE` 없는지, 잡 훑기·빌드 한 번 확인
5. 플러그인을 **뺐다면** 볼륨에 옛 파일이 남는다: `docker exec yeonos-jenkins sh -c 'rm -rf /var/jenkins_home/plugins/<이름>*'` 뒤 재시작
6. 화면의 플러그인 관리자에서 설치·업데이트하지 않는다(이미지와 어긋난다)

### 5.4 비밀값 교체

- 관리자 비밀번호: `.env` 를 바꾸고 `docker compose up -d`(JCasC 가 기동 때 맞춘다)
- GitHub 토큰: 새 토큰을 만들고 → 자격증명 `github-yeonos` → Update → Password 만 바꿈 → 옛 토큰을 GitHub 에서 Revoke

### 5.5 멈추기·지우기

```bash
docker compose stop
```

빌드 기록·자격증명까지 지우려면(되돌릴 수 없음) `docker compose down -v`.

### 5.6 지워졌을 때 다시 만들기

컨테이너·이미지·볼륨이 모두 없어져도(2026-11-10 실제로 Docker 정리 중 사라짐) `docker compose up -d --build` 한 번으로
관리자·권한·실행기 1·잡 `yeonos`·플러그인 67개가 JCasC 로 그대로 돌아온다(빌드 약 20초 + 기동 약 30초). **사람이 다시 할 것**:

| 항목 | 이유 |
|---|---|
| `.env`(관리자 비밀번호) | 저장소 밖 파일 — 남아 있으면 그대로, 없으면 1장 |
| GitHub 자격증명 `github-yeonos` | 볼륨 안에만 있었다 — 3장 다시(토큰은 새로 발급 권장) |
| 빌드 기록·시험 보고서 | 볼륨과 함께 사라짐 — 되살릴 수 없다. 병합 근거로 쓴 결과는 docs/29·완료 보고에 숫자로 남긴다 |
| Gradle·npm 캐시 | 첫 빌드가 의존성을 다시 받아 몇 분 더 걸린다 |

새 볼륨의 첫 훑기는 모든 가지를 새로 본다 — 최근 커밋 가지는 곧바로 줄에 서므로, 다른 무거운 작업 중이면 3장 자격증명 등록을 미룬다.

## 6. 문제 해결

| 증상 | 원인·조치 |
|---|---|
| 기동 직후 멈추고 로그에 `Failed ConfigurationAsCode.init` · `UnknownAttributesException` | `casc/jenkins.yaml` 의 칸 이름이 이 플러그인 판에 없다. 메시지의 "Available attributes" 대로 고친다(예: 이 판 `crumbIssuer` 는 `standard` 한 단어) |
| 훑기 로그 `Authentication failed` · `Invalid username or token` | 자격증명이 없거나 ID 가 `github-yeonos` 가 아님, 토큰 만료, 저장소 선택 누락(2장 3) |
| 시험이 전부 `Could not find a valid Docker environment` | 소켓 마운트 또는 `group_add: ["0"]` 빠짐. `docker exec yeonos-jenkins ls -ln /var/run/docker.sock` |
| 시험 DB `Connection refused` | 이 PC 에서 다른 Testcontainers check 가 여러 개 돈다(docs/29 7.10). 하위 세션이 로컬 전체 check 를 돌리지 않는지 확인 |
| `Java heap space` | 시험 JVM 힙은 build.gradle(1536m). 컨테이너 한도 `mem_limit: 6g` 와 Docker Desktop 메모리를 같이 본다 |
| 한글 시험 이름 보고서 `Malformed input` | `LC_ALL=C.UTF-8` 이 빠졌다(compose·Jenkinsfile 둘 다 넣어 둠) |
| `./gradlew: Permission denied` | gradlew 가 실행 권한 없이 커밋돼 있다 — Jenkinsfile 은 `sh ./gradlew` 로 부른다 |
| `fatal: detected dubious ownership` | 로컬 경로(`file://`) 원격을 쓸 때만. GitHub 원격에서는 생기지 않는다 |
| 옛 가지가 줄을 막는다 | '준비' 단계 설명에 "옛 가지 … 건너뜀" 이 있어야 정상. 커밋이 최근인 버려진 가지면 GitHub 에서 가지를 지운다 |
| 화면에서 바꾼 설정이 사라졌다 | 정상 — JCasC 가 원본(5.1) |
