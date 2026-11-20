package io.smarthappy.module.qms.link;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 부적합(NCR) 계약 (docs/29_P3 38장, 사용자 결정 R-2 (가)) — WMS 고객 반품 입고가 같은 트랜잭션에서 클레임 NCR 을 만들고·읽고·취소할 때.
 * - {@link #createForClaim}·{@link #cancelForClaim} 은 부르는 쪽 트랜잭션 안(MANDATORY). 입력이 틀리면 칸 오류(BusinessException) — 부르는 쪽 저장도 함께 실패
 * - 멱등: 같은 (refType, refId) 두 번 = 같은 NCR(상태 무관, 처음 것)
 * - 잠금 순서 = 부르는 쪽 근거 행(반품) → NCR → LOT 보류(정책 QMS_NCR_AUTO_HOLD = Y 면 MES LotHoldPort)
 * - 불량 유형은 비운 채 만든다(반품 순간엔 원인 모름) — 품질 담당이 NCR 화면에서 고른다. 처분·종결은 NCR 화면 그대로
 * - 권한(qms-ncr SAVE)은 보지 않는다 — 부르는 쪽 화면 권한으로
 * QMS 가 없는 설치본은 {@link #NONE} — 만들기는 empty(NCR 없음), 취소는 false, 상태는 빈 목록(반품 입고는 QMS 없이도 받는다).
 */
public interface NcrPort {

    /** 근거 종류 — 고객 반품 입고(docs/59) */
    String WMS_CUST_RTN = "WMS_CUST_RTN";

    /** 한 번에 상태를 읽을 수 있는 NCR 수 */
    int MAX_IDS = 500;

    /** NCR 요약 — status = OPEN·PENDING(특채 결재 중)·CLOSED·CANCELLED */
    record NcrView(long ncrId, String ncrNo, String status) {
    }

    /**
     * 클레임 NCR 만들기 요청 — refType = {@link #WMS_CUST_RTN}, refId = 반품 id, refNo = 반품 번호(서술 기본값에 씀), lotId = 받은 LOT,
     * qty = 받은 수량(LOT 단위 = 품목 기본 단위, 0 초과·소수 3자리까지), description = 서술(2,000자 안, 비우면 "고객 반품 {refNo}"), by = 만든 사람
     */
    record ClaimRequest(String refType, long refId, String refNo, long lotId, BigDecimal qty, String description, String by) {
    }

    NcrPort NONE = new NcrPort() {
        @Override
        public Optional<NcrView> createForClaim(ClaimRequest req) {
            return Optional.empty();
        }

        @Override
        public List<NcrView> ncrStatus(Collection<Long> ncrIds) {
            return List.of();
        }

        @Override
        public boolean cancelForClaim(String refType, long refId, String reason, String by) {
            return false;
        }
    };

    /** 만들거나(처음) 이미 있는 것을 돌려준다(같은 ref) */
    Optional<NcrView> createForClaim(ClaimRequest req);

    /** 상태 읽기 — 순서 = ncr_id, 중복·null 무시, 없는·다른 회사 id 는 빠짐. {@link #MAX_IDS} 초과는 IllegalArgumentException */
    List<NcrView> ncrStatus(Collection<Long> ncrIds);

    /**
     * 근거 취소(반품 입고 취소 — 결정 R-6): 그 ref 의 NCR 이 OPEN 이면 취소(이 NCR 이 잡은 LOT 보류 해제) → true, NCR 이 없으면 false,
     * OPEN 이 아니면(특채 결재 중·종결·이미 취소) BusinessException QMS_NCR_STATE(409) — 처분이 시작된 재고는 반품 취소로 되돌리지 않는다
     */
    boolean cancelForClaim(String refType, long refId, String reason, String by);

    static NcrPort of(ObjectProvider<NcrPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
