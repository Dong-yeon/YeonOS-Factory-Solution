package io.smarthappy.module.production.link;

import java.util.List;

/**
 * 공정 완료판정 경고 확장점 (docs/25 4.46 — MES 정의, 다른 모듈(QMS 종품 판정 등)이 빈으로 구현, 여러 개면 {@code @Order} 순).
 *
 * - MES 가 완료판정 미리보기(지시 완료판정 목록·공정 close-check)와 판정(close)에서 모든 빈을 부르고 경고를 모은다
 * - **막지 않는다**: 결과는 막는 이유(problems)와 다른 목록(notices)으로만 보인다 — 판정은 경고가 있어도 그대로 된다
 * - 미리보기 = 읽기 전용 트랜잭션({@link CloseContext#preview()} = true), 판정 = 판정 트랜잭션 안·판정 행을 쓴 뒤(false)
 * - 구현은 **읽기만, 잠금 없음**(FOR UPDATE·FOR SHARE 안 씀), 다른 모듈 표는 계약으로만 읽는다
 * - 예외를 던지지 않게 쓴다. 던지면 MES 가 빈마다 세이브포인트로 되돌리고 로그만 남긴다(그 빈의 경고 없음, 판정은 그대로)
 * 구현 빈이 없으면 경고 없음(빈 목록).
 */
public interface CloseNotice {

    List<Notice> notices(CloseContext ctx);

    /** 판정하려는 공정 — itemId = 지시 품목, outputLotIds = 이 공정 산출 LOT(실적·재작업/수리 처리 양품, 중복 없음·오름차순) */
    record CloseContext(String plantCd, long woId, long woProcId, long procId, long itemId, List<Long> outputLotIds, boolean preview) {
    }

    /**
     * 경고 한 줄 — code = 기록용 짧은 코드(예 QMS_FINAL_PENDING), messageKey = 서버 문구 키(구현 모듈의 ko/en 메시지에 정의, js. 아님),
     * args = 문구 인자, refType·refId = 참조(예 "LOT"·lot_id, 없으면 null)
     */
    record Notice(String code, String messageKey, List<Object> args, String refType, Long refId) {
    }
}
