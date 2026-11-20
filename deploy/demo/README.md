# 공개 데모 VM 런북 (docs/23)

Oracle Cloud Always Free Ampere A1 VM(Ubuntu 24.04) 1대 + docker compose(Caddy · 앱 · PostgreSQL).
비밀번호는 VM 의 `/opt/yeonos/.env` 에만 둔다(이 저장소·GitHub Secrets 에는 SSH 정보만).

## 1. VM 첫 준비 (1회)

```bash
# Docker (cloud-init 으로 이미 했다면 건너뜀)
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 rsync
sudo usermod -aG docker ubuntu        # 다시 로그인해야 적용
# 80·443 (Oracle 콘솔 Security List 에도 Ingress 추가)
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

sudo install -d -o ubuntu -g ubuntu /opt/yeonos
```

`/opt/yeonos/.env` 를 `.env.example` 대로 만든다(`chmod 600`). DB 비밀번호 4개는 `openssl rand -hex 24`.
`MFA_SECRET_KEY` 는 `openssl rand -base64 32` 로 **한 번만** 만든다 — 바꾸면 데모 관리자의 2단계 인증 등록이 모두 풀리지 않게 된다. 사본은 VM 밖 비밀 보관소에.

> **이미 돌고 있는 VM(2026-11-09 이전 설치)**: `.env` 에 `MFA_SECRET_KEY` 가 없으면 다음 배포에서 compose 가 "MFA_SECRET_KEY 를 .env 에 넣으세요" 로 멈춘다(앱 운영 기동 가드가 요구하는 값을 compose 단계에서 먼저 확인 — docs/67 F4). 위 명령으로 한 줄 추가한 뒤 다시 배포.
>
> 컨테이너 강화(docs/67 F10): 모든 컨테이너 `no-new-privileges` + 기본 권한 전부 제거(DB 는 entrypoint 몫 5개, Caddy 는 포트 열기만), 앱·Caddy 루트 파일 시스템 읽기 전용. 이미지는 다이제스트로 고정 — 올릴 때는 `docker buildx imagetools inspect <이미지>:<태그>` 의 Digest 로 `compose.yml`·`Dockerfile` 을 고치고 `ImagePinGuardTest` 가 통과하는지 본다.
> 사설망 직접 연결(라벨 프린터 TCP·장비 DIRECT 어댑터)은 데모(SaaS 모드)에서 꺼져 있다(docs/67 F3) — 데모 시연은 가짜 엣지(EDGE)로.

## 2. GitHub 설정 (1회)

배포 전용 키를 만든다(비밀번호 없음 — 자동 실행용, 개인 접속 키와 따로):

```bash
ssh-keygen -t ed25519 -N "" -f yeonos_deploy -C "yeonos-deploy"
# VM 에 공개 키 등록
cat yeonos_deploy.pub >> ~/.ssh/authorized_keys      # (VM 에서)
ssh-keyscan -t ed25519 <VM 공인 IP>                    # known_hosts 값
```

| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `DEMO_SSH_KEY` | `yeonos_deploy` 개인 키 전체 |
| Secret | `DEMO_KNOWN_HOSTS` | `ssh-keyscan` 출력 |
| Variable | `DEMO_HOST` | VM 공인 IP 또는 도메인 |
| Variable | `DEMO_USER` | `ubuntu` (생략 가능) |

`DEMO_HOST` 가 없으면 워크플로는 빌드·테스트만 하고 배포는 건너뛴다.

## 3. 배포

main 에 push → `.github/workflows/demo-deploy.yml` 이 `check` → jar → VM `/opt/yeonos` 로 rsync → `deploy.sh`
(이미지 조립 → `migrate`(소유자 계정) → 앱 재기동 → readiness 확인). Actions 탭의 **Run workflow** 로 수동 실행도 된다.

## 4. 매일 초기화

```bash
crontab -e
0 19 * * * /opt/yeonos/reset.sh >> /opt/yeonos/reset.log 2>&1    # UTC 19:00 = KST 04:00
```

## 5. 자주 쓰는 명령

```bash
cd /opt/yeonos
docker compose ps
docker compose logs -f --tail 100 app
./reset.sh                  # 지금 바로 초기화
docker compose down         # 중지(데이터 유지)
```

VM 이 회수·삭제되면: 새 VM 에 1~2절을 다시 하고 Actions 를 수동 실행하면 복구된다(데이터는 매일 지우는 가짜라 잃을 것이 없다).
