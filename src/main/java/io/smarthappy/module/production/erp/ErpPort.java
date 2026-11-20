package io.smarthappy.module.production.erp;

import java.util.Map;

/**
 * ERP 연계 경계 (docs/07 5.2, docs/25 4.11). 업무 코드는 ERP 를 모르고 아웃박스에 발행만 한다 — 송신기가 이 포트를 부른다.
 *
 * 트랜잭션·회사 범위 밖에서 불린다(DB 금지). 필요한 값은 발행 때 본문에 다 실려 온다. 실제 어댑터(이카운트 등)는 P6.
 */
public interface ErpPort {

    /** 회사 정책 ERP_SYSTEM 의 값 (FAKE …) */
    String system();

    /** 전표 하나 보내기. 예외 대신 결과로 — 읽기 타임아웃은 UNKNOWN(상대가 받았을 수 있다) */
    ErpResult post(ErpDoc doc);

    /** 그 업무 키로 상대에 생긴 전표 수 — UNKNOWN 뒤 사람이 확인하는 길 (멱등 없는 상대에서 중복을 찾는다) */
    int lookup(String compCd, String bizKey);

    /** @param kind WO_RESULT / RECEIPT */
    record ErpDoc(String compCd, String kind, String bizKey, Map<String, Object> body) {
    }

    /**
     * detail = **짧은 사유만**(사유 코드·HTTP 상태 수준, 예 "400 ITEM_UNMAPPED"·"429 too many requests"·"read timeout after store").
     * **상대 응답 본문은 싣지 않는다**(사용자 결정 Q-O1 2026-10-30, docs/36 19장 O13) — 송신기가 아웃박스 last_error(연계 현황 화면)에 남기므로
     * 개인정보·거래처 정보가 섞일 수 있는 본문은 어댑터가 버린다. 송신기도 한 번 더 거른다({@link ErpOutboundHandler#reason}).
     */
    record ErpResult(Kind kind, String externalNo, String detail) {
        public static ErpResult ok(String externalNo) {
            return new ErpResult(Kind.OK, externalNo, null);
        }

        public static ErpResult of(Kind kind, String detail) {
            return new ErpResult(kind, null, detail);
        }
    }

    enum Kind {
        /** 전표 생성 */
        OK,
        /** 일시 오류(5xx·연결) — 다시 보내면 된다 */
        RETRY,
        /** 호출 제한(429) */
        RATE_LIMITED,
        /** 4xx — 매핑 없음·형식. 사람이 고쳐야 한다 */
        REJECTED,
        /** 읽기 타임아웃 — 상대가 받았는지 모른다. 멱등이 확인된 채널이 아니면 자동 재전송 금지(07 D3) */
        UNKNOWN
    }
}
