<#
  YeonOS 복원 시험 (Windows 서버) — 최신 pg_basebackup 을 임시 폴더에 풀어 다른 포트로 잠시 기동 → 점검 → 정리.
  운영 인스턴스는 건드리지 않는다. 월 1회 작업 스케줄러 권장. 결과는 상태 파일(JSON).
  점검(docs/67 F8): 스키마 버전·회사 수 + 첨부 표본(sys_file 무작위 N건의 SHA-256 을 FilesBackupDir 사본과 대조)
                   + 로그 다이제스트 체인 반출(DigestExportDir 에 날짜 붙은 파일, 같은 날 다시 돌리면 건너뜀)
  점검은 복원본의 슈퍼유저(-PgUser, 기본 postgres)로 한다 — 회사 범위 없이 전 회사 행을 보려고(값은 내보내지 않는다).
  사용: powershell -ExecutionPolicy Bypass -File restore-test.ps1 -BackupRoot D:\yeonos\backup -FilesBackupDir E:\yeonos-files
#>
param(
    [string]$BackupRoot = "D:\yeonos\backup",
    [string]$StatusDir = "D:\yeonos\backup-status",
    [string]$PgBin = "C:\Program Files\PostgreSQL\16\bin",
    [int]$TestPort = 55432,
    [string]$PgUser = "postgres",
    [string]$FilesBackupDir = "",
    [ValidateRange(1, 9999)][int]$FileSample = 20,
    [string]$DigestExportDir = ""
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $StatusDir | Out-Null

function Write-Status([string]$status, [string]$detail) {
    $obj = [ordered]@{ status = $status; kind = "restore-test"; finishedAt = (Get-Date).ToString("o"); detail = $detail }
    $tmp = Join-Path $StatusDir ".last-restore.tmp"
    $obj | ConvertTo-Json -Compress | Set-Content -Path $tmp -Encoding UTF8
    Move-Item -Force -Path $tmp -Destination (Join-Path $StatusDir "last-restore-test.json")
}

$latest = Get-ChildItem $BackupRoot -Directory | Sort-Object CreationTime -Descending | Select-Object -First 1
if (-not $latest) { Write-Status "FAIL" "no backup found"; exit 1 }
$work = Join-Path $env:TEMP ("yeonos-restore-" + [guid]::NewGuid().ToString("N"))
$data = Join-Path $work "data"
New-Item -ItemType Directory -Force -Path $data | Out-Null
try {
    tar -xzf (Join-Path $latest.FullName "base.tar.gz") -C $data
    New-Item -ItemType Directory -Force -Path (Join-Path $data "pg_wal") | Out-Null
    tar -xzf (Join-Path $latest.FullName "pg_wal.tar.gz") -C (Join-Path $data "pg_wal")
    # 복원본: 아카이브 끔, 로컬 접속만
    Add-Content (Join-Path $data "postgresql.auto.conf") "archive_mode = off`nlisten_addresses = '127.0.0.1'"
    & (Join-Path $PgBin "pg_ctl.exe") -D $data -o "-p $TestPort" -w -t 120 start
    if ($LASTEXITCODE -ne 0) { throw "pg_ctl start exit $LASTEXITCODE" }
    $psql = Join-Path $PgBin "psql.exe"
    function Q([string]$sql) { & $psql -h 127.0.0.1 -p $TestPort -U $PgUser -d yeonos -tAc $sql }
    $version = Q "select max(version::int) from flyway_schema_history where success"
    $comps = Q "select count(*) from sys_comp"
    if (-not $version -or [int]$comps -lt 1) { throw "check failed v=$version comps=$comps" }

    # 첨부 표본 대조
    $files = "files=skipped"
    if ($FilesBackupDir) {
        $rows = @(Q "select storage_key || '|' || sha256 from sys_file order by random() limit $FileSample" | Where-Object { $_ })
        $bad = 0
        foreach ($r in $rows) {
            $key, $sha = $r.Split('|', 2)
            $f = Join-Path $FilesBackupDir ($key -replace '/', '\')
            if (-not (Test-Path -LiteralPath $f) -or (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLower() -ne $sha) { $bad++ }
        }
        if ($bad -gt 0) { throw "files=$bad/$($rows.Count) mismatch" }
        $files = "files=$($rows.Count) ok"
    }

    # 로그 다이제스트 체인 반출
    $digest = "digest=skipped"
    if ($DigestExportDir) {
        New-Item -ItemType Directory -Force -Path $DigestExportDir | Out-Null
        $out = Join-Path $DigestExportDir ("log-digest-" + (Get-Date -Format "yyyyMMdd") + ".tsv")
        if (-not (Test-Path -LiteralPath $out)) {
            $lines = Q "copy (select comp_cd, table_nm, log_date, row_cnt, digest, prev_digest from sys_log_digest order by comp_cd, table_nm, log_date) to stdout"
            $lines | Set-Content -Path "$out.part" -Encoding UTF8
            Move-Item -Path "$out.part" -Destination $out
        }
        $digest = "digest=$(@(Get-Content -LiteralPath $out).Count) rows"
    }
    Write-Status "OK" "v=$version comps=$comps $files $digest from $($latest.Name)"
} catch {
    Write-Status "FAIL" ($_.Exception.Message)
    exit 1
} finally {
    & (Join-Path $PgBin "pg_ctl.exe") -D $data -m fast stop 2>$null | Out-Null
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
