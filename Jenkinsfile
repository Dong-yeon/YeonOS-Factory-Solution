// YeonOS 자동 빌드(docs/71). Jenkins Multibranch(main·claude/*)가 가지마다 이 파일로 빌드한다.
// 핵심: Testcontainers check 를 한 번에 하나씩 — Jenkins 실행기 1개 + 가지 안 동시 빌드 금지.
// 단계: 체크아웃 → (옛 가지면 건너뜀) → npm ci → ./gradlew check(-Phb.requireNode=true, JS 검사를 건너뛰지 않는다)
//       → 시험 보고서 보관(실패해도).
pipeline {
    agent any

    options {
        disableConcurrentBuilds()            // 같은 가지에 새 커밋이 와도 앞 빌드가 끝난 뒤에(취소하지 않음)
        timeout(time: 90, unit: 'MINUTES')   // 전체 check 40~45분(2026-11-09) — 두 배 여유, 멈춘 빌드가 줄을 막지 않게
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
    }

    environment {
        // 한글 시험 이름 — 없으면 보고서 생성이 "Malformed input" 으로 실패한다(SKILL §5, 2026-10-20)
        LC_ALL = 'C.UTF-8'
        // Gradle·npm 캐시는 Jenkins 홈 볼륨에 남겨 빌드마다 다시 받지 않는다
        GRADLE_USER_HOME = "${env.JENKINS_HOME}/.gradle"
        npm_config_cache = "${env.JENKINS_HOME}/.npm"
        // 가지 훑기(폴링)로 시작됐는데 마지막 커밋이 이보다 오래되면 check 없이 끝낸다(docs/71 4.3)
        HB_STALE_DAYS = '3'
    }

    stages {
        stage('준비') {
            steps {
                sh 'java -version 2>&1 | head -1; node --version; npm --version; git log -1 --format="%h %ci %s"'
                script {
                    // Jenkins 를 처음 연결하면 첫 훑기가 옛 claude/* 가지 30여 개를 한꺼번에 줄 세운다(45분 × 30).
                    // 폴링으로 시작된 빌드이고 커밋이 HB_STALE_DAYS 보다 오래됐으면 NOT_BUILT 로 바로 끝낸다.
                    // 새 가지의 첫 push·새 커밋은 커밋이 최근이라 그대로 돈다. "지금 빌드"(사람이 누름)는 언제나 돈다.
                    def byIndexing = !currentBuild.getBuildCauses('jenkins.branch.BranchIndexingCause').isEmpty()
                    def ageSec = sh(script: 'echo $(( $(date +%s) - $(git log -1 --format=%ct) ))', returnStdout: true).trim().toLong()
                    env.HB_SKIP = (byIndexing && ageSec > env.HB_STALE_DAYS.toLong() * 86400) ? 'true' : 'false'
                    if (env.HB_SKIP == 'true') {
                        currentBuild.result = 'NOT_BUILT'
                        currentBuild.description = "옛 가지(마지막 커밋 ${(ageSec / 86400) as long}일 전) — 폴링 빌드 건너뜀"
                        echo currentBuild.description
                    }
                }
            }
        }
        stage('npm ci') {
            when { environment name: 'HB_SKIP', value: 'false' }
            steps {
                sh 'npm ci --no-audit --no-fund'
            }
        }
        stage('check') {
            when { environment name: 'HB_SKIP', value: 'false' }
            steps {
                // gradlew 는 저장소에 실행 권한 없이 들어 있다(100644) — sh 로 부른다(ci.yml 과 같음)
                sh 'sh ./gradlew check -Phb.requireNode=true --no-daemon --console=plain'
            }
        }
    }

    post {
        always {
            script {
                if (env.HB_SKIP != 'true') {
                    // JS 결과를 파일로도 남긴다: check 안의 testJs·typecheckJs 는 콘솔에만 찍으므로(build.gradle 은 그대로 두고)
                    // 같은 시험을 JUnit XML 로 한 번 더 — 수 초. 판정은 위 check 가 이미 했으므로 이 결과로 빌드 상태를 바꾸지 않는다
                    sh returnStatus: true, script: '''
                        mkdir -p build/test-results/js build/reports/js
                        node --test --test-reporter=junit --test-reporter-destination=build/test-results/js/node-test.xml "src/test/js/*.test.js" > /dev/null 2>&1
                        node node_modules/typescript/bin/tsc -p jsconfig.json > build/reports/js/typecheck.txt 2>&1 \
                          && echo "tsc 오류 0" >> build/reports/js/typecheck.txt
                    '''
                    // 실패해도 시험 결과를 남긴다 — 실패한 시험을 Jenkins 화면(Tests)에서 바로 본다.
                    // Gradle 원본 XML 은 시험별 Spring 로그(stdout)까지 1.5GB → Jenkins 본체 힙(512m)이 읽다가 Java heap space
                    // (2026-11-10 merge-c, docs/71 6장). stdout·stderr 만 뺀 사본을 넘긴다(실패 메시지·스택은 남음)
                    sh returnStatus: true, script: 'rm -rf build/test-results/jenkins && node deploy/ci/strip-junit-stdout.mjs build/test-results/test build/test-results/jenkins'
                    junit testResults: 'build/test-results/jenkins/*.xml, build/test-results/js/*.xml', allowEmptyResults: true
                    archiveArtifacts artifacts: 'build/reports/js/**', allowEmptyArchive: true
                }
            }
        }
        failure {
            // Gradle HTML 보고서(시험 로그 전문, 약 750MB)는 실패한 빌드만 보관 — 성공마다 쌓으면 볼륨이 금방 찬다
            script {
                if (env.HB_SKIP != 'true') {
                    archiveArtifacts artifacts: 'build/reports/tests/test/**', allowEmptyArchive: true
                }
            }
        }
        cleanup {
            // 작업 공간의 시험 결과·보고서(가지마다 약 2GB)는 보관·집계 뒤 지운다 — 다음 빌드는 어차피 시험을 다시 돈다
            sh returnStatus: true, script: 'rm -rf build/test-results build/reports'
        }
    }
}
