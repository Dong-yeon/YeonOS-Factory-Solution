package io.smarthappy.module.apqp;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.smarthappy.module.apqp.ApqpDtos.ActionRow;
import io.smarthappy.module.apqp.ApqpDtos.ApRuleRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.ChainRow;
import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.DefectOption;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaFilter;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaValue;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.ProjOption;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.ApqpDtos.RoutingOption;
import io.smarthappy.module.apqp.ApqpDtos.StepOption;
import io.smarthappy.platform.common.query.PageQuery;

/**
 * APQP 데이터 접근 계약(docs/41, 결정 docs/29 #52) — 서비스는 이 인터페이스에만 기댄다.
 * 구현 = jOOQ({@link JooqApqpRepository}, 2026-10-23 MyBatis 구현에서 교체 — ApqpFmeaTest 무수정 통과가 동등성 근거, 비교는 docs/41 4.2).
 * 회사 범위는 RLS(조건에 comp_cd 를 쓰지 않는다), 기준정보는 직접 조인(P4)
 */
public interface ApqpRepository {

    void ensureNumberingRule(String ruleCd, String ruleNm, String pattern,
            String resetCycle, String by);

    // ---------- AP 규칙표 ----------

    List<ApSetRow> findApSets(boolean useOnly);

    ApSetRow findApSet(long id);

    ApSetRow findApSetByCd(String setCd);

    /** 행 잠금 — 규칙 저장·사용 전환이 겹치지 않게 */
    Long lockApSet(long id);

    List<ApRuleRow> findApRules(long setId);

    long insertApSet(String setCd, String setNm, String remark,
            String useYn, String sampleYn, String by);

    int updateApSet(long id, String setNm, String remark, String useYn,
            int rowVer, String by);

    int deleteApRules(long setId);

    void insertApRule(long setId, int seq, ApRuleRow r, String by);

    List<Long> findDraftFmeasBySet(long setId);

    // ---------- FMEA 머리 ----------

    List<FmeaRow> findFmeaPage(FmeaFilter f, PageQuery page);

    long countFmeas(FmeaFilter f);

    /** 목록 엑셀 — grid 와 같은 조건·정렬, 페이지 없이 한 줄씩(커서) */
    void eachFmea(FmeaFilter f, PageQuery sort, Consumer<FmeaRow> each);

    FmeaRow findFmea(long id);

    /** 행 잠금 + 상태 — 하위 행 편집·확정·새 리비전이 같은 머리에서 겹치지 않게 */
    String lockFmea(long id);

    boolean draftExists(String fmeaNo);

    long insertFmea(String fmeaNo, int rev, FmeaValue v, Long prevId,
            String by);

    int updateFmea(long id, FmeaValue v, int rowVer, String by);

    int deleteFmea(long id, int rowVer);

    int obsoleteReleased(String fmeaNo, String by);

    int releaseFmea(long id, int rowVer, String by);

    /** 결재 상신 — 작성 중 → 결재 중(내용 고정) */
    int submitFmea(long id, long aprId, int rowVer, String by);

    /** 결재 승인 = 확정(결재 중 → 확정) */
    int approveFmea(long id, String by);

    /** 결재 반려·회수 — 결재 중 → 작성 중 */
    int draftFmea(long id, String by);

    /** 하위 행이 바뀌면 머리 row_ver 를 올린다(화면이 머리로 낡음을 안다) — 상태는 그대로 */
    void touchFmea(long id, String by);

    /** 하위 행이 속한 FMEA — kind = NODE·FUNC·FAIL·CHAIN·RISK·ACTION(문자열 치환 없이 choose) */
    Long fmeaOf(String kind, long id);

    boolean codeUsable(String grp, String code);

    // ---------- 구조 ----------

    List<NodeRow> findNodes(long fmeaId);

    long insertNode(long fmeaId, Long parentId, String kind, int seq,
            String nm, Long stepId, String m4, String by);

    /** DFMEA 하위 시스템·구성품(A5-2) — 자품목 연결 */
    long insertNodeRef(long fmeaId, Long parentId, String kind, int seq, String nm, Long refItemId, String by);

    /** 머리 유형(PFMEA·DFMEA) — 없으면 null */
    String fmeaType(long fmeaId);

    int updateNode(long id, String nm, Long stepId, String m4,
            int rowVer, String by);

    int updateNodeSeq(long id, int seq, String by);

    int deleteNodes(Collection<Long> ids);

    // ---------- 기능 · 고장 · 사슬 ----------

    List<FuncRow> findFuncs(long fmeaId);

    long insertFunc(long fmeaId, long nodeId, int seq, String text,
            Long charId, String by);

    int updateFunc(long id, String text, Long charId, int rowVer,
            String by);

    int deleteFuncs(Collection<Long> ids);

    List<FailRow> findFails(long fmeaId);

    long insertFail(long fmeaId, long funcId, String kind, int seq,
            String text, Integer severity, Long defectId, Long srcFailId, String by);

    int updateFail(long id, String text, Integer severity, Long defectId, Long srcFailId,
            int rowVer, String by);

    /** 같은 품목 확정 DFMEA 들의 고장 형태(A5-2b D7) */
    List<ApqpDtos.DfmeaSource> findDfmeaSources(long itemId);

    /** 같은 품목 확정 DFMEA 기능에 붙은 특성 id */
    Set<Long> findDfmeaCharIds(long itemId);

    /** DFMEA 노드의 자품목 연결만 바꾸기(BOM 다시 잇기) */
    int updateNodeRef(long id, Long refItemId, int rowVer, String by);

    int deleteFails(Collection<Long> ids);

    List<ChainRow> findChains(long fmeaId);

    long insertChain(long fmeaId, long fmId, long feId, String by);

    int deleteChains(Collection<Long> ids);

    // ---------- 위험 · 조치 ----------

    List<RiskRow> findRisks(long fmeaId);

    long insertRisk(long fmeaId, RiskRow r, String by);

    int updateRisk(long id, RiskRow r, int rowVer, String by);

    /** 파생 값(S·AP) — row_ver 는 올리지 않는다(사용자 편집과 충돌하지 않게) */
    int updateRiskDerived(long id, Integer sev, String ap);

    int deleteRisks(Collection<Long> ids);

    List<ActionRow> findActions(long fmeaId);

    long insertAction(long fmeaId, ActionRow a, String by);

    int updateAction(long id, ActionRow a, int rowVer, String by);

    int updateActionDerived(long id, String ap);

    int deleteActions(Collection<Long> ids);

    // ---------- 고르기 목록(기준정보·PMS 직접 조회) ----------

    List<ItemOption> findItems(String keywordLike, int limit);

    ItemOption findItemByCd(String itemCd);

    List<RoutingOption> findRoutings(long itemId);

    List<StepOption> findSteps(long routingId);

    /** PFD(A5-1) — 라우팅 단계 + 공정 분류·외주·표준 CT, 공정 순서 */
    List<ApqpDtos.FlowStepRow> findFlowSteps(long routingId);

    /** 제품 특성(품목) + 공정 특성(그 라우팅 단계들의 공정) */
    List<CharOption> findChars(long itemId, Long routingId);

    /** DFMEA — 품목들의 제품 특성(머리 품목 + 구성품 자품목) */
    List<CharOption> findProductChars(java.util.Collection<Long> itemIds);

    /** DFMEA 대상 — 품목의 확정 BOM 리비전(D5) */
    List<ApqpDtos.BomOption> findReleasedBoms(long itemId);

    /** BOM 리비전 한 줄씩 자품목(줄 번호 순) */
    List<ApqpDtos.BomChild> findBomChildren(long bomId);

    /** 자품목의 지금 유효한 확정 BOM(대안 1) — 2단계 끌어오기. 없으면 null */
    Long findCurrentBom(long itemId, java.time.LocalDate day);

    List<DefectOption> findDefects();

    List<ProjOption> findProjects();
}
