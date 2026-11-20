package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.plm.PlmDtos.AprLineRow;
import io.smarthappy.module.plm.PlmDtos.Candidate;
import io.smarthappy.module.plm.PlmDtos.CustOption;
import io.smarthappy.module.plm.PlmDtos.DocRef;
import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.EcrRow;
import io.smarthappy.module.plm.PlmDtos.ItemRef;
import io.smarthappy.module.plm.PlmDtos.MarkRow;
import io.smarthappy.module.plm.PlmDtos.RefInfo;
import io.smarthappy.module.plm.PlmDtos.TargetRow;

@Mapper
public interface PlmMapper {

    // ---------- 선택지 ----------

    ItemRef findItem(@Param("itemCd") String itemCd);

    /** 확정 리비전 → 같은 모품목·대체 번호의 작성 중 리비전 + 다른 열린 ECO 번호·이 ECO 대상 여부 (20.7) */
    List<DraftOf> draftsOf(@Param("ids") java.util.Collection<Long> bomIds, @Param("ecoId") long ecoId);

    /** 이 ECO 의 ITEM 대상 품목 id (20.7 — 2단계 이상 후보의 IN_THIS) */
    List<Long> itemTargetsOf(@Param("ecoId") long ecoId);

    @lombok.Getter
    @lombok.Setter
    class DraftOf {
        private long srcBomId;
        private long draftBomId;
        private String draftRev;
        private String openEcoNo;
        private boolean inThis;
    }

    /** 사용 중인 공장 (재고 소진 조건, 18장) */
    boolean plantUsable(@Param("plantCd") String plantCd);

    CustOption findSalesCust(@Param("custCd") String custCd);

    List<CustOption> salesCusts();

    boolean reasonUsable(@Param("code") String code);

    /** 설비·공정 코드 → id (사용 중만, 다른 회사는 RLS 로 null) */
    Long findEquip(@Param("equipCd") String equipCd);

    Long findProc(@Param("procCd") String procCd);

    /** 문서 번호 → 문서 유형 + 열린 리비전(작성 중·결재 중) + 그 리비전이 묶인 다른 열린 지시 (2단계, 읽기만) */
    DocRef findDocRef(@Param("docNo") String docNo, @Param("ecoId") Long ecoId);

    /** 작성 중 BOM·라우팅 리비전 (품목 지정 선택) */
    List<Candidate> candidates(@Param("itemId") Long itemId, @Param("limit") int limit);

    /** 대상 리비전의 지금 상태 + 다른 열린 지시(이 지시 제외) */
    RefInfo findBomRef(@Param("bomId") long bomId, @Param("ecoId") Long ecoId);

    RefInfo findRoutingRef(@Param("routingId") long routingId, @Param("ecoId") Long ecoId);

    // ---------- ECR ----------

    List<EcrRow> findEcrs(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecrId") Long ecrId, @Param("ecoId") Long ecoId, @Param("limit") int limit);

    /** grid(UI 단계 3) — findEcrs 와 같은 조건 + 페이지·정렬. ecrId·ecoId 는 늘 null */
    List<EcrRow> findEcrPage(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecrId") Long ecrId, @Param("ecoId") Long ecoId, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    /** 엑셀(docs/39 25장) — findEcrPage 와 같은 조건·정렬, 쪽 없음 */
    org.apache.ibatis.cursor.Cursor<io.smarthappy.module.plm.PlmDtos.EcrRow> cursorEcrs(@Param("status") String status,
            @Param("keyword") String keyword, @Param("itemId") Long itemId, @Param("ecrId") Long ecrId, @Param("ecoId") Long ecoId,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countEcrs(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecrId") Long ecrId, @Param("ecoId") Long ecoId);

    EcrRow lockEcr(@Param("ecrId") long ecrId);

    long insertEcr(@Param("no") String no, @Param("title") String title, @Param("reasonCd") String reasonCd,
            @Param("description") String description, @Param("itemId") Long itemId, @Param("requesterId") long requesterId,
            @Param("by") String by);

    int updateEcr(@Param("ecrId") long ecrId, @Param("rowVer") int rowVer, @Param("title") String title,
            @Param("reasonCd") String reasonCd, @Param("description") String description, @Param("itemId") Long itemId,
            @Param("by") String by);

    int closeEcr(@Param("ecrId") long ecrId, @Param("rowVer") int rowVer, @Param("status") String status,
            @Param("ecoId") Long ecoId, @Param("comment") String comment, @Param("by") String by);

    // ---------- ECO ----------

    /** custWaitOn = 고객 응답 대기 조건의 기준일(회사 오늘), null 이면 조건 없음 (docs/39 30장) */
    List<EcoRow> findEcos(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecoId") Long ecoId, @Param("custWaitOn") java.time.LocalDate custWaitOn, @Param("limit") int limit);

    /** grid(UI 단계 3, docs/39 21.2) — findEcos 와 같은 조건 + 페이지·정렬(허용 목록). ecoId 는 늘 null */
    List<EcoRow> findEcoPage(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecoId") Long ecoId, @Param("custWaitOn") java.time.LocalDate custWaitOn,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    /** 엑셀(docs/39 25장) — findEcoPage 와 같은 조건·정렬, 쪽 없음 */
    org.apache.ibatis.cursor.Cursor<EcoRow> cursorEcos(@Param("status") String status, @Param("keyword") String keyword,
            @Param("itemId") Long itemId, @Param("ecoId") Long ecoId, @Param("custWaitOn") java.time.LocalDate custWaitOn,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countEcos(@Param("status") String status, @Param("keyword") String keyword, @Param("itemId") Long itemId,
            @Param("ecoId") Long ecoId, @Param("custWaitOn") java.time.LocalDate custWaitOn);

    EcoRow lockEco(@Param("ecoId") long ecoId);

    long insertEco(@Param("no") String no, @Param("v") EcoValue v, @Param("by") String by);

    int updateEco(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("v") EcoValue v, @Param("by") String by);

    int touchEco(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("by") String by);

    int submitEco(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("by") String by);

    int setAprId(@Param("ecoId") long ecoId, @Param("aprId") long aprId);

    /** 결재 끝 — 승인이면 APPROVED, 반려·회수면 DRAFT(대상 고정 해제는 따로) */
    int finishApproval(@Param("ecoId") long ecoId, @Param("status") String status, @Param("by") String by);

    int clearBaseRowVer(@Param("ecoId") long ecoId, @Param("by") String by);

    int snapshotBaseRowVer(@Param("ecoId") long ecoId, @Param("by") String by);

    int setCustApproval(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("status") String status,
            @Param("ref") String ref, @Param("approvedOn") LocalDate approvedOn, @Param("by") String by);

    int releaseEco(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("by") String by);

    int cancelEco(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    // ---------- 대상 ----------

    List<TargetRow> findTargets(@Param("ecoId") long ecoId);

    void insertTarget(@Param("ecoId") long ecoId, @Param("seq") int seq, @Param("type") String type,
            @Param("itemId") Long itemId, @Param("bomId") Long bomId, @Param("routingId") Long routingId,
            @Param("equipId") Long equipId, @Param("procId") Long procId, @Param("docRevId") Long docRevId,
            @Param("changeDesc") String changeDesc, @Param("by") String by);

    /** 첨부 잠금 표시(D7, 한 방향) — target_id = ECO id 또는 PPAP-{id} */
    int lockFiles(@Param("targetId") String targetId, @Param("by") String by);

    /** 결재 중·승인된 지시의 도면 대상인가 — 그 문서 리비전 첨부 잠금(2단계) */
    boolean docRevInOpenEco(@Param("docRevId") long docRevId);

    int updateTarget(@Param("targetId") long targetId, @Param("seq") int seq, @Param("changeDesc") String changeDesc,
            @Param("by") String by);

    int deleteTarget(@Param("targetId") long targetId);

    // ---------- 변경 표시 (3b) ----------

    List<MarkRow> findMarks(@Param("ecoId") long ecoId, @Param("limit") int limit);

    // ---------- 결재 ----------

    List<AprLineRow> findAprLines(@Param("aprId") long aprId);

    String aprStatus(@Param("aprId") long aprId);

    String ecoStatus(@Param("ecoId") long ecoId);

    /** ECO 를 쓰는 값 묶음 (검증 끝) */
    record EcoValue(String title, String reasonCd, String description, LocalDate effectiveDate, long ownerId,
            String custApprovalReqYn, Long custId, String custApprovalStatus, String changeKind, Integer markLots,
            LocalDate markUntil, String applyCond, Long stockItemId, String stockPlantCd) {
    }

    /** 지금 회사 이름·기본 언어(변경 통지서, docs/39 29장) — RLS 로 자기 회사 한 행 */
    java.util.Map<String, Object> compHead();
}
