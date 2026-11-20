package io.smarthappy.platform.notify;

/** 셸 종 버튼 숫자에 더할 "안 읽은 것" (공지 등) — 회사 범위 트랜잭션 안에서 불린다 */
public interface InboxBadgeSource {

    /** 응답 키 (예 notice) */
    String code();

    long unread(long userId);
}
