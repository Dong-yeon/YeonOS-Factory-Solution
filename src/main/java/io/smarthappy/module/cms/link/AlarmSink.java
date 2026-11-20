package io.smarthappy.module.cms.link;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 설비 알람 계약 (docs/33 11.4 Q2) — MES 수집(`EquipEventService` 의 ALARM 분기)이 CMS 알람 원장으로 넘길 때 부른다.
 * MES 는 CMS 구현을 직접 의존하지 않고 이 계약만 본다(PLM link 와 같은 모양).
 *
 * - 수집 트랜잭션 안에서 부른다(같은 트랜잭션 — 이벤트 멱등은 MES 의 `sys_inbox` 가 이미 봤다). 회사 범위는 호출 쪽 것
 * - 수집을 막지 않는다: 정의 없는 알람 코드·열린 알람 없는 해제·같은 알람 두 번 발생은 예외가 아니라 CMS 가 기록·무시로 처리
 * - 설비·장치는 호출 쪽이 이미 이 회사 것으로 확인한 id
 * - MES 는 {@link #of(ObjectProvider)} 로 받는다 — CMS 알람(C8)이 없는 설치본·C8 이전은 {@link #NONE}
 */
public interface AlarmSink {

    /** 받기만 — C8 구현 빈이 없을 때(지금 MES 동작과 같음) */
    AlarmSink NONE = new AlarmSink() {
        @Override
        public void raise(long equipId, String alarmCd, String alarmText, Instant occurredAt, Long terminalId, String eventId) {
        }

        @Override
        public void clear(long equipId, String alarmCd, Instant occurredAt, Long terminalId, String eventId) {
        }
    };

    /**
     * 알람 발생.
     *
     * @param equipId    설비(이 회사)
     * @param alarmCd    장치가 보낸 알람 코드(대문자로 정규화한 값)
     * @param alarmText  장치가 보낸 문구(없으면 null) — 정의가 없을 때 화면에 보인다
     * @param occurredAt 현장 발생 시각
     * @param terminalId 수집 장치(없으면 null)
     * @param eventId    수집 이벤트 id(추적용)
     */
    void raise(long equipId, String alarmCd, String alarmText, Instant occurredAt, Long terminalId, String eventId);

    /** 알람 해제 — 같은 설비·코드의 열린 알람을 한 번만 닫는다 */
    void clear(long equipId, String alarmCd, Instant occurredAt, Long terminalId, String eventId);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static AlarmSink of(ObjectProvider<AlarmSink> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
