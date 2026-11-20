-- YeonOS 설치형 DB 초기화 — PostgreSQL 슈퍼유저(postgres)로 1회 실행 (01 7.1)
--   psql -U postgres -v owner_pw="'...'" -v app_pw="'...'" -v backup_pw="'...'" -f init-roles.sql
-- 운영 구성: 소유자는 슈퍼유저가 아니다(로컬 docker 의 소유자=슈퍼유저는 개발 전용).
-- 비밀번호는 이 파일에 쓰지 않는다 — psql 변수(-v)로 넘기고 셸 기록에 남지 않게 한다.

CREATE ROLE yeonos_owner LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB PASSWORD :owner_pw;
CREATE ROLE yeonos_app   LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB PASSWORD :app_pw;
-- 백업 전용(Windows pg_basebackup·모니터링). 업무 테이블 권한 없음
CREATE ROLE yeonos_backup LOGIN REPLICATION NOSUPERUSER NOBYPASSRLS PASSWORD :backup_pw;

CREATE DATABASE yeonos OWNER yeonos_owner ENCODING 'UTF8' TEMPLATE template0;
\connect yeonos
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO yeonos_owner;
GRANT USAGE ON SCHEMA public TO yeonos_app;
-- 앱 계정이 소유자 롤의 멤버가 되면 기동이 막힌다(DatabaseRoleGuard) — GRANT yeonos_owner TO yeonos_app 금지
