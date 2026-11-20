package io.smarthappy.platform.notify;

import java.util.regex.Pattern;

/**
 * 알림 이벤트 정의 (docs/36 11.3). 정의는 코드 — 각 모듈이 {@link NotifyEventSource} 빈으로 내놓는다.
 * 이름 문구는 {@code notify.event.{eventCd}}(ko/en), 구독 화면이 보여 준다.
 *
 * @param eventCd   대문자 코드 (sys_notify.event_cd)
 * @param defaultOn 구독 행이 없을 때 받는가
 * @param sortNo    구독 화면 순서
 */
public record NotifyEventDef(String eventCd, boolean defaultOn, int sortNo) {

    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    public NotifyEventDef {
        if (eventCd == null || !CODE.matcher(eventCd).matches()) {
            throw new IllegalArgumentException("알림 이벤트 코드 형식: " + eventCd);
        }
    }

    public String nameKey() {
        return "notify.event." + eventCd;
    }
}
