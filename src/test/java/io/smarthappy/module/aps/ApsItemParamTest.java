package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * 계획 기준정보 (docs/38 17장, docs/63 G1) — 정상(등록·목록·수정), 입력 검사(B 범위·C 형식·자릿수), 동시 수정(F rowVer), 권한(D·API 직접),
 * 직접 SQL(삭제·공장 바꾸기·규칙 CHECK), A사로 B사.
 */
class ApsItemParamTest extends ApsTestBase {

    static final String P = "/api/aps/item-params";

    @BeforeEach
    void params() {
        accounts.grant(comp, userId, "APS", "aps-item-param", "SEARCH", "SAVE");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RMKG', '강판', 'RM', 'BUY', 'KG', 't', 't'), (?, 'OLD', '사용 중지', 'RM', 'BUY', 'EA', 't', 't')""", comp, comp);
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'OLD'");
    }

    ResultActions create(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(P), body).with(who));
    }

    @Test
    void 등록하고_목록에서_찾고_rowVer_로_고친다() throws Exception {
        long id = JsonPathId(create(admin, "{\"plantCd\":\"p1\",\"itemCd\":\"fg\",\"safetyQty\":\"20\",\"lotRule\":\"FX\",\"lotQty\":\"50\","
                + "\"makeLeadDays\":2,\"fenceDays\":3,\"remark\":\"ZT\"}"));
        mvc.perform(get(P + "/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantCd").value("P1")).andExpect(jsonPath("$.data.itemCd").value("FG"))
                .andExpect(jsonPath("$.data.safetyQty").value("20")).andExpect(jsonPath("$.data.lotRule").value("FX"))
                .andExpect(jsonPath("$.data.lotQty").value("50")).andExpect(jsonPath("$.data.lotMult").doesNotExist())
                .andExpect(jsonPath("$.data.makeLeadDays").value(2)).andExpect(jsonPath("$.data.fenceDays").value(3))
                .andExpect(jsonPath("$.data.rowVer").value(0));
        mvc.perform(get(P + "/grid").param("plantCd", "P1").param("keyword", "F").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].procType").value("MAKE"));
        // LIKE 특수 문자는 글자 그대로
        mvc.perform(get(P + "/grid").param("keyword", "%").with(admin)).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(P + "/columns").with(admin)).andExpect(status().isOk());
        // 수정: 공장·품목은 무시(바꿀 수 없음), 규칙 MIN + 배수, 리드타임 비움
        mvc.perform(json(put(P + "/" + id), "{\"plantCd\":\"P2\",\"itemCd\":\"FG2\",\"safetyQty\":\"0\",\"lotRule\":\"MIN\",\"lotQty\":\"10\","
                + "\"lotMult\":\"5\",\"useYn\":\"Y\",\"rowVer\":0}").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantCd").value("P1")).andExpect(jsonPath("$.data.itemCd").value("FG"))
                .andExpect(jsonPath("$.data.lotMult").value("5")).andExpect(jsonPath("$.data.makeLeadDays").doesNotExist())
                .andExpect(jsonPath("$.data.rowVer").value(1));
        // 낡은 rowVer → 409, 값은 그대로
        mvc.perform(json(put(P + "/" + id), "{\"safetyQty\":\"99\",\"lotRule\":\"EX\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select trim_scale(safety_qty)::text from aps_item_param where param_id = ?", String.class, id))
                .isEqualTo("0");
        // 같은 공장·품목 두 번 → 409(칸 오류 itemCd)
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PARAM_DUP"));
        // 다른 공장은 따로
        create(admin, "{\"plantCd\":\"P2\",\"itemCd\":\"FG\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.lotRule").value("EX"))
                .andExpect(jsonPath("$.data.safetyQty").value("0"));
    }

    @Test
    void 규칙별_칸과_범위_자릿수를_서버가_검사한다() throws Exception {
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"lotRule\":\"EX\",\"lotQty\":\"5\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_LOT_EX_NO_QTY"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"lotRule\":\"FX\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lotQty")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"lotRule\":\"FX\",\"lotQty\":\"10\",\"lotMult\":\"5\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_LOT_FX_NO_MULT"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"lotRule\":\"POQ\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lotRule"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"safetyQty\":\"-1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"lotRule\":\"MIN\",\"lotQty\":\"0\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"safetyQty\":\"1.5\"}").andExpect(status().isBadRequest())   // EA 0자리
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"makeLeadDays\":366}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"fenceDays\":-1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fenceDays"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"RMKG\",\"makeLeadDays\":2}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PARAM_NOT_MAKE"));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"OLD\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        create(admin, "{\"plantCd\":\"PX\",\"itemCd\":\"NOPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"useYn\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("useYn"));
        // KG 은 소수 자리 — 구매 품목 안전재고·배수
        create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"RMKG\",\"safetyQty\":\"12.5\",\"lotMult\":\"0.5\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lotMult").value("0.5"));
        mvc.perform(json(put(P + "/999999"), "{\"rowVer\":0}").with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(put(P + "/" + accounts.query(comp, "select param_id from aps_item_param", Long.class)), "{\"safetyQty\":\"1\"}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("rowVer"));
    }

    @Test
    void 조회만_가진_사용자는_API_로_직접_등록할_수_없고_직접_SQL_은_삭제_공장바꾸기_규칙위반을_막는다() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-item-param", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        create(v, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\"}").andExpect(status().isForbidden());
        long id = JsonPathId(create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\"}"));
        mvc.perform(json(put(P + "/" + id), "{\"safetyQty\":\"5\",\"rowVer\":0}").with(v)).andExpect(status().isForbidden());
        mvc.perform(get(P + "/" + id).with(v)).andExpect(status().isOk());

        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_item_param where param_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        Throwable moved = catchThrowable(() -> accounts.exec(comp, "update aps_item_param set plant_cd = 'P2' where param_id = ?", id));
        assertThat(PgErrors.constraint(moved)).contains("ck_aps_item_param_fixed");
        Throwable lot = catchThrowable(() -> accounts.exec(comp, "update aps_item_param set lot_qty = 5 where param_id = ?", id));
        assertThat(PgErrors.constraint(lot)).contains("ck_aps_item_param_lot");
        Throwable mult = catchThrowable(() -> accounts.exec(comp,
                "update aps_item_param set lot_rule = 'FX', lot_qty = 5, lot_mult = 2 where param_id = ?", id));
        assertThat(PgErrors.constraint(mult)).contains("ck_aps_item_param_mult");
        // 감사 기록
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'aps_item_param'", Long.class)).isPositive();
    }

    @Test
    void B사는_A사_기준정보를_보거나_고칠_수_없고_A사_품목으로_행을_넣지_못한다() throws Exception {
        long id = JsonPathId(create(admin, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"safetyQty\":\"7\"}"));
        long fgA = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        String compB = accounts.newComp("PRMB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-item-param", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        mvc.perform(get(P + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(P + "/" + id), "{\"safetyQty\":\"0\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/grid").with(b)).andExpect(jsonPath("$.list.length()").value(0));
        // B사에는 FG 코드가 없다 — A사 품목 코드로 등록 불가
        create(b, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: B사 범위에서 A사 item_id → 같은 회사 트리거
        Throwable cross = catchThrowable(() -> accounts.exec(compB, """
                insert into aps_item_param (comp_cd, plant_cd, item_id, created_by, updated_by) values (?, 'P1', ?, 't', 't')""", compB, fgA));
        assertThat(PgErrors.constraint(cross)).contains("fk_aps_item_param_comp");
        assertThat(accounts.query(comp, "select trim_scale(safety_qty)::text from aps_item_param where param_id = ?", String.class, id))
                .isEqualTo("7");
    }

    private static long JsonPathId(ResultActions r) throws Exception {
        String body = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.paramId")).longValue();
    }
}
