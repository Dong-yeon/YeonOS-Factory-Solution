package io.smarthappy.platform.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * 트랜잭션을 시작할 때마다 현재 회사 범위를 DB 세션 변수로 설정한다 (트랜잭션 로컬 → 커넥션 풀 재사용에도 남지 않음).
 * 범위가 없으면 빈 값으로 "명시적으로" 덮어써서, 다른 경로로 세션 수준에 남은 값이 있어도 쓰이지 않게 한다.
 * MyBatis·JdbcTemplate 모두 Spring 관리 커넥션을 쓰므로 이 한 곳으로 충분하다.
 *
 * 연결을 반납한 뒤 할 일 {@link #afterRelease} (docs/36 15.1) — 스레드가 쥔 물리 트랜잭션(연결) 수를 세어, 0 이 되면
 * 모아 둔 일을 실행한다. 접속 로그처럼 "본 요청이 롤백돼도 남아야 하는" 별도 트랜잭션 기록을 바깥 트랜잭션 안에서 바로 쓰면
 * 요청 하나가 연결 둘을 쥐고, 동시 요청이 풀을 바닥내 앱 전체가 멈춘다(14.4.1 ②) — 그 기록을 연결 반납 뒤로 미룬다.
 */
@Slf4j
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    /** 이 스레드가 지금 쥔 물리 트랜잭션 수 (REQUIRES_NEW 로 바깥이 멈춰 있어도 바깥 연결은 쥔 채로 센다) */
    private static final ThreadLocal<int[]> OPEN = new ThreadLocal<>();
    private static final ThreadLocal<List<Runnable>> AFTER_RELEASE = new ThreadLocal<>();

    private static final String SET_TENANT_SQL =
            "select set_config('app.comp_cd', ?, true), set_config('app.scope', ?, true), set_config('app.actor', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 이 스레드가 쥔 연결을 모두 반납한 뒤 실행한다 — 지금 쥔 것이 없으면 바로. 롤백·커밋 실패여도 실행한다(거부 기록이 남게).
     * 실행 중 예외는 로그만 남기고 삼킨다(본 요청 결과를 바꾸지 않는다). 앱이 반납 직후 죽으면 아직 실행하지 못한 일은 사라진다(15.1 한계)
     */
    public static void afterRelease(Runnable work) {
        int[] open = OPEN.get();
        if (open == null || open[0] == 0) {
            work.run();
            return;
        }
        List<Runnable> list = AFTER_RELEASE.get();
        if (list == null) {
            list = new ArrayList<>();
            AFTER_RELEASE.set(list);
        }
        list.add(work);
    }

    /** 이 스레드가 지금 연결(물리 트랜잭션)을 쥐고 있는가 — 시험·진단용 */
    public static boolean holdsConnection() {
        int[] open = OPEN.get();
        return open != null && open[0] > 0;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        int[] open = OPEN.get();
        if (open == null) {
            open = new int[1];
            OPEN.set(open);
        }
        open[0]++;
    }

    /**
     * 연결 반납 뒤 셈을 줄이고, 0 이면 모아 둔 일을 실행한다. **반납(super)이 예외를 던져도** finally 에서 줄이고 실행한다 —
     * 줄지 않으면 풀 스레드에 셈이 남아 그 스레드의 이후 기록이 영영 미뤄진 채 사라진다(조용히 틀림). 예외는 그대로 던진다
     */
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        try {
            releaseConnection(transaction);
        } finally {
            afterCleanup();
        }
    }

    /** 연결 반납 — 시험이 반납 실패를 흉내 내는 지점 */
    protected void releaseConnection(Object transaction) {
        super.doCleanupAfterCompletion(transaction);
    }

    private static void afterCleanup() {
        int[] open = OPEN.get();
        if (open == null || --open[0] > 0) {
            return;
        }
        OPEN.remove();
        List<Runnable> list = AFTER_RELEASE.get();
        AFTER_RELEASE.remove();
        if (list == null) {
            return;
        }
        for (Runnable work : list) {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("연결 반납 뒤 작업 실패", e);
            }
        }
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        try (PreparedStatement ps = con.prepareStatement(SET_TENANT_SQL)) {
            ps.setString(1, TenantContext.currentCompCd().orElse(""));
            ps.setString(2, TenantContext.currentDbScope());
            ps.setString(3, TenantContext.currentActor());
            ps.execute();
        }
    }
}
