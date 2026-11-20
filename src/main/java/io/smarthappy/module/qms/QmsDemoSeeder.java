package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import io.smarthappy.module.demo.DemoSeedOrder;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.qms.GaugeDtos.CalibForm;
import io.smarthappy.module.qms.InspDtos.ActionForm;
import io.smarthappy.module.qms.InspDtos.CreateForm;
import io.smarthappy.module.qms.InspDtos.LineRow;
import io.smarthappy.module.qms.InspDtos.MeasEntry;
import io.smarthappy.module.qms.InspDtos.MeasForm;
import io.smarthappy.module.qms.SpcDtos.LimitForm;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬 QMS 데모 데이터(local 프로필만) — 기준정보 데모(DemoDataSeeder)의 드로잉 공정검사 계획(64311-DM100 · DRAW)으로
 * **판정까지 확정된 검사 30건**을 지난 30일에 걸쳐 하루 1건 정도 만든다. 그래서 qms-spc 의 X̄-R 관리도와 SPC 위반 홈 카드가 비어 있지 않다.
 *
 * - 화면과 같은 서비스 길: 생산 LOT(LotService) → 검사 등록 → 측정값 입력 → 판정 확정(InspService) → 고정 관리한계(SpcService).
 *   채번·측정 원장·SPC 위반 원장·감사 컬럼이 화면 흐름과 같게 채워진다(mapper 직접 INSERT 없음). 실행자 = 회사 관리자.
 *   계측기만 기준정보라 DemoDataSeeder 처럼 직접 넣고(물리 자산), 교정 기록은 GaugeCalibService 로.
 * - 앞 26건 = 관리 상태(고정 시드 정규분포) → 그 26군으로 고정 한계(WE1·WE2) → 마지막 4건은 평균이 공차의 25% 위로 이동(규격 안 · 한계 밖)
 *   → 판정 확정 때 SPC 위반(WE1 등)이 기록돼 "미처리 SPC 위반" 카드에 잡힌다. 판정은 모두 OK(규격 안)라 NCR·재고 연동은 없다.
 * - 지난 시각: 서비스는 BizClock 의 지금을 쓰므로 **이 시더 스레드에서만** 지정 시각을 돌려주는 시계를 잠깐 끼운다(다른 스레드는 실제 시각,
 *   끝나면 실제 시계로 되돌림). 검사계획 유효 시작일보다 앞으로는 가지 않는다 — 시작일이 가까우면 그 사이에 몰아 넣는다.
 * - 회사에 검사가 하나라도 있으면 아무것도 하지 않는다(사용자 데이터를 덮지 않음). 전부 한 트랜잭션이라 실패하면 하나도 남지 않는다.
 * - 모든 ApplicationRunner(DemoDataSeeder 포함) 뒤인 ApplicationReadyEvent 에서 돈다(PmsDemoSeeder 와 같은 이유).
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class QmsDemoSeeder {

    static final int COUNT = 30;
    /** 마지막 이 건수만 한계 밖 */
    static final int SHIFTED = 4;
    static final int BASELINE = COUNT - SHIFTED;
    static final int SUBGROUP = 5;
    static final long SEED = 20261102L;
    static final String ITEM_CD = "64311-DM100";
    static final String INSP_TYPE = "PROCESS";
    static final long LOT_QTY = 600;
    /** 검사 로트 크기 — 전수(S-ALL) 육안 행이 이 수만큼 */
    static final long INSP_LOT_QTY = 50;

    /** 계측기 클래스별 데모 계측기 이름(없는 클래스는 공통 이름) */
    static final Map<String, String> GAUGE_NAMES = Map.of(
            "GO_NOGO", "핀 게이지 세트(GO/NO-GO)",
            "CMM", "3차원 측정기",
            "HEIGHT", "하이트 게이지",
            "CALIPER", "디지털 버니어 캘리퍼스");

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final LotService lots;
    private final InspService insps;
    private final SpcService spc;
    private final GaugeCalibService gauges;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.QMS)
    public void onReady() {
        if (!enabled) {
            return;
        }
        List<LoginUser> admins = runner.call(compCd, () -> jdbc.query("""
                select u.user_id, u.login_id, u.user_nm, u.locale_cd from sys_user u
                 where u.use_yn = 'Y' and exists (select 1 from sys_user_auth a join sys_auth_group g
                                                     on g.comp_cd = a.comp_cd and g.auth_grp_cd = a.auth_grp_cd
                                                  where a.user_id = u.user_id and g.admin_yn = 'Y' and g.use_yn = 'Y')
                 order by u.user_id limit 1""",
                (rs, i) -> new LoginUser(rs.getLong("user_id"), compCd, rs.getString("login_id"), rs.getString("user_nm"),
                        rs.getString("locale_cd"), false)));
        if (admins.isEmpty()) {
            return;
        }
        try {
            int n = seed(compCd, admins.get(0));
            if (n > 0) {
                log.info("데모 데이터: 회사 {} 에 판정 확정 검사 {}건(SPC 관리도 · 마지막 {}건 한계 밖)", compCd, n, SHIFTED);
            }
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 원인은 로그로
            log.warn("QMS 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }

    /** 반환 = 만든 검사 수(이미 있거나 데모 기준정보가 없으면 0). user = 실행자(화면 권한·감사 컬럼) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, () -> {
            Integer n = jdbc.queryForObject("select count(*) from qms_insp", Integer.class);
            if (n == null || n > 0) {
                return 0;
            }
            List<Map<String, Object>> plan = jdbc.queryForList("""
                    select p.item_id, p.proc_id, pr.proc_cd, p.valid_from from qms_insp_plan p
                      join mst_item i on i.item_id = p.item_id join mst_proc pr on pr.proc_id = p.proc_id
                     where i.item_cd = ? and p.insp_type = ? and p.status = 'RELEASED'
                     order by p.valid_from, p.insp_plan_id limit 1""", ITEM_CD, INSP_TYPE);
            List<Long> wo = jdbc.queryForList("""
                    select w.wo_id from prd_wo w join mst_item i on i.item_id = w.item_id where i.item_cd = ? order by w.wo_id limit 1""",
                    Long.class, ITEM_CD);
            if (plan.isEmpty() || wo.isEmpty()) {
                return 0;
            }
            long itemId = ((Number) plan.get(0).get("item_id")).longValue();
            long procId = ((Number) plan.get(0).get("proc_id")).longValue();
            String procCd = (String) plan.get(0).get("proc_cd");
            LocalDate validFrom = ((java.sql.Date) plan.get(0).get("valid_from")).toLocalDate();
            ZoneId zone = clock.zone();
            Instant now = clock.instant();
            List<Instant> times = schedule(clock.today(), validFrom, zone, now);
            Map<String, Long> gaugeOf = gauges(times.get(0), zone);
            return withClock(c -> run(c, wo.get(0), itemId, procId, procCd, times, gaugeOf));
        }));
    }

    private int run(SeedClock c, long woId, long itemId, long procId, String procCd, List<Instant> times, Map<String, Long> gaugeOf) {
        Random rnd = new Random(SEED);
        Long targetChar = null;
        for (int k = 0; k < times.size(); k++) {
            if (k == BASELINE) {
                c.at(times.get(k - 1).plus(30, ChronoUnit.MINUTES));
                spc.createLimit(new LimitForm(itemId, targetChar, INSP_TYPE, procId, BASELINE, null, null,
                        "데모 — 앞 " + BASELINE + "군(관리 상태)으로 정한 고정 한계(가상)"));
            }
            Instant t = times.get(k);
            c.at(t.minus(90, ChronoUnit.MINUTES));
            long lotId = lots.createProduced(woId, BigDecimal.valueOf(LOT_QTY), t.minus(90, ChronoUnit.MINUTES), SamplingService.me().loginId());
            String lotNo = jdbc.queryForObject("select lot_no from lot_master where lot_id = ?", String.class, lotId);
            c.at(t);
            long id = insps.create(new CreateForm(lotNo, INSP_TYPE, procCd, INSP_LOT_QTY, "데모 — 드로잉 공정검사(가상)"));
            c.at(t.plus(1, ChronoUnit.MINUTES));
            List<LineRow> lines = insps.detail(id).lines();
            if (targetChar == null) {
                targetChar = target(lines).getCharId();
            }
            for (LineRow l : lines) {
                boolean shifted = k >= BASELINE && l.getCharId() == targetChar;
                insps.measure(id, new MeasForm(l.getInspLineId(), entries(l, rnd, shifted, gaugeOf)));
            }
            insps.judge(id, new ActionForm(insps.detail(id).insp().getRowVer(), null));
        }
        return times.size();
    }

    /** 관리도 대상 = 규격 상하한이 있는 첫 계량형 행(데모 계획에선 "홀 지름 A") */
    static LineRow target(List<LineRow> lines) {
        return lines.stream().filter(l -> "VARIABLE".equals(l.getDataType()) && l.getLsl() != null && l.getUsl() != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("관리도 대상 계량형 행이 없습니다"));
    }

    /**
     * 한 행의 샘플 값. 계량형 = 규격 중앙 + 정규분포(σ = 공차/12), shifted 면 평균을 공차의 25% 위로 — 행 자릿수로 반올림, 규격 안으로 자른다
     * (판정은 OK, 관리한계만 밖). 계수형 = 모두 OK
     */
    static List<MeasEntry> entries(LineRow l, Random rnd, boolean shifted, Map<String, Long> gaugeOf) {
        Long gauge = "VISUAL".equals(l.getGaugeClass()) ? null : gaugeOf.get(l.getGaugeClass());
        List<MeasEntry> out = new ArrayList<>();
        for (int s = 1; s <= l.getSampleSize(); s++) {
            if (!"VARIABLE".equals(l.getDataType())) {
                out.add(new MeasEntry(s, null, "Y", gauge));
                continue;
            }
            out.add(new MeasEntry(s, value(l, rnd, shifted), null, gauge));
        }
        return out;
    }

    static BigDecimal value(LineRow l, Random rnd, boolean shifted) {
        int scale = l.getScale() == null ? 2 : l.getScale();
        BigDecimal step = BigDecimal.ONE.movePointLeft(scale);
        double lsl = l.getLsl() != null ? l.getLsl().doubleValue() : l.getNominal().doubleValue() - 1;
        double usl = l.getUsl() != null ? l.getUsl().doubleValue() : l.getNominal().doubleValue() + 1;
        double tol = usl - lsl;
        double center = l.getNominal() != null ? l.getNominal().doubleValue() : (lsl + usl) / 2;
        double v = center + (shifted ? tol * 0.25 : 0) + rnd.nextGaussian() * tol / 12;
        BigDecimal d = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal lo = BigDecimal.valueOf(lsl).add(step).setScale(scale, RoundingMode.CEILING);
        BigDecimal hi = BigDecimal.valueOf(usl).subtract(step).setScale(scale, RoundingMode.FLOOR);
        return d.max(lo).min(hi);
    }

    /**
     * 검사 시각 30개(오래된 순) — 시작일 = max(오늘 - 29, 계획 유효 시작일). 하루 1건(9시 + 0~40분)이 기본이고, 날이 모자라면 같은 날 50분 간격.
     * 오늘 몫은 지금보다 앞(마지막 측정 1분 뒤도 지금 전)으로 당긴다
     */
    static List<Instant> schedule(LocalDate today, LocalDate validFrom, ZoneId zone, Instant now) {
        LocalDate start = today.minusDays(COUNT - 1L);
        if (validFrom.isAfter(start)) {
            start = validFrom.isAfter(today) ? today : validFrom;
        }
        int days = (int) ChronoUnit.DAYS.between(start, today) + 1;
        Random rnd = new Random(SEED + 1);
        List<Instant> out = new ArrayList<>();
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        for (int k = 0; k < COUNT; k++) {
            int day = (int) ((long) k * days / COUNT);
            int slot = slots.merge(day, 1, Integer::sum) - 1;
            Instant t = start.plusDays(day).atTime(LocalTime.of(9, 0)).plusMinutes(slot * 50L + rnd.nextInt(40)).atZone(zone).toInstant();
            Instant latest = now.minus(Duration.ofMinutes((COUNT - k) * 3L + 2));
            out.add(t.isAfter(latest) ? latest : t);
        }
        return out;
    }

    /**
     * 계획 행의 계측기 클래스마다 첫 검사 시각에 쓸 수 있는 게이지 — 없으면 데모 계측기(물리 자산)를 만들고 첫 검사 30일 전 사내 교정(합격)을
     * GaugeCalibService 로 남긴다. VISUAL 은 게이지 없음
     */
    private Map<String, Long> gauges(Instant first, ZoneId zone) {
        List<String> classes = jdbc.queryForList("""
                select distinct l.gauge_class from qms_insp_plan_line l join qms_insp_plan p on p.insp_plan_id = l.insp_plan_id
                  join mst_item i on i.item_id = p.item_id
                 where i.item_cd = ? and p.insp_type = ? and p.status = 'RELEASED' and l.gauge_class <> 'VISUAL'
                 order by l.gauge_class""", String.class, ITEM_CD, INSP_TYPE);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String cls : classes) {
            Long usable = null;
            for (Long id : jdbc.queryForList("""
                    select asset_id from mst_phys_asset where asset_type = 'GAUGE' and gauge_class = ? and use_yn = 'Y' order by asset_id""",
                    Long.class, cls)) {
                if (!InspService.BLOCKED.contains(gauges.statusAt(id, first.atZone(zone).toOffsetDateTime()))) {
                    usable = id;
                    break;
                }
            }
            if (usable == null) {
                String cd = "GG-" + cls.replace('_', '-') + "-D1";
                usable = jdbc.queryForObject("""
                        insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                        values (hb_current_comp(), ?, 'GAUGE', ?, ?, 365, 'DEMOSEED', 'DEMOSEED') returning asset_id""",
                        Long.class, cd, GAUGE_NAMES.getOrDefault(cls, "데모 계측기 " + cls), cls);
                gauges.addCalib(usable, new CalibForm(first.atZone(zone).toLocalDate().minusDays(30).toString(), "OK", null, "INTERNAL",
                        null, "CAL-DEMO-" + cls, 365, "데모 — 사내 교정(가상)", null));
            }
            out.put(cls, usable);
        }
        return out;
    }

    /** 시더 스레드에만 지정 시각을 주는 시계를 BizClock 에 끼우고, 끝나면(실패해도) 실제 시계로 되돌린다 */
    private <T> T withClock(java.util.function.Function<SeedClock, T> work) {
        SeedClock c = new SeedClock(Thread.currentThread(), ZoneId.of("UTC"), new Instant[1]);
        clock.setClock(c);
        try {
            return work.apply(c);
        } finally {
            clock.setClock(Clock.systemUTC());
        }
    }

    private static <T> T as(LoginUser user, Supplier<T> work) {
        SecurityContext before = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    /** owner 스레드에서 at 이 정해져 있으면 그 시각, 그 밖(요청·스케줄러 스레드)은 실제 시각. at 은 withZone 사본끼리 공유 */
    static final class SeedClock extends Clock {

        private final Thread owner;
        private final ZoneId zone;
        private final Instant[] at;
        private static final Clock REAL = Clock.systemUTC();

        SeedClock(Thread owner, ZoneId zone, Instant[] at) {
            this.owner = owner;
            this.zone = zone;
            this.at = at;
        }

        void at(Instant t) {
            at[0] = t;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new SeedClock(owner, z, at);
        }

        @Override
        public Instant instant() {
            Instant t = at[0];
            return t != null && Thread.currentThread() == owner ? t : REAL.instant();
        }
    }
}
