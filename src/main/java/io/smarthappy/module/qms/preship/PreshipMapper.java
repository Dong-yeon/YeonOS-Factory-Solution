package io.smarthappy.module.qms.preship;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.preship.PreshipDtos.InspHead;
import io.smarthappy.module.qms.preship.PreshipDtos.LineSave;
import io.smarthappy.module.qms.preship.PreshipDtos.LiveInsp;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchRow;
import io.smarthappy.module.qms.preship.PreshipDtos.RequiredItem;
import io.smarthappy.module.qms.preship.PreshipDtos.ResultRow;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipRow;
import io.smarthappy.module.qms.preship.PreshipDtos.TplLine;
import io.smarthappy.module.qms.preship.PreshipDtos.TplRow;
import io.smarthappy.module.qms.preship.PreshipDtos.TplSave;
import io.smarthappy.platform.common.query.PageQuery;

/** 출하 전 검수 (docs/78) — 회사 범위는 RLS. 출하·품목·NCR 표는 읽기만 */
@Mapper
public interface PreshipMapper {

    // ---------------------------------------------------------------- 양식

    List<TplRow> findTplPage(@Param("kw") String keywordLike, @Param("useYn") String useYn, @Param("page") PageQuery page);

    long countTpl(@Param("kw") String keywordLike, @Param("useYn") String useYn);

    TplRow findTpl(@Param("id") long id);

    /** 사용 중·중지 모두, 순번 순 */
    List<TplLine> findTplLines(@Param("id") long id);

    Long itemIdByCd(@Param("cd") String itemCd);

    Long classIdByCd(@Param("cd") String classCd);

    long insertTpl(@Param("f") TplSave f, @Param("by") String by);

    int updateTpl(@Param("id") long id, @Param("f") TplSave f, @Param("by") String by);

    long insertTplLine(@Param("tplId") long tplId, @Param("l") LineSave l, @Param("by") String by);

    /** 바뀐 칸이 있을 때만 row_ver 증가 — 0 = 다른 양식의 id 이거나 그대로 */
    int updateTplLine(@Param("tplId") long tplId, @Param("l") LineSave l, @Param("by") String by);

    int disableTplLines(@Param("tplId") long tplId, @Param("keep") List<Long> keepIds, @Param("by") String by);

    // ---------------------------------------------------------------- 출하 목록·검수

    List<ShipRow> findShipPage(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("kw") String keywordLike,
            @Param("insp") String inspStatus, @Param("req") boolean onlyRequired, @Param("page") PageQuery page);

    long countShip(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("kw") String keywordLike,
            @Param("insp") String inspStatus, @Param("req") boolean onlyRequired);

    ShipRow findShip(@Param("shipId") long shipId);

    /** 출하를 잠근다(FOR SHARE) — 검수 시작이 출하 확정·줄 변경과 줄 서게. 상태 */
    String lockShip(@Param("shipId") long shipId);

    /** 양식(사용 중 항목 1개 이상)이 걸린 출하 품목과 그 양식 — 품목 양식 > 품목군 양식, 품목 코드 순 */
    List<RequiredItem> requiredItems(@Param("shipId") long shipId);

    long insertInsp(@Param("no") String inspNo, @Param("shipId") long shipId, @Param("by") String by);

    /** 양식 사용 중 항목을 판정 줄로 복사 */
    int insertResults(@Param("inspId") long inspId, @Param("itemId") long itemId, @Param("tplId") long tplId, @Param("by") String by);

    InspHead findInsp(@Param("inspId") long inspId);

    InspHead findLiveInspOfShip(@Param("shipId") long shipId);

    InspHead lockInsp(@Param("inspId") long inspId);

    InspHead shareInsp(@Param("inspId") long inspId);

    List<ResultRow> findResults(@Param("inspId") long inspId);

    ResultRow findResult(@Param("resultId") long resultId);

    int countFiles(@Param("resultId") long resultId);

    int judge(@Param("resultId") long resultId, @Param("rowVer") int rowVer, @Param("judge") String judge, @Param("remark") String remark,
            @Param("by") String by);

    Long openPunchOf(@Param("resultId") long resultId);

    long insertPunch(@Param("inspId") long inspId, @Param("resultId") long resultId, @Param("issue") String issue, @Param("by") String by);

    int updateHead(@Param("inspId") long inspId, @Param("rowVer") int rowVer, @Param("inspector") String inspector,
            @Param("witness") String witness, @Param("remark") String remark, @Param("by") String by);

    int markDone(@Param("inspId") long inspId, @Param("by") String by);

    int markOpen(@Param("inspId") long inspId, @Param("by") String by);

    int markCancelled(@Param("inspId") long inspId, @Param("reason") String reason, @Param("by") String by);

    int countUnjudged(@Param("inspId") long inspId);

    /** 열린 미결 중 조건부 출하 허용이 없는 것 */
    int countBlockingPunch(@Param("inspId") long inspId);

    // ---------------------------------------------------------------- 미결

    List<PunchRow> findPunchPage(@Param("status") String status, @Param("kw") String keywordLike, @Param("page") PageQuery page);

    long countPunch(@Param("status") String status, @Param("kw") String keywordLike);

    PunchRow findPunch(@Param("punchId") long punchId);

    PunchRow lockPunch(@Param("punchId") long punchId);

    int closePunch(@Param("punchId") long punchId, @Param("rowVer") int rowVer, @Param("action") String action, @Param("by") String by);

    int allowPunch(@Param("punchId") long punchId, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    int linkNcr(@Param("punchId") long punchId, @Param("rowVer") int rowVer, @Param("ncrId") Long ncrId, @Param("by") String by);

    Long ncrIdByNo(@Param("no") String ncrNo);

    /** 재확인 OK — 미결 닫기 안에서만(판정 rowVer 확인) */
    int recheckOk(@Param("resultId") long resultId, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------------------------------------------------------------- 출하 확정 확인·첨부 잠금

    /** 양식이 걸린 출하 품목 id */
    List<Long> requiredItemIds(@Param("shipId") long shipId);

    LiveInsp liveInsp(@Param("shipId") long shipId);

    List<Long> coveredItemIds(@Param("inspId") long inspId);

    /** 첨부 잠금 — 판정 줄의 머리 상태와 열린 미결 여부("OPEN" / "DONE:Y" / "DONE:N" / "CANCELLED"), 없으면 null */
    String fileLockState(@Param("resultId") long resultId);
}
