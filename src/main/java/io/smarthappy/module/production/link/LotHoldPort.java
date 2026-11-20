package io.smarthappy.module.production.link;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * LOT 보류 근거 계약 (docs/25 4.19, QMS Q8) — QMS(NCR·봉쇄)가 MES LOT 을 보류·해제할 때. {@code lot_master} 를 직접 고치지 않는다.
 *
 * - 부르는 쪽 트랜잭션 안(MANDATORY). 출처는 {@link #QMS_NCR}·{@link #QMS_CONTAIN}·{@link #QMS_SPC} 만(그 밖은 IllegalArgumentException)
 * - 근거마다 한 행: 같은 (LOT, 출처, 참조) 가 살아 있으면 다시 보류해도 그대로(false), 살아 있는 근거가 없으면 해제도 그대로(false)
 * - LOT 은 근거가 하나라도 살아 있는 동안 보류. MES 화면은 QMS 근거를 풀 수 없다
 * - 잠금 순서: 이 계약이 LOT 행을 잠근다(FOR UPDATE) → 근거 행. 부르는 쪽이 **이미** 잡은 자기 행 잠금(qms_insp·SPC 이벤트 …)은 괜찮다 —
 *   단 LOT 을 먼저 잡고 그 행을 잠그는 경로가 없어야 한다(거꾸로 잡는 경로가 하나라도 있으면 교착, docs/29_P3 23.3).
 *   **여러 LOT 은 lot_id 오름차순으로** 부른다(교착 방지)
 * - 없는·다른 회사 LOT 은 IllegalArgumentException(빈 값이면 "보류됐다"고 착각한다)
 * MES 가 없는 설치본은 {@link #NONE} — 보류·해제는 IllegalStateException(조용히 false 를 돌려주면 "이미 보류됨" 으로 착각한다),
 * 조회만 빈 목록.
 */
public interface LotHoldPort {

    String QMS_NCR = "QMS_NCR";
    String QMS_CONTAIN = "QMS_CONTAIN";
    /** SPC 자동 보류(QMS SV-6, docs/29_P3 23장) — refId = SPC 이벤트 id */
    String QMS_SPC = "QMS_SPC";

    /** 한 번에 읽을 수 있는 LOT 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /** 살아 있는 근거 — source = MANUAL·MES_CORRECT·QMS_NCR·QMS_CONTAIN·QMS_SPC, refId 는 MANUAL 이면 null */
    record HoldView(long holdId, long lotId, String source, Long refId, String reason, OffsetDateTime heldAt, String heldBy) {
    }

    /** MES 모듈이 없는 설치본(빈 없음) — 보류·해제는 거부, 조회는 빈 목록 */
    LotHoldPort NONE = new LotHoldPort() {
        @Override
        public boolean hold(long lotId, String source, long refId, String reason, String by) {
            throw new IllegalStateException("MES 없음 — LOT 보류 불가");
        }

        @Override
        public boolean release(long lotId, String source, long refId, String reason, String by) {
            throw new IllegalStateException("MES 없음 — LOT 보류 해제 불가");
        }

        @Override
        public List<HoldView> holds(Collection<Long> lotIds) {
            return List.of();
        }
    };

    /** 보류 근거 추가 — 새로 잡으면 true, 같은 근거가 이미 살아 있으면 false. reason 필수(500자) */
    boolean hold(long lotId, String source, long refId, String reason, String by);

    /** 그 근거 해제 — 풀면 true, 살아 있는 그 근거가 없으면 false. reason 필수(500자) */
    boolean release(long lotId, String source, long refId, String reason, String by);

    /** 살아 있는 근거(모든 출처), 순서 = lot_id → 보류 시각. 없는·다른 회사 id 는 빠진다. {@link #MAX_IDS} 초과는 IllegalArgumentException */
    List<HoldView> holds(Collection<Long> lotIds);

    /** 한 LOT 의 살아 있는 근거 */
    default List<HoldView> holds(long lotId) {
        return holds(List.of(lotId));
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static LotHoldPort of(ObjectProvider<LotHoldPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
