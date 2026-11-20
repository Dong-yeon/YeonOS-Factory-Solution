package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import io.smarthappy.module.cms.CmsDtos.Created;
import io.smarthappy.module.cms.CmsDtos.FailCodeForm;
import io.smarthappy.module.cms.CmsDtos.LaborForm;
import io.smarthappy.module.cms.CmsDtos.MwoForm;
import io.smarthappy.module.cms.CmsDtos.ProfileForm;
import io.smarthappy.module.cms.CmsDtos.TransitionForm;
import io.smarthappy.module.cms.CmsPmDtos.PlanForm;
import io.smarthappy.module.cms.CmsPmDtos.ResultForm;
import io.smarthappy.module.cms.CmsPmDtos.TmplForm;
import io.smarthappy.module.cms.CmsSpareDtos.PartForm;
import io.smarthappy.module.cms.CmsSpareDtos.SpareForm;
import io.smarthappy.module.cms.CmsStateDtos.LinkForm;
import io.smarthappy.module.cms.CmsStateDtos.ReasonForm;
import io.smarthappy.module.master.item.ItemDtos;
import io.smarthappy.module.master.item.ItemService;
import io.smarthappy.module.pop.PopDtos.TerminalForm;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.pop.TerminalService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CMS 데모 이력 (docs/51 C3 "데모 설비 데이터", docs/64) — 데모 설비 4대(PR-1200·SPOT-101·SPOT-102·CO2-201)에 지난 90일을 화면과 같은 서비스 길로 넣는다.
 * <ul>
 * <li>설비 상태: 데모 수집 장치로 STATE 이벤트를 시각 순으로({@link EquipEventService} — 원장 occurred_at = 그때, recorded_at = 지금). 평일 06~22시 가동,
 *     밤·주말 비계획, 주간 점검 날 06:00~06:30 계획 정지, 고장 = 비계획 정지(UDT) → MTBF·MTTR·가용률이 KPI 화면에 나온다</li>
 * <li>고장 하나 = 고장 작업지시(BM) 하나: 현상·원인·조치 코드(원인 파레토가 한쪽으로 쏠리게 가중치), 정지 시각 = 상태 구간과 같게, 사용 예비품·공수,
 *     오래된 것은 확인(CLOSED), 최근 3건은 완료(DONE), 최근 2건은 상태 구간 연결 없이(검토 대기 카드)</li>
 * <li>주간 점검 계획(CHECK 방식): 생성기 → 결과 입력 → 그날 시각으로 완료({@link ChkRunService#completeAt}) 를 반복 — 몇 번은 하루 늦게, 한 번은 건너뜀(준수율 &lt; 100%)</li>
 * <li>오늘: 진행 중 고장 1건(07시 뒤 기동이면), 신고 1건, 개선 작업지시 1건</li>
 * </ul>
 * 그 설비에 상태 구간·작업지시가 하나라도 있거나 DM- 점검 계획이 있으면 아무것도 하지 않는다(사용자 데이터를 덮지 않음). 한 트랜잭션 — 중간에 실패하면 전부 없던 일.
 * 고장 시각·원인은 고정 씨앗 난수라 다시 만들어도 같은 모양. 예비품 재고 출고는 공장 정책 WMS_MRO_LINK(기본 끔)를 따른다.
 */
@Service
@RequiredArgsConstructor
public class CmsDemoFlow {

    static final String PLANT = "P1";
    static final int DAYS = 90;
    static final String TERMINAL_CD = "DEMO-CMS-COL";
    static final String REASON_UDT = "BREAKDOWN";
    static final String REASON_SDT = "PM-CHECK";

    /** 데모 설비: 코드, 고장 확률(가동일당), 주간 점검 요일, 중요도 */
    record Eq(String cd, double rate, DayOfWeek pmDay, String crit) {
    }

    static final List<Eq> EQUIPS = List.of(
            new Eq("PR-1200", 0.13, DayOfWeek.MONDAY, "A"),
            new Eq("SPOT-101", 0.22, DayOfWeek.TUESDAY, "A"),
            new Eq("SPOT-102", 0.10, DayOfWeek.WEDNESDAY, "B"),
            new Eq("CO2-201", 0.15, DayOfWeek.THURSDAY, "B"));

    /** 고장 모양: 현상·원인·조치, 정지 분(최소~최대), 가중치, 예비품(없으면 null)·수량, 제목, 작업 내용 */
    record Scenario(String symptom, String cause, String action, int minMin, int maxMin, int weight, String spare, String spareQty,
            String title, String work) {
    }

    static final List<Scenario> PRESS = List.of(
            new Scenario("LEAK", "SEAL-WEAR", "REPLACE", 60, 150, 4, "SP-HYD-SEAL", "1", "유압 실린더 누유", "실린더 로드 실 교체, 작동유 보충, 누유 재확인"),
            new Scenario("ALARM", "SENSOR", "REPLACE", 25, 60, 3, "SP-PROX-M12", "1", "하사점 센서 알람", "근접 센서 교체, 감지 거리 조정"),
            new Scenario("NOISE", "BEARING", "REPLACE", 120, 240, 2, "SP-BRG-6205", "2", "메인 모터 이상 소음", "모터 측 베어링 2개 교체, 진동 측정 정상"),
            new Scenario("ESTOP", "OVERLOAD", "RESET", 10, 30, 3, null, null, "과부하 비상 정지", "금형 안 이물 제거, 과부하 해제 뒤 재가동"));

    static final List<Scenario> WELD = List.of(
            new Scenario("WELD-NG", "TIP-WEAR", "REPLACE", 10, 25, 6, "SP-CAP-TIP", "2", "용접 너깃 미달", "캡 팁 교체, 팁 드레싱 주기 확인"),
            new Scenario("ALARM", "SENSOR", "REPLACE", 20, 50, 3, "SP-PROX-M12", "1", "지그 클램프 센서 알람", "클램프 근접 센서 교체"),
            new Scenario("COOLANT", "COOL-CLOG", "CLEAN", 30, 70, 2, null, null, "냉각수 유량 부족", "냉각 라인 스트레이너 청소, 유량 6 L/min 확인"),
            new Scenario("ESTOP", "CABLE", "REPLACE", 60, 120, 2, "SP-WELD-CABLE", "1", "2차 케이블 단선", "2차 케이블 교체, 절연 확인"),
            new Scenario("ALARM", "PROGRAM", "ADJUST", 15, 40, 1, null, null, "로봇 경로 이탈 알람", "티칭 포인트 보정, 시험 용접 확인"));

    /** 고장 코드: 유형, 코드, 이름, 상위 분류(원인만) */
    static final String[][] FAIL_CODES = {
            {"SYMPTOM", "LEAK", "누유", null}, {"SYMPTOM", "NOISE", "이상 소음·진동", null}, {"SYMPTOM", "ESTOP", "비상 정지", null},
            {"SYMPTOM", "ALARM", "제어기 알람", null}, {"SYMPTOM", "WELD-NG", "용접 불량", null}, {"SYMPTOM", "COOLANT", "냉각수 이상", null},
            {"CAUSE", "SEAL-WEAR", "실(seal) 마모", "FLUID"}, {"CAUSE", "BEARING", "베어링 손상", "MECH"}, {"CAUSE", "SENSOR", "센서 불량", "ELEC"},
            {"CAUSE", "OVERLOAD", "과부하·이물", "OPER"}, {"CAUSE", "TIP-WEAR", "전극·콘택트 팁 마모", "TOOL"}, {"CAUSE", "CABLE", "케이블 단선", "ELEC"},
            {"CAUSE", "COOL-CLOG", "냉각 라인 막힘", "UTIL"}, {"CAUSE", "PROGRAM", "프로그램 오류", "CTRL"},
            {"ACTION", "REPLACE", "부품 교체", null}, {"ACTION", "ADJUST", "조정·보정", null}, {"ACTION", "RESET", "리셋·재가동", null},
            {"ACTION", "CLEAN", "청소·세척", null}};

    /** 예비품 품목: 코드, 이름, 규격, 연결 클래스 */
    static final String[][] SPARES = {
            {"SP-HYD-SEAL", "유압 실린더 실 키트", "Φ80 로드 실·더스트 실", "PRESS"},
            {"SP-BRG-6205", "볼 베어링 6205", "6205-2RS", "PRESS"},
            {"SP-PROX-M12", "근접 센서 M12", "M12 PNP 4mm", "PRESS"},
            {"SP-PROX-M12", "근접 센서 M12", "M12 PNP 4mm", "WELD"},
            {"SP-CAP-TIP", "스폿 캡 팁", "CuCr Φ16 DR형", "WELD"},
            {"SP-WELD-CABLE", "스폿 2차 케이블", "250 sq 1.2 m", "WELD"}};

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final TerminalService terminals;
    private final EquipEventService events;
    private final StateReasonService reasons;
    private final FailCodeService failCodes;
    private final EquipProfileService profiles;
    private final ItemService items;
    private final SpareService spares;
    private final ChkTmplService tmpls;
    private final PmPlanService plans;
    private final PmGenerator generator;
    private final ChkRunService runs;
    private final MwoService mwos;
    private final StateReviewService review;

    /** 고장 하나(과거) — 시각은 분 단위 */
    record Failure(Eq eq, String classCd, Scenario sc, Instant down, Instant up) {
    }

    /** 점검 예정 하나의 처리: ON_TIME(예정일) / LATE(하루 뒤) / SKIP */
    enum PmAct { ON_TIME, LATE, SKIP }

    /** 상태 이벤트 하나 */
    record Ev(Eq eq, Instant at, String cat, String reason) {
    }

    /** 반환 = 만든 작업지시 수(건너뛰면 0). user = 실행자(회사 관리자) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, () -> run(user)));
    }

    private int run(LoginUser u) {
        Map<String, String> classOf = new LinkedHashMap<>();
        jdbc.query("""
                select e.equip_cd, c.class_cd from mst_equip e join mst_equip_class c on c.class_id = e.class_id
                 where e.plant_cd = ? and e.use_yn = 'Y' and c.class_cd in ('PRESS', 'WELD') and e.equip_cd in ('PR-1200', 'SPOT-101', 'SPOT-102', 'CO2-201')
                 order by e.equip_cd""", rs -> {
                    classOf.put(rs.getString(1), rs.getString(2));
                }, PLANT);
        List<Eq> eqs = EQUIPS.stream().filter(e -> classOf.containsKey(e.cd())).toList();
        if (eqs.isEmpty() || used(classOf.keySet())) {
            return 0;
        }
        ZoneId z = clock.zone(PLANT);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, z);
        LocalDate start = today.minusDays(DAYS);

        masters(u, eqs, classOf);
        Map<String, Map<LocalDate, PmAct>> pm = pmSchedule(eqs, start, today);
        List<Failure> failures = failures(eqs, classOf, start, today, z, pm);
        boolean liveDown = !weekend(today) && now.isAfter(at(today, 7, 0, z)) && classOf.containsKey("SPOT-101");
        Instant liveAt = liveDown ? now.minus(35, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES) : null;

        stateEvents(u, eqs, start, today, now, z, pm, failures, liveAt);
        pmHistory(u, eqs, classOf, start, today, z, pm);
        int n = workOrders(u, failures, z);
        n += today(u, z, today, liveAt);
        return n;
    }

    /** 그 설비에 상태 구간·작업지시가 있거나 데모 점검 계획이 있으면 이미 쓰는 회사 */
    private boolean used(java.util.Collection<String> cds) {
        String[] arr = cds.toArray(String[]::new);
        Integer n = jdbc.queryForObject("""
                select (select count(*) from cms_equip_state_log s join mst_equip e on e.equip_id = s.equip_id where e.equip_cd = any(?))
                     + (select count(*) from cms_mwo m join mst_equip e on e.equip_id = m.equip_id where e.equip_cd = any(?))
                     + (select count(*) from cms_pm_plan where plan_cd like 'DM-%')""", Integer.class, arr, arr);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ 기준: 사유·고장 코드·프로필·예비품·점검표·계획·수집 장치

    private void masters(LoginUser u, List<Eq> eqs, Map<String, String> classOf) {
        reason(REASON_UDT, "고장 정지", "UDT", "N");
        reason(REASON_SDT, "주간 점검", "SDT", "Y");
        int sort = 10;
        for (String[] f : FAIL_CODES) {
            if (count("select count(*) from cms_fail_code where code_type = ? and fail_cd = ?", f[0], f[1]) == 0) {
                failCodes.create(new FailCodeForm(f[0], f[1], f[2], f[3], null, sort, "Y", "데모", null));
            }
            sort += 10;
        }
        for (Eq e : eqs) {
            Long equipId = jdbc.queryForObject("select equip_id from mst_equip where equip_cd = ?", Long.class, e.cd());
            Integer rv = profiles.get(equipId).getRowVer();
            profiles.save(equipId, new ProfileForm(e.crit(), "A".equals(e.crit()) ? "Y" : "N", u.loginId(), null, "데모 — 보전 담당", rv));
        }
        if (!items.erpPull()) {
            for (String[] s : SPARES) {
                if (count("select count(*) from mst_item where item_cd = ?", s[0]) == 0) {
                    items.create(new ItemDtos.ItemForm(s[0], s[1], s[2], "CON", "BUY", "EA", "N", null, null, "데모 예비품", "Y", null, null));
                }
            }
        }
        for (String[] s : SPARES) {
            if (classOf.containsValue(s[3]) && count("select count(*) from mst_item where item_cd = ? and use_yn = 'Y'", s[0]) > 0
                    && count("""
                            select count(*) from cms_equip_spare p join mst_item i on i.item_id = p.item_id
                              join mst_equip_class c on c.class_id = p.equip_class_id where i.item_cd = ? and c.class_cd = ?""", s[0], s[3]) == 0) {
                spares.create(new SpareForm("CLASS", s[3], s[0], "2", s[0].equals("SP-CAP-TIP") ? "Y" : "N", "Y", "데모", null));
            }
        }
        for (String cls : classOf.values().stream().distinct().toList()) {
            String cd = "DM-" + cls + "-W";
            if (count("select count(*) from cms_chk_tmpl where tmpl_cd = ?", cd) > 0) {
                continue;
            }
            boolean press = "PRESS".equals(cls);
            long id = tmpls.create(new TmplForm(cd, press ? "프레스 주간 점검(데모)" : "용접 로봇 주간 점검(데모)", "EQUIP", cls, "Y", "데모", null));
            tmpls.saveItems(id, 0, press ? List.of(
                    new CmsPmDtos.ItemForm(null, 1, "유압 작동유 압력", "압력계 지시값", "NUM", "18", "22", "MPa", 1, "Y"),
                    new CmsPmDtos.ItemForm(null, 2, "윤활유 수준", "게이지 범위 안", "OKNG", null, null, null, 0, "Y"),
                    new CmsPmDtos.ItemForm(null, 3, "슬라이드 이상 소음·진동", "공운전 1분", "OKNG", null, null, null, 0, "Y"),
                    new CmsPmDtos.ItemForm(null, 4, "특이사항", null, "TEXT", null, null, null, 0, "N"))
                    : List.of(
                    new CmsPmDtos.ItemForm(null, 1, "냉각수 유량", "유량계 지시값", "NUM", "4", "8", "L/min", 1, "Y"),
                    new CmsPmDtos.ItemForm(null, 2, "전극 팁 드레싱 상태", "팁 끝 모양 육안", "OKNG", null, null, null, 0, "Y"),
                    new CmsPmDtos.ItemForm(null, 3, "2차 케이블 손상", "피복·단자 육안", "OKNG", null, null, null, 0, "Y"),
                    new CmsPmDtos.ItemForm(null, 4, "특이사항", null, "TEXT", null, null, null, 0, "N")));
        }
    }

    private void reason(String cd, String nm, String cat, String planned) {
        if (count("select count(*) from cms_state_reason where reason_cd = ?", cd) == 0) {
            reasons.create(new ReasonForm(cd, nm, cat, planned, 900, "Y", null));
        }
    }

    /** 사유가 다른 분류로 이미 있거나 쓰지 않으면 사유 없이 */
    private String reasonOrNull(String cd, String cat) {
        return count("select count(*) from cms_state_reason where reason_cd = ? and state_cat = ? and use_yn = 'Y'", cd, cat) > 0 ? cd : null;
    }

    // ------------------------------------------------------------------ 일정(고정 씨앗)

    /** 설비마다 주간 점검 예정일(시작 뒤 첫 그 요일부터, 오늘 전까지) → 처리. 여섯 번에 한 번 하루 늦게, SPOT-102 의 여덟 번째는 건너뜀 */
    static Map<String, Map<LocalDate, PmAct>> pmSchedule(List<Eq> eqs, LocalDate start, LocalDate today) {
        Map<String, Map<LocalDate, PmAct>> out = new HashMap<>();
        for (Eq e : eqs) {
            Map<LocalDate, PmAct> m = new LinkedHashMap<>();
            int k = 0;
            for (LocalDate d = anchor(start, e); d.isBefore(today); d = d.plusWeeks(1), k++) {
                PmAct a = "SPOT-102".equals(e.cd()) && k == 7 ? PmAct.SKIP : k % 6 == 4 && d.plusDays(1).isBefore(today) ? PmAct.LATE : PmAct.ON_TIME;
                m.put(d, a);
            }
            out.put(e.cd(), m);
        }
        return out;
    }

    static LocalDate anchor(LocalDate start, Eq e) {
        return start.with(TemporalAdjusters.nextOrSame(e.pmDay()));
    }

    /** 그 날 점검을 실제로 한 설비인지(계획 정지 06:00~06:30) */
    private static boolean pmDone(Map<LocalDate, PmAct> m, LocalDate d) {
        if (m.get(d) == PmAct.ON_TIME) {
            return true;
        }
        return m.get(d.minusDays(1)) == PmAct.LATE;
    }

    /** 평일 가동 시간(06:30~ 이후) 중 무작위 고장. 씨앗 = 설비 순번 — 다시 만들어도 같은 날·같은 원인 */
    static List<Failure> failures(List<Eq> eqs, Map<String, String> classOf, LocalDate start, LocalDate today, ZoneId z,
            Map<String, Map<LocalDate, PmAct>> pm) {
        List<Failure> out = new ArrayList<>();
        for (Eq e : eqs) {
            Random r = new Random(20261102L + EQUIPS.indexOf(e));
            String cls = classOf.get(e.cd());
            List<Scenario> scs = "PRESS".equals(cls) ? PRESS : WELD;
            int total = scs.stream().mapToInt(Scenario::weight).sum();
            for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
                if (weekend(d)) {
                    continue;
                }
                double roll = r.nextDouble();
                int pick = r.nextInt(total);
                int offset = r.nextInt(600);
                int durPick = r.nextInt(1000);
                if (roll >= e.rate()) {
                    continue;
                }
                Scenario sc = scs.get(0);
                for (Scenario s : scs) {
                    if (pick < s.weight()) {
                        sc = s;
                        break;
                    }
                    pick -= s.weight();
                }
                if ("CO2-201".equals(e.cd()) && "TIP-WEAR".equals(sc.cause())) {
                    sc = new Scenario(sc.symptom(), sc.cause(), sc.action(), sc.minMin(), sc.maxMin(), sc.weight(), "TIP-M6-1.2", "3",
                            "아크 불안정·비드 불량", "콘택트 팁·노즐 교체, 와이어 송급 확인");
                }
                int dur = sc.minMin() + durPick % (sc.maxMin() - sc.minMin() + 1);
                Instant down = at(d, 6, 40, z).plus(offset, ChronoUnit.MINUTES);
                out.add(new Failure(e, cls, sc, down, down.plus(dur, ChronoUnit.MINUTES)));
            }
        }
        out.sort(Comparator.comparing(Failure::down));
        return out;
    }

    // ------------------------------------------------------------------ 설비 상태 (수집 이벤트)

    private void stateEvents(LoginUser u, List<Eq> eqs, LocalDate start, LocalDate today, Instant now, ZoneId z,
            Map<String, Map<LocalDate, PmAct>> pm, List<Failure> failures, Instant liveAt) {
        String udt = reasonOrNull(REASON_UDT, "UDT");
        String sdt = reasonOrNull(REASON_SDT, "SDT");
        List<Ev> evs = new ArrayList<>();
        for (Eq e : eqs) {
            evs.add(new Ev(e, at(start.minusDays(1), 22, 0, z), "NST", null));
            Map<LocalDate, PmAct> m = pm.get(e.cd());
            for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
                if (weekend(d)) {
                    continue;
                }
                if (d.equals(today)) {
                    if (now.isAfter(at(d, 6, 5, z))) {
                        evs.add(new Ev(e, at(d, 6, 0, z), "PRD", null));
                    }
                    continue;
                }
                if (pmDone(m, d)) {
                    evs.add(new Ev(e, at(d, 6, 0, z), "SDT", sdt));
                    evs.add(new Ev(e, at(d, 6, 30, z), "PRD", null));
                } else {
                    evs.add(new Ev(e, at(d, 6, 0, z), "PRD", null));
                }
                evs.add(new Ev(e, at(d, 22, 0, z), "NST", null));
            }
        }
        for (Failure f : failures) {
            evs.add(new Ev(f.eq(), f.down(), "UDT", udt));
            evs.add(new Ev(f.eq(), f.up(), "PRD", null));
        }
        if (liveAt != null) {
            evs.add(new Ev(EQUIPS.get(1), liveAt, "UDT", udt));
        }
        evs.sort(Comparator.comparing(Ev::at));

        long termId = terminals.create(new TerminalForm(TERMINAL_CD, "데모 수집 장치(시더)", PLANT, null, TerminalService.COLLECTOR,
                "데모 — CMS 시더가 지난 90일 상태 이벤트를 넣을 때만 씀(키 버림)", null)).terminalId();
        TerminalPrincipal t = new TerminalPrincipal(u.compCd(), termId, TERMINAL_CD, "데모 수집 장치(시더)", PLANT, null, null, null,
                TerminalService.COLLECTOR);
        JsonNodeFactory f = JsonNodeFactory.instance;
        for (Ev ev : evs) {
            ObjectNode o = f.objectNode();
            o.put("eventId", eventId(ev.eq().cd(), ev.at()));
            o.put("type", "STATE");
            o.put("occurredAt", ev.at().toString());
            o.put("equipCd", ev.eq().cd());
            ArrayNode ms = o.putArray("metrics");
            ms.addObject().put("name", "state").put("value", ev.cat());
            if (ev.reason() != null) {
                ms.addObject().put("name", "reason").put("value", ev.reason());
            }
            events.handle(t, o);
        }
    }

    static String eventId(String equipCd, Instant at) {
        return "CMSDEMO-" + equipCd + "-" + at.getEpochSecond();
    }

    // ------------------------------------------------------------------ 주간 점검 이력

    private void pmHistory(LoginUser u, List<Eq> eqs, Map<String, String> classOf, LocalDate start, LocalDate today, ZoneId z,
            Map<String, Map<LocalDate, PmAct>> pm) {
        Map<Long, String> planEq = new HashMap<>();
        for (Eq e : eqs) {
            String cd = "DM-PM-" + e.cd();
            long id = plans.create(new PlanForm(cd, e.cd() + " 주간 점검(데모)", "EQUIP", e.cd(), "DM-" + classOf.get(e.cd()) + "-W", "CHECK",
                    "WEEK", 1, "FIXED", anchor(start, e).toString(), 2, u.loginId(), 30, "Y", "데모", null, "CAL", null, null, null, null));
            planEq.put(id, e.cd());
        }
        Long[] ids = planEq.keySet().toArray(Long[]::new);
        Random r = new Random(4242L);
        for (int guard = 0; guard < 40; guard++) {
            generator.generate(u.loginId());
            List<Map<String, Object>> open = jdbc.queryForList("""
                    select run_id, plan_id, due_date from cms_chk_run where plan_id = any(?) and status = 'OPEN' and due_date < ?
                     order by due_date, run_id""", ids, today);
            if (open.isEmpty()) {
                return;
            }
            for (Map<String, Object> o : open) {
                long runId = ((Number) o.get("run_id")).longValue();
                LocalDate due = ((java.sql.Date) o.get("due_date")).toLocalDate();
                PmAct act = pm.get(planEq.get(((Number) o.get("plan_id")).longValue())).getOrDefault(due, PmAct.ON_TIME);
                if (act == PmAct.SKIP) {
                    runs.skip(runId, runRv(runId), "설비 이설 공사로 그 주 생산 중단 — 점검 건너뜀");
                    continue;
                }
                List<ResultForm> res = new ArrayList<>();
                for (Map<String, Object> it : jdbc.queryForList("select seq, judge_type, lsl, usl from cms_chk_result where run_id = ? order by seq", runId)) {
                    int seq = ((Number) it.get("seq")).intValue();
                    boolean ng = r.nextInt(30) == 0;
                    switch ((String) it.get("judge_type")) {
                        case "NUM" -> {
                            BigDecimal lo = (BigDecimal) it.get("lsl");
                            BigDecimal hi = (BigDecimal) it.get("usl");
                            BigDecimal v = ng ? hi.add(BigDecimal.ONE)
                                    : lo.add(hi.subtract(lo).multiply(BigDecimal.valueOf(0.2 + r.nextDouble() * 0.6)));
                            res.add(new ResultForm(seq, v.setScale(1, RoundingMode.HALF_UP).toPlainString(), null, null, ng ? "기준 밖 — 보고" : null));
                        }
                        case "OKNG" -> res.add(new ResultForm(seq, null, null, ng ? "NG" : "OK", ng ? "다음 고장 작업 때 확인" : null));
                        default -> res.add(new ResultForm(seq, null, null, null, null));
                    }
                }
                runs.saveResults(runId, null, runRv(runId), res);
                LocalDate day = act == PmAct.LATE ? due.plusDays(1) : due;
                runs.completeAt(runId, runRv(runId), at(day, 6, 10, z).atZone(z).toOffsetDateTime());
            }
        }
    }

    // ------------------------------------------------------------------ 고장 작업지시

    private int workOrders(LoginUser u, List<Failure> failures, ZoneId z) {
        int n = failures.size();
        for (int i = 0; i < n; i++) {
            Failure f = failures.get(i);
            Scenario sc = f.sc();
            String ds = local(f.down(), z);
            String de = local(f.up(), z);
            String rep = local(f.down().plus(3, ChronoUnit.MINUTES), z);
            String st = local(f.down().plus(8, ChronoUnit.MINUTES), z);
            int prio = "A".equals(f.eq().crit()) ? 1 : 2;
            String title = f.eq().cd() + " " + sc.title();
            Created c = mwos.create(new MwoForm("BM", "EQUIP", f.eq().cd(), title, prio, sc.symptom(), null, rep, u.loginId(), null, "Y", ds,
                    null, null, null, null, null, null, null, null));
            mwos.start(c.mwoId(), new TransitionForm(mwoRv(c.mwoId()), null, st, null));
            if (sc.spare() != null && count("select count(*) from mst_item where item_cd = ? and use_yn = 'Y'", sc.spare()) > 0) {
                spares.addPart(c.mwoId(), new PartForm(sc.spare(), sc.spareQty(), null));
            }
            int minutes = (int) Math.max(1, ChronoUnit.MINUTES.between(f.down(), f.up()) - 5);
            mwos.addLabor(c.mwoId(), new LaborForm(u.loginId(), LocalDate.ofInstant(f.down(), z).toString(), minutes, null));
            mwos.finish(c.mwoId(), new MwoForm("BM", "EQUIP", f.eq().cd(), title, prio, sc.symptom(), null, rep, u.loginId(), null, "Y", ds, de,
                    st, de, sc.cause(), sc.action(), sc.work(), null, mwoRv(c.mwoId())));
            if (i < n - 3) {
                mwos.close(c.mwoId(), new TransitionForm(mwoRv(c.mwoId()), null, null, null));
            }
            if (i < n - 2) {
                Long log = jdbc.queryForObject("select state_log_id from cms_equip_state_log where event_id = ?", Long.class,
                        eventId(f.eq().cd(), f.down()));
                review.link(log, new LinkForm(c.mwoId()));
            }
        }
        return n;
    }

    /** 오늘: 진행 중 고장(가능하면) · 신고 · 개선 작업지시 */
    private int today(LoginUser u, ZoneId z, LocalDate today, Instant liveAt) {
        int n = 0;
        if (liveAt != null) {
            Eq e = EQUIPS.get(1);
            String ds = local(liveAt, z);
            Created c = mwos.create(new MwoForm("BM", "EQUIP", e.cd(), e.cd() + " 용접 너깃 미달 — 진행 중", 1, "WELD-NG", null,
                    local(liveAt.plus(3, ChronoUnit.MINUTES), z), u.loginId(), null, "Y", ds, null, null, null, null, null, null, null, null));
            mwos.start(c.mwoId(), new TransitionForm(mwoRv(c.mwoId()), null,
                    local(liveAt.plus(8, ChronoUnit.MINUTES), z), null));
            n++;
        }
        if (count("select count(*) from mst_equip where equip_cd = 'PR-1200' and use_yn = 'Y'") > 0) {
            mwos.report(new MwoForm("BM", "EQUIP", "PR-1200", "PR-1200 슬라이드 가이드 미세 소음", 3, "NOISE",
                    "가동 중 가끔 '딱' 소리 — 다음 점검 때 확인 요청", null, null, null, "N", null, null, null, null, null, null, null, null, null));
            mwos.create(new MwoForm("CM", "EQUIP", "PR-1200", "PR-1200 안전 펜스 인터록 개선(이중화)", 3, null, null, null, u.loginId(),
                    today.plusDays(14).toString(), "N", null, null, null, null, null, null, null, null, null));
            n += 2;
        }
        return n;
    }

    // ------------------------------------------------------------------ 도우미

    static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static Instant at(LocalDate d, int h, int m, ZoneId z) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(z).toInstant();
    }

    /** 화면 입력과 같은 공장 현지 "yyyy-MM-ddTHH:mm" */
    static String local(Instant t, ZoneId z) {
        return LocalDateTime.ofInstant(t, z).truncatedTo(ChronoUnit.MINUTES).toString();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Integer mwoRv(long mwoId) {
        return jdbc.queryForObject("select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwoId);
    }

    private Integer runRv(long runId) {
        return jdbc.queryForObject("select row_ver from cms_chk_run where run_id = ?", Integer.class, runId);
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
