#!/bin/bash
# 공개 데모 배포 — GitHub Actions 가 jar·설정을 /opt/yeonos 에 올린 뒤 SSH 로 실행한다 (docs/23 2.1).
# 순서: 이미지 조립 → 마이그레이션 전용 실행(소유자 계정) → 앱 재기동 → readiness 확인.
# 마이그레이션 뒤에는 옛 jar 로 되돌릴 수 없다(버전 불일치 기동 가드) — 실패하면 고쳐서 다시 배포한다.
set -euo pipefail
cd "$(dirname "$0")"
exec 9>.deploy.lock
flock -w 600 9   # 매일 초기화(reset.sh)와 겹치지 않게

[ -f .env ] || { echo ".env 가 없습니다 (.env.example 참고)"; exit 1; }
[ -f smarthappy.jar ] || { echo "smarthappy.jar 가 없습니다"; exit 1; }

docker compose build app
docker compose up -d --wait db
docker compose run --rm migrate
docker compose up -d app caddy
./wait-ready.sh
docker image prune -f >/dev/null
echo "배포 완료: $(date -u '+%F %T') UTC"
