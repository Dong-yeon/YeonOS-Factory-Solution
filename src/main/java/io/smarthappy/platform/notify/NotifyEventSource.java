package io.smarthappy.platform.notify;

import java.util.List;

/** 모듈이 내놓는 알림 이벤트 목록 — 빈으로 등록하면 {@link NotificationService} 가 모은다(코드 중복이면 기동 실패) */
public interface NotifyEventSource {

    List<NotifyEventDef> events();
}
