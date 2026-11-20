package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.smarthappy.module.aps.ApsDtos.PlanForm;
import io.smarthappy.module.aps.ItemParamDtos.ParamForm;
import io.smarthappy.module.demo.DemoSeedSupport;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * APS 계획 데모 (docs/38 17.6, docs/63 G10 — docs/61 10장 "데모" 방식: 서비스 계층, 비어 있을 때만, 한 트랜잭션).
 * <ul>
 * <li>공장 P1 앞으로 10일 계획 지시 30건(용접 ASSY 18 + 프레스 12, {@link WoService#createAs} — 발행 안 함). 고정 씨앗 난수라 다시 만들어도 같은 모양</li>
 * <li><b>병목</b>: 용접 ASSY 가 첫 사흘에 몰린다(하루 3건) — 스폿 셀 설비 2대가 차서 뒤 지시가 밀린다</li>
 * <li><b>늦음</b>: 용접 지시 5건은 계획 완료일 = 계획 시작일(납기 = 지시 계획 완료일, 수주 연결 없음)이라 밀리면 늦음</li>
 * <li><b>부족</b>: 용접 너트·냉연강판 소요가 데모 재고 + 열린 발주보다 크다 → 자재 표에 부족 줄, 반제품(사이드 멤버) 부족은 "지시 필요"</li>
 * <li>계획 기준정보 4행({@link ItemParamService}) + 계획 1건 계산(14일, WARN, 입고 예정 포함 — {@link ApsService})</li>
 * </ul>
 * 건너뛰기: 이 비고의 지시가 이미 있거나, 데모 품목·확정 라우팅·공장이 없으면. SRM 공급원·재고·수주는 읽기만(다른 시더 것).
 */
@Service
@RequiredArgsConstructor
public class ApsDemoFlow {

    static final String PLANT = "P1";
    public static final String REMARK = "APS 데모 — 계획 시나리오(늦음·부족·병목)";
    static final String WELD = "64310-DM100";
    static final String PRESS = "64311-DM100";
    static final int DAYS = 10;
    static final long SEED = 20261109L;

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final WoService wos;
    private final ItemParamService params;
    private final ApsService plans;

    /** 반환 = 만든 지시 수(건너뛰면 0) */
    public int seed(String comp, LoginUser user) {
        return DemoSeedSupport.as(user, () -> runner.call(comp, () -> run(user)));
    }

    private int run(LoginUser u) {
        Integer done = jdbc.queryForObject("select count(*) from prd_wo where remark = ?", Integer.class, REMARK);
        Integer plant = jdbc.queryForObject("select count(*) from sys_plant where plant_cd = ? and use_yn = 'Y'", Integer.class, PLANT);
        Integer routes = jdbc.queryForObject("""
                select count(distinct i.item_cd) from mst_routing r join mst_item i on i.item_id = r.item_id
                 where i.item_cd in (?, ?) and r.plant_cd = ? and r.status = 'RELEASED'""", Integer.class, WELD, PRESS, PLANT);
        if (done == null || done > 0 || plant == null || plant == 0 || routes == null || routes < 2) {
            return 0;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.zone(PLANT));
        Random r = new Random(SEED);
        int n = 0;
        // 용접 ASSY 18건 — 첫 사흘 하루 3건(병목), 그 뒤 하루 1~2건. 0·1·2·4·7 번째는 계획 완료일 = 시작일(밀리면 늦음)
        int[] weldDays = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 4, 4, 5, 6, 6, 7, 8, 9};
        for (int i = 0; i < weldDays.length; i++) {
            LocalDate start = today.plusDays(weldDays[i]);
            boolean tight = i == 0 || i == 1 || i == 2 || i == 4 || i == 7;
            LocalDate end = tight ? start : start.plusDays(2);
            int qty = 800 + 100 * r.nextInt(7);
            wos.createAs(new WoForm(PLANT, WELD, String.valueOf(qty), start.toString(), end.toString(), null, null, null,
                    tight ? 3 : 5, REMARK, null), u.loginId());
            n++;
        }
        // 프레스 12건 — 이틀에 하나 이상, 큰 로트
        int[] pressDays = {0, 0, 1, 2, 3, 3, 4, 5, 6, 7, 8, 9};
        for (int d : pressDays) {
            LocalDate start = today.plusDays(d);
            int qty = 2000 + 250 * r.nextInt(5);
            wos.createAs(new WoForm(PLANT, PRESS, String.valueOf(qty), start.toString(), start.plusDays(1).toString(), null, null, null,
                    5, REMARK, null), u.loginId());
            n++;
        }
        // 계획 기준정보 — 완제품 FX, 반제품 MIN + 배수 + 자체 생산 리드타임, 구매품 MIN + 배수(구매 조건은 SRM 공급원)
        List<ParamForm> ps = List.of(
                new ParamForm(PLANT, WELD, "200", "FX", "600", null, 2, 3, REMARK, "Y", null),
                new ParamForm(PLANT, PRESS, "500", "MIN", "2000", "500", 1, null, REMARK, "Y", null),
                new ParamForm(PLANT, "NUT-M8-WELD", "5000", "MIN", "10000", "1000", null, null, REMARK, "Y", null),
                new ParamForm(PLANT, "RM-SPFC590-1.6", "3000", "EX", null, "100", null, null, REMARK, "Y", null));
        for (ParamForm p : ps) {
            Integer existing = jdbc.queryForObject("""
                    select count(*) from aps_item_param a join mst_item i on i.item_id = a.item_id
                     where a.plant_cd = ? and i.item_cd = ?""", Integer.class, PLANT, p.itemCd());
            Integer item = jdbc.queryForObject("select count(*) from mst_item where item_cd = ? and use_yn = 'Y'", Integer.class, p.itemCd());
            if (existing != null && existing == 0 && item != null && item > 0) {
                params.save(null, p);       // 사용자가 이미 둔 값은 덮어쓰지 않는다
            }
        }
        plans.calculate(new PlanForm(PLANT, today.toString(), 14, 24, "APS 데모 — 늦음·부족·병목", "WARN", Boolean.TRUE), u.loginId());
        return n;
    }

}
