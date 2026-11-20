package io.smarthappy.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.smarthappy.platform.tenant.TenantContext;

/** happyboot.* 설정 */
@ConfigurationProperties("happyboot")
public record HappyBootProperties(Deploy deploy, Security security, Files files, Ops ops) {

    public HappyBootProperties {
        deploy = deploy == null ? new Deploy(null, null) : deploy;
        security = security == null ? new Security(0, 0, 0, 0, 0, 0) : security;
        files = files == null ? new Files(null) : files;
        ops = ops == null ? new Ops(null, null, 0) : ops;
    }

    /**
     * @param mode   saas | onprem
     * @param compCd 설치형(onprem)의 고정 회사코드. 로그인 화면에서 회사코드를 받지 않는다
     */
    public record Deploy(String mode, String compCd) {
        public Deploy {
            mode = (mode == null || mode.isBlank()) ? "saas" : mode.trim().toLowerCase();
            if (!mode.equals("saas") && !mode.equals("onprem")) {
                throw new IllegalArgumentException("happyboot.deploy.mode 는 saas 또는 onprem 이어야 합니다: " + mode);
            }
            if (mode.equals("onprem") && !TenantContext.isValidCompCd(compCd)) {
                throw new IllegalArgumentException("설치형(onprem)은 happyboot.deploy.comp-cd 에 올바른 회사코드가 필요합니다");
            }
        }

        public boolean onprem() {
            return mode.equals("onprem");
        }
    }

    /**
     * 로그인 보호 (정책 테이블이 생기면 회사별 정책으로 이동, P0 5단계)
     *
     * @param maxLoginFail      계정 연속 실패 시 잠금 횟수
     * @param lockMinutes       실패 잠금 자동 해제까지 분
     * @param ipCompMaxFail     같은 IP + 회사코드의 실패 허용 횟수(창 안) — 넘으면 잠시 차단
     * @param ipMaxFail         같은 IP 의 실패 허용 횟수(회사코드 바꿔 가며 시도하는 경우)
     * @param ipWindowMinutes   IP 실패 집계 창(분)
     * @param sessionAbsoluteHours 로그인 후 활동과 관계없이 세션이 끝나는 시간 (docs/05 G7, 교대 근무 1회 기준 12)
     */
    public record Security(int maxLoginFail, int lockMinutes, int ipCompMaxFail, int ipMaxFail, int ipWindowMinutes,
            int sessionAbsoluteHours) {
        public Security {
            maxLoginFail = maxLoginFail <= 0 ? 5 : maxLoginFail;
            lockMinutes = lockMinutes <= 0 ? 15 : lockMinutes;
            ipCompMaxFail = ipCompMaxFail <= 0 ? 20 : ipCompMaxFail;
            ipMaxFail = ipMaxFail <= 0 ? 100 : ipMaxFail;
            ipWindowMinutes = ipWindowMinutes <= 0 ? 10 : ipWindowMinutes;
            sessionAbsoluteHours = sessionAbsoluteHours <= 0 ? 12 : sessionAbsoluteHours;
        }
    }

    /**
     * 첨부 저장소 (로컬 디스크). S3 호환 저장소는 FileStorage 구현을 추가해 바꾼다.
     *
     * @param root 저장 루트 디렉터리. 웹 루트·소스 폴더 밖이어야 한다 (기본 ./data/files)
     */
    public record Files(String root) {
        public Files {
            root = (root == null || root.isBlank()) ? "./data/files" : root.trim();
        }
    }

    /**
     * 운영자 콘솔 (/ops, 01 7장 · 05 D4·D8)
     *
     * @param allowedIps  운영자 콘솔 접근 허용 IP·CIDR (쉼표). 밖에서는 콘솔이 "없는 것처럼" 404
     * @param secretKey   운영자 MFA 비밀 암호화 키 (Base64 32바이트, DB·백업과 다른 곳에 보관). 없으면 콘솔 비활성
     * @param idleMinutes 운영자 세션 비활동 만료(분)
     */
    public record Ops(String allowedIps, String secretKey, int idleMinutes) {
        public Ops {
            allowedIps = allowedIps == null ? "" : allowedIps.trim();
            secretKey = secretKey == null ? "" : secretKey.trim();
            idleMinutes = idleMinutes <= 0 ? 15 : idleMinutes;
        }

        public boolean enabled() {
            return !secretKey.isEmpty() && !allowedIps.isEmpty();
        }
    }
}
