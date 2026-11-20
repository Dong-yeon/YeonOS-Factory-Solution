package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pms.GateDtos.AprLineRow;
import io.smarthappy.module.pms.GateDtos.CheckRow;
import io.smarthappy.module.pms.GateDtos.CondRow;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.pms.GateDtos.HistRow;
import io.smarthappy.module.pms.GateDtos.ReviewRow;
import io.smarthappy.module.pms.GateDtos.TemplateItemRow;

/** PMS 3단계 산출물·게이트 심사·이슈 생성 (docs/32 PM3). 회사 범위는 RLS */
@Mapper
public interface GateMapper {

    // ---------- 템플릿 산출물 칸·점검 문구 ----------

    List<TemplateItemRow> findTemplateItems(@Param("tmplId") long tmplId);

    TemplateItemRow findTemplateItem(@Param("id") long id);

    Long findTemplateItemTmplId(@Param("id") long id);

    long insertTemplateItem(@Param("tmplId") long tmplId, @Param("ttId") long ttId, @Param("kind") String kind,
            @Param("itemNm") String itemNm, @Param("requiredYn") String requiredYn, @Param("formDocNo") String formDocNo,
            @Param("by") String by);

    int deleteTemplateItem(@Param("id") long id);

    int deleteTemplateItemsOfTask(@Param("ttId") long ttId);

    // ---------- 게이트 점검 문구(프로젝트) ----------

    int insertTaskCheck(@Param("projId") long projId, @Param("taskId") long taskId, @Param("seq") int seq,
            @Param("checkNm") String checkNm, @Param("by") String by);

    List<String> findTaskChecks(@Param("taskId") long taskId);

    int deleteTaskChecks(@Param("taskId") long taskId);

    // ---------- 산출물 ----------

    List<DelivRow> findDeliverables(@Param("projId") long projId);

    DelivRow findDeliverable(@Param("id") long id);

    Long findDeliverableProjId(@Param("id") long id);

    /** 산출물이 속한 프로젝트 상태 — 없으면(다른 회사 포함)·QMS 문서에 연결된 칸이면 null(파일을 받지 않는다) */
    String findDeliverableProjStatus(@Param("id") long id);

    /** QMS 문서 리비전 연결(D6) — 늘 SUBMITTED 로, 배포본이면 이어서 {@link #approveFromDoc} */
    int linkDeliverableDoc(@Param("id") long id, @Param("refType") String refType, @Param("refId") long refId, @Param("rowVer") int rowVer,
            @Param("by") String by);

    /** 연결한 리비전이 배포됨 → APPROVED (제출 중·문서 연결 칸만) */
    int approveFromDoc(@Param("id") long id, @Param("note") String note, @Param("by") String by);

    /** 배포·확정을 기다리는 연결 칸(제출 중 — QMS 문서·PPAP·APQP) */
    List<DelivRow> findDocPending(@Param("projId") long projId);

    /** LINK 이력 — docRevId·ppapId·fmeaId·cpId 중 하나 */
    int insertHistDoc(@Param("projId") long projId, @Param("delivId") long delivId, @Param("docRevId") Long docRevId,
            @Param("ppapId") Long ppapId, @Param("fmeaId") Long fmeaId, @Param("cpId") Long cpId, @Param("note") String note,
            @Param("by") String by);

    /** PPAP 에 연결된 산출물 수(프로젝트 ECO 를 바꾸지 못하게, D18) */
    int countPpapLinked(@Param("projId") long projId);

    /** 프로젝트 ECO — 없으면 null */
    Long findProjectEcoId(@Param("projId") long projId);

    int lockFile(@Param("fileId") long fileId, @Param("by") String by);

    long insertDeliverable(@Param("projId") long projId, @Param("taskId") long taskId, @Param("delivNm") String delivNm,
            @Param("requiredYn") String requiredYn, @Param("formDocNo") String formDocNo, @Param("ownerUserId") Long ownerUserId, @Param("dueDate") LocalDate dueDate,
            @Param("by") String by);

    int updateDeliverable(@Param("id") long id, @Param("delivNm") String delivNm, @Param("requiredYn") String requiredYn,
            @Param("formDocNo") String formDocNo, @Param("ownerUserId") Long ownerUserId, @Param("dueDate") LocalDate dueDate, @Param("rowVer") int rowVer,
            @Param("by") String by);

    int submitDeliverable(@Param("id") long id, @Param("fileId") long fileId, @Param("rowVer") int rowVer, @Param("by") String by);

    /** status = APPROVED·REJECTED·WAIVED·NONE(면제 풀기) */
    int decideDeliverable(@Param("id") long id, @Param("status") String status, @Param("note") String note,
            @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteDeliverable(@Param("id") long id);

    int countDeliverableHist(@Param("id") long id);

    int insertHist(@Param("projId") long projId, @Param("delivId") long delivId, @Param("action") String action,
            @Param("fileId") Long fileId, @Param("note") String note, @Param("by") String by);

    List<HistRow> findHist(@Param("delivId") long delivId);

    /** 작업 삭제 전: 이 작업의 산출물 칸 중 이력 있는 것 수 */
    int countDeliverablesWithHist(@Param("taskId") long taskId);

    int deleteDeliverablesOfTask(@Param("taskId") long taskId);

    int countReviewsOfTask(@Param("taskId") long taskId);

    int countIssuesOfTask(@Param("taskId") long taskId);

    /** 종료 검사: 필수인데 승인·면제 아닌 산출물 수, 닫히지 않은 이슈 수 */
    int countOpenRequired(@Param("projId") long projId);

    int countOpenIssues(@Param("projId") long projId);

    // ---------- 게이트 심사 ----------

    List<ReviewRow> findReviewsOfProject(@Param("projId") long projId);

    ReviewRow findReview(@Param("id") long id);

    /** 행 잠금 — 같은 심사 저장·상신·결재 끝을 줄 세운다 */
    String lockReview(@Param("id") long id);

    int nextReviewNo(@Param("gateTaskId") long gateTaskId);

    long insertReview(@Param("projId") long projId, @Param("gateTaskId") long gateTaskId, @Param("no") int no,
            @Param("reviewDate") LocalDate reviewDate, @Param("by") String by);

    int updateReview(@Param("id") long id, @Param("reviewDate") LocalDate reviewDate, @Param("decision") String decision,
            @Param("summary") String summary, @Param("rowVer") int rowVer, @Param("by") String by);

    int toApproval(@Param("id") long id, @Param("reqCnt") int reqCnt, @Param("readyCnt") int readyCnt,
            @Param("rowVer") int rowVer, @Param("by") String by);

    int setAprId(@Param("id") long id, @Param("aprId") long aprId);

    int backToDraft(@Param("id") long id, @Param("by") String by);

    int decideReview(@Param("id") long id, @Param("reqCnt") int reqCnt, @Param("readyCnt") int readyCnt, @Param("by") String by);

    int cancelReview(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<CheckRow> findChecks(@Param("reviewId") long reviewId);

    int deleteChecks(@Param("reviewId") long reviewId);

    int insertCheck(@Param("reviewId") long reviewId, @Param("seq") int seq, @Param("checkNm") String checkNm,
            @Param("result") String result, @Param("note") String note);

    List<CondRow> findConds(@Param("reviewId") long reviewId);

    int deleteConds(@Param("reviewId") long reviewId);

    int insertCond(@Param("reviewId") long reviewId, @Param("seq") int seq, @Param("title") String title,
            @Param("ownerUserId") Long ownerUserId, @Param("dueDate") LocalDate dueDate);

    int setCondIssue(@Param("condId") long condId, @Param("issueId") long issueId);

    List<AprLineRow> findAprLines(@Param("aprId") long aprId);

    // ---------- 이슈(조건부 통과 조건) ----------

    long insertGateIssue(@Param("issueNo") String issueNo, @Param("projId") long projId, @Param("taskId") long taskId,
            @Param("title") String title, @Param("ownerUserId") Long ownerUserId, @Param("dueDate") LocalDate dueDate,
            @Param("reviewId") long reviewId, @Param("by") String by);
}
