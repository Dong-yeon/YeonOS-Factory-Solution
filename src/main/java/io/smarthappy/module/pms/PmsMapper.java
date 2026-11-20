package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pms.PmsDtos.ItemRef;
import io.smarthappy.module.pms.PmsDtos.ProjectFilter;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.ProjectValue;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TaskValue;
import io.smarthappy.module.pms.PmsDtos.TemplateForm;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.module.pms.PmsDtos.TemplateRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskValue;

/** PMS 프로젝트·WBS·템플릿 (docs/32 PM1) */
@Mapper
public interface PmsMapper {

    // ---------- 참조(같은 회사 — RLS) ----------

    Long findCustId(@Param("custCd") String custCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    /** 사용 중 공장 (code = plant_cd, name = plant_nm) */
    List<PmsDtos.CodeOption> findPlants();

    ItemRef findItem(@Param("itemCd") String itemCd);

    /** PM4: 공통코드(시스템 + 회사) 사용 가능 여부 */
    Boolean codeUsable(@Param("grp") String grp, @Param("code") String code);

    /** PM4: 머리 공유 잠금(FOR SHARE) — 공수·이슈를 넣는 동안 프로젝트가 닫히지 않게 */
    String lockProjectShare(@Param("id") long id);

    /** 채번 규칙이 없으면 기본 규칙을 만든다(있으면 그대로 — 회사가 바꾼 패턴을 덮지 않는다) */
    int ensureNumberingRule(@Param("ruleCd") String ruleCd, @Param("ruleNm") String ruleNm, @Param("pattern") String pattern,
            @Param("resetCycle") String resetCycle, @Param("by") String by);

    // ---------- 템플릿 ----------

    List<TemplateRow> findTemplates(@Param("useYn") String useYn, @Param("keywordLike") String keywordLike);

    List<TemplateRow> findTemplatePage(@Param("useYn") String useYn, @Param("keywordLike") String keywordLike,
            @Param("page") PageQuery page);

    long countTemplates(@Param("useYn") String useYn, @Param("keywordLike") String keywordLike);

    TemplateRow findTemplate(@Param("id") long id);

    TemplateRow findTemplateByCd(@Param("tmplCd") String tmplCd);

    long insertTemplate(@Param("f") TemplateForm f, @Param("by") String by);

    int updateTemplate(@Param("id") long id, @Param("f") TemplateForm f, @Param("by") String by);

    /** 머리 행 잠금 — 같은 템플릿 작업 편집을 줄 세운다(순서·개수 상한) */
    Integer lockTemplate(@Param("id") long id);

    List<TmplTaskRow> findTmplTasks(@Param("tmplId") long tmplId);

    TmplTaskRow findTmplTask(@Param("id") long id);

    long insertTmplTask(@Param("tmplId") long tmplId, @Param("parentId") Long parentId, @Param("seq") int seq,
            @Param("f") TmplTaskValue f, @Param("by") String by);

    // ---------- 선행 미완 통과 기록 (docs/32 4.21) ----------

    int insertDepWaive(@Param("projId") long projId, @Param("taskId") long taskId, @Param("reason") String reason,
            @Param("openPreds") String openPreds, @Param("by") String by);

    List<PmsDtos.DepWaiveRow> findDepWaives(@Param("taskId") long taskId);

    // ---------- 템플릿 선후행 (docs/32 4.18) ----------

    List<PmsDtos.TmplDepRow> findTmplDeps(@Param("tmplId") long tmplId);

    Long findTmplDepTmplId(@Param("id") long id);

    long insertTmplDep(@Param("tmplId") long tmplId, @Param("pred") long pred, @Param("succ") long succ, @Param("lag") int lag,
            @Param("type") String type,
            @Param("by") String by);

    int deleteTmplDep(@Param("id") long id);

    int deleteTmplDepsOfTask(@Param("ttId") long ttId);

    int updateTmplTask(@Param("id") long id, @Param("f") TmplTaskValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    int moveTmplTask(@Param("id") long id, @Param("parentId") Long parentId, @Param("seq") int seq, @Param("by") String by);

    int deleteTmplTask(@Param("id") long id);

    int countTmplChildren(@Param("id") long id);

    // ---------- 프로젝트 ----------

    List<ProjectRow> findProjects(@Param("f") ProjectFilter f, @Param("lateRef") LocalDate lateRef, @Param("limit") int limit);

    List<ProjectRow> findProjectPage(@Param("f") ProjectFilter f, @Param("lateRef") LocalDate lateRef, @Param("page") PageQuery page);

    long countProjects(@Param("f") ProjectFilter f);

    /** lateRef = 지연 기준일(PmsDays) — late_cnt 에만 쓴다 */
    ProjectRow findProject(@Param("id") long id, @Param("lateRef") LocalDate lateRef);

    /** PLM ECO 연결·해제(PM7, D17) */
    int updateProjectEco(@Param("id") long id, @Param("ecoId") Long ecoId, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 머리 행 잠금(FOR UPDATE) — 같은 프로젝트 WBS 편집·요약 재계산을 줄 세운다 */
    String lockProject(@Param("id") long id);

    long insertProject(@Param("projNo") String projNo, @Param("f") ProjectValue f, @Param("tmplId") Long tmplId,
            @Param("by") String by);

    int updateProject(@Param("id") long id, @Param("f") ProjectValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    int updateProjectStatus(@Param("id") long id, @Param("status") String status, @Param("reason") String reason,
            @Param("closedOn") LocalDate closedOn, @Param("rowVer") int rowVer, @Param("by") String by);

    int startProjectApproval(@Param("id") long id, @Param("aprTo") String aprTo, @Param("rowVer") int rowVer, @Param("by") String by);

    int setProjectAprId(@Param("id") long id, @Param("aprId") long aprId);

    int finishProjectApproval(@Param("id") long id, @Param("status") String status, @Param("closedOn") LocalDate closedOn,
            @Param("by") String by);

    String findProjectNo(@Param("id") long id);

    int updateProjectRollup(@Param("id") long id, @Param("planStart") LocalDate planStart, @Param("planEnd") LocalDate planEnd,
            @Param("progressPct") int progressPct);

    List<ItemRef> findProjectItems(@Param("projId") long projId);

    int insertProjectItem(@Param("projId") long projId, @Param("itemId") long itemId, @Param("by") String by);

    int deleteProjectItem(@Param("projId") long projId, @Param("itemId") long itemId);

    // ---------- 작업 ----------

    List<TaskRow> findTasks(@Param("projId") long projId);

    /** 작업별 실적 공수(확정·미확정, task_id null = 프로젝트에만 올린 공수) — docs/48 R-2 */
    List<PmsDtos.TaskHours> findTaskHours(@Param("projId") long projId);

    TaskRow findTask(@Param("id") long id);

    long insertTask(@Param("projId") long projId, @Param("parentId") Long parentId, @Param("seq") int seq,
            @Param("f") TaskValue f, @Param("by") String by);

    int updateTask(@Param("id") long id, @Param("f") TaskValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    int moveTask(@Param("id") long id, @Param("parentId") Long parentId, @Param("seq") int seq, @Param("by") String by);

    /** 요약 파생 값 저장(서비스가 계산) */
    int updateTaskRollup(@Param("t") TaskRow t, @Param("by") String by);

    int deleteTask(@Param("id") long id);

    // ---------- PM2 의존·기준선·계획 변경 ----------

    List<PmsDtos.DepRow> findDeps(@Param("projId") long projId);

    PmsDtos.DepRow findDep(@Param("id") long id);

    /** 이 프로젝트의 의존 행 잠금 없이 projId 만(삭제 때 프로젝트 잠금용) */
    Long findDepProjId(@Param("id") long id);

    long insertDep(@Param("projId") long projId, @Param("pred") long pred, @Param("succ") long succ, @Param("lag") int lag,
            @Param("type") String type,
            @Param("by") String by);

    int deleteDep(@Param("id") long id);

    int deleteDepsOfTask(@Param("taskId") long taskId);

    int countDepsOfTask(@Param("taskId") long taskId);

    PmsDtos.BaselineRow findLatestBaseline(@Param("projId") long projId);

    List<PmsDtos.BaselineRow> findBaselines(@Param("projId") long projId);

    long insertBaseline(@Param("projId") long projId, @Param("no") int no, @Param("reason") String reason,
            @Param("taskCnt") int taskCnt, @Param("planEnd") LocalDate planEnd, @Param("by") String by);

    int insertBaselineTasks(@Param("baselineId") long baselineId, @Param("rows") List<TaskRow> rows);

    List<PmsDtos.BaseTask> findBaselineTasks(@Param("baselineId") long baselineId);

    int insertPlanChange(@Param("projId") long projId, @Param("taskId") long taskId, @Param("baselineNo") int baselineNo,
            @Param("before") TaskRow before, @Param("afterStart") LocalDate afterStart, @Param("afterEnd") LocalDate afterEnd,
            @Param("source") String source, @Param("reason") String reason, @Param("by") String by);

    List<PmsDtos.PlanChangeRow> findPlanChanges(@Param("taskId") long taskId);

    int updateTaskPlan(@Param("id") long id, @Param("planStart") LocalDate planStart, @Param("planEnd") LocalDate planEnd,
            @Param("by") String by);
}
