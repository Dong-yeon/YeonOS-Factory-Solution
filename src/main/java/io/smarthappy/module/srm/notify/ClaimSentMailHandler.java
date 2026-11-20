package io.smarthappy.module.srm.notify;

import java.util.Map;

import org.springframework.stereotype.Component;

/** 토픽 SRM_CLAIM_SENT — 협력사 클레임 보내기 통지(docs/31 4.12.4, 발주 통지와 같은 길: 아웃박스 → 가짜 메일함) */
@Component
public class ClaimSentMailHandler extends PoMailHandler {

    public static final String TOPIC = "SRM_CLAIM_SENT";

    public ClaimSentMailHandler(SrmMailbox mailbox) {
        super(mailbox);
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    String subject(Map<String, Object> p) {
        return "[클레임] " + text(p, "claimNo") + " · " + text(p, "title") + " · " + text(p, "compNm");
    }

    @Override
    String body(Map<String, Object> p) {
        String charge = text(p, "chargeAmt").isEmpty() ? "" : "\n청구 금액(표기) " + text(p, "chargeAmt") + " " + text(p, "currencyCd");
        String due = text(p, "replyDue").isEmpty() ? "" : "\n회신 기한 " + text(p, "replyDue");
        return text(p, "custNm") + " 귀하\n클레임 " + text(p, "claimNo") + " — " + text(p, "title") + "\n근거 부적합 " + text(p, "ncrCnt") + "건"
                + charge + due + "\n원인과 대책을 회신해 주십시오.";
    }
}
