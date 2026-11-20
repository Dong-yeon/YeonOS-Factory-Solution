package io.smarthappy.module.plm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 메일 설정이 꺼진 설치(기본) — 목록 mailEnabled false(화면 버튼 숨김), 후보 없음, 보내기 409 MAIL_DISABLED (docs/39 23장 E8) */
@AutoConfigureMockMvc
class PlmPcnMailOffTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Test
    void 메일이_꺼져_있으면_버튼_정보가_없고_보내기는_409() throws Exception {
        String comp = accounts.newComp("PCO");
        long u = accounts.newUser(comp, "eng1", "Maple-anchor-63");
        accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE");
        RequestPostProcessor owner = accounts.as(comp, u, "eng1");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1', 'SALES', 't', 't')", comp);
        long contact = accounts.query(comp, """
                insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, created_by, updated_by)
                select ?, cust_id, '가', 'a@cust.test', 'Y', 't', 't' from mst_cust where cust_cd = 'C1' returning contact_id""", Long.class, comp);
        MvcResult r = mvc.perform(post("/api/plm/ecos").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"꺼짐\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}")).andExpect(status().isOk()).andReturn();
        long eco = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        r = mvc.perform(post("/api/plm/ecos/" + eco + "/pcns").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")).andExpect(status().isOk()).andReturn();
        long pcn = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get("/api/plm/ecos/" + eco + "/pcns").with(owner)).andExpect(jsonPath("$.data[0].mailEnabled").value(false));
        mvc.perform(get("/api/plm/pcns/" + pcn + "/mail-targets").with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.contacts.length()").value(0));
        mvc.perform(post("/api/plm/pcns/" + pcn + "/mail").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", UUID.randomUUID().toString()).content("{\"contactIds\":[" + contact + "]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAIL_DISABLED"));
    }
}
