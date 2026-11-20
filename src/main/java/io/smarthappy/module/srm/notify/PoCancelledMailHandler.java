package io.smarthappy.module.srm.notify;

import java.util.Map;

import org.springframework.stereotype.Component;

/** 토픽 SRM_PO_CANCELLED — 발행된 발주의 취소 통지 */
@Component
public class PoCancelledMailHandler extends PoMailHandler {

    public static final String TOPIC = "SRM_PO_CANCELLED";

    public PoCancelledMailHandler(SrmMailbox mailbox) {
        super(mailbox);
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    String subject(Map<String, Object> p) {
        return "[발주 취소] " + text(p, "poNo") + " · " + text(p, "compNm");
    }

    @Override
    String body(Map<String, Object> p) {
        return text(p, "custNm") + " 귀하\n발주 " + text(p, "poNo") + " (개정 " + text(p, "rev") + ") 을 취소합니다.\n사유: "
                + text(p, "reason");
    }
}
