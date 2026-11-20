package io.smarthappy.platform.approval;

/**
 * 결재 문서 유형 — 업무 모듈이 빈으로 등록한다. 결재 엔진은 업무 테이블을 모르고 이 핸들러만 부른다 (01 5.4).
 *
 * 스프링 이벤트(@TransactionalEventListener) 대신 인터페이스로 둔 이유:
 * 기본 AFTER_COMMIT 리스너는 결재와 업무 상태 변경이 **다른 트랜잭션**이 되고(결재만 승인되고 업무는 그대로일 수 있음),
 * 리스너가 없어도 조용히 지나간다. 핸들러는 같은 트랜잭션에서 불리고, 없는 문서 유형은 상신부터 거부된다.
 */
public interface ApprovalDocType {

    /** 문서 유형 코드 (apr_request.doc_type, 대문자) */
    String docType();

    /** 상신 직전 업무 검증 (같은 트랜잭션) — 예: 문서가 작성 중 상태인지, 상신자가 작성자인지. 거부는 BusinessException */
    default void beforeSubmit(String bizKey, long requesterId) {
    }

    /**
     * 결재선 한 줄 처리(승인·합의·반려) — 누가 어느 줄을 어떻게 처리하는가.
     * approve = 승인·합의(false = 반려), finishes = 이 처리로 결재가 끝난다(마지막 승인 또는 반려),
     * actorId = 실제로 누른 사람, onBehalfOf = 위임으로 대신 처리하면 원 결재자(본인 차례면 null),
     * esignToken = 결재 처리 요청에 실려 온 전자서명 토큰(hb.esign, 없으면 null) — 엔진은 보지 않고 넘기기만 한다.
     * 문서 유형이 서명을 요구하면 여기서 {@code ESignService.consume} 으로 검증·소비(없음·틀림 = 403, docs/36 18.1)
     */
    record LineAct(long aprId, int seq, ApprovalService.LineType lineType, boolean approve, boolean finishes,
            long actorId, Long onBehalfOf, String esignToken) {
    }

    /**
     * 결재선 한 줄을 처리하기 직전(차례·위임 확인 뒤, 줄 상태를 바꾸기 전) — **같은 트랜잭션**. 거부는 BusinessException 으로,
     * 그러면 그 처리 전체가 롤백된다(줄·이력·알림 없음). 예: 회사 정책상 중간 합의 줄에도 전자서명을 요구(docs/39 19.3 S2).
     * 마지막 승인·반려면 이어서 {@link #onFinished} 가 같은 트랜잭션에서 불린다. 회수(요청자)는 줄 처리가 아니라 부르지 않는다
     */
    default void beforeAct(String bizKey, LineAct act) {
    }

    /** 결재함이 처리 전에 열 서명 대화상자(hb.esign) — 의미(AUTHOR·REVIEW·APPROVE)·대상 유형·대상 id·보일 제목 */
    record ESignSpec(String meaningCd, String targetType, long targetId, String title) {
    }

    /**
     * 지금 차례 줄을 이렇게(approve = 승인·합의, false = 반려) 처리할 때 서명이 필요하면 그 내용, 아니면 null(기본).
     * 공통 결재함 화면이 상세 응답의 이 값을 보고 서명 대화상자를 열어 토큰을 실어 보낸다 — 검증은 여전히 {@link #beforeAct}.
     * 화면을 위한 안내일 뿐이라 여기서 null 이어도 beforeAct 가 요구하면 403 이다(어긋나면 조용히 통과하지 않는다)
     */
    default ESignSpec esignFor(String bizKey, ApprovalService.LineType lineType, boolean approve) {
        return null;
    }

    /**
     * 결재가 끝났을 때(승인·반려·회수) 업무 상태를 바꾼다. **같은 트랜잭션** — 예외를 던지면 결재 처리 전체가 롤백된다.
     * 외부로 나가는 일(ERP 송신·메일)은 여기서 직접 하지 말고 아웃박스로 발행한다.
     */
    void onFinished(String bizKey, ApprovalStatus result);

    /**
     * 이 문서를 보여 주는 업무 화면(내 결재함의 "문서 열기", docs/36 Q21). 없으면 null — 결재함 안의 제목·결재선만.
     * 조회 조건은 {@link #linkParams} (셸 q.* — docs/37 14.2). 화면 권한은 그 화면이 다시 판정한다.
     */
    default String progCd() {
        return null;
    }

    default java.util.Map<String, String> linkParams(String bizKey) {
        return java.util.Map.of();
    }
}
