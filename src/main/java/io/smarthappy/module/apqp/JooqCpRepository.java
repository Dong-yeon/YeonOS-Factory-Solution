package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.jooq.Tables.APQP_CP;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_CP_LINE;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_RISK;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ITEM;
import static io.smarthappy.module.apqp.jooq.Tables.MST_PROC;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ROUTING;
import static io.smarthappy.module.apqp.jooq.Tables.MST_ROUTING_STEP;
import static io.smarthappy.module.apqp.jooq.Tables.PMS_PROJECT;
import static io.smarthappy.module.apqp.jooq.Tables.QMS_CHAR;
import static org.jooq.impl.DSL.coalesce;
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
import java.util.List;
import java.util.function.Consumer;

import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectJoinStep;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import io.smarthappy.module.apqp.CpDtos.CpFilter;
import io.smarthappy.module.apqp.CpDtos.CpLineRow;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.module.apqp.CpDtos.CpSort;
import io.smarthappy.module.apqp.CpDtos.CpValue;
import io.smarthappy.module.apqp.CpDtos.FmeaOption;
import io.smarthappy.module.apqp.jooq.tables.ApqpCp;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * {@link CpRepository} 의 jOOQ 구현(docs/41 5.5) — 생성된 표·칸과 바인드 변수만(plain SQL 금지 가드).
 * 회사 범위는 RLS(조회 조건에 comp_cd 를 쓰지 않는다), INSERT 의 comp_cd 는 열린 회사 범위 값(다르면 RLS WITH CHECK 가 막는다)
 */
@Repository
@RequiredArgsConstructor
class JooqCpRepository implements CpRepository {

    private final DSLContext dsl;

    private static String comp() {
        return TenantContext.requireCompCd();
    }

    // ---------- 머리 ----------

    private static final ApqpCp C = APQP_CP.as("c");
    private static final ApqpCp D = APQP_CP.as("d");

    private static final Field<String> ROUTING_TEXT = when(MST_ROUTING.ROUTING_ID.isNotNull(),
            concat(MST_ROUTING.PLANT_CD, val(" · "), MST_ROUTING.ALT_NO.cast(String.class), val(" · "), MST_ROUTING.REV))
            .as("routing_text");
    private static final Field<Integer> LINE_CNT = selectCount().from(APQP_CP_LINE).where(APQP_CP_LINE.CP_ID.eq(C.CP_ID)).asField("line_cnt");
    private static final Field<Boolean> FMEA_STALE = DSL.field(APQP_FMEA.STATUS.eq("OBSOLETE")).as("fmea_stale");
    private static final Field<Boolean> DRAFT_EXISTS = DSL.field(C.STATUS.eq("RELEASED")
            .and(exists(selectOne().from(D).where(D.CP_NO.eq(C.CP_NO), D.STATUS.in("DRAFT", "IN_APPROVAL"))))).as("draft_exists");

    private static List<SelectFieldOrAsterisk> cpCols() {
        return List.of(C.CP_ID, C.CP_NO, C.REV, C.PHASE, C.TITLE, C.TEAM, C.CHANGE_REASON, C.ITEM_ID, MST_ITEM.ITEM_CD, MST_ITEM.ITEM_NM,
                C.ROUTING_ID, ROUTING_TEXT, C.FMEA_ID, APQP_FMEA.FMEA_NO, APQP_FMEA.REV.as("fmea_rev"), APQP_FMEA.STATUS.as("fmea_status"),
                C.PROJ_ID, PMS_PROJECT.PROJ_NO, C.STATUS, C.RELEASED_AT, C.RELEASED_BY, C.PREV_CP_ID, LINE_CNT, FMEA_STALE, DRAFT_EXISTS,
                JooqApqpRepository.routingStale(C.ITEM_ID), C.APR_ID, C.ROW_VER, C.UPDATED_AT, C.UPDATED_BY);
    }

    private SelectJoinStep<Record> cpFrom() {
        return dsl.select(cpCols())
                .from(C)
                .join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(C.ITEM_ID))
                .leftJoin(MST_ROUTING).on(MST_ROUTING.ROUTING_ID.eq(C.ROUTING_ID))
                .leftJoin(APQP_FMEA).on(APQP_FMEA.FMEA_ID.eq(C.FMEA_ID))
                .leftJoin(PMS_PROJECT).on(PMS_PROJECT.PROJ_ID.eq(C.PROJ_ID));
    }

    private static Condition cpWhere(CpFilter f) {
        Condition c = noCondition();
        if (f.keywordLike() != null) {
            String k = f.keywordLike();
            c = c.and(C.CP_NO.likeIgnoreCase(k, '\\').or(C.TITLE.likeIgnoreCase(k, '\\'))
                    .or(MST_ITEM.ITEM_CD.likeIgnoreCase(k, '\\')).or(MST_ITEM.ITEM_NM.likeIgnoreCase(k, '\\')));
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            c = c.and(C.STATUS.in(f.statuses()));
        }
        if (f.phase() != null) {
            c = c.and(C.PHASE.eq(f.phase()));
        }
        if (f.itemId() != null) {
            c = c.and(C.ITEM_ID.eq(f.itemId()));
        }
        return c;
    }

    private static Field<?> sortField(String key) {
        return switch (CpSort.valueOf(key)) {
            case CP_NO -> C.CP_NO;
            case REV -> C.REV;
            case PHASE -> C.PHASE;
            case TITLE -> C.TITLE;
            case ITEM_CD -> MST_ITEM.ITEM_CD;
            case STATUS -> C.STATUS;
            case LINE_CNT -> LINE_CNT;
            case UPDATED_AT -> C.UPDATED_AT;
        };
    }

    private static List<SortField<?>> cpOrder(PageQuery page) {
        List<SortField<?>> order = new ArrayList<>();
        for (PageQuery.Sort s : page.getSorts()) {
            Field<?> x = sortField(s.getKey());
            order.add(s.isDesc() ? x.desc() : x.asc());
        }
        order.add(C.CP_ID.desc());
        return order;
    }

    @Override
    public List<CpRow> findCpPage(CpFilter f, PageQuery page) {
        return cpFrom().where(cpWhere(f)).orderBy(cpOrder(page)).limit(page.getSize()).offset(page.getOffset()).fetchInto(CpRow.class);
    }

    @Override
    public void eachCp(CpFilter f, PageQuery sort, Consumer<CpRow> each) {
        try (Cursor<Record> c = cpFrom().where(cpWhere(f)).orderBy(cpOrder(sort)).fetchSize(500).fetchLazy()) {
            c.forEach(r -> each.accept(r.into(CpRow.class)));
        }
    }

    @Override
    public long countCps(CpFilter f) {
        return dsl.selectCount().from(C).join(MST_ITEM).on(MST_ITEM.ITEM_ID.eq(C.ITEM_ID)).where(cpWhere(f)).fetchOne(0, long.class);
    }

    @Override
    public CpRow findCp(long id) {
        return cpFrom().where(C.CP_ID.eq(id)).fetchOneInto(CpRow.class);
    }

    @Override
    public String lockCp(long id) {
        return dsl.select(APQP_CP.STATUS).from(APQP_CP).where(APQP_CP.CP_ID.eq(id)).forUpdate().fetchOne(APQP_CP.STATUS);
    }

    @Override
    public boolean draftExists(String cpNo) {
        return dsl.fetchExists(APQP_CP, APQP_CP.CP_NO.eq(cpNo), APQP_CP.STATUS.in("DRAFT", "IN_APPROVAL"));
    }

    @Override
    public long insertCp(String cpNo, int rev, String phase, CpValue v, Long prevId, String by) {
        var c = APQP_CP;
        return dsl.insertInto(c)
                .set(c.COMP_CD, comp()).set(c.CP_NO, cpNo).set(c.REV, rev).set(c.PHASE, phase).set(c.ITEM_ID, v.itemId())
                .set(c.ROUTING_ID, v.routingId()).set(c.FMEA_ID, v.fmeaId()).set(c.PROJ_ID, v.projId()).set(c.TITLE, v.title())
                .set(c.TEAM, v.team()).set(c.CHANGE_REASON, v.changeReason()).set(c.PREV_CP_ID, prevId)
                .set(c.CREATED_BY, by).set(c.UPDATED_BY, by)
                .returningResult(c.CP_ID).fetchOne().value1();
    }

    @Override
    public int updateCp(long id, CpValue v, int rowVer, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.ROUTING_ID, v.routingId()).set(c.FMEA_ID, v.fmeaId()).set(c.PROJ_ID, v.projId()).set(c.TITLE, v.title())
                .set(c.TEAM, v.team()).set(c.CHANGE_REASON, v.changeReason())
                .set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id), c.ROW_VER.eq(rowVer), c.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int deleteCp(long id, int rowVer) {
        var c = APQP_CP;
        return dsl.deleteFrom(c).where(c.CP_ID.eq(id), c.ROW_VER.eq(rowVer), c.STATUS.eq("DRAFT")).execute();
    }

    @Override
    public int obsoleteReleased(String cpNo, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.STATUS, "OBSOLETE").set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_NO.eq(cpNo), c.STATUS.eq("RELEASED"))
                .execute();
    }

    @Override
    public int releaseCp(long id, int rowVer, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.STATUS, "RELEASED").set(c.RELEASED_AT, currentOffsetDateTime()).set(c.RELEASED_BY, by)
                .set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id), c.ROW_VER.eq(rowVer), c.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int submitCp(long id, long aprId, int rowVer, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.STATUS, "IN_APPROVAL").set(c.APR_ID, aprId)
                .set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id), c.ROW_VER.eq(rowVer), c.STATUS.eq("DRAFT"))
                .execute();
    }

    @Override
    public int approveCp(long id, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.STATUS, "RELEASED").set(c.RELEASED_AT, currentOffsetDateTime()).set(c.RELEASED_BY, by)
                .set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id), c.STATUS.eq("IN_APPROVAL"))
                .execute();
    }

    @Override
    public int draftCp(long id, String by) {
        var c = APQP_CP;
        return dsl.update(c)
                .set(c.STATUS, "DRAFT").set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id), c.STATUS.eq("IN_APPROVAL"))
                .execute();
    }

    @Override
    public void touchCp(long id, String by) {
        var c = APQP_CP;
        dsl.update(c)
                .set(c.ROW_VER, c.ROW_VER.plus(1)).set(c.UPDATED_AT, currentOffsetDateTime()).set(c.UPDATED_BY, by)
                .where(c.CP_ID.eq(id))
                .execute();
    }

    @Override
    public Long cpOfLine(long lineId) {
        return dsl.select(APQP_CP_LINE.CP_ID).from(APQP_CP_LINE).where(APQP_CP_LINE.LINE_ID.eq(lineId)).fetchOne(APQP_CP_LINE.CP_ID);
    }

    // ---------- 행 ----------

    @Override
    public List<CpLineRow> findLines(long cpId) {
        var l = APQP_CP_LINE;
        return dsl.select(l.LINE_ID, l.LINE_NO, l.STEP_ID, MST_ROUTING_STEP.OP_SEQ, MST_PROC.PROC_CD, MST_PROC.PROC_NM, l.STEP_TEXT,
                        l.CHAR_ID, QMS_CHAR.CHAR_NO, QMS_CHAR.CHAR_NM, QMS_CHAR.CHAR_TYPE, l.CHAR_TEXT, l.CTRL_METHOD, l.EVAL_METHOD,
                        l.GAUGE_CLASS, l.INSP_TYPE, l.SAMPLE_N, l.SAMPLE_TEXT, l.FREQ_TYPE, l.FREQ_N, l.REACTION_PLAN, l.RISK_ID,
                        APQP_FMEA_RISK.AP.as("risk_ap"), l.SPECIAL_CLS, QMS_CHAR.SPECIAL_CLS.as("char_special"),
                        QMS_CHAR.NOMINAL.as("char_nominal"), QMS_CHAR.LSL.as("char_lsl"), QMS_CHAR.USL.as("char_usl"),
                        QMS_CHAR.UNIT_NM.as("char_unit"), l.NOMINAL, l.LSL, l.USL, l.UNIT_NM, l.DATA_TYPE, l.ROW_VER)
                .from(l)
                .leftJoin(MST_ROUTING_STEP).on(MST_ROUTING_STEP.STEP_ID.eq(l.STEP_ID))
                .leftJoin(MST_PROC).on(MST_PROC.PROC_ID.eq(MST_ROUTING_STEP.PROC_ID))
                .leftJoin(QMS_CHAR).on(QMS_CHAR.CHAR_ID.eq(l.CHAR_ID))
                .leftJoin(APQP_FMEA_RISK).on(APQP_FMEA_RISK.RISK_ID.eq(l.RISK_ID))
                .where(l.CP_ID.eq(cpId))
                .orderBy(l.LINE_NO, l.LINE_ID)
                .fetchInto(CpLineRow.class);
    }

    @Override
    public long insertLine(long cpId, CpLineRow x, String by) {
        var l = APQP_CP_LINE;
        return dsl.insertInto(l)
                .set(l.COMP_CD, comp()).set(l.CP_ID, cpId).set(l.LINE_NO, x.getLineNo())
                .set(l.STEP_ID, x.getStepId()).set(l.STEP_TEXT, x.getStepText()).set(l.CHAR_ID, x.getCharId()).set(l.CHAR_TEXT, x.getCharText())
                .set(l.CTRL_METHOD, x.getCtrlMethod()).set(l.EVAL_METHOD, x.getEvalMethod()).set(l.GAUGE_CLASS, x.getGaugeClass())
                .set(l.INSP_TYPE, x.getInspType()).set(l.SAMPLE_N, x.getSampleN()).set(l.SAMPLE_TEXT, x.getSampleText())
                .set(l.FREQ_TYPE, x.getFreqType()).set(l.FREQ_N, x.getFreqN()).set(l.REACTION_PLAN, x.getReactionPlan())
                .set(l.RISK_ID, x.getRiskId()).set(l.SPECIAL_CLS, x.getSpecialCls())
                .set(l.CREATED_BY, by).set(l.UPDATED_BY, by)
                .returningResult(l.LINE_ID).fetchOne().value1();
    }

    @Override
    public int updateLine(long id, CpLineRow x, int rowVer, String by) {
        var l = APQP_CP_LINE;
        return dsl.update(l)
                .set(l.STEP_ID, x.getStepId()).set(l.STEP_TEXT, x.getStepText()).set(l.CHAR_ID, x.getCharId()).set(l.CHAR_TEXT, x.getCharText())
                .set(l.CTRL_METHOD, x.getCtrlMethod()).set(l.EVAL_METHOD, x.getEvalMethod()).set(l.GAUGE_CLASS, x.getGaugeClass())
                .set(l.INSP_TYPE, x.getInspType()).set(l.SAMPLE_N, x.getSampleN()).set(l.SAMPLE_TEXT, x.getSampleText())
                .set(l.FREQ_TYPE, x.getFreqType()).set(l.FREQ_N, x.getFreqN()).set(l.REACTION_PLAN, x.getReactionPlan())
                .set(l.RISK_ID, x.getRiskId()).set(l.SPECIAL_CLS, x.getSpecialCls())
                .set(l.ROW_VER, l.ROW_VER.plus(1)).set(l.UPDATED_AT, currentOffsetDateTime()).set(l.UPDATED_BY, by)
                .where(l.LINE_ID.eq(id), l.ROW_VER.eq(rowVer))
                .execute();
    }

    @Override
    public int updateLineNo(long id, int lineNo, String by) {
        var l = APQP_CP_LINE;
        return dsl.update(l)
                .set(l.LINE_NO, lineNo).set(l.ROW_VER, l.ROW_VER.plus(1)).set(l.UPDATED_AT, currentOffsetDateTime()).set(l.UPDATED_BY, by)
                .where(l.LINE_ID.eq(id))
                .execute();
    }

    @Override
    public int snapshotLines(long cpId) {
        var l = APQP_CP_LINE;
        return dsl.update(l)
                .set(l.SPECIAL_CLS, coalesce(l.SPECIAL_CLS, QMS_CHAR.SPECIAL_CLS)).set(l.NOMINAL, QMS_CHAR.NOMINAL).set(l.LSL, QMS_CHAR.LSL)
                .set(l.USL, QMS_CHAR.USL).set(l.UNIT_NM, QMS_CHAR.UNIT_NM).set(l.DATA_TYPE, QMS_CHAR.DATA_TYPE)
                .from(QMS_CHAR)
                .where(QMS_CHAR.CHAR_ID.eq(l.CHAR_ID), l.CP_ID.eq(cpId))
                .execute();
    }

    @Override
    public int deleteLines(Collection<Long> ids) {
        return dsl.deleteFrom(APQP_CP_LINE).where(APQP_CP_LINE.LINE_ID.in(ids)).execute();
    }

    // ---------- 근거 PFMEA ----------

    @Override
    public List<FmeaOption> findReleasedFmeas(long itemId) {
        var f = APQP_FMEA;
        return dsl.select(f.FMEA_ID, f.FMEA_NO, f.REV, f.TITLE, f.ROUTING_ID).from(f)
                .where(f.ITEM_ID.eq(itemId), f.STATUS.eq("RELEASED"), f.FMEA_TYPE.eq("PFMEA"))
                .orderBy(f.FMEA_NO.desc())
                .fetchInto(FmeaOption.class);
    }

    @Override
    public Long findLatestCpOfFmea(long fmeaId) {
        return dsl.select(C.CP_ID).from(C).where(C.FMEA_ID.eq(fmeaId), C.STATUS.ne("OBSOLETE"))
                .orderBy(C.REV.desc(), C.CP_ID.desc()).limit(1).fetchOne(C.CP_ID);
    }

    @Override
    public Long findReleasedFmeaIdByNo(String fmeaNo) {
        var f = APQP_FMEA;
        return dsl.select(f.FMEA_ID).from(f).where(f.FMEA_NO.eq(fmeaNo), f.STATUS.eq("RELEASED"), f.FMEA_TYPE.eq("PFMEA")).fetchOne(f.FMEA_ID);
    }
}
