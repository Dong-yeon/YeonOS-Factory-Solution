#!/usr/bin/env bash
# YeonOS 백업 (Linux, pgBackRest) — 01 7.2 / docs/05 3장
#   ./backup.sh full|diff|incr     DB 백업 + 검증 → 이어서 첨부 파일 백업(files 단계)
#   ./backup.sh files              첨부 파일 백업만
# 전제: pgBackRest 설치, /etc/pgbackrest/pgbackrest.conf (pgbackrest.conf.example 참고), postgresql.conf 에
#       archive_mode=on, archive_command='pgbackrest --stanza=yeonos archive-push %p', archive_timeout=60
# 결과는 DB 가 아니라 상태 파일(JSON)로 남긴다 — 백업 도구에 DB 쓰기 권한을 주지 않는다. 앱이 읽어 건강 상태로 표시.
# 불변 사본(Object Lock·WORM·오프라인 매체)은 pgBackRest repo2 또는 별도 복제로 — 이 스크립트는 repo 구성에 따른다.
#
# 첨부 파일(docs/67 F8): DB 만 PITR 로 돌아오고 도면·성적서가 사라지지 않게 FILE_ROOT 를 FILES_BACKUP_DIR 로 복사한다.
#   - 추가만(--ignore-existing): 저장 키가 uuid 라 같은 이름이 다시 쓰이지 않는다 → 원본이 암호화·손상돼도 이미 받은 사본을 덮지 않는다
#   - 지우지 않는다(--delete 없음): 앱의 첨부 삭제는 use_yn='N' 표시뿐이고, 보존 정리는 사람이 한다
#   - FILES_BACKUP_DIR 은 WORM·NAS 스냅샷·Object Lock 버킷 마운트처럼 이 서버 계정으로 지울 수 없는 곳이 좋다
#   - 결과는 last-files-backup.json(앱 "백업 건강 상태" 의 filesBackup). 설정이 없으면 FAIL 로 남겨 경고가 보이게 한다
set -euo pipefail

KIND="${1:-diff}"
STANZA="${STANZA:-yeonos}"
STATUS_DIR="${BACKUP_STATUS_DIR:-/var/lib/yeonos/backup-status}"
FILE_ROOT="${FILE_ROOT:-/var/lib/yeonos/files}"
FILES_BACKUP_DIR="${FILES_BACKUP_DIR:-}"

case "$KIND" in full|diff|incr|files) ;; *) echo "사용법: $0 full|diff|incr|files" >&2; exit 2 ;; esac
mkdir -p "$STATUS_DIR"

write_status() { # file kind status detail
  local tmp
  tmp="$(mktemp "$STATUS_DIR/.$1.XXXXXX")"
  printf '{"status":"%s","kind":"%s","finishedAt":"%s","detail":"%s"}\n' \
    "$3" "$2" "$(date --iso-8601=seconds)" "$4" > "$tmp"
  mv -f "$tmp" "$STATUS_DIR/$1"   # 원자적 교체 — 앱이 반쯤 쓴 파일을 읽지 않게
}

# 옮길(옮겨야 할) 파일 수 — 폴더 줄은 세지 않는다
count_pending() { # extra-rsync-option — rsync 가 실패하면 함수도 실패(개수 세기의 grep 결과와 섞지 않는다)
  local out
  out="$(rsync -a --ignore-existing $1 --exclude='/.tmp/' --out-format='%n' "$FILE_ROOT/" "$FILES_BACKUP_DIR/")" || return 1
  printf '%s\n' "$out" | grep -v '^$' | grep -vc '/$' || true
}

backup_files() {
  if [ -z "$FILES_BACKUP_DIR" ]; then
    write_status last-files-backup.json files FAIL "FILES_BACKUP_DIR not set"
    return 1
  fi
  if [ ! -d "$FILE_ROOT" ]; then
    write_status last-files-backup.json files FAIL "FILE_ROOT missing"
    return 1
  fi
  mkdir -p "$FILES_BACKUP_DIR"
  local copied missing
  # .tmp = 앱의 업로드 임시 폴더(LocalFileStorage) — 완성 전 파일은 받지 않는다
  if ! copied="$(count_pending "")"; then
    write_status last-files-backup.json files FAIL "rsync failed"
    return 1
  fi
  # 확인: 원본에 있고 사본에 없는 파일이 0 이어야 한다(다시 돌려도 옮길 것이 없음)
  missing="$(count_pending --dry-run)"
  if [ "$missing" != "0" ]; then
    write_status last-files-backup.json files FAIL "missing=$missing after copy"
    return 1
  fi
  write_status last-files-backup.json files OK "copied=$copied"
}

if [ "$KIND" = "files" ]; then
  backup_files
  exit $?
fi

if pgbackrest --stanza="$STANZA" --type="$KIND" backup; then
  # 백업 직후 무결성 확인 (체크섬·WAL 연속성)
  if pgbackrest --stanza="$STANZA" verify >/dev/null 2>&1; then
    write_status last-backup.json "$KIND" OK "verified"
  else
    write_status last-backup.json "$KIND" FAIL "verify failed"
    exit 1
  fi
else
  write_status last-backup.json "$KIND" FAIL "backup command failed"
  exit 1
fi
# DB 백업이 끝난 같은 시점에 첨부도 — 실패해도 DB 백업 결과는 그대로(상태 파일이 따로)
backup_files
