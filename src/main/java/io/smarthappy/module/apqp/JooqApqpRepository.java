package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.jooq.Tables.APQP_AP_RULE;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_AP_SET;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_ACTION;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_CHAIN;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_FAIL;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_FUNC;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_NODE;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_RISK;
import static io.smarthappy.module.apqp.jooq.Tables.MST_BOM;
import static io.smarthappy.module.apqp.jooq.Tables.MST_BOM_LINE;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ITEM;
import static io.smarthappy.module.apqp.jooq.Tables.MST_PROC;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ROUTING;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ROUTING_STEP;
import static io.smarthappy.module.apqp.jooq.Tables.PMS_PROJECT;
import static io.smarthappy.module.apqp.jooq.Tables.QMS_CHAR;
import static io.smarthappy.module.apqp.jooq.Tables.QMS_DEFECT;
import static io.smarthappy.module.apqp.jooq.Tables.SYS_NUMBERING_RULE;
import static io.smarthappy.module.apqp.jooq.Tables.SYS_USER;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectJoinStep;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import io.smarthappy.module.apqp.ApqpDtos.ActionRow;
import io.smarthappy.module.apqp.ApqpDtos.ApRuleRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.ChainRow;
import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.DefectOption;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaFilter;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaSort;
import io.smarthappy.module.apqp.ApqpDtos.FmeaValue;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.ProjOption;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.ApqpDtos.RoutingOption;
import io.smarthappy.module.apqp.ApqpDtos.StepOption;
import io.smarthappy.module.apqp.jooq.Routines;
import io.smarthappy.module.apqp.jooq.tables.ApqpFmea;
import io.smarthappy.module.apqp.jooq.tables.MstRouting;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * {@link ApqpRepository} 의 jOOQ 구현(결정 docs/29 #52, docs/43) — 생성된 표·칸과 바인드 변수만(plain SQL 금지 가드).
 * 회사 범위는 RLS(조회 조건에 comp_cd 를 쓰지 않는다). INSERT 의 comp_cd 는 열린 회사 범위 값 — 다른 값이면 RLS WITH CHECK 가 막는다.
 * 행 → DTO 는 jOOQ 기본 매퍼(칸 이름 snake_case → 속성 camelCase). DB 함수는 허용 목록 루틴(hb_code_usable)만 — hb_current_comp 는 부르지 않는다(docs/43)
 */
@Repository
@RequiredArgsConstructor
class JooqApqpRepository implements ApqpRepository {

    private final DSLContext dsl;

    private static String comp() {
        return TenantContext.requireCompCd();
    }

    /** 점수 칸은 smallint — 서비스는 Integer 로 다룬다 */
    private static Short sh(Integer v) {
        return v == null ? null : v.shortValue();
    }

    @Override
    public void ensureNumberingRule(String ruleCd, String ruleNm, String pattern, String resetCycle, String by) {
        var n = SYS_NUMBERING_RULE;
        dsl.insertInto(n, n.COMP_CD, n.RULE_CD, n.RULE_NM, n.PATTERN, n.RESET_CYCLE, n.CREATED_BY, n.UPDATED_BY)
                .values(comp(), ruleCd, ruleNm, pattern, resetCycle, by, by)
                .onConflict(n.COMP_CD, n.RULE_CD).doNothing()
                .execute();
    }

    // ---------- AP 규칙표 ----------

    private static List<SelectFieldOrAsterisk> apSetCols() {
        var s = APQP_AP_SET;
        return List.of(s.SET_ID, s.SET_CD, s.SET_NM, s.SAMPLE_YN, s.REMARK, s.USE_YN, s.ROW_VER, s.UPDATED_AT, s.UPDATED_BY,
                selectCount().from(APQP_AP_RULE).where(APQP_AP_RULE.SET_ID.eq(s.SET_ID)).asField("rule_cnt"),
                selectCount().from(APQP_FMEA).where(APQP_FMEA.AP_SET_ID.eq(s.SET_ID), APQP_FMEA.STATUS.in("RELEASED", "OBSOLETE"))
                        .asField("released_cnt"));
    }

    @Override
    public List<ApSetRow> findApSets(boolean useOnly) {
        return dsl.select(apSetCols()).from(APQP_AP_SET)
                .where(useOnly ? APQP_AP_SET.USE_YN.eq("Y") : noCondition())
                .orderBy(APQP_AP_SET.SET_CD)
                .fetchInto(ApSetRow.class);
    }

    @Override
    public ApSetRow findApSet(long id) {
        return dsl.select(apSetCols()).from(APQP_AP_SET).where(APQP_AP_SET.SET_ID.eq(id)).fetchOneInto(ApSetRow.class);
    }

    @Override
    public ApSetRow findApSetByCd(String setCd) {
        return dsl.select(apSetCols()).from(APQP_AP_SET).where(APQP_AP_SET.SET_CD.eq(setCd)).fetchOneInto(ApSetRow.class);
    }

    @Override
    public Long lockApSet(long id) {
        return dsl.select(APQP_AP_SET.SET_ID).from(APQP_AP_SET).where(APQP_AP_SET.SET_ID.eq(id)).forUpdate().fetchOne(APQP_AP_SET.SET_ID);
    }

    @Override
    public List<ApRuleRow> findApRules(long setId) {
        var r = APQP_AP_RULE;
        return dsl.select(r.RULE_ID, r.SEQ, r.SEV_FROM, r.SEV_TO, r.OCC_FROM, r.OCC_TO, r.DET_FROM, r.DET_TO, r.AP)
                .from(r).where(r.SET_ID.eq(setId)).orderBy(r.SEQ)
                .fetchInto(ApRuleRow.class);
    }

    @Override
    public long insertApSet(String setCd, String setNm, String remark, String useYn, String sampleYn, String by) {
        var s = APQP_AP_SET;
        return dsl.insertInto(s, s.COMP_CD, s.SET_CD, s.SET_NM, s.REMARK, s.USE_YN, s.SAMPLE_YN, s.CREATED_BY, s.UPDATED_BY)
                .values(comp(), setCd, setNm, remark, useYn, sampleYn, by, by)
                .returningResult(s.SET_ID).fetchOne().value1();
    }

    @Override
    public int updateApSet(long id, String setNm, String remark, String useYn, int rowVer, String by) {
        var s = APQP_AP_SET;
        return dsl.update(s)
                .set(s.SET_NM, setNm).set(s.REMARK, remark).set(s.USE_YN, useYn)
                .set(s.ROW_VER, s.ROW_VER.plus(1)).set(s.UPDATED_AT, currentOffsetDateTime()).set(s.UPDATED_BY, by)
                .where(s.SET_ID.eq(id), s.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int deleteApRules(long setId) {
        return dsl.deleteFrom(APQP_AP_RULE).where(APQP_AP_RULE.SET_ID.eq(setId)).execute();
    }

    @Override
    public void insertApRule(long setId, int seq, ApRuleRow r, String by) {
        var t = APQP_AP_RULE;
        dsl.insertInto(t, t.COMP_CD, t.SET_ID, t.SEQ, t.SEV_FROM, t.SEV_TO, t.OCC_FROM, t.OCC_TO, t.DET_FROM, t.DET_TO, t.AP, t.CREATED_BY)
                .values(comp(), setId, seq, sh(r.getSevFrom()), sh(r.getSevTo()), sh(r.getOccFrom()), sh(r.getOccTo()), sh(r.getDetFrom()),
                        sh(r.getDetTo()), r.getAp(), by)
                .execute();
    }

    @Override
    public List<Long> findDraftFmeasBySet(long setId) {
        return dsl.select(APQP_FMEA.FMEA_ID).from(APQP_FMEA)
                .where(APQP_FMEA.AP_SET_ID.eq(setId), APQP_FMEA.STATUS.eq("DRAFT"))
                .orderBy(APQP_FMEA.FMEA_ID)
                .fetch(APQP_FMEA.FMEA_ID);
    }

    // ---------- FMEA 머리 ----------

    private static final ApqpFmea F = APQP_FMEA.as("f");
    private static final ApqpFmea D = APQP_FMEA.as("d");
    private static final MstRouting R2 = MST_ROUTING.as("r2");
    private static final io.smarthappy.module.apqp.jooq.tables.MstBom B2 = MST_BOM.as("b2");

    private static final Field<String> ROUTING_TEXT = when(MST_ROUTING.ROUTING_ID.isNotNull(),
            concat(MST_ROUTING.PLANT_CD, val(" · "), MST_ROUTING.ALT_NO.cast(String.class), val(" · "), MST_ROUTING.REV))
            .as("routing_text");
    private static final Field<String> BOM_TEXT = when(MST_BOM.BOM_ID.isNotNull(),
            concat(MST_BOM.ALT_NO.cast(String.class), val(" · "), MST_BOM.REV)).as("bom_text");
    private static final Field<Integer> RISK_CNT = selectCount().from(APQP_FMEA_RISK).where(APQP_FMEA_RISK.FMEA_ID.eq(F.FMEA_ID))
            .asField("risk_cnt");
    private static final Field<Integer> HIGH_CNT = selectCount().from(APQP_FMEA_RISK)
            .where(APQP_FMEA_RISK.FMEA_ID.eq(F.FMEA_ID), APQP_FMEA_RISK.AP.eq("H")).asField("high_cnt");
    private static final Field<Integer> OPEN_ACTION_CNT = selectCount().from(APQP_FMEA_ACTION)
            .where(APQP_FMEA_ACTION.FMEA_ID.eq(F.FMEA_ID), APQP_FMEA_ACTION.STATUS.eq("OPEN")).asField("open_action_cnt");
    private static final Field<Boolean> DRAFT_EXISTS = DSL.field(F.STATUS.eq("RELEASED")
            .and(exists(selectOne().from(D).where(D.FMEA_NO.eq(F.FMEA_NO), D.STATUS.in("DRAFT", "IN_APPROVAL"))))).as("draft_exists");
    /** 같은 품목·공장·대안의 더 늦은 확정 라우팅 리비전(A3-2 ①) — 머리 라우팅이 없으면 거짓 */
    static Field<Boolean> routingStale(Field<Long> itemId) {
        return DSL.field(MST_ROUTING.ROUTING_ID.isNotNull().and(exists(selectOne().from(R2).where(R2.ITEM_ID.eq(itemId),
                R2.PLANT_CD.eq(MST_ROUTING.PLANT_CD), R2.ALT_NO.eq(MST_ROUTING.ALT_NO), R2.STATUS.eq("RELEASED"),
                R2.VALID_FROM.gt(MST_ROUTING.VALID_FROM))))).as("routing_stale");
    }

    /** DFMEA: 같은 품목·대체 번호의 더 늦은 확정 BOM 리비전(A5-2b D6) — 머리 BOM 이 없으면 거짓 */
    static Field<Boolean> bomStale(Field<Long> itemId) {
        return DSL.field(MST_BOM.BOM_ID.isNotNull().and(exists(selectOne().from(B2).where(B2.ITEM_ID.eq(itemId),
                B2.ALT_NO.eq(MST_BOM.ALT_NO), B2.STATUS.eq("RELEASED"), B2.VALID_FROM.gt(MST_BOM.VALID_FROM))))).as("bom_stale");
    }

    private static List<SelectFieldOrAsterisk> fmeaCols() {
        return List.of(F.FMEA_ID, F.FMEA_NO, F.REV, F.FMEA_TYPE, F.TITLE, F.SCOPE, F.TEAM, F.DRAWING_REV, F.ITEM_ID,
                MST_ITEM.ITEM_CD, MST_ITEM.ITEM_NM, F.ROUTING_ID, ROUTING_TEXT, F.BOM_ID, BOM_TEXT, F.DOC_REV_ID, F.PROJ_ID, PMS_PROJECT.PROJ_NO, F.AP_SET_ID,
                APQP_AP_SET.SET_CD.as("ap_set_cd"), F.STATUS, F.RELEASED_AT, F.RELEASED_BY, F.PREV_FMEA_ID,
                RISK_CNT, HIGH_CNT, OPEN_ACTION_CNT, DRAFT_EXISTS, routingStale(F.ITEM_ID), bomStale(F.ITEM_ID), F.APR_ID, F.ROW_VER, F.UPDATED_AT, F.UPDATED_BY);
    }

    /** 목록·건수 공통 조건 — keywordLike 는 서비스가 이스케이프한 LIKE 값 */
    private static Condition fmeaWhere(FmeaFilter f) {
        Condition c = noCondition();
        if (f.keywordLike() != null) {
            String k = f.keywordLike();
            c = c.and(F.FMEA_NO.likeIgnoreCase(k, '\\').or(F.TITLE.likeIgnoreCase(k, '\\'))
                    .or(MST_ITEM.ITEM_CD.likeIgnoreCase(k, '\\')).or(MST_ITEM.ITEM_NM.likeIgnoreCase(k, '\\')));
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            c = c.and(F.STATUS.in(f.statuses()));
        }
        if (f.projId() != null) {
            c = c.and(F.PROJ_ID.eq(f.projId()));
        }
        if (f.fmeaType() != null) {
            c = c.and(F.FMEA_TYPE.eq(f.fmeaType()));
        }
        return c;
    }

    /** 정렬 — 허용 목록 enum → 칸(계산 칸은 별칭), 마지막에 PK */
    private static Field<?> sortField(String key) {
        return switch (FmeaSort.valueOf(key)) {
            case REV -> F.REV;
            case TITLE -> F.TITLE;
            case ITEM_CD -> MST_ITEM.ITEM_CD;
            case STATUS -> F.STATUS;
            case HIGH_CNT -> HIGH_CNT;
            case OPEN_ACTIONS -> OPEN_ACTION_CNT;
            case UPDATED_AT -> F.UPDATED_AT;
            case FMEA_NO -> F.FMEA_NO;
        };
    }

    private static List<SortField<?>> fmeaOrder(PageQuery page) {
        List<SortField<?>> order = new ArrayList<>();
        for (PageQuery.Sort s : page.getSorts()) {
            Field<?> x = sortField(s.getKey());
            order.add(s.isDesc() ? x.desc() : x.asc());
        }
        order.add(F.FMEA_ID.desc());
        return order;
    }

    private SelectJoinStep<Record> fmeaFrom() {
        return dsl.select(fmeaCols())
                .from(F)
                .join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(F.ITEM_ID))
                .leftJoin(MST_ROUTING).on(MST_ROUTING.ROUTING_ID.eq(F.ROUTING_ID))
                .leftJoin(MST_BOM).on(MST_BOM.BOM_ID.eq(F.BOM_ID))
                .leftJoin(PMS_PROJECT).on(PMS_PROJECT.PROJ_ID.eq(F.PROJ_ID))
                .leftJoin(APQP_AP_SET).on(APQP_AP_SET.SET_ID.eq(F.AP_SET_ID));
    }

    @Override
    public List<FmeaRow> findFmeaPage(FmeaFilter f, PageQuery page) {
        return fmeaFrom().where(fmeaWhere(f)).orderBy(fmeaOrder(page)).limit(page.getSize()).offset(page.getOffset())
                .fetchInto(FmeaRow.class);
    }

    @Override
    public void eachFmea(FmeaFilter f, PageQuery sort, Consumer<FmeaRow> each) {
        try (Cursor<Record> c = fmeaFrom().where(fmeaWhere(f)).orderBy(fmeaOrder(sort)).fetchSize(500).fetchLazy()) {
            c.forEach(r -> each.accept(r.into(FmeaRow.class)));
        }
    }

    @Override
    public long countFmeas(FmeaFilter f) {
        return dsl.selectCount().from(F).join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(F.ITEM_ID)).where(fmeaWhere(f)).fetchOne(0, long.class);
    }

    @Override
    public FmeaRow findFmea(long id) {
        return fmeaFrom().where(F.FMEA_ID.eq(id)).fetchOneInto(FmeaRow.class);
    }

    @Override
    public String lockFmea(long id) {
        return dsl.select(APQP_FMEA.STATUS).from(APQP_FMEA).where(APQP_FMEA.FMEA_ID.eq(id)).forUpdate().fetchOne(APQP_FMEA.STATUS);
    }

    @Override
    public boolean draftExists(String fmeaNo) {
        return dsl.fetchExists(APQP_FMEA, APQP_FMEA.FMEA_NO.eq(fmeaNo), APQP_FMEA.STATUS.in("DRAFT", "IN_APPROVAL"));
    }

    @Override
    public long insertFmea(String fmeaNo, int rev, FmeaValue v, Long prevId, String by) {
        var f = APQP_FMEA;
        return dsl.insertInto(f)
                .set(f.COMP_CD, comp()).set(f.FMEA_NO, fmeaNo).set(f.REV, rev).set(f.ITEM_ID, v.itemId()).set(f.ROUTING_ID, v.routingId())
                .set(f.PROJ_ID, v.projId()).set(f.TITLE, v.title()).set(f.SCOPE, v.scope()).set(f.TEAM, v.team())
                .set(f.DRAWING_REV, v.drawingRev()).set(f.AP_SET_ID, v.apSetId()).set(f.PREV_FMEA_ID, prevId)
                .set(f.FMEA_TYPE, v.fmeaType()).set(f.BOM_ID, v.bomId()).set(f.DOC_REV_ID, v.docRevId())
                .set(f.CREATED_BY, by).set(f.UPDATED_BY, by)
                .returningResult(f.FMEA_ID).fetchOne().value1();
    }

    @Override
    public int updateFmea(long id, FmeaValue v, int rowVer, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.ROUTING_ID, v.routingId()).set(f.PROJ_ID, v.projId()).set(f.TITLE, v.title()).set(f.SCOPE, v.scope())
                .set(f.TEAM, v.team()).set(f.DRAWING_REV, v.drawingRev()).set(f.AP_SET_ID, v.apSetId())
                .set(f.BOM_ID, v.bomId()).set(f.DOC_REV_ID, v.docRevId())
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id), f.ROW_VER.eq(rowVer), f.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int deleteFmea(long id, int rowVer) {
        var f = APQP_FMEA;
        return dsl.deleteFrom(f).where(f.FMEA_ID.eq(id), f.ROW_VER.eq(rowVer), f.STATUS.eq("DRAFT")).execute();
    }

    @Override
    public int obsoleteReleased(String fmeaNo, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.STATUS, "OBSOLETE").set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_NO.eq(fmeaNo), f.STATUS.eq("RELEASED"))
                .execute();
    }

    @Override
    public int releaseFmea(long id, int rowVer, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.STATUS, "RELEASED").set(f.RELEASED_AT, currentOffsetDateTime()).set(f.RELEASED_BY, by)
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id), f.ROW_VER.eq(rowVer), f.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int submitFmea(long id, long aprId, int rowVer, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.STATUS, "IN_APPROVAL").set(f.APR_ID, aprId)
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id), f.ROW_VER.eq(rowVer), f.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int approveFmea(long id, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.STATUS, "RELEASED").set(f.RELEASED_AT, currentOffsetDateTime()).set(f.RELEASED_BY, by)
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id), f.STATUS.eq("IN_APPROVAL"))
                .execute();
    }

    @Override
    public int draftFmea(long id, String by) {
        var f = APQP_FMEA;
        return dsl.update(f)
                .set(f.STATUS, "DRAFT").set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id), f.STATUS.eq("IN_APPROVAL"))
                .execute();
    }

    @Override
    public void touchFmea(long id, String by) {
        var f = APQP_FMEA;
        dsl.update(f)
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FMEA_ID.eq(id))
                .execute();
    }

    @Override
    public Long fmeaOf(String kind, long id) {
        return switch (kind) {
            case "NODE" -> dsl.select(APQP_FMEA_NODE.FMEA_ID).from(APQP_FMEA_NODE).where(APQP_FMEA_NODE.NODE_ID.eq(id)).fetchOne(APQP_FMEA_NODE.FMEA_ID);
            case "FUNC" -> dsl.select(APQP_FMEA_FUNC.FMEA_ID).from(APQP_FMEA_FUNC).where(APQP_FMEA_FUNC.FUNC_ID.eq(id)).fetchOne(APQP_FMEA_FUNC.FMEA_ID);
            case "FAIL" -> dsl.select(APQP_FMEA_FAIL.FMEA_ID).from(APQP_FMEA_FAIL).where(APQP_FMEA_FAIL.FAIL_ID.eq(id)).fetchOne(APQP_FMEA_FAIL.FMEA_ID);
            case "CHAIN" -> dsl.select(APQP_FMEA_CHAIN.FMEA_ID).from(APQP_FMEA_CHAIN).where(APQP_FMEA_CHAIN.CHAIN_ID.eq(id))
                    .fetchOne(APQP_FMEA_CHAIN.FMEA_ID);
            case "RISK" -> dsl.select(APQP_FMEA_RISK.FMEA_ID).from(APQP_FMEA_RISK).where(APQP_FMEA_RISK.RISK_ID.eq(id)).fetchOne(APQP_FMEA_RISK.FMEA_ID);
            default -> dsl.select(APQP_FMEA_ACTION.FMEA_ID).from(APQP_FMEA_ACTION).where(APQP_FMEA_ACTION.ACTION_ID.eq(id))
                    .fetchOne(APQP_FMEA_ACTION.FMEA_ID);
        };
    }

    /** 코드 사용 판정 = DB 함수 hb_code_usable(트리거와 같은 함수) — 생성된 루틴 호출(docs/43 루틴 허용 목록) */
    @Override
    public boolean codeUsable(String grp, String code) {
        return dsl.select(Routines.hbCodeUsable(comp(), grp, code)).fetchOne(0, boolean.class);
    }

    // ---------- 구조 ----------

    @Override
    public List<NodeRow> findNodes(long fmeaId) {
        var n = APQP_FMEA_NODE;
        return dsl.select(n.NODE_ID, n.PARENT_ID, n.NODE_KIND, n.SEQ, n.NODE_NM, n.STEP_ID, MST_ROUTING_STEP.OP_SEQ, MST_PROC.PROC_CD,
                        MST_PROC.PROC_NM, n.M4, n.REF_ITEM_ID, MST_ITEM.ITEM_CD.as("ref_item_cd"), n.ROW_VER)
                .from(n)
                .leftJoin(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(n.REF_ITEM_ID))
                .leftJoin(MST_ROUTING_STEP).on(MST_ROUTING_STEP.STEP_ID.eq(n.STEP_ID))
                .leftJoin(MST_PROC).on(MST_PROC.PROC_ID.eq(MST_ROUTING_STEP.PROC_ID))
                .where(n.FMEA_ID.eq(fmeaId))
                .orderBy(n.SEQ, n.NODE_ID)
                .fetchInto(NodeRow.class);
    }

    @Override
    public long insertNode(long fmeaId, Long parentId, String kind, int seq, String nm, Long stepId, String m4, String by) {
        var n = APQP_FMEA_NODE;
        return dsl.insertInto(n, n.COMP_CD, n.FMEA_ID, n.PARENT_ID, n.NODE_KIND, n.SEQ, n.NODE_NM, n.STEP_ID, n.M4, n.CREATED_BY, n.UPDATED_BY)
                .values(comp(), fmeaId, parentId, kind, seq, nm, stepId, m4, by, by)
                .returningResult(n.NODE_ID).fetchOne().value1();
    }

    @Override
    public long insertNodeRef(long fmeaId, Long parentId, String kind, int seq, String nm, Long refItemId, String by) {
        var n = APQP_FMEA_NODE;
        return dsl.insertInto(n, n.COMP_CD, n.FMEA_ID, n.PARENT_ID, n.NODE_KIND, n.SEQ, n.NODE_NM, n.REF_ITEM_ID, n.CREATED_BY, n.UPDATED_BY)
                .values(comp(), fmeaId, parentId, kind, seq, nm, refItemId, by, by)
                .returningResult(n.NODE_ID).fetchOne().value1();
    }

    @Override
    public String fmeaType(long fmeaId) {
        return dsl.select(APQP_FMEA.FMEA_TYPE).from(APQP_FMEA).where(APQP_FMEA.FMEA_ID.eq(fmeaId)).fetchOne(APQP_FMEA.FMEA_TYPE);
    }

    @Override
    public int updateNodeRef(long id, Long refItemId, int rowVer, String by) {
        var n = APQP_FMEA_NODE;
        return dsl.update(n)
                .set(n.REF_ITEM_ID, refItemId)
                .set(n.ROW_VER, n.ROW_VER.plus(1)).set(n.UPDATED_AT, currentOffsetDateTime()).set(n.UPDATED_BY, by)
                .where(n.NODE_ID.eq(id), n.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int updateNode(long id, String nm, Long stepId, String m4, int rowVer, String by) {
        var n = APQP_FMEA_NODE;
        return dsl.update(n)
                .set(n.NODE_NM, nm).set(n.STEP_ID, stepId).set(n.M4, m4)
                .set(n.ROW_VER, n.ROW_VER.plus(1)).set(n.UPDATED_AT, currentOffsetDateTime()).set(n.UPDATED_BY, by)
                .where(n.NODE_ID.eq(id), n.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int updateNodeSeq(long id, int seq, String by) {
        var n = APQP_FMEA_NODE;
        return dsl.update(n)
                .set(n.SEQ, seq).set(n.ROW_VER, n.ROW_VER.plus(1)).set(n.UPDATED_AT, currentOffsetDateTime()).set(n.UPDATED_BY, by)
                .where(n.NODE_ID.eq(id))
                .execute();
    }

    @Override
    public int deleteNodes(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_NODE).where(APQP_FMEA_NODE.NODE_ID.in(ids)).execute();
    }

    // ---------- 기능 · 고장 · 사슬 ----------

    @Override
    public List<FuncRow> findFuncs(long fmeaId) {
        var f = APQP_FMEA_FUNC;
        return dsl.select(f.FUNC_ID, f.NODE_ID, f.SEQ, f.FUNC_TEXT, f.CHAR_ID, QMS_CHAR.CHAR_NO, QMS_CHAR.CHAR_NM,
                        QMS_CHAR.SPECIAL_CLS.as("char_special"), f.ROW_VER)
                .from(f).leftJoin(QMS_CHAR).on(QMS_CHAR.CHAR_ID.eq(f.CHAR_ID))
                .where(f.FMEA_ID.eq(fmeaId))
                .orderBy(f.SEQ, f.FUNC_ID)
                .fetchInto(FuncRow.class);
    }

    @Override
    public long insertFunc(long fmeaId, long nodeId, int seq, String text, Long charId, String by) {
        var f = APQP_FMEA_FUNC;
        return dsl.insertInto(f, f.COMP_CD, f.FMEA_ID, f.NODE_ID, f.SEQ, f.FUNC_TEXT, f.CHAR_ID, f.CREATED_BY, f.UPDATED_BY)
                .values(comp(), fmeaId, nodeId, seq, text, charId, by, by)
                .returningResult(f.FUNC_ID).fetchOne().value1();
    }

    @Override
    public int updateFunc(long id, String text, Long charId, int rowVer, String by) {
        var f = APQP_FMEA_FUNC;
        return dsl.update(f)
                .set(f.FUNC_TEXT, text).set(f.CHAR_ID, charId)
                .set(f.ROW_VER, f.ROW_VER.plus(1)).set(f.UPDATED_AT, currentOffsetDateTime()).set(f.UPDATED_BY, by)
                .where(f.FUNC_ID.eq(id), f.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int deleteFuncs(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_FUNC).where(APQP_FMEA_FUNC.FUNC_ID.in(ids)).execute();
    }

    @Override
    public List<FailRow> findFails(long fmeaId) {
        var x = APQP_FMEA_FAIL;
        var src = APQP_FMEA_FAIL.as("src");
        var sf = APQP_FMEA.as("sf");
        return dsl.select(x.FAIL_ID, x.FUNC_ID, x.FAIL_KIND, x.SEQ, x.FAIL_TEXT, x.SEVERITY, x.DEFECT_ID, QMS_DEFECT.DEFECT_CD,
                        QMS_DEFECT.DEFECT_NM, x.SRC_FAIL_ID, concat(sf.FMEA_NO, val(" rev "), sf.REV.cast(String.class)).as("src_text"), x.ROW_VER)
                .from(x).leftJoin(QMS_DEFECT).on(QMS_DEFECT.DEFECT_ID.eq(x.DEFECT_ID))
                .leftJoin(src).on(src.FAIL_ID.eq(x.SRC_FAIL_ID)).leftJoin(sf).on(sf.FMEA_ID.eq(src.FMEA_ID))
                .where(x.FMEA_ID.eq(fmeaId))
                .orderBy(x.SEQ, x.FAIL_ID)
                .fetchInto(FailRow.class);
    }

    @Override
    public long insertFail(long fmeaId, long funcId, String kind, int seq, String text, Integer severity, Long defectId, Long srcFailId,
            String by) {
        var x = APQP_FMEA_FAIL;
        return dsl.insertInto(x, x.COMP_CD, x.FMEA_ID, x.FUNC_ID, x.FAIL_KIND, x.SEQ, x.FAIL_TEXT, x.SEVERITY, x.DEFECT_ID, x.SRC_FAIL_ID,
                        x.CREATED_BY, x.UPDATED_BY)
                .values(comp(), fmeaId, funcId, kind, seq, text, sh(severity), defectId, srcFailId, by, by)
                .returningResult(x.FAIL_ID).fetchOne().value1();
    }

    @Override
    public int updateFail(long id, String text, Integer severity, Long defectId, Long srcFailId, int rowVer, String by) {
        var x = APQP_FMEA_FAIL;
        return dsl.update(x)
                .set(x.FAIL_TEXT, text).set(x.SEVERITY, sh(severity)).set(x.DEFECT_ID, defectId).set(x.SRC_FAIL_ID, srcFailId)
                .set(x.ROW_VER, x.ROW_VER.plus(1)).set(x.UPDATED_AT, currentOffsetDateTime()).set(x.UPDATED_BY, by)
                .where(x.FAIL_ID.eq(id), x.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public List<ApqpDtos.DfmeaSource> findDfmeaSources(long itemId) {
        var x = APQP_FMEA_FAIL;
        var f = APQP_FMEA;
        return dsl.select(x.FAIL_ID, x.FAIL_TEXT, f.FMEA_ID, f.FMEA_NO, f.REV, APQP_FMEA_NODE.NODE_NM)
                .from(x).join(f).on(f.FMEA_ID.eq(x.FMEA_ID))
                .join(APQP_FMEA_FUNC).on(APQP_FMEA_FUNC.FUNC_ID.eq(x.FUNC_ID))
                .join(APQP_FMEA_NODE).on(APQP_FMEA_NODE.NODE_ID.eq(APQP_FMEA_FUNC.NODE_ID))
                .where(f.ITEM_ID.eq(itemId), f.FMEA_TYPE.eq("DFMEA"), f.STATUS.eq("RELEASED"), x.FAIL_KIND.eq("FM"))
                .orderBy(f.FMEA_NO, APQP_FMEA_NODE.SEQ, APQP_FMEA_FUNC.SEQ, x.SEQ, x.FAIL_ID)
                .fetchInto(ApqpDtos.DfmeaSource.class);
    }

    @Override
    public Set<Long> findDfmeaCharIds(long itemId) {
        var f = APQP_FMEA;
        return new HashSet<>(dsl.selectDistinct(APQP_FMEA_FUNC.CHAR_ID).from(APQP_FMEA_FUNC).join(f).on(f.FMEA_ID.eq(APQP_FMEA_FUNC.FMEA_ID))
                .where(f.ITEM_ID.eq(itemId), f.FMEA_TYPE.eq("DFMEA"), f.STATUS.eq("RELEASED"), APQP_FMEA_FUNC.CHAR_ID.isNotNull())
                .fetch(APQP_FMEA_FUNC.CHAR_ID));
    }

    @Override
    public int deleteFails(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_FAIL).where(APQP_FMEA_FAIL.FAIL_ID.in(ids)).execute();
    }

    @Override
    public List<ChainRow> findChains(long fmeaId) {
        var c = APQP_FMEA_CHAIN;
        return dsl.select(c.CHAIN_ID, c.FM_ID, c.FE_ID).from(c).where(c.FMEA_ID.eq(fmeaId)).orderBy(c.CHAIN_ID).fetchInto(ChainRow.class);
    }

    @Override
    public long insertChain(long fmeaId, long fmId, long feId, String by) {
        var c = APQP_FMEA_CHAIN;
        return dsl.insertInto(c, c.COMP_CD, c.FMEA_ID, c.FM_ID, c.FE_ID, c.CREATED_BY)
                .values(comp(), fmeaId, fmId, feId, by)
                .returningResult(c.CHAIN_ID).fetchOne().value1();
    }

    @Override
    public int deleteChains(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_CHAIN).where(APQP_FMEA_CHAIN.CHAIN_ID.in(ids)).execute();
    }

    // ---------- 위험 · 조치 ----------

    @Override
    public List<RiskRow> findRisks(long fmeaId) {
        var k = APQP_FMEA_RISK;
        return dsl.select(k.RISK_ID, k.FM_ID, k.FC_ID, k.PREV_CTRL, k.DET_CTRL, k.SEV, k.OCC, k.DET, k.AP, k.SPECIAL_CLS, k.ROW_VER)
                .from(k).where(k.FMEA_ID.eq(fmeaId)).orderBy(k.RISK_ID)
                .fetchInto(RiskRow.class);
    }

    @Override
    public long insertRisk(long fmeaId, RiskRow r, String by) {
        var k = APQP_FMEA_RISK;
        return dsl.insertInto(k)
                .set(k.COMP_CD, comp()).set(k.FMEA_ID, fmeaId).set(k.FM_ID, r.getFmId()).set(k.FC_ID, r.getFcId())
                .set(k.PREV_CTRL, r.getPrevCtrl()).set(k.DET_CTRL, r.getDetCtrl()).set(k.SEV, sh(r.getSev())).set(k.OCC, sh(r.getOcc()))
                .set(k.DET, sh(r.getDet())).set(k.AP, r.getAp()).set(k.SPECIAL_CLS, r.getSpecialCls())
                .set(k.CREATED_BY, by).set(k.UPDATED_BY, by)
                .returningResult(k.RISK_ID).fetchOne().value1();
    }

    @Override
    public int updateRisk(long id, RiskRow r, int rowVer, String by) {
        var k = APQP_FMEA_RISK;
        return dsl.update(k)
                .set(k.PREV_CTRL, r.getPrevCtrl()).set(k.DET_CTRL, r.getDetCtrl()).set(k.OCC, sh(r.getOcc())).set(k.DET, sh(r.getDet()))
                .set(k.SPECIAL_CLS, r.getSpecialCls())
                .set(k.ROW_VER, k.ROW_VER.plus(1)).set(k.UPDATED_AT, currentOffsetDateTime()).set(k.UPDATED_BY, by)
                .where(k.RISK_ID.eq(id), k.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int updateRiskDerived(long id, Integer sev, String ap) {
        var k = APQP_FMEA_RISK;
        return dsl.update(k)
                .set(k.SEV, sh(sev)).set(k.AP, ap)
                .where(k.RISK_ID.eq(id), k.SEV.isDistinctFrom(sh(sev)).or(k.AP.isDistinctFrom(ap)))
                .execute();
    }

    @Override
    public int deleteRisks(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_RISK).where(APQP_FMEA_RISK.RISK_ID.in(ids)).execute();
    }

    @Override
    public List<ActionRow> findActions(long fmeaId) {
        var a = APQP_FMEA_ACTION;
        return dsl.select(a.ACTION_ID, a.RISK_ID, a.ACTION_KIND, a.ACTION_TEXT, a.OWNER_USER_ID, SYS_USER.LOGIN_ID.as("owner_login_id"),
                        SYS_USER.USER_NM.as("owner_nm"), a.DUE_DATE, a.STATUS, a.DONE_ON, a.EVIDENCE, a.NEW_OCC, a.NEW_DET, a.NEW_AP,
                        a.ROW_VER)
                .from(a).leftJoin(SYS_USER).on(SYS_USER.USER_ID.eq(a.OWNER_USER_ID))
                .where(a.FMEA_ID.eq(fmeaId))
                .orderBy(a.ACTION_ID)
                .fetchInto(ActionRow.class);
    }

    @Override
    public long insertAction(long fmeaId, ActionRow x, String by) {
        var a = APQP_FMEA_ACTION;
        return dsl.insertInto(a)
                .set(a.COMP_CD, comp()).set(a.FMEA_ID, fmeaId).set(a.RISK_ID, x.getRiskId()).set(a.ACTION_KIND, x.getActionKind())
                .set(a.ACTION_TEXT, x.getActionText()).set(a.OWNER_USER_ID, x.getOwnerUserId()).set(a.DUE_DATE, x.getDueDate())
                .set(a.STATUS, x.getStatus()).set(a.DONE_ON, x.getDoneOn()).set(a.EVIDENCE, x.getEvidence())
                .set(a.NEW_OCC, sh(x.getNewOcc())).set(a.NEW_DET, sh(x.getNewDet())).set(a.NEW_AP, x.getNewAp())
                .set(a.CREATED_BY, by).set(a.UPDATED_BY, by)
                .returningResult(a.ACTION_ID).fetchOne().value1();
    }

    @Override
    public int updateAction(long id, ActionRow x, int rowVer, String by) {
        var a = APQP_FMEA_ACTION;
        return dsl.update(a)
                .set(a.ACTION_KIND, x.getActionKind()).set(a.ACTION_TEXT, x.getActionText()).set(a.OWNER_USER_ID, x.getOwnerUserId())
                .set(a.DUE_DATE, x.getDueDate()).set(a.STATUS, x.getStatus()).set(a.DONE_ON, x.getDoneOn()).set(a.EVIDENCE, x.getEvidence())
                .set(a.NEW_OCC, sh(x.getNewOcc())).set(a.NEW_DET, sh(x.getNewDet())).set(a.NEW_AP, x.getNewAp())
                .set(a.ROW_VER, a.ROW_VER.plus(1)).set(a.UPDATED_AT, currentOffsetDateTime()).set(a.UPDATED_BY, by)
                .where(a.ACTION_ID.eq(id), a.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int updateActionDerived(long id, String ap) {
        var a = APQP_FMEA_ACTION;
        return dsl.update(a).set(a.NEW_AP, ap).where(a.ACTION_ID.eq(id), a.NEW_AP.isDistinctFrom(ap)).execute();
    }

    @Override
    public int deleteActions(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_FMEA_ACTION).where(APQP_FMEA_ACTION.ACTION_ID.in(ids)).execute();
    }

    // ---------- 고르기 목록 ----------

    @Override
    public List<ItemOption> findItems(String keywordLike, int limit) {
        var i = MST_ITEM;
        return dsl.select(i.ITEM_ID, i.ITEM_CD, i.ITEM_NM).from(i)
                .where(i.USE_YN.eq("Y"))
                .and(keywordLike == null ? noCondition() : i.ITEM_CD.likeIgnoreCase(keywordLike, '\\').or(i.ITEM_NM.likeIgnoreCase(keywordLike, '\\')))
                .orderBy(i.ITEM_CD)
                .limit(limit)
                .fetchInto(ItemOption.class);
    }

    @Override
    public ItemOption findItemByCd(String itemCd) {
        var i = MST_ITEM;
        return dsl.select(i.ITEM_ID, i.ITEM_CD, i.ITEM_NM).from(i).where(i.ITEM_CD.eq(itemCd)).fetchOneInto(ItemOption.class);
    }

    @Override
    public List<RoutingOption> findRoutings(long itemId) {
        var r = MST_ROUTING;
        return dsl.select(r.ROUTING_ID, r.PLANT_CD, r.ALT_NO, r.REV, r.STATUS, r.VALID_FROM).from(r)
                .where(r.ITEM_ID.eq(itemId), r.STATUS.in("DRAFT", "RELEASED"))
                .orderBy(r.PLANT_CD, r.ALT_NO, r.VALID_FROM.desc(), r.ROUTING_ID.desc())
                .fetchInto(RoutingOption.class);
    }

    @Override
    public List<StepOption> findSteps(long routingId) {
        var s = MST_ROUTING_STEP;
        return dsl.select(s.STEP_ID, s.OP_SEQ, s.PROC_ID, MST_PROC.PROC_CD, MST_PROC.PROC_NM)
                .from(s).join(MST_PROC).on(MST_PROC.PROC_ID.eq(s.PROC_ID))
                .where(s.ROUTING_ID.eq(routingId))
                .orderBy(s.OP_SEQ)
                .fetchInto(StepOption.class);
    }

    @Override
    public List<ApqpDtos.FlowStepRow> findFlowSteps(long routingId) {
        var s = MST_ROUTING_STEP;
        return dsl.select(s.STEP_ID, s.OP_SEQ, MST_PROC.PROC_CD, MST_PROC.PROC_NM, MST_PROC.PROC_CAT, MST_PROC.OUTSRC_YN, s.STD_CT_SEC)
                .from(s).join(MST_PROC).on(MST_PROC.PROC_ID.eq(s.PROC_ID))
                .where(s.ROUTING_ID.eq(routingId))
                .orderBy(s.OP_SEQ, s.STEP_ID)
                .fetchInto(ApqpDtos.FlowStepRow.class);
    }

    @Override
    public List<CharOption> findChars(long itemId, Long routingId) {
        var c = QMS_CHAR;
        Condition which = c.CHAR_TYPE.eq("PRODUCT").and(c.ITEM_ID.eq(itemId));
        if (routingId != null) {
            which = which.or(c.CHAR_TYPE.eq("PROCESS").and(c.PROC_ID.in(
                    DSL.select(MST_ROUTING_STEP.PROC_ID).from(MST_ROUTING_STEP).where(MST_ROUTING_STEP.ROUTING_ID.eq(routingId)))));
        }
        return dsl.select(c.CHAR_ID, c.CHAR_TYPE, c.CHAR_NO, c.CHAR_NM, c.SPECIAL_CLS, MST_PROC.PROC_CD)
                .from(c).leftJoin(MST_PROC).on(MST_PROC.PROC_ID.eq(c.PROC_ID))
                .where(c.USE_YN.eq("Y"), which)
                .orderBy(c.CHAR_TYPE.desc(), c.SORT_NO, c.CHAR_NO)
                .fetchInto(CharOption.class);
    }

    @Override
    public List<CharOption> findProductChars(Collection<Long> itemIds) {
        var c = QMS_CHAR;
        return dsl.select(c.CHAR_ID, c.CHAR_TYPE, c.CHAR_NO, c.CHAR_NM, c.SPECIAL_CLS, MST_ITEM.ITEM_CD.as("proc_cd"))
                .from(c).join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(c.ITEM_ID))
                .where(c.USE_YN.eq("Y"), c.CHAR_TYPE.eq("PRODUCT"), c.ITEM_ID.in(itemIds))
                .orderBy(MST_ITEM.ITEM_CD, c.SORT_NO, c.CHAR_NO)
                .fetchInto(CharOption.class);
    }

    @Override
    public List<ApqpDtos.BomOption> findReleasedBoms(long itemId) {
        var b = MST_BOM;
        return dsl.select(b.BOM_ID, b.ALT_NO, b.REV, b.VALID_FROM).from(b)
                .where(b.ITEM_ID.eq(itemId), b.STATUS.eq("RELEASED"))
                .orderBy(b.ALT_NO, b.VALID_FROM.desc(), b.BOM_ID.desc())
                .fetchInto(ApqpDtos.BomOption.class);
    }

    @Override
    public List<ApqpDtos.BomChild> findBomChildren(long bomId) {
        var l = MST_BOM_LINE;
        return dsl.select(l.ITEM_ID, MST_ITEM.ITEM_CD, MST_ITEM.ITEM_NM, l.LINE_NO).from(l)
                .join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(l.ITEM_ID))
                .where(l.BOM_ID.eq(bomId))
                .orderBy(l.LINE_NO, l.LINE_ID)
                .fetchInto(ApqpDtos.BomChild.class);
    }

    @Override
    public Long findCurrentBom(long itemId, java.time.LocalDate day) {
        var b = MST_BOM;
        return dsl.select(b.BOM_ID).from(b)
                .where(b.ITEM_ID.eq(itemId), b.ALT_NO.eq((short) 1), b.STATUS.eq("RELEASED"), b.VALID_FROM.le(day),
                        b.VALID_TO.isNull().or(b.VALID_TO.ge(day)))
                .orderBy(b.VALID_FROM.desc(), b.BOM_ID.desc()).limit(1).fetchOne(b.BOM_ID);
    }

    @Override
    public List<DefectOption> findDefects() {
        var d = QMS_DEFECT;
        return dsl.select(d.DEFECT_ID, d.DEFECT_CD, d.DEFECT_NM).from(d).where(d.USE_YN.eq("Y")).orderBy(d.SORT_NO, d.DEFECT_CD)
                .fetchInto(DefectOption.class);
    }

    @Override
    public List<ProjOption> findProjects() {
        var p = PMS_PROJECT;
        return dsl.select(p.PROJ_ID, p.PROJ_NO, p.PROJ_NM).from(p).where(p.STATUS.in("DRAFT", "ACTIVE", "ON_HOLD")).orderBy(p.PROJ_NO.desc())
                .fetchInto(ProjOption.class);
    }
}
