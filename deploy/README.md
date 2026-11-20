# YeonOS 설치·운영 런북 (P0 9단계)

> 설계 근거: docs/01 7장(보안 기본값·DB 계정·백업), docs/05(보안·랜섬웨어). 비밀번호·키는 이 문서와 저장소에 쓰지 않는다.

> 공개 데모(Oracle VM + docker compose)는 `deploy/demo/README.md` — 같은 원칙(소유자/앱 계정 분리·migrate 전용 실행)을 compose 로 묶은 것이다.

## 1. 설치 (Linux 기본, Windows 지원)

1. PostgreSQL 16 설치 → 슈퍼유저로 `install/init-roles.sql` 1회 (소유자·앱·백업 계정, 소유자는 슈퍼유저 아님)
2. `postgresql.conf`: `archive_mode = on`, `archive_command`(Linux: pgBackRest, Windows: `backup/windows/backup.ps1` 주석), `archive_timeout = 60`, `listen_addresses` 는 앱 서버만
3. **비밀값은 환경 파일 두 개로**(명령줄에 쓰지 않는다 — 셸 기록·프로세스 목록에 남는다). 키는 설치 때 **한 번** 만들고 바꾸지 않는다(1.1).
   - `/etc/yeonos/migrate.env` — 마이그레이션 실행 전용: `DB_URL`·`DB_OWNER_USER`·`DB_OWNER_PASSWORD`·(첫 설치만) `OPS_BOOTSTRAP_LOGIN`·`OPS_BOOTSTRAP_PASSWORD`
   - `/etc/yeonos/app.env` — 앱 전용: `DB_URL`·`DB_APP_USER`·`DB_APP_PASSWORD`·`OPS_SECRET_KEY`·`MFA_SECRET_KEY`·`OPS_ALLOWED_IPS`·(프록시 뒤) `TRUSTED_PROXIES`. **소유자 비밀번호를 넣지 않는다**(기동 가드가 막는다)
   - 두 파일 다 `chmod 600`, 소유자 = 서비스 계정. Windows 는 서비스 계정만 읽는 폴더 + 서비스 래퍼(WinSW·NSSM)의 환경 설정. 값에 따옴표·`$`·역슬래시를 쓰지 않는다(읽는 도구마다 다르게 해석 — docs/67 F12)
   ```
   umask 077; sudo install -d -m 700 -o yeonos /etc/yeonos
   # 키 두 개 — 한 번만. 서로 다른 값(하나가 새도 다른 경계는 남게)
   printf 'OPS_SECRET_KEY=%s\nMFA_SECRET_KEY=%s\n' "$(openssl rand -base64 32)" "$(openssl rand -base64 32)" | sudo tee -a /etc/yeonos/app.env >/dev/null
   ```
4. **마이그레이션 전용 실행** — 소유자 자격증명은 이 프로세스에만:
   ```
   set -a; . /etc/yeonos/migrate.env; set +a
   java -jar smarthappy.jar --spring.profiles.active=migrate
   ```
   Flyway 적용 + (운영자가 없으면) 첫 운영자 생성 후 끝난다. 웹 서버·작업·기동 가드는 돌지 않는다. 첫 운영자를 만든 뒤 `OPS_BOOTSTRAP_PASSWORD` 줄은 지운다.
5. **앱 실행** — 소유자 자격증명 없이. systemd 는 `EnvironmentFile=/etc/yeonos/app.env` + `ExecStart=/usr/bin/java -jar /opt/yeonos/smarthappy.jar --spring.profiles.active=prod`.
   작업 폴더에 `.env` 를 두지 않는다 — 앱은 작업 폴더의 `.env` 를 읽기 때문에(개발용), 운영에서 있으면 기동 로그에 경고가 남는다(docs/67 F12).
   리버스 프록시(nginx·IIS·Caddy) 뒤에 두면 `TRUSTED_PROXIES=프록시주소/32`(CIDR 목록 또는 정규식)도 준다 — 비우면 X-Forwarded-For 를 믿지 않아
   모든 사용자가 프록시 IP 하나로 보인다(시도 제한·접속 로그·운영자 허용 IP·HSTS). 공인 대역까지 넓히면 기동 가드가 막는다.
   기동 가드가 하나라도 어긋나면 기동을 멈춘다: 앱 계정 슈퍼유저·BYPASSRLS·테이블 소유·**소유자 롤 멤버**, RLS 우회 객체,
   (prod) 앱 Flyway 활성·소유자 비밀번호 보유·**jar/DB 스키마 버전 불일치**·예시 비밀번호·개발 계정·쿠키 secure 꺼짐.
6. 운영자 콘솔 `/ops` (허용 IP 에서만, 밖에서는 404) → 첫 로그인 때 TOTP 등록 → 회사 생성(초기 관리자 임시 비밀번호는 한 번만 보임)

### 1.1 봉인 키(OPS_SECRET_KEY·MFA_SECRET_KEY) 보관 (docs/67 F2)

두 키는 DB 에 저장된 2단계 인증 비밀(운영자 TOTP·회사 관리자 TOTP)을 여는 열쇠다. **키가 바뀌면 그 비밀을 열 수 없어 관리자 전원이 로그인하지 못한다** — 그래서 기동할 때마다 새로 만들면 안 된다.

- **한 번 만들고 그대로**: 재기동·업그레이드·서버 이전 때 같은 값을 쓴다. 서버를 옮기면 환경 파일도 같이 옮긴다.
- **사본은 DB 백업과 다른 곳**: 비밀 보관소(또는 봉인 봉투·금고의 오프라인 매체)에 둔다. DB 백업과 같은 저장소에 두면 백업 하나를 훔친 사람이 MFA 비밀까지 연다(01 7.2 "백업 암호화 키는 DB 서버와 다른 곳"과 같은 원칙).
- **두 키는 서로 다르게**: `MFA_SECRET_KEY` 를 비우면 앱은 운영자 키를 대신 쓴다(되기는 하지만 하나가 새면 두 경계가 같이 무너진다). 분리를 운영에서 강제할지는 정책 질의(docs/67 Q2).
- **확인**: 기동 로그에 MFA·운영자 콘솔 키 관련 경고가 없고, 운영자 콘솔에 로그인이 되면 맞다. 키 형식이 틀리면(Base64 32바이트 아님) 운영 기동 가드가 멈춘다.

**키를 잃었을 때**(사본도 없음):
1. 새 키 두 개를 만들어 환경 파일에 넣고 앱을 재기동한다.
2. 운영자: DB 소유자 계정으로 운영자 행의 TOTP 등록을 지운다(`totp_enabled_yn='N'`, 봉인값 비움) → 다음 로그인 때 다시 등록. 운영 대장에 일시·작업자·사유.
3. 회사 관리자·사용자: 등록된 MFA 를 열 수 없으므로 5.1 의 4번 절차(본인 확인 → 소유자 계정 작업 → 기록·통지)로 대상마다 MFA 등록을 지운다. 사용자가 많으면 회사에 먼저 알리고 회사 단위로 한꺼번에.
4. 원인(키 사본을 왜 잃었나)을 운영 대장에 남기고 보관 절차를 고친다. 키가 **유출**된 경우도 같은 순서 — 새 키 + 전원 재등록(봉인 키 회전 기능은 docs/67 F7, 아직 없음).

## 2. 배포(업그레이드) 순서

1. 백업 성공 확인(운영자 콘솔 "백업 건강 상태" 또는 `last-backup.json`)
2. 새 jar 로 **마이그레이션 전용 실행** → 3. 앱 재시작 (버전 불일치면 기동 거부되므로 순서를 바꿀 수 없다)

## 3. 백업·복구 (3-2-1-1-0)

| 대상 | Linux | Windows |
|---|---|---|
| 전체·차등 백업 | `backup/linux/backup.sh full|diff` (pgBackRest, repo2 = Object Lock 불변 사본) | `backup/windows/backup.ps1` (pg_basebackup + verify, 불변 사본은 오프라인 매체 교대) |
| WAL 연속 보관 | pgBackRest archive-push | `archive_command` 복사(덮어쓰기 금지) |
| 복원 시험 | `backup/linux/restore-test.sh` (일회용 컨테이너) | `backup/windows/restore-test.ps1` (임시 포트) |
| 첨부 파일 | `backup.sh` 가 DB 백업에 이어 `FILE_ROOT` → `FILES_BACKUP_DIR` (rsync, 새 파일만) · 따로 `backup.sh files` | `backup.ps1 -FilesBackupDir …` (robocopy, 새 파일만) · 따로 `-FilesOnly` |
| 첨부 표본 대조 · 다이제스트 반출 | `restore-test.sh` 에 `FILES_BACKUP_DIR`·`DIGEST_EXPORT_DIR` | `restore-test.ps1 -FilesBackupDir … -DigestExportDir …` |

- 스크립트는 DB 에 쓰지 않고 상태 파일(`BACKUP_STATUS_DIR` 의 `last-backup.json`, `last-restore-test.json`)을 남긴다 → 앱이 읽어 경고(백업 26시간·복원 시험 35일 초과, WAL 아카이브 실패·꺼짐). 기동은 막지 않는다.
- 권장 주기: 전체 주 1 · 차등 일 1 · WAL 연속, 복원 시험 SaaS 주 1·설치형 월 1, PITR 시험 분기 1.
- **회사 하나만 복구**: 물리 PITR 은 DB 전체 단위 → 별도 인스턴스에 PITR 후 그 회사 행만 내보내기·가져오기.
- **첨부 파일(docs/67 F8)** — DB 만 돌아오고 도면·성적서가 사라지지 않게. 사본 폴더는 이 서버 계정으로 지울 수 없는 곳(WORM·NAS 스냅샷·Object Lock 버킷 마운트·교대 오프라인 매체)으로.
  - **추가만**: 저장 키가 uuid 라 같은 이름이 다시 쓰이지 않는다 → 원본이 암호화·손상돼도 이미 받은 사본을 덮지 않는다. 지우지도 않는다(앱의 삭제는 `use_yn='N'` 표시뿐).
  - 결과는 `last-files-backup.json` → 백업 건강 상태의 `filesBackup`(26시간 기준). **사본 폴더를 정하지 않으면 FAIL 로 남아 경고가 계속 보인다**(의도 — 첨부 백업이 빠진 것을 숨기지 않는다).
  - 복원 시험이 복원본 `sys_file` 무작위 20건(`FILE_SAMPLE`)의 SHA-256 을 사본과 대조한다. 하나라도 다르면 복원 시험 FAIL.
  - 복원하면: DB PITR 시점과 같거나 늦은 첨부 사본을 `FILE_ROOT` 로 되돌린다(사본이 더 많은 것은 문제없음 — DB 에 없는 키는 쓰이지 않는다).
- **로그 다이제스트 반출(05 D6)** — 복원 시험이 복원본의 `sys_log_digest` 를 `DIGEST_EXPORT_DIR/log-digest-YYYYMMDD.tsv` 로 남긴다(같은 날 다시 돌리면 건너뜀). 불변 저장소 쪽 폴더를 준다. 주기는 복원 시험 주기(SaaS 주 1·설치형 월 1)라 매일 반출은 아니다 — 매일이 필요하면 복원 시험을 매일 돌린다.

## 4. 장애 공지

`STATUS_FILE`(기본 `./data/status.json`)에 `{"status":"maintenance","messageKo":"…","messageEn":"…","updatedAt":"…"}` → `/status.json` 으로 공개(허용 값·길이만 다시 만들어 내보냄). 정상이면 파일을 지운다.

### 4.1 현장 단말 APK 놓기 (docs/50 2장)

`PDA_APK_DIR`(기본 `./data/pda`, 저장소 밖) 에 **두 파일만** 놓는다. 다른 이름은 읽지 않는다.

1. `yeonos-pda.apk` — 빌드 머신에서 서명한 APK 그대로(서명 키·keystore 는 이 서버에 두지 않는다).
2. `release.json` — `{"version":"1.2.0","minVersion":"1.1.0","releasedAt":"2026-11-01","notesKo":"…","notesEn":"…"}`. 버전은 `숫자.숫자.숫자`, `minVersion` ≤ `version`, 노트는 각 500자 이하. 하나라도 틀리면 다운로드 화면이 "배포 파일 설정 오류" 를 보이고 내려받기를 막는다.
3. 바꿀 때는 같은 디렉터리에 임시 이름으로 복사한 뒤 **이름 바꾸기**(`mv`·`Move-Item`)로 교체한다 — 복사 중인 반쪽 파일을 내려주지 않게. 심볼릭 링크는 거부된다.
4. 확인: 화면 "현장 단말 앱"(시스템 메뉴)의 크기·SHA-256 이 빌드 산출물과 같은지, `/pda/version.json` 이 세 칸(`version`·`minVersion`·`releasedAt`)만 보이는지.

`minVersion` 은 껍데기의 플러그인 계약이 깨질 때만 올린다(docs/50 V4) — 올리면 그보다 낮은 껍데기는 웹을 열지 않고 "업데이트 필요" 만 보인다.

## 5. 고객 지원 (지원 세션)

운영자는 회사의 업무 데이터·로그 원문을 **회사 관리자가 허락한 지원 세션 안에서만** 본다(화면 "지원 세션 허락": 운영자·범위 LOGS/FEEDBACK·최대 7일·사유, 언제든 회수). 운영자가 한 일은 회사 관리자가 같은 화면의 "운영 기록"으로 본다. 활용 현황은 집계 숫자만(사용자 ID 없음).

### 5.1 2단계 인증(MFA)을 모두 잃었을 때 — 절차 (docs/36 16장 남은 일)

연락처·담당자 이름·비밀 정보는 이 문서에 적지 않는다(회사별 계약서·운영 대장에 둔다). 위에서부터 차례로, 앞 단계가 되면 멈춘다.

1. **복구 코드** — 본인이 등록 때 받은 복구 코드(10개, 하나씩 한 번)로 로그인한다. 로그인 뒤 내 정보 › 2단계 인증에서 복구 코드를 새로 만든다(남은 개수가 적으면).
2. **회사 안 다른 관리자** — 사용자 관리 화면 FN1 권한이 있는 다른 사람이 그 사용자의 "MFA 초기화" 를 한다. 그 사용자의 세션이 끊기고, 다음 로그인 때 다시 등록한다(강제 대상이면).
3. **운영자 초기화** — 회사 관리자가 한 명이라도 로그인할 수 있으면 그 사람이 "지원 세션 허락"(범위 USERS)을 열고, 운영자가 운영자 콘솔에서 대상 사용자의 MFA 를 초기화한다. 운영자가 한 일은 회사 관리자가 "운영 기록" 으로 본다.
4. **지원 세션을 허락할 사람이 아무도 없을 때**(회사 관리자 전원이 MFA 를 잃음) — 앱 안에는 길이 없다. 아래를 모두 지킨다.
   - 본인 확인: 회사의 **서면 요청** + 계약 때 등록한 회사 연락 경로로 **따로 회신 확인**(두 경로). 요청 메일·전화 한 통만으로 하지 않는다.
   - 작업: 설치 담당이 DB 소유자 계정으로 **대상 관리자 1명만** MFA 등록을 지운다(사용자 관리 "MFA 초기화" 와 같은 효과 — 그 사용자의 MFA 등록 행과 복구 코드 행). 비밀번호·권한은 건드리지 않는다.
   - 기록: 앱의 운영 기록에 남지 않으므로 요청·확인·작업 일시·작업자·대상을 운영 대장에 따로 남기고, 작업 뒤 그 회사에 알린다.
   - 그 관리자는 다음 로그인 때 MFA 를 다시 등록하고, 바로 다른 관리자 계정의 상태를 점검한다.
5. **예방** — 회사 관리자는 2명 이상 둔다. 등록 화면의 복구 코드는 안전한 곳에 적어 둔다.

## 6. Web Push 키 (휴대전화 알림, docs/45 9장)

키 값은 이 문서·저장소·공유 문서에 적지 않는다 — 서버 환경 변수(`.env`)에만 둔다. 설치마다 새로 만든다(설치끼리 같은 키를 쓰지 않는다).

1. 키 쌍 만들기(P-256): `openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem`
2. 공개 키(65바이트 base64url) → `HAPPYBOOT_PUSH_VAPID_PUBLIC_KEY`:
   `openssl ec -in vapid.pem -pubout -outform DER | tail -c 65 | base64 | tr '/+' '_-' | tr -d '=\n'`
3. 개인 키(32바이트 base64url) → `HAPPYBOOT_PUSH_VAPID_PRIVATE_KEY`:
   `openssl ec -in vapid.pem -outform DER | tail -c +8 | head -c 32 | base64 | tr '/+' '_-' | tr -d '=\n'`
4. 연락처 → `HAPPYBOOT_PUSH_VAPID_SUBJECT` (예 `mailto:` 운영 담당 주소 — RFC 8292, 푸시 서비스가 문제 때 연락)
5. `vapid.pem` 은 서버 밖 안전한 곳(비밀 보관소)에 두거나 지운다. 키를 바꾸면 기존 기기 구독이 모두 무효가 되어 사용자가 내 정보에서 다시 켜야 한다.

- 공개·개인 키가 짝이 아니거나 모양이 틀리면 기동이 멈춘다(조용히 꺼지지 않게). 둘 다 비우면 푸시만 꺼진다.
- 폐쇄망 설치: 브라우저 푸시는 외부 푸시 서비스(FCM·Mozilla·Apple·Windows)를 거친다 — 서버에서 그 호스트로 나가는 HTTPS 를 열지 못하면 푸시를 켜지 않는다. 허용 호스트는 `HAPPYBOOT_PUSH_ALLOWED_HOSTS`.

## 7. 보존 정리 — 아웃박스 (docs/36 19.2)

보낸·취소·수동 종결된 연계 메시지(메일 본문·받는 주소가 들어 있다)를 보존 기간(회사 설정, 기본 90일·최소 30일)이 지나면 지운다. **앱은 지우지 못한다** — 소유자 계정 함수 `hb_purge_outbox` 를 운영 도구가 하루 한 번 부른다(로그 정리 `hb_purge_logs` 와 같은 방식). 소유자 자격증명은 운영 비밀 보관소에만 두고 앱 설정(`.env`)에 넣지 않는다.

1. **첫 실행**은 작게: `select * from hb_purge_outbox(now(), 500);` → 결과 = 회사·지운 수·남은 수(상한에 걸린 회사만 남은 수를 센다). 남은 수가 크면 며칠에 나눠 지운다(한 번에 다 지우려고 상한을 크게 올리지 않는다 — 긴 잠금·WAL 폭증).
2. **매일**(새벽, 업무 시간 밖): `select * from hb_purge_outbox();`(회사당 5,000행). 결과 줄이 있으면(0 이 아닌 날만) 운영 로그에 회사·지운 수·남은 수·걸린 시간을 남긴다. 지운 행 내용은 남기지 않는다. 남은 수가 며칠 계속 0 이 아니면 상한을 올리거나 하루 두 번 돌린다.
3. 남기는 것: 보내는 중·재시도 대기·실패(FAIL — 종결 결재 대기 포함)는 기간과 상관없이 남는다. 법적 보류(회사 × 클래스 `OUTBOX`) 기간 안에 끝난 행도 남는다.
4. 정리된 뒤: 메일 발송 상태 조회는 "없는 메일"과 같고, 창고 인쇄 이력은 상태 칸이 빈다(인쇄 작업 행은 남는다).
5. 설치형(Windows 포함)도 같은 절차 — 예약 작업에서 `psql` 로 위 문장을 실행한다. cron 예시(자격증명은 `PGPASSFILE` 등 비밀 파일로, 명령줄에 쓰지 않는다):
   `15 3 * * * psql -X -q -d yeonos -U <소유자 계정> -c "select * from hb_purge_outbox()" >> /var/log/yeonos/purge-outbox.log 2>&1`

## 8. DB 이름 변경 — happyboot → yeonos (2026-11-09)

저장소 기본값이 DB `yeonos`, 소유자 `yeonos_owner`, 앱 `yeonos_app` 으로 바뀌었다. **새로 설치하는 곳은 할 일이 없다**(init-roles.sql·compose 가 새 이름). 이미 `happyboot*` 로 돌고 있는 DB(공개 데모 등)는 새 버전을 올리기 **전에** 한 번 옮긴다 — 데이터·권한·RLS 정책은 그대로 따라간다(정책·GRANT 는 이름이 아니라 OID 로 묶여 있다).

1. 앱을 멈춘다(데모: `docker compose stop app`) — 대상 DB 에 접속이 하나라도 있으면 DB 이름을 바꿀 수 없다.
2. 슈퍼유저로 **다른 DB**(`postgres`)에 접속해서:
   ```sql
   ALTER DATABASE happyboot RENAME TO yeonos;
   ALTER ROLE happyboot_owner RENAME TO yeonos_owner;
   ALTER ROLE happyboot_app   RENAME TO yeonos_app;
   ```
   비밀번호가 SCRAM(PostgreSQL 14+ 기본)이면 그대로 남는다. **MD5 로 저장된 계정은 이름을 바꾸면 비밀번호가 지워지므로** `ALTER ROLE … PASSWORD` 로 다시 넣는다(`select rolname, left(rolpassword, 6) from pg_authid` 로 확인).
3. 서버의 환경 설정(`.env`·서비스 설정)에 `DB_NAME`·`DB_OWNER_USER`·`DB_APP_USER` 나 `DB_URL` 을 직접 적어 두었다면 새 이름으로 고친다.
4. 새 버전을 올린다(migrate → 앱). Flyway 이력은 DB 안에 있어 그대로 이어진다.

로컬 개발 DB 는 옮기지 말고 `.env` 의 세 값을 고친 뒤 `docker compose down -v` → `docker compose up -d --wait` 로 새로 만든다.

## Java 런타임

- **Eclipse Temurin**(또는 Amazon Corretto) LTS 를 쓴다. **Oracle JDK 는 설치하지 않는다** — Oracle JDK 21 무료(NFTC) 기간이 2026-09 에 끝나 상용 설치형 환경에서는 라이선스 문제가 생길 수 있다(docs/13 L2).
- 빌드·실행 기준은 **Eclipse Temurin 25(LTS)** 이다(2026-11-09 21 → 25, 그전 2026-10-22 17 → 21). Oracle JDK 는 쓰지 않는다(Oracle JDK 21 무료 NFTC 기간 2026-09 종료 — docs/13 L2).
