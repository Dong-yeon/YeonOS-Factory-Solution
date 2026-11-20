# 운영 지표 예시 (docs/52 Y2) — **예시만**

YeonOS 는 지표를 **내보내기만** 한다. 수집(Prometheus)·화면(Grafana)·경보 전달(Alertmanager)의 설치·운영은 고객·운영자 몫이다.
이 폴더는 그 출발점이다: 수집 설정 `prometheus.yml`, 경보 규칙 `alerts.yml`, 대시보드 `grafana-dashboard.json`.

## 1. 앱 쪽 설정 (운영 prod 프로필)

운영 프로필에서 지표 끝점은 **기본 닫힘**이다. 열려면 아래를 모두 준다 — 하나라도 빠지면 `ProdStartupGuard` 가 기동을 막는다.

| 환경 변수 | 예 | 뜻 |
|---|---|---|
| `METRICS_ENDPOINT` | `read-only` | 끝점 열기(기본 `none`) |
| `MANAGEMENT_SERVER_PORT` | `9464` | 관리 포트 — 앱 포트(8090)와 달라야 한다 |
| `MANAGEMENT_SERVER_ADDRESS` | `10.0.0.5` 또는 `127.0.0.1` | 관리 포트를 **내부 주소에만** 바인딩. `0.0.0.0`·공인 IP·호스트 이름 금지 |
| `METRICS_TOKEN` | (32자 이상 무작위) `openssl rand -hex 32` | 수집기 Bearer 토큰 |
| `METRICS_ALLOWED_IPS` | `10.0.0.20/32` | 수집기 주소(쉼표 CIDR). 밖에서는 404 |
| `METRICS_COMP_TAG` | `false`(기본) | 업무 지표의 회사 꼬리표 — **SaaS 에서만** 켤 수 있다(설치형은 무시). 회사 수만큼 시계열이 늘고 지표 저장소에 회사 목록이 남는다(docs/52 Q2) |

관리 포트를 나누면 `/actuator/health/*` 도 관리 포트로 간다. 로드밸런서·컨테이너 헬스체크는 앱 포트의 `/livez`·`/readyz` 를 쓴다.

로그는 운영 프로필에서 JSON 한 줄(ECS, `LOG_FORMAT` 으로 바꿀 수 있음 — `ecs`·`logstash`·`gelf`)이고, 줄마다 `compCd`(회사)·`reqId`(요청 ID, 응답 헤더 `X-Request-Id`)·`userId`(숫자)가 실린다. 사용자가 오류를 알려 오면 응답 헤더의 요청 ID 하나로 그 요청의 로그를 찾는다.

## 2. 업무 지표

| 지표 | 뜻 | 경보 예(alerts.yml) |
|---|---|---|
| `hb_outbox_pending{topic}` | 보낼 것(NEW·SENDING) | — |
| `hb_outbox_oldest_pending_seconds{topic}` | 가장 오래 기다린 보낼 것 | 15분 넘게 |
| `hb_outbox_failed{topic}` | 실패로 멈춘 것(FAIL) — 화면 오류 없이 **조용히 ERP 에 안 간다** | 0 초과가 10분 |
| `hb_inbox_duplicate_total{consumer}` | 수신 중복(멱등으로 버림) | 참고(급증하면 상대 시스템 재전송 폭주) |
| `hb_pop_resend_total{kind}` | POP 단말 재전송(같은 요청 id) | 참고(현장 네트워크 상태) |
| `hb_login_fail_total` · `hb_login_throttled_total` | 로그인 실패·시도 제한 | 5분에 50건 넘게(대입 공격) |
| `hb_tenant_forbidden_total` | RLS 위반 차단(403) — **1건이라도 코드 결함 신호** | 1건 |
| `hb_excel_busy_total` · `hb_excel_export_seconds` | 엑셀 내보내기 자리 없음(바로 거절, 대기 없음) · 걸린 시간 | 참고 |
| `hb_job_lag_seconds{job}` | 정기 작업 마지막 성공 뒤 시간(이 프로세스 기준, 기동 뒤 첫 성공 전에는 기동 뒤 시간) | 작업 주기의 3배 |
| `hb_backup_age_hours` | 마지막 백업 성공 뒤 시간(상태 파일). 기록 없음·마지막 실패면 값 없음 | 26시간 넘게 또는 값 없음 |

개수(`_total`)는 프로세스 기준이라 재기동하면 0부터다 — `increase()`·`rate()` 로 본다. 꼬리표는 코드가 정한 작은 집합(토픽·작업명·종류)만 쓴다. 사용자 입력(아이디·회사코드 입력값·URL)은 꼬리표가 되지 않는다.

## 3. 다음

추적(OpenTelemetry, docs/27 S12)은 요청이 여러 프로세스를 지날 때 값이 있다 — 지금은 한 프로세스라 지표·로그가 먼저다.
