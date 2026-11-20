package io.smarthappy.platform.notify;

import java.util.List;

import org.springframework.stereotype.Component;

/** 플랫폼 알림 이벤트 (docs/36 11.3·12.3) */
@Component
public class PlatformNotifyEvents implements NotifyEventSource {

    /** 내 결재 차례 */
    public static final String APR_TODO = "APR_TODO";
    /** 결재 위임이 시작됨 — 대리에게 "지금 대신 처리할 결재 n건" 한 번 (docs/36 14.8) */
    public static final String APR_DELEG_START = "APR_DELEG_START";
    /** 대리가 사용 중지돼 내 위임이 끝남 — 위임자에게 (docs/36 16.6) */
    public static final String APR_DELEG_ENDED = "APR_DELEG_ENDED";
    /** 내가 올린 결재가 끝남(승인·반려) */
    public static final String APR_DONE = "APR_DONE";
    /** 오늘 확인할 일 하루 요약 */
    public static final String DUE_DIGEST = "DUE_DIGEST";
    /** 보안 경고(접속 로그 ALERT) — 로그 화면을 볼 수 있는 사람에게, 이벤트별 한 시간 한 번 (docs/36 12.3) */
    public static final String SECURITY_ALERT = "SECURITY_ALERT";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(APR_TODO, true, 10), new NotifyEventDef(APR_DELEG_START, true, 15),
                new NotifyEventDef(APR_DELEG_ENDED, true, 16),
                new NotifyEventDef(APR_DONE, true, 20),
                new NotifyEventDef(DUE_DIGEST, true, 30), new NotifyEventDef(SECURITY_ALERT, true, 40));
    }
}
