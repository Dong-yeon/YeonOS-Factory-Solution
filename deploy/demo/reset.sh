#!/bin/bash
# 공개 데모 매일 초기화 (docs/23 5장) — cron: 0 19 * * * (UTC 19:00 = KST 04:00)
# 앱 계정은 기준정보 DELETE 권한이 없고 원장·감사 로그는 삭제가 막혀 있다 → DB·첨부 볼륨을 통째로 다시 만든다.
# 그동안(약 1~2분) Caddy 가 "점검 중" 안내를 보여 준다.
set -euo pipefail
cd "$(dirname "$0")"
exec 9>.deploy.lock
flock -w 600 9   # 배포와 겹치지 않게

echo "초기화 시작: $(date -u '+%F %T') UTC"
docker compose stop app
docker compose rm -sf app db
docker volume rm -f yeonos-demo_db-data yeonos-demo_app-data >/dev/null
docker compose up -d --wait db          # 빈 볼륨 → initdb 가 롤·DB 를 다시 만든다
docker compose run --rm migrate
docker compose up -d app
./wait-ready.sh
echo "초기화 완료: $(date -u '+%F %T') UTC"
