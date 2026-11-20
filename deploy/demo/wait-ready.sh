#!/bin/bash
# 앱 readiness(DB 포함)가 UP 이 될 때까지 최대 180초 기다린다. 실패하면 앱 로그 끝을 보여 주고 1 로 끝난다.
set -uo pipefail
cd "$(dirname "$0")"
for _ in $(seq 1 60); do
  if curl -fsS --noproxy "*" -o /dev/null http://127.0.0.1:8090/actuator/health/readiness; then
    echo "readiness UP"; exit 0
  fi
  sleep 3
done
echo "readiness 실패 — 앱 로그:"
docker compose logs --tail 80 app
exit 1
