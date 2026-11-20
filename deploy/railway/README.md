# Railway 데모 배포 런북

공개 데모를 Railway(앱 서비스 + PostgreSQL 서비스)에 올리는 절차. 구성 파일은 저장소 루트 `railway.json` → `deploy/railway/Dockerfile`.

## 왜 이렇게 했나

| 결정 | 이유 |
|---|---|
| Railway 기본 빌더 대신 Dockerfile | 기본 시작 명령이 `*/build/libs/*jar`(한 단계 아래)를 찾아, 단일 Gradle 프로젝트의 `build/libs/*.jar` 를 못 찾고 `java` 사용법만 찍고 재시작을 반복했다(2026-11-20). JDK 25 를 이미지로 고정하는 효과도 있다. |
| 마이그레이션은 Railway 배포 전 명령이 아니라 **내 PC 에서 1회** | 배포 전 명령은 앱과 같은 서비스 변수를 받는다. `DB_OWNER_PASSWORD` 를 서비스 변수에 넣으면 운영 앱에 소유자 자격증명이 생겨 `ProdStartupGuard` 가 기동을 막는다(그 가드를 약화시키지 않는다 — 앱은 소유자 자격증명을 갖지 않는다, docs/01 7.1). |
| 앱 계정·소유자 계정을 따로 만든다 | Railway Postgres 가 주는 `postgres` 는 슈퍼유저라 `DatabaseRoleGuard` 가 앱 접속 계정으로 쓰는 것을 막는다(RLS 우회 방지). |

## 1. DB 계정·DB 만들기 (1회, 내 PC)

Railway Postgres 서비스 → Variables 에서 `DATABASE_PUBLIC_URL` 을 복사한다. 비밀번호 3개는 영문·숫자 16자 이상으로 새로 만든다(`openssl rand -hex 24`, 특수문자 금지).

```bash
docker run --rm -it -v "$PWD/deploy/install:/x:ro" postgres:16 \
  psql "<DATABASE_PUBLIC_URL>" -v ON_ERROR_STOP=1 \
  -v owner_pw="'<소유자 비밀번호>'" -v app_pw="'<앱 비밀번호>'" -v backup_pw="'<백업 비밀번호>'" \
  -f /x/init-roles.sql
```

먼저 `select version();` 으로 버전을 본다 — 저장소 기준은 PostgreSQL 16(jOOQ 3.19 줄 고정, docs/43).

## 2. 마이그레이션 (처음 1회 + 스키마가 바뀐 jar 를 배포하기 전마다, 내 PC)

소유자 자격증명은 이 셸에만 둔다. Railway 변수에 넣지 않는다.

```bash
./gradlew bootJar -x test
DB_URL="jdbc:postgresql://<공개 호스트>:<공개 포트>/yeonos" DB_OWNER_USER=yeonos_owner DB_OWNER_PASSWORD="<소유자 비밀번호>" \
  java -jar build/libs/yeonos-0.0.1-SNAPSHOT.jar --spring.profiles.active=migrate
```

공개 호스트·포트는 `DATABASE_PUBLIC_URL` 의 `@` 뒤 부분. 앱은 기동 때 DB 스키마 버전과 jar 버전이 다르면 멈춘다(`ProdStartupGuard`) — 마이그레이션을 잊어도 틀린 상태로 뜨지 않는다.

## 3. 앱 서비스 Variables

| 이름 | 값 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,demo` |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/yeonos` (사설망, Postgres 서비스 이름이 다르면 맞춘다) |
| `DB_APP_USER` | `yeonos_app` |
| `DB_APP_PASSWORD` | 1단계의 앱 비밀번호 |
| `DB_POOL_SIZE` | `5` |
| `DEMO_PASSWORD` | 데모 계정(회사 `DEMO` / `user1`) 비밀번호, 12자 이상 — 로그인 화면에 공개하는 값 |
| `MFA_SECRET_KEY` | `openssl rand -base64 32` 로 **한 번만** 만들고 바꾸지 않는다 |
| `JAVA_TOOL_OPTIONS` | `-Xmx768m` (플랜 메모리에 맞춘다) |

**넣지 말 것**: `DB_OWNER_PASSWORD`, `OPS_BOOTSTRAP_PASSWORD`, `DEV_ADMIN_PASSWORD` — 있으면 운영 가드가 기동을 막는다.

## 4. 서비스 설정

- Settings → Deploy 에 **Custom Start Command 가 적혀 있으면 지운다**(Dockerfile 의 CMD 를 쓰게).
- Networking → Generate Domain. 포트는 기동 로그의 Tomcat 포트(Railway 가 `PORT` 를 주면 그 값, 없으면 8090).

## 알려진 한계

- `TRUSTED_PROXIES` 를 비워 두었다 — Railway 프록시의 주소 범위를 확인하지 못했다. 그래서 앱은 모든 요청의 접속 IP 를 프록시 IP 로 본다: IP 기준 시도 제한(IP 100회/10분)을 방문자 전원이 나눠 쓰고, 접속 로그 IP 가 의미 없다. 데모로는 감수, 범위를 확인하면 사설 대역으로만 넣는다(공인 대역은 가드가 막는다).
- 업로드 파일은 컨테이너의 `/data` 에 쓴다 — 재배포하면 사라진다. 남겨야 하면 Railway Volume 을 `/data` 에 붙인다.
- 이 묶음은 Docker·Railway 없이 만든 것이다. 확인한 것: Dockerfile 이 하는 `bootJar` 빌드(`mobile/` 제외 상태) 성공. 이미지 빌드·기동은 Railway 로그로만 확인된다.
