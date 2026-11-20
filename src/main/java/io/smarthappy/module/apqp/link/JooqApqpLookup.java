package io.smarthappy.module.apqp.link;

import static io.smarthappy.module.apqp.jooq.Tables.APQP_CP;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_ACTION;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_FAIL;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_FMEA_RISK;
import static io.smarthappy.module.apqp.jooq.Tables.APQP_QMS_DOC;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * {@link ApqpLookup} 구현 — 읽기만, 회사 범위는 RLS(다른 회사 id 는 없는 것으로). 예외를 내지 않는다.
 * FMEA = PFMEA 만(DFMEA 는 A5-2 에서 생긴 기록 유형 — 다른 모듈 연결은 아직 없다, docs/41 13.7)
 */
@Component
@RequiredArgsConstructor
class JooqApqpLookup implements ApqpLookup {

    private static final String PFMEA = "PFMEA";

    private final DSLContext dsl;

    @Override
    public Optional<RevView> rev(String kind, long id) {
        if (FMEA.equals(kind)) {
            var f = APQP_FMEA;
            return dsl.select(f.FMEA_ID, f.FMEA_NO, f.REV, f.TITLE, f.STATUS, f.RELEASED_AT).from(f).where(f.FMEA_ID.eq(id), f.FMEA_TYPE.eq(PFMEA))
                    .fetchOptional(r -> new RevView(FMEA, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
        }
        if (CP.equals(kind)) {
            var c = APQP_CP;
            return dsl.select(c.CP_ID, c.CP_NO, c.REV, c.TITLE, c.STATUS, c.RELEASED_AT).from(c).where(c.CP_ID.eq(id))
                    .fetchOptional(r -> new RevView(CP, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
        }
        return Optional.empty();
    }

    @Override
    public List<RevView> revsByNo(String kind, String no) {
        if (no == null || no.isBlank()) {
            return List.of();
        }
        String n = no.strip();
        if (FMEA.equals(kind)) {
            var f = APQP_FMEA;
            return dsl.select(f.FMEA_ID, f.FMEA_NO, f.REV, f.TITLE, f.STATUS, f.RELEASED_AT).from(f).where(upper(f.FMEA_NO).eq(upper(val(n))), f.FMEA_TYPE.eq(PFMEA))
                    .orderBy(f.REV.desc())
                    .fetch(r -> new RevView(FMEA, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
        }
        if (CP.equals(kind)) {
            var c = APQP_CP;
            return dsl.select(c.CP_ID, c.CP_NO, c.REV, c.TITLE, c.STATUS, c.RELEASED_AT).from(c).where(upper(c.CP_NO).eq(upper(val(n))))
                    .orderBy(c.REV.desc())
                    .fetch(r -> new RevView(CP, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
        }
        return List.of();
    }

    @Override
    public List<CurrentView> current(long itemId) {
        var f = APQP_FMEA;
        var c = APQP_CP;
        var d = APQP_QMS_DOC;
        List<CurrentView> out = new ArrayList<>(dsl.select(f.FMEA_ID, f.FMEA_NO, f.REV, f.TITLE, f.STATUS, f.RELEASED_AT, d.DOC_REV_ID)
                .from(f).leftJoin(d).on(d.SRC_KIND.eq(FMEA), d.SRC_ID.eq(f.FMEA_ID))
                .where(f.ITEM_ID.eq(itemId), f.STATUS.eq("RELEASED"), f.FMEA_TYPE.eq(PFMEA))
                .orderBy(f.FMEA_NO)
                .fetch(r -> new CurrentView(new RevView(FMEA, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()), null,
                        r.value7())));
        // 단계 순서 = 시작품 → 양산 전 → 양산(C1)
        Field<Integer> phaseOrder = when(c.PHASE.eq("PROTO"), 1).when(c.PHASE.eq("PRE_LAUNCH"), 2).otherwise(3);
        out.addAll(dsl.select(c.CP_ID, c.CP_NO, c.REV, c.TITLE, c.STATUS, c.RELEASED_AT, c.PHASE, d.DOC_REV_ID)
                .from(c).leftJoin(d).on(d.SRC_KIND.eq(CP), d.SRC_ID.eq(c.CP_ID))
                .where(c.ITEM_ID.eq(itemId), c.STATUS.eq("RELEASED"))
                .orderBy(phaseOrder, c.CP_NO)
                .fetch(r -> new CurrentView(new RevView(CP, r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()), r.value7(),
                        r.value8())));
        return List.copyOf(out);
    }

    @Override
    public Optional<ActionView> action(long actionId) {
        var a = APQP_FMEA_ACTION;
        var f = APQP_FMEA;
        var r = APQP_FMEA_RISK;
        var fm = APQP_FMEA_FAIL.as("fm");
        var fc = APQP_FMEA_FAIL.as("fc");
        return dsl.select(a.ACTION_ID, a.FMEA_ID, f.FMEA_NO, f.REV, f.STATUS, f.PROJ_ID, f.ITEM_ID, a.STATUS, a.ACTION_KIND, a.ACTION_TEXT,
                        a.OWNER_USER_ID, a.DUE_DATE, fm.FAIL_TEXT, fc.FAIL_TEXT, r.AP)
                .from(a).join(f).on(f.FMEA_ID.eq(a.FMEA_ID)).join(r).on(r.RISK_ID.eq(a.RISK_ID))
                .leftJoin(fm).on(fm.FAIL_ID.eq(r.FM_ID)).leftJoin(fc).on(fc.FAIL_ID.eq(r.FC_ID))
                .where(a.ACTION_ID.eq(actionId), f.FMEA_TYPE.eq(PFMEA))
                .fetchOptional(x -> new ActionView(x.value1(), x.value2(), x.value3(), x.value4(), x.value5(), x.value6(), x.value7(),
                        x.value8(), x.value9(), x.value10(), x.value11(), x.value12(), x.value13(), x.value14(), x.value15()));
    }
}
