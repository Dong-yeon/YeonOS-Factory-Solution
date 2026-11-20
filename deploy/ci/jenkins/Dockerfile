# YeonOS Jenkins (docs/71). 컨트롤러 1대가 빌드도 한다(실행기 1) — 1인 PC 라 에이전트를 따로 두지 않는다(docs/71 4장).
# 버전은 모두 고정: Jenkins LTS(JDK 25 = Gradle toolchain 25 와 같은 JDK) + Node 22(ci.yml 과 같은 주 버전).
# 태그 + 다이제스트(ImagePinGuardTest, docs/67 F10). 갱신: docker buildx imagetools inspect 이미지:태그 의 Digest
FROM node:22.23.5-trixie-slim@sha256:68c76cfad89921b927eae28ba21407475e29d0ce450afcdbe70c1532db042b64 AS node

FROM jenkins/jenkins:2.580.2-lts-jdk25@sha256:415ab90d4e3b25148f7191bc04f455a20817d6c4c3b0a98f6c6b0da4877f7081
USER root
# Node 는 JS 타입 검사·단위 시험(npm ci → tsc, node --test)용 빌드 도구로만. 같은 Debian trixie 라 바이너리를 그대로 옮긴다.
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && node --version && npm --version
USER jenkins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
