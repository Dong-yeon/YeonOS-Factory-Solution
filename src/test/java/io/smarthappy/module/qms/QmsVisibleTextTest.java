package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * QA R4-a 2단계(QMS) — 필수 사유·설명 칸이 보이지 않는 글자(U+200B·U+00A0·U+FEFF·U+00AD·U+3164)만으로 통과하지 않는다
 * (제안 docs/qa/2026-11-02_invisible-reason-proposal.md, 플랫폼 VisibleText). 오류 코드는 그대로 REQUIRED, 앞뒤 보이지 않는 글자는 걷어내고 저장,
 * 가운데 제어 문자는 FORMAT. 판정 도우미가 QMS 전체에서 하나(SamplingService.blank·text 를 지움)라 대표 칸만 API 로 본다.
 */
class QmsVisibleTextTest extends QmsTestBase {

    static final String N = "/api/qms/ncrs";
    static final String O = "/api/qms/ocaps";
    static final String C = "/api/qms/capas";

    /** JSON 안에 이스케이프로 — 서버가 받는 값은 그 글자 자체 */
    static final List<String> INVISIBLE = List.of("\\u200B", "\\u00A0\\u200B", "\\uFEFF", "\\u00AD", "\\u3164", " \\u2060\\u3000 ");

    long defect;

    @BeforeEach
    void vtSetUp() {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-ocap", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-capa", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-VT', '스크래치', 'APPEARANCE', 't', 't')""", comp);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-VT'", Long.class);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'L-VT', item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp);
    }

    String ncrBody(String desc) {
        return "{\"lotNo\":\"L-VT\",\"source\":\"PROCESS\",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"" + desc + "\"}";
    }

    long count(String table) {
        return accounts.query(comp, "select count(*) from " + table, Long.class);
    }

    @Test
    void NCR_설명_취소_사유는_보이지_않는_글자만이면_REQUIRED() throws Exception {
        for (String v : INVISIBLE) {
            postJson(N, ncrBody(v)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("description")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        assertThat(count("qms_ncr")).isZero();
        // 앞뒤 보이지 않는 글자는 걷어내고 저장, 가운데는 그대로
        long id = id(postJson(N, ncrBody("\\u200B 외관\\u200D스크래치 \\uFEFF")));
        assertThat(accounts.query(comp, "select description from qms_ncr where ncr_id = ?", String.class, id)).isEqualTo("외관\u200D스크래치");
        int ver = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, id);
        for (String v : INVISIBLE) {
            postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"" + v + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        // 가운데 제어 문자(탭·줄바꿈 아닌 것) → FORMAT
        postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"중복\\u0007등록\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        assertThat(accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, id)).isEqualTo("OPEN");
        // 여러 줄 사유는 된다
        postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"중복 등록\\n같은 LOT\"}").andExpect(status().isOk());
    }

    @Test
    void OCAP_조치_문구와_8D_제목_취소_사유도_같은_규칙() throws Exception {
        for (String v : INVISIBLE) {
            postJson(O, "{\"ruleCd\":\"WE1\",\"seq\":1,\"actionText\":\"" + v + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("actionText")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
            postJson(C, "{\"source\":\"INTERNAL\",\"title\":\"" + v + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'title')].code").value(org.hamcrest.Matchers.hasItem("REQUIRED")));
        }
        assertThat(count("qms_ocap_item")).isZero();
        assertThat(count("qms_capa")).isZero();
        long capa = id(postJson(C, "{\"source\":\"INTERNAL\",\"title\":\"\\u00A0내부 크랙\\u200B\"}"));
        assertThat(accounts.query(comp, "select title from qms_capa where capa_id = ?", String.class, capa)).isEqualTo("내부 크랙");
        int ver = accounts.query(comp, "select row_ver from qms_capa where capa_id = ?", Integer.class, capa);
        for (String v : INVISIBLE) {
            postJson(C + "/" + capa + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"" + v + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        assertThat(accounts.query(comp, "select status from qms_capa where capa_id = ?", String.class, capa)).isEqualTo("OPEN");
        // 길이는 코드 포인트(이모지 하나 = 1) — OCAP 문구 500 자 = 이모지 500 개는 통과
        postJson(O, "{\"ruleCd\":\"WE1\",\"seq\":2,\"actionText\":\"" + "😀".repeat(500) + "\"}").andExpect(status().isOk());
        postJson(O, "{\"ruleCd\":\"WE1\",\"seq\":3,\"actionText\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }
}
