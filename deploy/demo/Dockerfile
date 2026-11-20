# 공개 데모 앱 이미지 (docs/23). 빌드 단계 없음 — CI 가 만든 jar 를 넣기만 한다(jar 는 CPU 무관, 베이스가 arm64/amd64 멀티아키).
# 베이스 이미지는 태그 + 다이제스트로 고정(docs/67 F10, ImagePinGuardTest) — 올릴 때 `docker buildx imagetools inspect eclipse-temurin:25-jre`
FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
RUN useradd --system --uid 10001 --home-dir /app app && mkdir -p /data && chown app /data
WORKDIR /app
COPY smarthappy.jar /app/app.jar
USER app
# 메모리 상한을 고정한다 — 상한 없는 JVM 은 가진 메모리를 다 쓴다(docs/23 2.2)
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:MaxMetaspaceSize=192m -Xss512k -XX:+UseSerialGC -Duser.timezone=UTC"
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
