#!/bin/bash
# 빈 데이터 디렉터리로 처음 기동할 때 한 번 실행된다 (postgres 이미지 규칙).
# - POSTGRES_USER(소유자): Flyway 마이그레이션 실행, 테이블 소유
# - APP_DB_USER(앱): 애플리케이션 접속용. 소유자가 아니고 BYPASSRLS 가 없어야 RLS 가 적용된다.
# 설치형 납품 시에는 DBA 가 같은 내용을 수동으로 실행한다 (docs 참고).
set -euo pipefail

: "${APP_DB_USER:?APP_DB_USER 필요}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD 필요}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v app_user="$APP_DB_USER" \
     -v app_pw="$APP_DB_PASSWORD" \
     -v db_name="$POSTGRES_DB" <<-'EOSQL'
	CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pw'
	    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
	REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
	GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO :"app_user";
	REVOKE CREATE ON SCHEMA public FROM PUBLIC;
	GRANT USAGE ON SCHEMA public TO :"app_user";
	ALTER ROLE :"app_user" SET timezone = 'Asia/Seoul';
	ALTER ROLE :"app_user" SET statement_timeout = '60s';
EOSQL
