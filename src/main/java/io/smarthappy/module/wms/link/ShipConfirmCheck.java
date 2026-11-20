package io.smarthappy.module.wms.link;

import java.util.List;

/**
 * 출하 확정 조건 확장 지점 (docs/72 2.1) — 다른 모듈이 "이 LOT 은 아직 내보내면 안 된다" 를 덧붙이는 곳.
 * WMS 출하 확정({@code ShipService.confirm})이 피킹 LOT 을 FOR SHARE 로 잠근 뒤·원장 SHIP 앞에서 빈을 {@code @Order} 순서로 **전부** 부르고,
 * 막는 것이 하나라도 있으면 409 {@code WMS_SHIP_CHECK_BLOCKED}(첫 건 LOT·사유, 전부 errors) — 빈이 없으면 지금 동작 그대로.
 *
 * <ul>
 * <li>구현은 <b>읽기만</b>(잠금·쓰기 없음) — 같은 데이터면 같은 결과. 출하 확정 트랜잭션 안에서 불린다</li>
 * <li>사유 문구는 messageKey(구현 모듈 블록의 messages 키, 인자 args) — WMS 가 사용자 언어로 바꾼다</li>
 * <li>쓸 자리 예: 고객 승인 대기·특채 미결 같은 "출하 전 확인" — 빈 하나를 더하면 된다(WMS 코드 변경 없음). 지금 구현 빈은 없다</li>
 * </ul>
 */
public interface ShipConfirmCheck {

    /** 막은 쪽 이름(로그·응답 구분) — 예 CUST_APPROVAL */
    String key();

    /** 막을 LOT 만 돌려준다(빈 목록 = 통과) */
    List<Block> check(ShipContext ctx);

    /** 출하 확정 한 건 — 확정할 피킹의 LOT(LOT 관리 품목만, 같은 LOT 은 한 번) */
    record ShipContext(long shipId, String shipNo, String plantCd, List<ShipLot> lots) {
    }

    /** orderLineId = 그 피킹의 수주 행(수주 단위 판단용) */
    record ShipLot(long lotId, String lotNo, long itemId, Long orderLineId) {
    }

    record Block(String key, long lotId, String lotNo, String messageKey, List<String> args) {
    }
}
