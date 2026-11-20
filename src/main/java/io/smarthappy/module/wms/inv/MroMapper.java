package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 예비품 출고·반납 원장(docs/30 12.8) — CMS 표는 읽지 않는다 */
@Mapper
public interface MroMapper {

    /** 이 품목이 원장에 한 번이라도 들어온 적 있나(D33 — 없으면 재고 관리 안 하는 소모품) */
    boolean itemTracked(@Param("itemId") long itemId);

    /** 출고 후보 칸 — 그 공장 AVAILABLE·보류 아닌 LOT, 폐기(조상 포함)·수입검사·출하 대기 위치 제외. LOT 선입(lot_id) → pick_seq → 노드 */
    List<Cell> issueCells(@Param("plantCd") String plantCd, @Param("itemId") long itemId);

    /** 칸을 칸 순서(품목·위치·LOT·상태 — InventoryService.CELL_ORDER)로 잠그고 지금 수량을 다시 읽는다 */
    List<Cell> lockCells(@Param("stockIds") List<Long> stockIds);

    /** 그 사용 예비품 줄의 칸별 출고·반납 합(역분개 포함), 처음 출고 순 — 반납 대상 */
    List<Issued> issuedOf(@Param("partId") long partId);

    /**
     * 줄마다 반납 누계(C18) — 출고 원장(ISSUE_MRO, 출처 CMS_PART)이 있는 줄만, 반납(MRO_RTN, 역분개 포함) 합. 회사 조건은 RLS. 잠금 없음
     */
    List<Returned> returnedOf(@Param("partIds") List<Long> partIds);

    @Getter
    @Setter
    class Returned {
        private long partId;
        private BigDecimal returned;
    }

    @Getter
    @Setter
    class Cell {
        private long stockId;
        private String plantCd;
        private long itemId;
        private long nodeId;
        private Long lotId;
        private String stockStatus;
        private BigDecimal qty;
    }

    @Getter
    @Setter
    class Issued {
        private Long stockId;
        private String plantCd;
        private long itemId;
        private long nodeId;
        private Long lotId;
        private String stockStatus;
        /** 출고 수량(양수) */
        private BigDecimal issued;
        /** 반납 수량(양수) */
        private BigDecimal returned;
    }
}
