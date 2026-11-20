package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopDtos.TerminalForm;
import io.smarthappy.module.pop.PopWorkDtos.DefectLine;
import io.smarthappy.module.pop.PopWorkDtos.EventForm;
import io.smarthappy.module.pop.PopWorkDtos.ParamForm;
import io.smarthappy.module.pop.PopWorkDtos.ResultForm;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.pop.TerminalService;
import io.smarthappy.module.production.CompletionDtos.CloseForm;
import io.smarthappy.module.production.ReceiptDtos.ReceiveForm;
import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.module.production.WoDtos.AssignForm;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * MES 데모 이력 (docs/25 4.53, docs/61 10장 "데모") — 데모 품목 2개(프레스 64311-DM100, 용접 ASSY 64310-DM100)의 지난 90일 평일 작업을
 * 화면과 같은 서비스 길로 넣는다. 생산 현황판(C1)의 일별 계획 대비 실적·근무조 달성률과 CMS KPI 의 성능·품질·OEE 칸이 채워진다.
 * <ul>
 * <li>하루에 지시 2건: 오늘 계획으로 만들고 발행(라우팅·BOM 이 오늘 유효 — 데모 기준정보는 첫 기동일부터 유효) → 계획일을 그날로 고친다(발행 뒤 일정 변경 길).
 *     발행 시각은 지금이다(V033 스냅샷 트리거 — 발행 트랜잭션 표식)</li>
 * <li>공정마다 착수·종료({@link PopWorkService#eventAt})와 실적·파라미터 이관({@link PopWorkService#resultImport}·{@link PopWorkService#paramImport},
 *     entry_method IMPORT) — 발생 시각은 그날, 기록 시각은 지금. 근무조 실적 둘(주간 19:55·야간 21:50)</li>
 * <li>양 = 그날 그 설비의 가동(PRD) 시간 × 성능(80~93%) ÷ 표준 사이클 — CMS 시더가 먼저 넣은 상태 원장이 있으면 그 시간, 없으면 15.5시간.
 *     앞 공정 양품 = 다음 공정 처리 합(보존식 허용 0% 통과), 폐기는 공정별 불량 유형 가중치(파레토 1위 = 스폿 누락)</li>
 * <li>공정 완료판정 → 지시 완료 → 용접 ASSY 양품 LOT 은 그날 시각으로 완제품 창고 입고({@link ReceiptService#receiveAt}, 재고 연령) →
 *     7일 넘은 지시는 마감(CLOSED)</li>
 * <li>오늘(평일 09시 뒤 기동이면): 용접 지시 1건 진행 중(첫 공정 착수 + 주간 부분 실적)</li>
 * </ul>
 * 데모 설비(PR-1200·SPOT-101·SPOT-102)에 실적이 하나라도 있거나 "데모 이력" 지시가 있으면 아무것도 하지 않는다. 한 트랜잭션 — 중간에 실패하면 전부 없던 일.
 * 양·불량·파라미터는 고정 씨앗 난수라 다시 만들어도 같은 모양(가동 시간이 같다면).
 */
@Service
@RequiredArgsConstructor
public class MesDemoFlow {

    static final String PLANT = "P1";
    static final int DAYS = 90;
    static final String TERMINAL_CD = "DEMO-MES-POP";
    public static final String REMARK = "데모 이력 — 실적·파라미터는 이관(IMPORT), 발행 시각은 시드 시각";
    static final String PRESS_ITEM = "64311-DM100";
    static final String WELD_ITEM = "64310-DM100";
    static final List<String> EQUIPS = List.of("PR-1200", "SPOT-101", "SPOT-102");
    static final BigDecimal PRESS_PLAN = new BigDecimal("4400");
    static final BigDecimal WELD_PLAN = new BigDecimal("1100");
    /** 상태 원장이 없을 때 하루 가동 가정(06:00~22:00 − 점검·고장 여유) */
    static final long DEFAULT_PRD_SEC = 55_800;
    /** 마감(CLOSED)까지 두는 날 — 그보다 최근 지시는 완료(DONE)로 */
    static final int CLOSE_AFTER_DAYS = 7;

    /** 공정 코드 → 폐기율(처리 합 대비)과 불량 유형 가중치 */
    record Scrap(double rate, String[] codes, int[] weights) {
    }

    static final Map<String, Scrap> SCRAP = Map.of(
            "BLANK", new Scrap(0.002, new String[] {"BURR", "DENT"}, new int[] {3, 1}),
            "DRAW", new Scrap(0.0025, new String[] {"CRACK", "WRINKLE", "BURR", "FLANGE"}, new int[] {4, 3, 1, 1}),
            "SPOT", new Scrap(0.012, new String[] {"SPOT-MISS", "SPATTER", "WELD-STR", "HOLE-POS"}, new int[] {7, 3, 2, 1}),
            "EDP", new Scrap(0.001, new String[] {"SCRATCH"}, new int[] {1}),
            "INSP", new Scrap(0.003, new String[] {"SCRATCH", "NUT-MISS", "DENT", "LABEL-ERR"}, new int[] {3, 2, 2, 1}));

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final TerminalService terminals;
    private final PopWorkService pop;
    private final WoService wos;
    private final CompletionService completion;
    private final ReceiptService receipts;

    /** 지시 공정 하나 — 순번·공정 코드·배정할 설비(null = 없음) */
    record Proc(long woProcId, int opSeq, String procCd, String equipCd) {
    }

    /** 실행 문맥 */
    private record Ctx(LoginUser u, TerminalPrincipal t, PopWorker welder, PopWorker presser, ZoneId z, Instant now, long fgLoc,
            Map<String, Long> prdSec, java.util.Set<String> defects, Random r) {
    }

    /** 반환 = 만든 지시 수(건너뛰면 0). user = 실행자(회사 관리자) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, () -> run(user)));
    }

    private int run(LoginUser u) {
        Map<String, Long> equipIds = new HashMap<>();
        jdbc.query("select equip_cd, equip_id from mst_equip where plant_cd = ? and use_yn = 'Y' and equip_cd = any(?)",
                rs -> {
                    equipIds.put(rs.getString(1), rs.getLong(2));
                }, PLANT, EQUIPS.toArray(String[]::new));
        if (equipIds.size() < EQUIPS.size() || count("""
                select count(*) from mst_routing r join mst_item i on i.item_id = r.item_id
                 where i.item_cd in (?, ?) and r.plant_cd = ? and r.status = 'RELEASED'""", PRESS_ITEM, WELD_ITEM, PLANT) < 2) {
            return 0;
        }
        List<Long> loc = jdbc.queryForList("""
                select h.node_id from mst_equip_hier h left join wms_loc_attr a on a.node_id = h.node_id
                 where h.plant_cd = ? and h.node_cd = 'FG-A01' and h.use_yn = 'Y'
                   and coalesce(a.loc_usage, 'STORAGE') <> 'STAGING' and coalesce(a.stock_yn, 'Y') = 'Y'""", Long.class, PLANT);
        List<Map<String, Object>> workers = jdbc.queryForList("""
                select worker_id, worker_cd, worker_nm from mst_worker where plant_cd = ? and use_yn = 'Y' order by worker_cd""", PLANT);
        if (loc.isEmpty() || workers.isEmpty() || used(equipIds.values())) {
            return 0;
        }
        ZoneId z = clock.zone(PLANT);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, z);
        LocalDate start = today.minusDays(DAYS);

        long termId = terminals.create(new TerminalForm(TERMINAL_CD, "데모 POP(시더)", PLANT, null, "POP",
                "데모 — MES 시더가 지난 " + DAYS + "일 실적을 넣을 때만 씀(활성화 안 함)", null)).terminalId();
        TerminalPrincipal t = new TerminalPrincipal(u.compCd(), termId, TERMINAL_CD, "데모 POP(시더)", PLANT, null, null, null, "POP");
        long since = now.toEpochMilli();
        Ctx c = new Ctx(u, t, worker(workers, "E1001", termId, since), worker(workers, "E1003", termId, since), z, now, loc.get(0),
                prdSeconds(z, start), new java.util.HashSet<>(jdbc.queryForList("select defect_cd from qms_defect where use_yn = 'Y'",
                        String.class)), new Random(20261103L));

        int n = 0;
        for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
            if (weekend(d)) {
                continue;
            }
            boolean close = d.isBefore(today.minusDays(CLOSE_AFTER_DAYS));
            pressDay(c, d, close);
            weldDay(c, d, close);
            n += 2;
        }
        n += live(c, today);
        return n;
    }

    /** 데모 설비에 실적이 있거나 데모 이력 지시가 있으면 이미 쓰는 회사 */
    private boolean used(java.util.Collection<Long> equipIds) {
        return count("select count(*) from prd_result where equip_id = any(?)", (Object) equipIds.toArray(Long[]::new)) > 0
                || count("select count(*) from prd_wo where remark = ?", REMARK) > 0;
    }

    private static PopWorker worker(List<Map<String, Object>> ws, String cd, long termId, long since) {
        Map<String, Object> w = ws.stream().filter(x -> cd.equals(x.get("worker_cd"))).findFirst().orElse(ws.get(0));
        return new PopWorker(termId, ((Number) w.get("worker_id")).longValue(), (String) w.get("worker_cd"), (String) w.get("worker_nm"),
                since, since);
    }

    /** 설비·공장 현지 날짜별 가동(PRD) 초 — CMS 상태 원장(살아 있는 행) */
    private Map<String, Long> prdSeconds(ZoneId z, LocalDate start) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("""
                select e.equip_cd, (s.start_at at time zone ?)::date as d,
                       sum(extract(epoch from coalesce(s.end_at, now()) - s.start_at))::bigint as sec
                  from cms_equip_state_log s join mst_equip e on e.equip_id = s.equip_id
                 where e.equip_cd = any(?) and s.state_cat = 'PRD' and s.reversal_of_id is null
                   and not exists (select 1 from cms_equip_state_log v where v.reversal_of_id = s.state_log_id)
                   and s.start_at >= ?
                 group by 1, 2""", rs -> {
                    out.put(rs.getString(1) + "@" + rs.getDate(2).toLocalDate(), rs.getLong(3));
                }, z.getId(), EQUIPS.toArray(String[]::new), java.sql.Timestamp.from(at(start.minusDays(1), 0, 0, z)));
        return out;
    }

    private long prd(Ctx c, String equipCd, LocalDate d) {
        return c.prdSec().getOrDefault(equipCd + "@" + d, DEFAULT_PRD_SEC);
    }

    // ------------------------------------------------------------------ 하루 작업

    /** 프레스: 블랭킹 → 드로잉, 둘 다 PR-1200. 이상 처리 시간 = 3초(6초/2캐비티) + 8초 */
    private void pressDay(Ctx c, LocalDate d, boolean close) {
        long wo = order(c, PRESS_ITEM, PRESS_PLAN, d);
        List<Proc> procs = procs(wo);
        double perf = 0.80 + c.r().nextDouble() * 0.13;
        long q = round10(prd(c, "PR-1200", d) * perf / 11.0);
        run(c, wo, procs, d, q, c.presser());
        finish(c, wo, d, close, false);
    }

    /** 용접 ASSY: 스폿 1(SPOT-101, 45초) → 스폿 2(SPOT-102, 40초) → 전착(외주) → 최종 검사. 병목 = 스폿 1, 스폿 2 가 못 따라가면 그만큼 */
    private void weldDay(Ctx c, LocalDate d, boolean close) {
        long wo = order(c, WELD_ITEM, WELD_PLAN, d);
        List<Proc> procs = procs(wo);
        double perf = 0.80 + c.r().nextDouble() * 0.13;
        long q = round10(Math.min(prd(c, "SPOT-101", d) * perf / 45.0, prd(c, "SPOT-102", d) * 0.95 / 40.0));
        run(c, wo, procs, d, q, c.welder());
        finish(c, wo, d, close, true);
    }

    /** 오늘 계획으로 만들고 발행 → 계획일을 그날로(발행 뒤 일정 변경). 설비 배정은 공정 위치의 데모 설비 */
    private long order(Ctx c, String itemCd, BigDecimal plan, LocalDate d) {
        String today = LocalDate.ofInstant(c.now(), c.z()).toString();
        long wo = wos.createAs(new WoForm(PLANT, itemCd, plan.toPlainString(), today, today, null, null, null, 5, REMARK, null),
                c.u().loginId());
        wos.releaseAs(wo, c.u().loginId());
        if (!d.toString().equals(today)) {
            wos.update(wo, new WoForm(null, null, null, d.toString(), d.toString(), null, null, null, 5, REMARK, woRv(wo)));
        }
        for (Proc p : procs(wo)) {
            if (p.equipCd() != null) {
                wos.assign(p.woProcId(), new AssignForm(p.equipCd(), procRv(p.woProcId())));
            }
        }
        return wo;
    }

    /** 지시 공정 + 위치 노드로 정한 데모 설비(PR-L1 → PR-1200, WD-L1-C1 → SPOT-101, WD-L1-C2 → SPOT-102) */
    private List<Proc> procs(long wo) {
        return jdbc.query("""
                select wp.wo_proc_id, wp.op_seq, wp.proc_cd,
                       case h.node_cd when 'PR-L1' then 'PR-1200' when 'WD-L1-C1' then 'SPOT-101' when 'WD-L1-C2' then 'SPOT-102' end as equip_cd
                  from prd_wo_proc wp left join mst_equip_hier h on h.node_id = wp.node_id
                 where wp.wo_id = ? order by wp.op_seq""",
                (rs, i) -> new Proc(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)), wo);
    }

    /**
     * 공정 순서대로 착수 → (스폿이면 파라미터) → 주간·야간 실적 → 종료 → 완료판정. 첫 공정 처리 합 = q, 다음 공정 처리 합 = 앞 공정 양품.
     * 착수는 06:05 부터 공정마다 5분씩 뒤, 실적은 19:55(주간 85%)·21:50(야간 나머지), 종료 21:55
     */
    private void run(Ctx c, long wo, List<Proc> procs, LocalDate d, long q, PopWorker w) {
        long input = q;
        for (Proc p : procs) {
            Instant startAt = at(d, 6, 5, c.z()).plus(5L * procs.indexOf(p), ChronoUnit.MINUTES);
            pop.eventAt(c.t(), w, p.woProcId(), "START", new EventForm(req(), null, true), startAt);
            if ("SPOT".equals(p.procCd())) {
                params(c, w, p, d);
            }
            Scrap s = SCRAP.getOrDefault(p.procCd(), new Scrap(0, new String[0], new int[0]));
            long scrap = Math.round(input * s.rate() * (0.5 + c.r().nextDouble()));
            long day = Math.round(input * 0.85);
            long dayScrap = Math.min(scrap, Math.round(scrap * 0.85));
            result(c, w, p, at(d, 19, 55, c.z()), day - dayScrap, dayScrap, s);
            result(c, w, p, at(d, 21, 50, c.z()), (input - day) - (scrap - dayScrap), scrap - dayScrap, s);
            pop.eventAt(c.t(), w, p.woProcId(), "END", new EventForm(req(), null, true), at(d, 21, 55, c.z()));
            completion.closeAs(p.woProcId(), new CloseForm("데모 이력", procRv(p.woProcId())), c.u().loginId());
            input -= scrap;
        }
    }

    private void result(Ctx c, PopWorker w, Proc p, Instant at, long good, long scrap, Scrap s) {
        if (good + scrap <= 0) {
            return;
        }
        List<DefectLine> defects = new ArrayList<>();
        if (scrap > 0) {
            // 폐기는 가중치로 고른 유형 하나 — 그 회사에 없는 유형이면 있는 첫 유형
            String cd = pick(c, s);
            defects.add(new DefectLine("SCRAP", cd, String.valueOf(scrap)));
        }
        pop.resultImport(c.t(), w, p.woProcId(), new ResultForm(req(), at.atZone(c.z()).toOffsetDateTime().toString(), String.valueOf(good),
                String.valueOf(scrap), null, null, defects, List.of(), null));
    }

    private String pick(Ctx c, Scrap s) {
        int total = 0;
        for (int wt : s.weights()) {
            total += wt;
        }
        int roll = total == 0 ? 0 : c.r().nextInt(total);
        for (int i = 0; i < s.codes().length; i++) {
            if (roll < s.weights()[i] && c.defects().contains(s.codes()[i])) {
                return s.codes()[i];
            }
            roll -= s.weights()[i];
        }
        for (String cd : s.codes()) {
            if (c.defects().contains(cd)) {
                return cd;
            }
        }
        return c.defects().stream().sorted().findFirst().orElseThrow();
    }

    /** 스폿 용접 조건: 전류 3번(09·13·17시), 통전 시간·가압력 1번(10시). 대체로 규격 안, 가끔(1/60) 밖 → NG */
    private void params(Ctx c, PopWorker w, Proc p, LocalDate d) {
        Map<String, BigDecimal[]> spec = new HashMap<>();
        jdbc.query("select param_cd, target_val, lsl, usl from prd_wo_param wp where wo_proc_id = ?", rs -> {
            spec.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)});
        }, p.woProcId());
        List<Object[]> plan = List.of(new Object[] {"WELD_CURRENT", 9}, new Object[] {"WELD_CURRENT", 13}, new Object[] {"WELD_CURRENT", 17},
                new Object[] {"WELD_TIME", 10}, new Object[] {"TIP_FORCE", 10});
        for (Object[] x : plan) {
            BigDecimal[] sp = spec.get((String) x[0]);
            if (sp == null || sp[0] == null || sp[1] == null || sp[2] == null) {
                continue;
            }
            double target = sp[0].doubleValue();
            double half = (sp[2].doubleValue() - sp[1].doubleValue()) / 2;
            boolean out = c.r().nextInt(60) == 0;
            double v = out ? target + half * (1.15 + c.r().nextDouble() * 0.2) * (c.r().nextBoolean() ? 1 : -1)
                    : target + c.r().nextGaussian() * half / 4;
            int scale = Math.max(sp[0].scale(), 1);
            String value = BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
            pop.paramImport(c.t(), w, p.woProcId(), new ParamForm(req(), at(d, (Integer) x[1], 0, c.z()).atZone(c.z()).toOffsetDateTime()
                    .toString(), (String) x[0], value));
        }
    }

    /** 지시 완료 → (용접 ASSY) 마지막 공정 양품 LOT 을 그날 22:10 으로 완제품 창고 입고 → 오래된 것은 마감 */
    private void finish(Ctx c, long wo, LocalDate d, boolean close, boolean receive) {
        completion.completeAs(wo, new ActForm(null, woRv(wo)), c.u().loginId());
        if (receive) {
            List<Map<String, Object>> out = jdbc.queryForList("""
                    select r.lot_id, trim_scale(sum(r.good_qty))::text as qty from prd_result r
                     where r.wo_proc_id = (select wo_proc_id from prd_wo_proc where wo_id = ? order by op_seq desc limit 1)
                       and r.lot_id is not null group by r.lot_id order by r.lot_id""", wo);
            for (Map<String, Object> o : out) {
                receipts.receiveAt(new ReceiveForm(req(), wo, ((Number) o.get("lot_id")).longValue(), c.fgLoc(), (String) o.get("qty")),
                        at(d, 22, 10, c.z()), c.u().loginId());
            }
        }
        if (close) {
            completion.closeWo(wo, new ActForm(null, woRv(wo)));
        }
    }

    /** 오늘 평일 09시 뒤면 용접 지시 하나를 진행 중으로: 첫 공정 착수 06:05 + 지금 20분 전까지의 주간 실적 */
    private int live(Ctx c, LocalDate today) {
        if (weekend(today) || c.now().isBefore(at(today, 9, 0, c.z()))) {
            return 0;
        }
        long wo = order(c, WELD_ITEM, WELD_PLAN, today);
        Proc first = procs(wo).get(0);
        pop.eventAt(c.t(), c.welder(), first.woProcId(), "START", new EventForm(req(), null, true), at(today, 6, 5, c.z()));
        Instant upTo = c.now().minus(20, ChronoUnit.MINUTES);
        long q = round10(ChronoUnit.SECONDS.between(at(today, 6, 5, c.z()), upTo) * 0.85 / 45.0);
        if (q > 0) {
            result(c, c.welder(), first, upTo, q, 0, SCRAP.get("SPOT"));
        }
        return 1;
    }

    // ------------------------------------------------------------------ 도우미

    static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static Instant at(LocalDate d, int h, int m, ZoneId z) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(z).toInstant();
    }

    private static long round10(double v) {
        return Math.max(0, Math.round(v / 10.0) * 10);
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Integer woRv(long wo) {
        return jdbc.queryForObject("select row_ver from prd_wo where wo_id = ?", Integer.class, wo);
    }

    private Integer procRv(long woProcId) {
        return jdbc.queryForObject("select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, woProcId);
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
}
