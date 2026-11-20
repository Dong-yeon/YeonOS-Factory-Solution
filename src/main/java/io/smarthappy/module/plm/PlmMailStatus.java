package io.smarthappy.module.plm;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import io.smarthappy.module.plm.PlmDtos.MailRow;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailStatus;
import lombok.RequiredArgsConstructor;

/**
 * 고객 메일 발송 결과(32장 M1~M4 — PCN·PPAP 37장 P7 공통): outbox_id 를 모아 플랫폼 statuses 를 500개씩. 지금 회사의 외부 메일만 돌아오고
 * (18.5 R1) 못 찾은 줄은 상태를 비운다. 문구는 플랫폼 키(mail.state.*·mail.reason.*)를 보는 사람 언어로
 */
@Component
@RequiredArgsConstructor
class PlmMailStatus {

    /** statuses 한 번의 상한(플랫폼 18.5 R5) */
    static final int STATUS_CHUNK = 500;

    private final MailService mail;
    private final MessageSource messages;

    void fill(List<? extends MailRow> rows) {
        List<UUID> ids = rows.stream().map(MailRow::getOutboxId).filter(Objects::nonNull).distinct().map(UUID::fromString).toList();
        Map<UUID, MailStatus> st = new HashMap<>();
        for (int i = 0; i < ids.size(); i += STATUS_CHUNK) {
            st.putAll(mail.statuses(ids.subList(i, Math.min(ids.size(), i + STATUS_CHUNK))));
        }
        Locale locale = LocaleContextHolder.getLocale();
        for (MailRow m : rows) {
            MailStatus s = m.getOutboxId() == null ? null : st.get(UUID.fromString(m.getOutboxId()));
            if (s == null) {
                continue;
            }
            m.setMailState(s.state().name());
            m.setStateNm(messages.getMessage("mail.state." + s.state().name(), null, locale));
            m.setAcceptedAt(s.sentAt());
            if (s.reason() != null) {
                m.setMailReason(s.reason().name());
                m.setReasonNm(messages.getMessage("mail.reason." + s.reason().name(), null, locale));
            }
        }
    }
}
