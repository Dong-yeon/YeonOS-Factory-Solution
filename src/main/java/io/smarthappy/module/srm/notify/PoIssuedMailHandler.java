package io.smarthappy.module.srm.notify;

import java.util.Map;

import org.springframework.stereotype.Component;

/** 토픽 SRM_PO_ISSUED — 발주 발행(개정 포함) 통지 */
@Component
public class PoIssuedMailHandler extends PoMailHandler {

    public static final String TOPIC = "SRM_PO_ISSUED";

    public PoIssuedMailHandler(SrmMailbox mailbox) {
        super(mailbox);
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    String subject(Map<String, Object> p) {
        int rev = Integer.parseInt(text(p, "rev").isEmpty() ? "0" : text(p, "rev"));
        return "[발주" + (rev > 0 ? " 개정 " + rev : "") + "] " + text(p, "poNo") + " · " + text(p, "compNm");
    }

    @Override
    String body(Map<String, Object> p) {
        return text(p, "custNm") + " 귀하\n발주 " + text(p, "poNo") + " (개정 " + text(p, "rev") + ")\n품목 " + text(p, "lineCnt")
                + "건 · 합계 " + text(p, "totalAmt") + " " + text(p, "currencyCd") + "\n가장 이른 요구 납기 " + text(p, "firstDue")
                + "\n납기 회신을 부탁드립니다.";
    }
}
