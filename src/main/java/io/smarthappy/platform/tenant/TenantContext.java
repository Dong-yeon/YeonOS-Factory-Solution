package io.smarthappy.platform.tenant;

import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * 현재 스레드의 회사(테넌트) 범위.
 * - 웹 요청: 로그인 사용자 기준으로 필터가 설정 (P0 3단계)
 * - 요청 밖 실행: {@link TenantRunner} 로만 설정
 * 트랜잭션이 시작될 때 {@link TenantAwareTransactionManager} 가 이 값을 DB 세션 변수(app.comp_cd)로 넘긴다.
 * 값이 없으면 DB 는 아무 행도 돌려주지 않는다(fail-closed).
 */
public final class TenantContext {

    /** DB CHECK 제약(ck_sys_comp_cd)과 같은 규칙 */
    private static final Pattern COMP_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");
    private static final String MDC_KEY = "compCd";

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<String> currentCompCd() {
        TenantScope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.compCd());
    }

    /** 회사가 반드시 있어야 하는 곳에서 사용. 없으면 {@link TenantNotSetException} */
    public static String requireCompCd() {
        return currentCompCd().orElseThrow(TenantNotSetException::new);
    }

    /** 변경 감사(sys_audit_log.actor)에 남길 행위자. 웹 요청 = "u{userId}:{loginId}", 그 밖 = SYSTEM */
    public static String currentActor() {
        TenantScope scope = CURRENT.get();
        return scope == null || scope.actor() == null ? "SYSTEM" : scope.actor();
    }

    static boolean isSystemScope() {
        TenantScope scope = CURRENT.get();
        return scope != null && scope.system();
    }

    /** DB 세션 변수 app.scope 값: 회사 범위 "", 회사 목록 "SYSTEM", 운영자 콘솔 "OPS" */
    static String currentDbScope() {
        TenantScope scope = CURRENT.get();
        return scope == null ? "" : scope.dbScope();
    }

    /** 운영자 콘솔 범위인지 (회사 데이터 없음 — 회사 목록·활용 집계 읽기만) */
    public static boolean isOpsScope() {
        return "OPS".equals(currentDbScope());
    }

    public static boolean isValidCompCd(String compCd) {
        return compCd != null && COMP_CD.matcher(compCd).matches();
    }

    static void validate(String compCd) {
        if (!isValidCompCd(compCd)) {
            throw new IllegalArgumentException("회사코드 형식이 올바르지 않습니다: [" + compCd + "]");
        }
    }

    /**
     * 웹 요청 범위를 연다 (로그인 사용자의 회사). 요청 필터 전용 — 반드시 try-with-resources 로 닫는다.
     * 트랜잭션 안에서는 열 수 없다(한 트랜잭션에 회사가 섞이는 것 방지).
     */
    public static Scope openRequestScope(String compCd, String actor) {
        validate(compCd);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("트랜잭션 안에서는 회사 범위를 열 수 없습니다");
        }
        return open(new TenantScope(compCd, "", actor));
    }

    /** 범위를 열고, 닫을 때 이전 범위로 되돌린다. TenantRunner·웹 필터 전용 */
    static Scope open(TenantScope scope) {
        TenantScope previous = CURRENT.get();
        String previousMdc = MDC.get(MDC_KEY);
        CURRENT.set(scope);
        MDC.put(MDC_KEY, scope.dbScope().isEmpty() ? scope.compCd() : scope.dbScope());
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousMdc == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdc);
            }
        };
    }

    /** try-with-resources 용. close 에서 예외를 던지지 않는다 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * @param compCd 회사코드 (system 범위면 null)
     * @param dbScope DB app.scope — "" 회사, "SYSTEM" 회사 목록 조회(V002), "OPS" 운영자 콘솔(V011)
     * @param actor  변경 감사 행위자 (null = SYSTEM)
     */
    record TenantScope(String compCd, String dbScope, String actor) {
        static TenantScope of(String compCd) {
            return new TenantScope(compCd, "", null);
        }

        static TenantScope systemDirectory() {
            return new TenantScope(null, "SYSTEM", null);
        }

        /** 운영자 콘솔 — 회사 범위 없음, 허용 정책(ops_*) 으로만 읽는다 */
        static TenantScope ops(String actor) {
            return new TenantScope(null, "OPS", actor);
        }

        boolean system() {
            return "SYSTEM".equals(dbScope);
        }
    }
}
