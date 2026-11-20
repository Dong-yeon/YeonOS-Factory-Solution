package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.SpareIssueLink;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C18 예비품 반납 누계 (docs/33 11.15.2) — 계약 SpareIssueLink.returnedQty 를 완료·확인 작업지시에서만 묻고,
 * 키 있는 줄 = 누계·남은 반납 가능, <b>키 없는 줄 = 모름(null — 0 아님)</b>, 기본 구현(NONE) = 모두 모름. 진행 중 작업지시는 묻지 않는다. A사로 B사.
 * 창고 구현은 WMS PR 몫이라 여기서는 계약을 가짜로 둔다.
 */
@AutoConfigureMockMvc
class CmsSpareReturnTotalTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SpareIssueLink link;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SR");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'p', ?, 't', 't')",
                comp, cls);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, 'BRG-6204', '베어링', 'CON', 'BUY', 'EA', 't', 't')", comp);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, 'OIL-68', '유압유', 'CON', 'BUY', 'L', 't', 't')", comp);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private long id(ResultActions r) throws Exception {
        String s = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object v = JsonPath.read(s, "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(s, "$.data.mwoId")).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    /** 작업지시 + 사용 예비품 2줄(베어링 2 EA, 유압유 3.25 L) → 착수. done 이면 완료까지 */
    private long[] mwo(boolean done) throws Exception {
        long m = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
        long p1 = id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"2\"}"));
        long p2 = id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"OIL-68\",\"qty\":\"3.25\"}"));
        postJson(M + "/" + m + "/start", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        if (done) {
            postJson(M + "/" + m + "/finish", "{\"title\":\"정비\",\"workText\":\"교체\",\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        }
        return new long[] {m, p1, p2};
    }

    @Test
    void 완료_작업지시는_누계와_남은반납을_내고_키없는_줄은_모름() throws Exception {
        long[] x = mwo(true);
        // 베어링 = 출고 원장 있음·반납 1, 유압유 = 키 없음(원장 밖 품목 등)
        List<Collection<Long>> asked = new java.util.ArrayList<>();
        when(link.returnedQty(anyCollection())).thenAnswer(inv -> {
            asked.add(List.copyOf(inv.<Collection<Long>>getArgument(0)));
            return Map.of(x[1], new BigDecimal("1"));
        });
        mvc.perform(get(M + "/" + x[0] + "/parts").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.itemCd == 'BRG-6204')].returnedText").value(org.hamcrest.Matchers.contains("1")))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'BRG-6204')].returnableText").value(org.hamcrest.Matchers.contains("1")))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'OIL-68')].returnedText").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'OIL-68')].returnableText").value(org.hamcrest.Matchers.contains((Object) null)));
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).containsExactlyInAnyOrder(x[1], x[2]);                 // 그 작업지시의 줄들로 한 번
        // 출고 원장은 있고 반납 0 → "0"(모름과 다르다), 남은 = 사용 전부(자릿수는 품목 단위)
        org.mockito.Mockito.doReturn(Map.of(x[1], BigDecimal.ZERO, x[2], new BigDecimal("3.25"))).when(link).returnedQty(anyCollection());
        mvc.perform(get(M + "/" + x[0] + "/parts").with(admin))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'BRG-6204')].returnedText").value(org.hamcrest.Matchers.contains("0")))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'BRG-6204')].returnableText").value(org.hamcrest.Matchers.contains("2")))
                .andExpect(jsonPath("$.data[?(@.itemCd == 'OIL-68')].returnableText").value(org.hamcrest.Matchers.contains("0.000")));
        // 빈 맵(창고 연계 없음) = 모두 모름 — 0 으로 보이지 않는다
        org.mockito.Mockito.doReturn(Map.of()).when(link).returnedQty(anyCollection());
        mvc.perform(get(M + "/" + x[0] + "/parts").with(admin))
                .andExpect(jsonPath("$.data[*].returnedText").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void 진행중_작업지시는_창고에_묻지_않는다_기본구현은_빈맵() throws Exception {
        long[] x = mwo(false);
        mvc.perform(get(M + "/" + x[0] + "/parts").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].returnedText").doesNotExist());
        verify(link, never()).returnedQty(any());
        // 계약 기본 구현·NONE = 빈 맵(모름) — WMS 구현은 이 PR 만으로 동작 그대로
        assertThat(SpareIssueLink.NONE.returnedQty(List.of(1L, 2L))).isEmpty();
    }

    @Test
    void B사로_A사_작업지시_예비품은_404_창고에_묻지_않는다() throws Exception {
        long[] x = mwo(true);
        String other = accounts.newComp("SS");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-mwo", "SEARCH", "SAVE");
        mvc.perform(get(M + "/" + x[0] + "/parts").with(accounts.as(other, bu, "admin1"))).andExpect(status().isNotFound());
        verify(link, never()).returnedQty(any());
    }
}
