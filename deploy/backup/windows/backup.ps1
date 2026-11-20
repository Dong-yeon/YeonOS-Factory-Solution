<#
  YeonOS 백업 (Windows 서버) — 01 7.2, docs/05 D5 (설치형 Windows 지원)
  pgBackRest 는 Windows 를 지원하지 않으므로 PostgreSQL 기본 도구로:
    - 전체 백업: pg_basebackup (tar, 압축, 체크섬 검증)
    - WAL: postgresql.conf 의 archive_command 로 연속 보관 (아래 예시), 덮어쓰기 금지
    - 첨부 파일(docs/67 F8): FileRoot → FilesBackupDir 로 "새 파일만" 복사(robocopy /XC /XN /XO — 덮어쓰기·삭제 없음)
  결과는 상태 파일(JSON)로 — 앱이 읽어 백업 건강 상태로 표시(last-backup.json, last-files-backup.json). 작업 스케줄러로 매일 실행.

  postgresql.conf 예시:
    archive_mode = on
    archive_command = 'if not exist "D:\\yeonos\\wal\\%f" copy "%p" "D:\\yeonos\\wal\\%f"'
    archive_timeout = 60

  사용: powershell -ExecutionPolicy Bypass -File backup.ps1 -BackupRoot D:\yeonos\backup -FilesBackupDir E:\yeonos-files
        첨부만: ... -FilesOnly
  접속: 백업 전용 역할(REPLICATION) — 비밀번호는 %APPDATA%\postgresql\pgpass.conf 로(스크립트에 쓰지 않는다)
  첨부 사본 폴더는 오프라인 매체·NAS WORM 스냅샷처럼 이 서버 계정으로 지울 수 없는 곳이 좋다. 비우면 FAIL 로 남겨 경고가 보이게 한다.
#>
param(
    [string]$BackupRoot = "D:\yeonos\backup",
    [string]$StatusDir = "D:\yeonos\backup-status",
    [string]$PgBin = "C:\Program Files\PostgreSQL\16\bin",
    [string]$PgHost = "127.0.0.1",
    [int]$PgPort = 5432,
    [string]$PgUser = "yeonos_backup",
    [int]$KeepDays = 14,
    [string]$FileRoot = "D:\yeonos\files",
    [string]$FilesBackupDir = "",
    [switch]$FilesOnly
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $BackupRoot, $StatusDir | Out-Null

function Write-Status([string]$file, [string]$kind, [string]$status, [string]$detail) {
    $obj = [ordered]@{ status = $status; kind = $kind; finishedAt = (Get-Date).ToString("o"); detail = $detail }
    $tmp = Join-Path $StatusDir (".$file.tmp")
    $obj | ConvertTo-Json -Compress | Set-Content -Path $tmp -Encoding UTF8
    Move-Item -Force -Path $tmp -Destination (Join-Path $StatusDir $file)   # 원자적 교체
}

# 원본에 있고 사본에 없는 파일(업로드 임시 폴더 .tmp 제외)
function Get-MissingFiles {
    $root = (Resolve-Path $FileRoot).Path.TrimEnd('\')
    Get-ChildItem -Path $root -Recurse -File -Force |
        Where-Object { -not $_.FullName.Substring($root.Length + 1).StartsWith(".tmp\") } |
        Where-Object { -not (Test-Path -LiteralPath (Join-Path $FilesBackupDir $_.FullName.Substring($root.Length + 1))) }
}

function Backup-Files {
    if (-not $FilesBackupDir) { Write-Status "last-files-backup.json" "files" "FAIL" "FilesBackupDir not set"; return $false }
    if (-not (Test-Path $FileRoot)) { Write-Status "last-files-backup.json" "files" "FAIL" "FileRoot missing"; return $false }
    New-Item -ItemType Directory -Force -Path $FilesBackupDir | Out-Null
    $before = @(Get-MissingFiles).Count
    # /XC /XN /XO = 이미 있는 이름은 건드리지 않음(추가만), /XD .tmp = 업로드 중 임시 파일 제외. 종료 코드 8 이상이 실패
    robocopy $FileRoot $FilesBackupDir /E /XC /XN /XO /XD (Join-Path $FileRoot ".tmp") /R:2 /W:5 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { Write-Status "last-files-backup.json" "files" "FAIL" "robocopy exit $LASTEXITCODE"; return $false }
    $missing = @(Get-MissingFiles).Count
    if ($missing -gt 0) { Write-Status "last-files-backup.json" "files" "FAIL" "missing=$missing after copy"; return $false }
    Write-Status "last-files-backup.json" "files" "OK" "copied=$before"
    return $true
}

if ($FilesOnly) {
    if (Backup-Files) { exit 0 } else { exit 1 }
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$target = Join-Path $BackupRoot $stamp
try {
    & (Join-Path $PgBin "pg_basebackup.exe") -h $PgHost -p $PgPort -U $PgUser -D $target -Ft -z -X stream -c fast --checkpoint=fast
    if ($LASTEXITCODE -ne 0) { throw "pg_basebackup exit $LASTEXITCODE" }
    & (Join-Path $PgBin "pg_verifybackup.exe") -n $target
    if ($LASTEXITCODE -ne 0) { throw "pg_verifybackup exit $LASTEXITCODE" }
    # 오래된 로컬 사본 정리 — 불변·오프사이트 사본은 별도 매체 교대 보관(이 스크립트가 지우지 않는다)
    Get-ChildItem $BackupRoot -Directory | Where-Object { $_.CreationTime -lt (Get-Date).AddDays(-$KeepDays) } |
        Remove-Item -Recurse -Force
    Write-Status "last-backup.json" "basebackup" "OK" "verified $stamp"
} catch {
    Write-Status "last-backup.json" "basebackup" "FAIL" ($_.Exception.Message)
    exit 1
}
# DB 백업과 같은 시점에 첨부도 — 실패해도 DB 백업 결과는 그대로(상태 파일이 따로)
if (-not (Backup-Files)) { exit 1 }
