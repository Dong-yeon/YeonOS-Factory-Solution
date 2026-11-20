package io.smarthappy.module.production.link;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 착수 게이트 확장점 (docs/25 4.21, docs/29_P3 12.9 — MES 정의, 다른 모듈(QMS)이 빈으로 구현, 여러 개면 {@code @Order} 순).
 *
 * - MES 가 POP 착수(START)에서 자격 게이트(QualGate) 바로 다음·이벤트 기록 전에 모든 빈을 부르고 **가장 나쁜 mode** 를 쓴다
 *   (BLOCK > WARN > OK). BLOCK = 착수 거부, WARN = 자격 경고와 같은 한 번의 확인(ack)
 * - 착수 트랜잭션 안(공정 행 FOR UPDATE 뒤) — 구현은 **읽기만, 잠금 없음**(FOR SHARE 도 안 씀), 다른 모듈 표는 계약으로만 읽는다
 * - 예외를 던지지 않게 쓴다. 던지면 MES 가 빈마다 세이브포인트로 되돌리고 이유 GATE_ERROR + 정책 START_GATE_ON_ERROR(기본 BLOCK)
 * - 공정 화면 미리보기에서도 부른다({@link StartContext#preview()} = true, 읽기 전용 트랜잭션)
 * 구현 빈이 없으면 자격 게이트만(지금과 같다).
 */
public interface StartGate {

    String OK = "OK";
    String WARN = "WARN";
    String BLOCK = "BLOCK";

    /** 결과·기록에 쓰는 출처 — 예 "QMS"(대문자 1~20자) */
    String key();

    GateVerdict check(StartContext ctx);

    /** 착수하려는 공정 — itemId = 지시 품목, equipId = 공정 배정 설비(없으면 null), at = 지금(회사 시계) */
    record StartContext(String plantCd, long woId, long woProcId, long procId, long itemId, Long equipId, long workerId,
            long terminalId, LocalDate workDay, Instant at, boolean preview) {
    }

    /** mode = OK·WARN·BLOCK, reasons = 경고·차단 이유(OK 면 빈 목록) */
    record GateVerdict(String mode, List<GateReason> reasons) {

        public static final GateVerdict PASS = new GateVerdict(OK, List.of());
    }

    /**
     * 이유 — code = 기록용 짧은 코드(예 QMS_FIRST_PENDING), messageKey = 서버 문구 키(구현 모듈의 ko/en 메시지에 정의, js. 아님),
     * args = 문구 인자, refType·refId = 참조(예 "LOT"·lot_id, 없으면 null)
     */
    record GateReason(String code, String messageKey, List<Object> args, String refType, Long refId) {
    }
}
