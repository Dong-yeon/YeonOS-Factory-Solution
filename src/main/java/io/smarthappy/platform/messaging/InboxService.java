package io.smarthappy.platform.messaging;

import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.metrics.HbMetrics;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 수신 멱등 (01 5.8, 5.7). **처리 트랜잭션 안에서만**(MANDATORY) — 처리가 롤백되면 기록도 없어 다시 처리할 수 있다.
 * 같은 키가 동시에 오면 두 번째는 첫 번째 커밋까지 기다렸다가 중복으로 판정된다(기본키).
 */
@Service
@RequiredArgsConstructor
public class InboxService {

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    static final Pattern CONSUMER = Pattern.compile("^[A-Z][A-Z0-9_:-]{1,49}$");
    static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");

    private final JdbcTemplate jdbc;
    private final HbMetrics metrics;

    /** 처음 받은 메시지면 true(기록함), 이미 처리했으면 false */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumerCd, String messageId) {
        if (consumerCd == null || !CONSUMER.matcher(consumerCd).matches()
                || messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        boolean first = jdbc.update("""
                insert into sys_inbox (comp_cd, consumer_cd, message_id) values (?, ?, ?)
                on conflict do nothing""", TenantContext.requireCompCd(), consumerCd, messageId) == 1;
        if (!first) {
            metrics.inboxDuplicate(consumerCd);   // 운영 지표 hb_inbox_duplicate_total(docs/52 Y2)
        }
        return first;
    }

    /**
     * 화면 저장 요청의 {@value #IDEMPOTENCY_HEADER} 처리 (연타·재전송 방지). 헤더가 없으면 아무것도 하지 않는다.
     * 같은 키가 이미 처리됐으면 409 DUPLICATE_REQUEST. 소비자 = "API:{작업}" — 작업마다 키 공간을 나눈다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimRequestKey(String operation) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        String key = attrs.getRequest().getHeader(IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank()) {
            return;
        }
        if (!firstTime("API:" + operation, key.trim())) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
    }
}
