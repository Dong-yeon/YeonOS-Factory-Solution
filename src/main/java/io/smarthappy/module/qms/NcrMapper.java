package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.NcrDtos.DefectOption;
import io.smarthappy.module.qms.NcrDtos.DispRow;
import io.smarthappy.module.qms.NcrDtos.InspRef;
import io.smarthappy.module.qms.NcrDtos.LotRef;
import io.smarthappy.module.qms.NcrDtos.NcrRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface NcrMapper {

    List<NcrRow> findList(@Param("keywordLike") String keywordLike, @Param("statuses") List<String> statuses, @Param("source") String source,
            @Param("basis") String basis, @Param("limit") int limit);

    List<NcrRow> findPage(@Param("keywordLike") String keywordLike, @Param("statuses") List<String> statuses, @Param("source") String source,
            @Param("basis") String basis, @Param("page") PageQuery page);

    long countList(@Param("keywordLike") String keywordLike, @Param("statuses") List<String> statuses, @Param("source") String source,
            @Param("basis") String basis);

    NcrRow findById(@Param("id") long id);

    /** 상태 바꾸기 전 머리 잠금(처분 행 트리거와 같은 순서 — 머리 먼저) */
    NcrRow lockById(@Param("id") long id);

    List<DispRow> findDisps(@Param("id") long id);

    /** 이 부적합에 이어진 8D(번호 순) */
    List<NcrDtos.CapaLink> findCapas(@Param("id") long id);

    LotRef findLot(@Param("lotNo") String lotNo);

    boolean lotBaseUnit(@Param("lotId") long lotId);

    /** 첨부 잠금용 상태(없는 NCR·다른 회사 = null) */
    String ncrStatus(@Param("id") long id);

    /** 근거 검사 — FOR SHARE(LOT 보다 먼저, 검사 무효와 같은 순서) */
    InspRef findInspByNo(@Param("inspNo") String inspNo);

    List<DefectOption> findDefects();

    boolean defectActive(@Param("id") long id);

    /** ncr_id 를 먼저 받는다 — LOT 보류(포트) 뒤 INSERT(잠금 순서 LOT → qms_ncr) */
    long nextId();

    int insert(@Param("id") long id, @Param("ncrNo") String ncrNo, @Param("source") String source, @Param("lot") LotRef lot,
            @Param("inspId") Long inspId, @Param("defectId") long defectId, @Param("ncQty") BigDecimal ncQty,
            @Param("description") String description, @Param("occurredAt") OffsetDateTime occurredAt, @Param("holdYn") String holdYn,
            @Param("by") String by);

    int updateHead(@Param("id") long id, @Param("defectId") long defectId, @Param("ncQty") BigDecimal ncQty,
            @Param("description") String description, @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteDisps(@Param("id") long id);

    int insertDisp(@Param("id") long id, @Param("lineNo") int lineNo, @Param("type") String type, @Param("qty") BigDecimal qty,
            @Param("note") String note, @Param("reworkLotNo") String reworkLotNo, @Param("by") String by);

    int close(@Param("id") long id, @Param("from") String from, @Param("rowVer") Integer rowVer, @Param("by") String by);

    int submit(@Param("id") long id, @Param("aprId") long aprId, @Param("rowVer") int rowVer, @Param("by") String by);

    int reopen(@Param("id") long id, @Param("by") String by);

    int cancel(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---- 계약 NcrPort (docs/29_P3 38장)

    /** 같은 근거의 동시 만들기를 줄 세운다(트랜잭션 끝까지) */
    int lockSrc(@Param("key") String key);

    /** 근거로 찾기 — 없거나 다른 회사면 null */
    io.smarthappy.module.qms.link.NcrPort.NcrView findBySrc(@Param("srcType") String srcType, @Param("srcId") long srcId);

    /** LOT id 로(RLS — 다른 회사면 null) */
    LotRef findLotById(@Param("lotId") long lotId);

    /** 계약 NCR — 출처 CLAIM, 불량 유형·근거 검사 없음, 근거(src) 있음 */
    int insertClaim(@Param("id") long id, @Param("ncrNo") String ncrNo, @Param("lot") LotRef lot, @Param("ncQty") BigDecimal ncQty,
            @Param("description") String description, @Param("occurredAt") OffsetDateTime occurredAt, @Param("holdYn") String holdYn,
            @Param("srcType") String srcType, @Param("srcId") long srcId, @Param("by") String by);

    /** 상태 읽기 — ncr_id 순 */
    List<io.smarthappy.module.qms.link.NcrPort.NcrView> findViews(@Param("ids") List<Long> ids);
}
