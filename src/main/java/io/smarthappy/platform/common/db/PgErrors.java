package io.smarthappy.platform.common.db;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** PostgreSQL 오류 판별. 메시지 문구는 서버 언어 설정(lc_messages)에 따라 바뀌므로 SQLState·routine·제약 이름으로 판별한다 */
public final class PgErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String CHECK_VIOLATION = "23514";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String STRING_TOO_LONG = "22001";
    public static final String NUMERIC_OUT_OF_RANGE = "22003";
    public static final String INVALID_TEXT_REPRESENTATION = "22P02";
    public static final String INVALID_DATETIME = "22007";
    /** text 에 NUL(U+0000) */
    public static final String CHARACTER_NOT_IN_REPERTOIRE = "22021";
    /** jsonb 에 \u0000 */
    public static final String UNTRANSLATABLE_CHARACTER = "22P05";
    public static final String DATETIME_OUT_OF_RANGE = "22008";
    /** 배제 제약 위반 (EXCLUDE USING gist — 예: 확정 BOM 기간 겹침) */
    public static final String EXCLUSION_VIOLATION = "23P01";
    /** 대상 상태가 맞지 않음 (상태 규칙 트리거 — 예: 확정 BOM 수정) */
    public static final String OBJECT_NOT_IN_STATE = "55000";

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** RLS WITH CHECK 위반을 내는 PostgreSQL 내부 함수 (execMain.c) */
    private static final String RLS_CHECK_ROUTINE = "ExecWithCheckOptions";

    private PgErrors() {
    }

    /** 다른 회사 comp_cd 로 insert/update 하려다 RLS 정책에 막힌 경우 */
    public static boolean isRlsViolation(Throwable t) {
        return findServerError(t)
                .filter(m -> INSUFFICIENT_PRIVILEGE.equals(m.getSQLState()))
                .filter(m -> RLS_CHECK_ROUTINE.equals(m.getRoutine()))
                .isPresent();
    }

    public static Optional<String> sqlState(Throwable t) {
        return findServerError(t).map(ServerErrorMessage::getSQLState);
    }

    /** 위반된 제약 이름 (unique·FK·check). 오류 코드 세분화·로그용 */
    public static Optional<String> constraint(Throwable t) {
        return findServerError(t).map(ServerErrorMessage::getConstraint);
    }

    /** 로그용 요약 (SQL 원문·파라미터는 넣지 않는다) */
    public static String summary(Throwable t) {
        return findServerError(t)
                .map(m -> m.getSQLState() + " " + m.getRoutine() + " table=" + m.getTable()
                        + (m.getConstraint() != null ? " constraint=" + m.getConstraint() : ""))
                .orElse(t.getClass().getSimpleName());
    }

    private static Optional<ServerErrorMessage> findServerError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException p && p.getServerErrorMessage() != null) {
                return Optional.of(p.getServerErrorMessage());
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Optional.empty();
    }
}
