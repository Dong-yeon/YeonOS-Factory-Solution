package io.smarthappy.module.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.CompletionDtos.NoticeLine;
import io.smarthappy.module.production.link.CloseNotice;
import io.smarthappy.module.production.link.CloseNotice.CloseContext;
import io.smarthappy.module.production.link.CloseNotice.Notice;

/**
 * 완료판정 경고 빈들을 부른다 (docs/25 4.46). 막지 않는다 — 빈마다 세이브포인트(NESTED)로, 빈이 예외(SQL 오류 포함)를 내면
 * 되돌리고 로그만 남긴다(그 빈의 경고 없음). 판정 트랜잭션이 aborted 가 되지 않는다.
 */
@Component
class CloseNotices {

    private static final Logger log = LoggerFactory.getLogger(CloseNotices.class);

    private final ObjectProvider<CloseNotice> beans;
    private final TransactionTemplate nested;
    private final MessageSource messages;

    CloseNotices(ObjectProvider<CloseNotice> beans, PlatformTransactionManager txm, MessageSource messages) {
        this.beans = beans;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.messages = messages;
    }

    /** 부르는 쪽 트랜잭션 안에서(판정 = 쓰기, 미리보기 = 읽기 전용). 빈이 없으면 빈 목록 */
    List<NoticeLine> collect(CloseContext ctx) {
        List<CloseNotice> all = beans.orderedStream().toList();
        if (all.isEmpty()) {
            return List.of();
        }
        Locale locale = LocaleContextHolder.getLocale();
        List<NoticeLine> lines = new ArrayList<>();
        for (CloseNotice b : all) {
            List<Notice> got;
            try {
                got = nested.execute(s -> b.notices(ctx));
            } catch (RuntimeException e) {
                log.warn("close notice {} failed on wo_proc {} — ignored", b.getClass().getName(), ctx.woProcId(), e);
                continue;
            }
            for (Notice n : got == null ? List.<Notice>of() : got) {
                if (n == null) {
                    continue;
                }
                Object[] args = n.args() == null ? new Object[0] : n.args().toArray();
                String text = n.messageKey() == null ? n.code() : messages.getMessage(n.messageKey(), args, n.code(), locale);
                lines.add(new NoticeLine(n.code(), text, n.refType(), n.refId()));
            }
        }
        return lines;
    }
}
