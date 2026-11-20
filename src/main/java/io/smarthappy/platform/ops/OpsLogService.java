package io.smarthappy.platform.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * 운영 기록 (sys_ops_log, 전역·추가만). 본 처리와 분리된 트랜잭션 — 거부·실패도 남는다.
 * 회사 관리자는 자기 회사가 대상인 기록을 지원 세션 화면에서 본다(투명성, 05 D8).
 */
@Slf4j
@Service
public class OpsLogService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OpsLogService(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String opLoginId, String action, String targetComp, String result, String ip, String detail) {
        try {
            tx.executeWithoutResult(s -> jdbc.update("""
                    insert into sys_ops_log (op_login_id, action, target_comp, result_cd, ip, detail)
                    values (?, ?, ?, ?, ?, ?)""", clip(opLoginId, 50), action, clip(targetComp, 20), result, clip(ip, 45),
                    clip(detail, 500)));
        } catch (RuntimeException e) {
            log.error("운영 기록 실패 action={} op={}", action, opLoginId, e);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String c = s.replaceAll("\\p{Cntrl}", " ").strip();
        return c.length() > max ? c.substring(0, max) : c;
    }
}
