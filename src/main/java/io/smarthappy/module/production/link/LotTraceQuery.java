package io.smarthappy.module.production.link;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * LOT 계보 추적 조회 계약 (docs/25 4.49 — WMS 출하 추적, 사용자 결정 W-5 "SN 대신 LOT 추적"의 전제). 다른 모듈이 {@code lot_genealogy} 를 직접 읽지 않게.
 * 읽기만, 잠금 없음, 부르는 쪽 트랜잭션 안(없으면 읽기 전용으로 연다). MES 가 없는 설치본은 {@link #NONE}.
 *
 * - forward = 어디로 갔나(부모 → 자식: 투입·분할·병합·재표시·재작업 등), backward = 어디서 왔나(자식 → 부모)
 * - 루트마다 자기 자신이 depth 0 줄(via·relType null). 없는·다른 회사 LOT 은 루트가 되지 않는다(RLS, 예외 없음)
 * - **살아 있는 계보만**: 역분개 행과 역분개된 원래 행 둘 다 뺀다(사무 LOT 추적과 같은 규칙)
 * - 같은 (부모, 자식, 종류)의 여러 계보 행은 한 줄. 경로에 이미 있는 LOT 에 다시 닿으면 그 줄에 {@code cycle = true} 를 달고 더 따라가지 않는다
 * - **잘림은 조용히 넘기지 않는다**: 깊이 maxDepth 에서 더 이어지는 계보가 있거나 줄이 {@link #MAX_ROWS} 를 넘으면 {@code truncated = true}
 *   (줄은 MAX_ROWS 까지). 리콜 범위가 덜 보인 채로 끝나지 않게 부르는 쪽이 이 플래그를 보여 줘야 한다
 * - LOT 이 {@link #MAX_IDS} 를 넘거나 maxDepth 가 0~{@link #MAX_DEPTH} 밖이면 IllegalArgumentException(호출 코드 오류)
 */
public interface LotTraceQuery {

    /** 한 번에 받을 수 있는 루트 LOT 수 — {@link LotLookup#MAX_IDS} 와 같다 */
    int MAX_IDS = LotLookup.MAX_IDS;
    /** 깊이 상한(루트 = 0) */
    int MAX_DEPTH = 10;
    /** 한 번 조회의 줄 상한(루트 줄 포함, 모든 루트 합) — 사무 LOT 추적 화면과 같다 */
    int MAX_ROWS = 5000;

    /**
     * 한 줄 — viaLotId = 이 LOT 에 닿기 직전 LOT(루트면 null), relType = 그 연결의 계보 종류(CONSUME·SPLIT·MERGE·RELABEL·REWORK·REPAIR·SORT, 루트면 null),
     * cycle = 이 LOT 이 경로에 이미 있음(여기서 멈춤)
     */
    record TraceRow(long rootLotId, long lotId, int depth, Long viaLotId, String relType, boolean cycle) {
    }

    /** rows 순서 = 루트 lot_id → 경로 순(깊이 우선), truncated = 깊이·줄 상한에 닿아 덜 보였을 수 있음 */
    record TraceResult(List<TraceRow> rows, boolean truncated) {

        public static final TraceResult EMPTY = new TraceResult(List.of(), false);
    }

    /** 조회 없음 — MES 모듈이 없는 설치본(빈 없음) */
    LotTraceQuery NONE = new LotTraceQuery() {
        @Override
        public TraceResult forward(Collection<Long> lotIds, int maxDepth) {
            return TraceResult.EMPTY;
        }

        @Override
        public TraceResult backward(Collection<Long> lotIds, int maxDepth) {
            return TraceResult.EMPTY;
        }
    };

    /** 어디로 갔나 — 중복·null id 는 한 번·무시 */
    TraceResult forward(Collection<Long> lotIds, int maxDepth);

    /** 어디서 왔나 — 중복·null id 는 한 번·무시 */
    TraceResult backward(Collection<Long> lotIds, int maxDepth);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static LotTraceQuery of(ObjectProvider<LotTraceQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
