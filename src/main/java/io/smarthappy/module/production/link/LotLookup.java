package io.smarthappy.module.production.link;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * LOT 조회 계약 (docs/25 4.21, QMS Q9 R-2·PMS PM7) — 다른 모듈이 {@code lot_master} 를 직접 읽지 않게. 읽기만, 잠금 없음.
 * 없는·다른 회사 LOT 은 빠지거나 empty(예외 없음 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * MES 가 없는 설치본은 {@link #NONE}.
 */
public interface LotLookup {

    /** 한 번에 받을 수 있는 LOT 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /** woId = 생산 LOT 을 만든 지시(입고·분할 등은 null), occurredAt = LOT 이 생긴 현장 시각 */
    record LotView(long lotId, String lotNo, long itemId, String itemCd, String itemNm, Long woId, OffsetDateTime occurredAt) {
    }

    /**
     * LOT 출처 — origin = RECEIVED·PRODUCED·SPLIT·MERGE·RELABEL(코드 LOT_ORIGIN), 공급사·공급사 LOT 번호는 RECEIVED 만(ck_lot_master_received, 없으면 null)
     */
    record LotOrigin(long lotId, String origin, Long supplierCustId, String supplierCd, String supplierNm, String supplierLotNo) {
    }

    /** 조회 없음 — MES 모듈이 없는 설치본(빈 없음) */
    LotLookup NONE = new LotLookup() {
        @Override
        public List<LotView> lots(Collection<Long> lotIds) {
            return List.of();
        }

        @Override
        public Optional<LotView> byNo(String lotNo) {
            return Optional.empty();
        }
    };

    /** 여러 LOT — 순서 = lot_id, 중복·null id 는 한 번·무시, 없는·다른 회사 id 는 빠진다. {@link #MAX_IDS} 초과는 IllegalArgumentException */
    List<LotView> lots(Collection<Long> lotIds);

    /** LOT 번호로(앞뒤 공백 무시, 대소문자 그대로) — 없거나 다른 회사면 empty */
    Optional<LotView> byNo(String lotNo);

    /**
     * 지시가 만든 생산 LOT 들 — 순서 = lot_id, limit 은 1~{@link #MAX_IDS} 로 자른다(넘는 LOT 은 빠짐 — 받은 수가 limit 이면 잘렸을 수 있다).
     * 없는·다른 회사 지시는 빈 목록. 기본 구현(NONE·옛 구현) = 빈 목록 — 부르는 쪽이 다른 방법으로 대체한다 (QMS 초품·종품 짝, docs/29_P3 27.4)
     */
    default List<LotView> lotsOfWo(long woId, int limit) {
        return List.of();
    }

    /**
     * 여러 LOT 의 출처·공급사 — 순서 = lot_id, 중복·null id 는 한 번·무시, 없는·다른 회사 id 는 빠진다. {@link #MAX_IDS} 초과는 IllegalArgumentException.
     * 기본 구현(NONE·옛 구현) = 빈 목록 — 부르는 쪽은 출처를 비워 보인다 (QMS 역추적, docs/29_P3 32장)
     */
    default List<LotOrigin> origins(Collection<Long> lotIds) {
        return List.of();
    }

    /** lotsOfWo 의 limit 을 1~MAX_IDS 로 */
    static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_IDS));
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static LotLookup of(ObjectProvider<LotLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
