package io.smarthappy.platform.approval;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyDigestSource;
import lombok.RequiredArgsConstructor;

/** 하루 요약 줄: 내 차례인 결재 수 (docs/36 11.3) */
@Component
@RequiredArgsConstructor
public class ApprovalDigestSource implements NotifyDigestSource {

    static final String LABEL = "notify.digest.aprTodo";

    private final JdbcTemplate jdbc;

    @Override
    public List<Due> due(LocalDate today) {
        return jdbc.query("""
                select l.approver_id, count(*)
                  from apr_line l join apr_request r on r.apr_id = l.apr_id and r.status = 'PENDING'
                 where l.status = 'CURRENT'
                 group by l.approver_id""",
                (rs, i) -> new Due(rs.getLong(1), LABEL, rs.getLong(2), null));
    }
}
