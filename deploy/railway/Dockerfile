# Railway 배포 이미지 (deploy/railway/README.md). 빌드 맥락 = 저장소 루트, railway.json 이 이 파일을 가리킨다.
# Railway 기본 빌더(Railpack)의 시작 명령은 `*/build/libs/*jar`(한 단계 아래)를 찾아 단일 프로젝트 jar 를 못 찾는다 → Dockerfile 로 고정.
# 베이스 이미지는 태그 + 다이제스트로 고정(ImagePinGuardTest). JRE 는 데모 이미지(deploy/demo/Dockerfile)와 같은 다이제스트.

FROM eclipse-temurin:25-jdk@sha256:884a2f8692cc1e2c7e21f95914adf0ae0eafee9a7eb663a12169cc44468256ec AS build
WORKDIR /src
COPY . .
# 시험은 Testcontainers(Docker) 가 필요해 여기서 돌리지 않는다 — 실행 jar 만 만든다(bootJar 는 -plain.jar 를 만들지 않음)
RUN chmod +x gradlew && ./gradlew --no-daemon -q bootJar -x test && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
RUN useradd --system --uid 10001 --home-dir /app app && mkdir -p /app /data && chown app /data
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
# 쓰기 경로는 /data 로(기본값 ./data 는 /app 안이라 쓸 수 없음). 메모리 상한은 Railway Variables 의 JAVA_TOOL_OPTIONS 로.
ENV TZ=UTC FILE_ROOT=/data/files STATUS_FILE=/data/status.json BACKUP_STATUS_DIR=/data/backup-status PDA_APK_DIR=/data/pda
# Railway 가 주는 PORT 로 듣는다(없으면 8090). 프로필은 Variables 의 SPRING_PROFILES_ACTIVE(prod,demo)
CMD ["sh", "-c", "exec java -Dserver.port=${PORT:-8090} -jar /app/app.jar"]
