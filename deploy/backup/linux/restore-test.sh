#!/usr/bin/env bash
# YeonOS 복원 시험 (Linux) — "복구 검증 오류 0" (3-2-1-1-0 의 0, 01 7.2)
#   ./restore-test.sh
# 최신 백업을 일회용 Docker 컨테이너에 복원 → 앱 계정으로 접속 → 기본 점검(스키마 버전·회사 수·원장/로그 행 수)
# 결과를 상태 파일로 남긴다. 운영 DB 는 건드리지 않는다. SaaS 주 1회, 설치형 월 1회 cron 권장.
set -euo pipefail

STANZA="${STANZA:-yeonos}"
STATUS_DIR="${BACKUP_STATUS_DIR:-/var/lib/yeonos/backup-status}"
WORK="$(mktemp -d /tmp/yeonos-restore.XXXXXX)"
NAME="yeonos-restore-$$"
PGIMAGE="${PGIMAGE:-postgres:16-alpine@sha256:81e77a4833487b8132fe4b00bedd39dc3f74b8641e387f19312a48f7de218c2e}"   # 운영 DB 와 같은 주 버전, 다이제스트 고정(docs/67 F10)
# 첨부 표본 대조(docs/67 F8): 복원본의 sys_file 에서 무작위 N건 → 첨부 백업(FILES_BACKUP_DIR)의 같은 저장 키 파일 SHA-256 비교
FILES_BACKUP_DIR="${FILES_BACKUP_DIR:-}"
FILE_SAMPLE="${FILE_SAMPLE:-20}"
[[ "$FILE_SAMPLE" =~ ^[0-9]{1,4}$ ]] || { echo "FILE_SAMPLE 은 숫자" >&2; exit 2; }
# 로그 다이제스트 체인 반출(05 D6): 복원본의 sys_log_digest 를 날짜 붙은 파일로 — 불변 저장소(WORM·Object Lock) 쪽 폴더를 준다
DIGEST_EXPORT_DIR="${DIGEST_EXPORT_DIR:-}"
mkdir -p "$STATUS_DIR"

finish() { # status detail
  local tmp
  tmp="$(mktemp "$STATUS_DIR/.last-restore.XXXXXX")"
  printf '{"status":"%s","kind":"restore-test","finishedAt":"%s","detail":"%s"}\n' \
    "$1" "$(date --iso-8601=seconds)" "$2" > "$tmp"
  mv -f "$tmp" "$STATUS_DIR/last-restore-test.json"
}
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# 1. 최신 백업을 임시 데이터 디렉터리로 복원
if ! pgbackrest --stanza="$STANZA" --pg1-path="$WORK/data" --type=immediate --target-action=promote restore; then
  finish FAIL "restore command failed"; exit 1
fi
chmod 700 "$WORK/data"
# 복원본에서 아카이브를 끈다(운영 저장소에 다시 쓰지 않게)
echo "archive_mode = off" >> "$WORK/data/postgresql.auto.conf"

# 2. 일회용 컨테이너로 기동 (포트를 열지 않는다)
docker run -d --name "$NAME" -v "$WORK/data:/var/lib/postgresql/data" "$PGIMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -q && break
  sleep 2
done

# 3. 점검 — 스키마 버전, 회사 수, 접속 로그 행 수 (값은 상태 파일 detail 에)
q() { docker exec "$NAME" psql -U postgres -d yeonos -tAc "$1"; }
if ! VERSION="$(q "select max(version::int) from flyway_schema_history where success")"; then
  finish FAIL "db not readable"; exit 1
fi
COMPS="$(q "select count(*) from sys_comp")"
LOGS="$(q "select count(*) from sys_access_log")"
if [ -z "$VERSION" ] || [ "$COMPS" -lt 1 ]; then
  finish FAIL "check failed v=$VERSION comps=$COMPS"; exit 1
fi

# 4. 첨부 표본 대조 — 복원 컨테이너는 슈퍼유저라 회사 범위 없이 전 회사 행을 본다(점검 전용, 값은 내보내지 않음)
FILES="files=skipped"
if [ -n "$FILES_BACKUP_DIR" ]; then
  checked=0; bad=0
  while IFS='|' read -r key sha; do
    [ -z "$key" ] && continue
    checked=$((checked + 1))
    f="$FILES_BACKUP_DIR/$key"
    if [ ! -f "$f" ] || [ "$(sha256sum "$f" | cut -d' ' -f1)" != "$sha" ]; then
      bad=$((bad + 1))
    fi
  done < <(q "select storage_key || '|' || sha256 from sys_file order by random() limit $FILE_SAMPLE")
  if [ "$bad" -gt 0 ]; then
    finish FAIL "v=$VERSION comps=$COMPS files=$bad/$checked mismatch"; exit 1
  fi
  FILES="files=$checked ok"
fi

# 5. 다이제스트 체인 반출 — 같은 날 다시 돌리면 같은 이름(덮어쓰기 대신 건너뜀: 불변 저장소는 덮어쓰기를 거부한다)
DIGEST="digest=skipped"
if [ -n "$DIGEST_EXPORT_DIR" ]; then
  mkdir -p "$DIGEST_EXPORT_DIR"
  out="$DIGEST_EXPORT_DIR/log-digest-$(date +%Y%m%d).tsv"
  if [ ! -e "$out" ]; then
    q "copy (select comp_cd, table_nm, log_date, row_cnt, digest, prev_digest from sys_log_digest order by comp_cd, table_nm, log_date) to stdout" > "$out.part"
    mv "$out.part" "$out"
  fi
  DIGEST="digest=$(wc -l < "$out") rows"
fi
finish OK "v=$VERSION comps=$COMPS access_log=$LOGS $FILES $DIGEST"
