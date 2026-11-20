#!/bin/bash
# 빈 DB 볼륨으로 처음 뜰 때 한 번(postgres 이미지 규칙). 설치형과 같은 deploy/install/init-roles.sql 을 쓴다 —
# 소유자는 슈퍼유저가 아니고, 앱은 소유자도 BYPASSRLS 도 아니다(DatabaseRoleGuard 가 기동 때 확인).
set -euo pipefail
: "${DB_OWNER_PASSWORD:?}" "${DB_APP_PASSWORD:?}" "${DB_BACKUP_PASSWORD:?}"
for v in "$DB_OWNER_PASSWORD" "$DB_APP_PASSWORD" "$DB_BACKUP_PASSWORD"; do
  [[ "$v" =~ ^[A-Za-z0-9_-]{16,}$ ]] || { echo "DB 비밀번호는 영문·숫자·_- 16자 이상 (openssl rand -hex 24)"; exit 1; }
done
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v owner_pw="'$DB_OWNER_PASSWORD'" -v app_pw="'$DB_APP_PASSWORD'" -v backup_pw="'$DB_BACKUP_PASSWORD'" \
     -f /init-roles.sql
