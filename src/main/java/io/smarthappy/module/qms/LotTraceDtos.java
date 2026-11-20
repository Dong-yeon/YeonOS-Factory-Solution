package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 출하 추적(리콜) DTO — docs/57 2.4. 읽기만 */
public final class LotTraceDtos {

    private LotTraceDtos() {
    }

    /** 조회 요청 — LOT 번호들(줄바꿈·쉼표로 나눈 것), 깊이 0~10(비우면 10), 방향 FORWARD(어디로 갔나, 비우면)·BACKWARD(어디서 왔나) */
    public record TraceForm(List<String> lotNos, Integer depth, String direction) {

        public TraceForm(List<String> lotNos, Integer depth) {
            this(lotNos, depth, null);
        }
    }

    /** 뿌리 LOT(입력에서 찾은 것) */
    public record RootLot(long lotId, String lotNo, String itemCd, String itemNm) {
    }

    /** 출하 한 줄 — 뿌리별로 다시 묶은 것(같은 출하 LOT 이 여러 뿌리에서 닿으면 뿌리마다 한 줄). path = 뿌리 → … → 출하 LOT 번호 */
    @Getter
    @Setter
    public static class ShipLine {
        private String rootLotNo;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private int depth;
        private String path;
        private long custId;
        private String custCd;
        private String custNm;
        private String shipNo;
        private String plantCd;
        private OffsetDateTime shippedAt;
        private BigDecimal qty;
        private String unitCd;
        private String pallets;
        private String ssccs;
    }

    /** 고객별 요약 — 연락 대상. qty 는 단위별(품목마다 기본 단위가 다를 수 있다) */
    public record UnitQty(String unitCd, BigDecimal qty) {
    }

    public record CustSum(long custId, String custCd, String custNm, int shipCnt, int lotCnt, List<UnitQty> qty) {
    }

    /**
     * 창고에 남은 것 — qty 는 품목 기본 단위(WMS 재고 단위). lotDate = LOT 이 생긴 날(회사 시간대 — 봉쇄 화면 후보 기간과 같은 기준, docs/29_P3 33장),
     * LOT 정보를 못 찾으면 null
     */
    public record StockLine(String lotNo, String itemCd, String itemNm, String plantCd, String path, String locUsage, String stockStatus,
            BigDecimal qty, LocalDate lotDate) {
    }

    /**
     * 원인 LOT 한 줄(역추적) — 뿌리마다 닿은 LOT(뿌리 자신 depth 0 포함). path = 뿌리 ← … ← 이 LOT, relType = 이 LOT 이 아래로 이어진 계보 종류
     * (LOT_REL_TYPE, 뿌리는 null), origin = LOT_ORIGIN, 공급사는 RECEIVED 만. leaf = 이 뿌리 안에서 더 거슬러 올라간 LOT 이 없음(가장 처음 원인 후보,
     * 잘림이면 아닐 수 있음)
     */
    @Getter
    @Setter
    public static class OriginLine {
        private String rootLotNo;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private int depth;
        private String path;
        private String relType;
        private String origin;
        private String supplierCd;
        private String supplierNm;
        private String supplierLotNo;
        private boolean leaf;
    }

    /** 공급사별 요약(역추적) — 입고 LOT 을 공급사로 묶음. 공급사 없는 입고는 supplierCustId null 한 줄. lotNos = 우리 LOT 번호, supplierLotNos = 공급사 LOT 번호 */
    public record SupplierSum(Long supplierCustId, String supplierCd, String supplierNm, int lotCnt, List<String> lotNos, List<String> supplierLotNos) {
    }

    /** LOT 의 QMS 일(37장) — 열린 NCR 번호·걸린 봉쇄 번호. 둘 다 빈 LOT 은 목록에 없다 */
    public record LotQms(String lotNo, List<String> openNcrNos, List<String> containNos) {
    }

    /**
     * 결과 — qms = 닿은 LOT 중 이미 QMS 일이 있는 것(37장 — 화면 배지, 중복 NCR·봉쇄 방지). direction = FORWARD·BACKWARD. FORWARD 는 customers·shipments 를, BACKWARD 는 origins·suppliers 를 채운다(나머지는 빈 목록).
     * stock 은 둘 다(역추적이면 원인 LOT 이 아직 남은 곳 — 같은 원인으로 다른 제품을 만들기 전에 막을 대상).
     * unknownLotNos = 찾지 못한 번호(없거나 다른 회사), lotCnt = 계보로 닿은 LOT 수(뿌리 포함),
     * truncated = 계보가 깊이·줄 상한에 걸려 덜 보였을 수 있음(MES LotTraceQuery) — 화면은 반드시 보인다
     */
    public record TraceView(String direction, int depth, List<RootLot> roots, List<String> unknownLotNos, int lotCnt, boolean truncated,
            List<CustSum> customers, List<ShipLine> shipments, List<OriginLine> origins, List<SupplierSum> suppliers, List<StockLine> stock,
            List<LotQms> qms) {
    }
}
