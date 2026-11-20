package io.smarthappy.module.demo;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.DefectLine;
import io.smarthappy.module.pop.PopWorkDtos.EventForm;
import io.smarthappy.module.pop.PopWorkDtos.InputLine;
import io.smarthappy.module.pop.PopWorkDtos.ResultForm;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.production.CompletionDtos.CloseForm;
import io.smarthappy.module.production.CompletionService;
import io.smarthappy.module.production.ReceiptDtos.ReceiveForm;
import io.smarthappy.module.production.ReceiptService;
import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.module.production.erp.ErpPublisher;
import io.smarthappy.module.production.erp.FakeErpAdapter;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.production.WoService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬 데모 데이터(local 프로필): 가상 고객 "자동차 2·3차 협력사(용접·프레스)"의 거래처·담당자·품목 (docs/06 검토 M1, docs/17 1단계).
 * 종류마다 회사에 그 데이터가 하나도 없을 때만 넣는다 — 사용자가 고친 데이터를 덮지 않는다.
 * 이름은 모두 가상이고, 사업자번호는 검증식은 맞지만 발급되지 않는 000 으로 시작한다. 메일은 example.com.
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    static final String SEED_BY = "DEMOSEED";

    /** 코드, 이름, 구분, 대표, 지역, 사용 여부, 비고 */
    record Cust(String cd, String nm, String type, String ceo, String area, String useYn, String remark) {
    }

    static final List<Cust> CUSTS = List.of(
            new Cust("S001", "가온모빌리티", "SALES", "김가온", "울산", "Y", "1차 협력사 — 차체 서브 어셈블리 납품"),
            new Cust("S002", "한결오토모티브", "SALES", "이한결", "경주", "Y", "1차 협력사 — 시트 프레임"),
            new Cust("S003", "누리차체", "SALES", "박누리", "아산", "Y", "SQ 심사 매년 4월"),
            new Cust("S004", "다온시트시스템", "SALES", "최다온", "화성", "Y", null),
            new Cust("S005", "미르전장", "SALES", "정미르", "평택", "Y", "브래킷류 소량 다품종"),
            new Cust("S006", "라온브레이크", "SALES", "강라온", "창원", "N", "2025년 거래 종료"),
            new Cust("P001", "단단강판", "PURCHASE", "조단단", "포항", "Y", "냉연·열연 코일"),
            new Cust("P002", "바름코일센터", "PURCHASE", "윤바름", "김해", "Y", "슬리팅·블랭킹"),
            new Cust("P003", "이음볼트", "PURCHASE", "장이음", "시흥", "Y", "용접 너트·볼트"),
            new Cust("P004", "고운도장", "PURCHASE", "임고운", "창원", "Y", "외주 — 전착 도장"),
            new Cust("P005", "한올열처리", "PURCHASE", "한한올", "김해", "Y", "외주 — 열처리"),
            new Cust("P006", "마루금형", "PURCHASE", "오마루", "부천", "Y", "프레스 금형 제작·수리"),
            new Cust("P007", "별빛용접재료", "PURCHASE", "서별빛", "창원", "Y", "용접 와이어·팁"),
            new Cust("P008", "해솔물류", "PURCHASE", "신해솔", "양산", "Y", "완성품 운송"),
            new Cust("P009", "초롱포장", "PURCHASE", "권초롱", "김해", "Y", "회수용 팔레트"),
            new Cust("P010", "아라산업가스", "PURCHASE", "황아라", "울산", "Y", "CO2·Ar 혼합 가스"),
            new Cust("P011", "푸름환경", "PURCHASE", "안푸름", "함안", "Y", "폐기물 처리(스크랩)"),
            new Cust("P012", "너울공구", "PURCHASE", "송너울", "창원", "Y", "소모 공구"),
            new Cust("P013", "다솜계측", "PURCHASE", "류다솜", "대전", "Y", "게이지 교정"),
            new Cust("P014", "여울전기", "PURCHASE", "전여울", "김해", "N", "설비 전기 유지보수 — 계약 만료"),
            new Cust("B001", "가람정밀", "BOTH", "홍가람", "김해", "Y", "외주 가공 + 잔여 자재 판매"),
            new Cust("B002", "소담프레스", "BOTH", "고소담", "창원", "Y", "물량 초과 시 상호 외주"),
            new Cust("B003", "빛길테크", "BOTH", "문빛길", "양산", "Y", null),
            new Cust("B004", "새솔오토텍", "BOTH", "양새솔", "밀양", "Y", "지그 제작"));

    /** 코드, 이름, 규격, 유형, 조달, 단위, LOT 관리, 순질량 kg, HS, 사용 여부 — 차체 용접 ASSY 와 그 프레스품·강판·체결류 */
    record Item(String cd, String nm, String spec, String type, String proc, String unit, String lot, String mass,
            String hs, String useYn) {
    }

    static final List<Item> ITEMS = List.of(
            new Item("64310-DM100", "프런트 사이드 멤버 ASSY LH", "용접 ASSY 스폿 18점", "FG", "MAKE", "EA", "Y", "4.85", "870829", "Y"),
            new Item("64320-DM100", "프런트 사이드 멤버 ASSY RH", "용접 ASSY 스폿 18점", "FG", "MAKE", "EA", "Y", "4.85", "870829", "Y"),
            new Item("65110-DM200", "플로어 크로스 멤버 ASSY", "용접 ASSY + 너트 4", "FG", "MAKE", "EA", "Y", "3.2", "870829", "Y"),
            new Item("88010-DM300", "시트 프레임 브래킷 ASSY", "CO2 용접", "FG", "MAKE", "EA", "Y", "1.12", "940190", "Y"),
            new Item("64310-DM090", "프런트 사이드 멤버 ASSY LH (구형)", "단종 — 64310-DM100 으로 대체", "FG", "MAKE", "EA", "Y", "4.9", "870829", "N"),
            new Item("64311-DM100", "사이드 멤버 INR", "SPFC590 1.6t 프레스", "SFG", "MAKE", "EA", "Y", "2.1", null, "Y"),
            new Item("64312-DM100", "사이드 멤버 OTR", "SPFC590 1.6t 프레스", "SFG", "MAKE", "EA", "Y", "2.4", null, "Y"),
            new Item("64313-DM100", "사이드 멤버 REINF", "SAPH440 2.3t, 열처리 외주", "SFG", "OUTSRC", "EA", "Y", "0.35", null, "Y"),
            new Item("65111-DM200", "크로스 멤버 UPR", "SGARC340 1.2t 프레스", "SFG", "MAKE", "EA", "Y", "1.5", null, "Y"),
            new Item("RM-SPFC590-1.6", "냉연강판 SPFC590", "1.6t x 1250 코일", "RM", "BUY", "KG", "Y", null, "720917", "Y"),
            new Item("RM-SGARC340-1.2", "합금화 용융아연도금강판 SGARC340", "1.2t x 1100 코일", "RM", "BUY", "KG", "Y", null, "721049", "Y"),
            new Item("RM-SAPH440-2.3", "열연강판 SAPH440", "2.3t x 1219 코일", "RM", "BUY", "KG", "Y", null, "720838", "Y"),
            new Item("NUT-M8-WELD", "용접 너트 M8", "M8x1.25 사각", "SUB", "BUY", "EA", "Y", "0.006", "731816", "Y"),
            new Item("BOLT-M10X25", "플랜지 볼트 M10x25", "8.8T 아연도금", "SUB", "BUY", "EA", "Y", "0.024", "731815", "Y"),
            new Item("WIRE-ER70S-1.2", "CO2 용접 와이어", "ER70S-6 1.2mm 20kg", "CON", "BUY", "KG", "N", null, null, "Y"),
            new Item("TIP-M6-1.2", "콘택트 팁", "M6 1.2mm CuCrZr", "CON", "BUY", "EA", "N", "0.008", null, "Y"));

    static final String[][] CONTACTS = {
            {"품질", "품질팀"}, {"구매", "구매팀"}, {"생산관리", "생산관리팀"}, {"영업", "영업팀"}};

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final NumberingService numbering;
    private final BizClock clock;
    private final WoService workOrders;
    private final LotService lots;
    private final PopWorkService popWork;
    private final CompletionService completion;
    private final ReceiptService receipts;
    private final PolicyService policies;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int n = seed(compCd);
        if (n > 0) {
            log.info("데모 데이터: 회사 {} 에 거래처 {}곳(가상)", compCd, n);
        }
        int items = seedItems(compCd);
        if (items > 0) {
            log.info("데모 데이터: 회사 {} 에 품목 {}개(가상)", compCd, items);
        }
        int boms = seedBoms(compCd);
        if (boms > 0) {
            log.info("데모 데이터: 회사 {} 에 BOM {}건(가상)", compCd, boms);
        }
        int nodes = seedEquipHier(compCd, "P1");
        if (nodes > 0) {
            log.info("데모 데이터: 회사 {} 공장 P1 에 장비 계층·창고 {}개(가상)", compCd, nodes);
        }
        int fgLocs = seedFgLocs(compCd, "P1");
        if (fgLocs > 0) {
            log.info("데모 데이터: 회사 {} 공장 P1 완제품 창고 칸 {}건 — 없던 FG-A01 일반 칸·FG-B01 출하 대기(STAGING) 속성(가상)", compCd, fgLocs);
        }
        int equips = seedEquips(compCd, "P1");
        if (equips > 0) {
            log.info("데모 데이터: 회사 {} 에 설비 {}대·금형·지그·게이지(가상)", compCd, equips);
        }
        int routings = seedRoutings(compCd, "P1");
        if (routings > 0) {
            log.info("데모 데이터: 회사 {} 공장 P1 에 공정·라우팅 {}건(가상)", compCd, routings);
        }
        int chars = seedQuality(compCd);
        if (chars > 0) {
            log.info("데모 데이터: 회사 {} 에 특성 {}개·불량 유형·동의어(가상)", compCd, chars);
        }
        int ext = seedExt(compCd);
        if (ext > 0) {
            log.info("데모 데이터: 회사 {} 에 외부 시스템·코드 매핑·외부 품번 {}건(가상)", compCd, ext);
        }
        int props = seedClassProps(compCd);
        if (props > 0) {
            log.info("데모 데이터: 회사 {} 에 클래스 속성 {}개(가상)", compCd, props);
        }
        int qms = seedQms(compCd);
        if (qms > 0) {
            log.info("데모 데이터: 회사 {} 에 가짜 AQL 표·샘플링 계획·검사계획 {}건(가상)", compCd, qms);
        }
        int calibs = seedQmsGauge(compCd);
        if (calibs > 0) {
            log.info("데모 데이터: 회사 {} 에 계측기 교정 기록 {}건(가상)", compCd, calibs);
        }
        int workers = seedWorkforce(compCd, "P1");
        if (workers > 0) {
            log.info("데모 데이터: 회사 {} 에 근무조·자격·작업자 {}명(가상)", compCd, workers);
        }
        int orders = seedSales(compCd);
        if (orders > 0) {
            log.info("데모 데이터: 회사 {} 에 수주 {}건·내시 2차(가상)", compCd, orders);
        }
        int wos = seedWorkOrders(compCd, "P1");
        if (wos > 0) {
            log.info("데모 데이터: 회사 {} 에 작업지시 {}건(가상, 1건 발행)", compCd, wos);
        }
        int lotCnt = seedLots(compCd);
        if (lotCnt > 0) {
            log.info("데모 데이터: 회사 {} 에 LOT {}개·계보(가상)", compCd, lotCnt);
        }
        int flow = seedMesFlow(compCd, "P1");
        if (flow > 0) {
            log.info("데모 데이터: 회사 {} 에 MES 관통 흐름(실적 {}건 → 완료판정 → 완료 → 입고, ERP_SYSTEM=FAKE)", compCd, flow);
        }
    }

    /**
     * MES 관통 (docs/25 4.11 — 7c): 완료된 지시가 없고 계획 상태 데모 지시(64311-DM100)·작업자·창고가 있는 회사에만.
     * 화면과 같은 서비스 길로 발행 → POP 착수·실적(강판 LOT 투입)·종료(BLANK·DRAW) → 공정 완료판정 → 지시 완료 → 입고.
     * 회사 정책 ERP_SYSTEM 이 비어 있으면 FAKE 로 — 완료·입고가 아웃박스로 가짜 ERP 에 간다. LOT 역추적 보고서에서 바로 볼 LOT 이 생긴다.
     * 넣은 실적 수
     */
    public int seedMesFlow(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer done = jdbc.queryForObject("select count(*) from prd_wo where status in ('DONE', 'CLOSED')", Integer.class);
            List<Long> wo = jdbc.queryForList("""
                    select w.wo_id from prd_wo w join mst_item i on i.item_id = w.item_id
                     where i.item_cd = '64311-DM100' and w.status = 'PLANNED' and w.plant_cd = ? order by w.wo_id limit 1""",
                    Long.class, plant);
            List<Map<String, Object>> worker = jdbc.queryForList("""
                    select worker_id, worker_cd, worker_nm from mst_worker where plant_cd = ? and use_yn = 'Y'
                     order by worker_cd limit 1""", plant);
            List<Long> loc = jdbc.queryForList("""
                    select h.node_id from mst_equip_hier h left join wms_loc_attr a on a.node_id = h.node_id
                     where h.plant_cd = ? and h.node_type in ('WH', 'LOC') and h.use_yn = 'Y'
                       and coalesce(a.loc_usage, 'STORAGE') <> 'STAGING' and coalesce(a.stock_yn, 'Y') = 'Y'
                     order by case h.node_type when 'LOC' then 0 else 1 end, h.node_cd limit 1""", Long.class, plant);
            Integer sup = jdbc.queryForObject("select count(*) from mst_cust where cust_cd = 'P001' and use_yn = 'Y'", Integer.class);
            List<String> defect = jdbc.queryForList("select defect_cd from qms_defect where use_yn = 'Y' order by defect_cd limit 1",
                    String.class);
            if (done == null || done > 0 || wo.isEmpty() || worker.isEmpty() || loc.isEmpty() || sup == null || sup == 0
                    || defect.isEmpty()) {
                return 0;
            }
            Integer erpSet = jdbc.queryForObject("select count(*) from sys_policy where policy_key = ?", Integer.class,
                    ErpPublisher.POLICY);
            if (erpSet != null && erpSet == 0) {
                policies.set(ErpPublisher.POLICY, null, FakeErpAdapter.SYSTEM, SEED_BY);
            }
            long woId = wo.get(0);
            workOrders.releaseAs(woId, SEED_BY);
            Long terminalId = jdbc.queryForObject("""
                    insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, remark, created_by, updated_by)
                    values (?, 'DEMO-POP1', '데모 POP', ?, '데모 — 시더 전용(활성화 안 됨), 관통 흐름 실적용', ?, ?)
                    returning terminal_id""", Long.class, comp, plant, SEED_BY, SEED_BY);
            TerminalPrincipal t = new TerminalPrincipal(comp, terminalId, "DEMO-POP1", "데모 POP", plant, null, null, null, "POP");
            Map<String, Object> wk = worker.get(0);
            long now = System.currentTimeMillis();
            PopWorker w = new PopWorker(terminalId, ((Number) wk.get("worker_id")).longValue(), (String) wk.get("worker_cd"),
                    (String) wk.get("worker_nm"), now, now);
            long coilId = lots.receiveAs("RM-SPFC590-1.6", new BigDecimal("1800"), "P001", "DD-CL-2610-07", SEED_BY);
            String coil = jdbc.queryForObject("select lot_no from lot_master where lot_id = ?", String.class, coilId);
            List<Long> procs = jdbc.queryForList("select wo_proc_id from prd_wo_proc where wo_id = ? order by op_seq", Long.class, woId);
            // 공정별 [양품, 폐기] — 첫 공정 투입 = 처리 합계, 다음 공정 투입 = 앞 공정 유효 양품(보존식 허용 0%)
            String[][] qty = {{"600", "4"}, {"596", "4"}};
            int n = 0;
            for (int i = 0; i < procs.size() && i < qty.length; i++) {
                long proc = procs.get(i);
                popWork.event(t, w, proc, "START", new EventForm(UUID.randomUUID().toString(), null, true));
                List<InputLine> inputs = i == 0 ? List.of(new InputLine(coil, "1560")) : List.of();
                popWork.result(t, w, proc, new ResultForm(UUID.randomUUID().toString(), null, qty[i][0], qty[i][1], null, null,
                        List.of(new DefectLine("SCRAP", defect.get(0), qty[i][1])), inputs, null));
                popWork.event(t, w, proc, "END", new EventForm(UUID.randomUUID().toString(), null, true));
                Integer rv = jdbc.queryForObject("select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, proc);
                completion.closeAs(proc, new CloseForm("데모 — 관통 흐름", rv), SEED_BY);
                n++;
            }
            Integer woRv = jdbc.queryForObject("select row_ver from prd_wo where wo_id = ?", Integer.class, woId);
            completion.completeAs(woId, new ActForm(null, woRv), SEED_BY);
            List<Map<String, Object>> out = jdbc.queryForList("""
                    select r.lot_id, trim_scale(sum(r.good_qty))::text as qty from prd_result r
                     where r.wo_proc_id = ? and r.lot_id is not null group by r.lot_id order by r.lot_id""", procs.get(procs.size() - 1));
            for (Map<String, Object> o : out) {
                receipts.receiveAs(new ReceiveForm(UUID.randomUUID().toString(), woId, ((Number) o.get("lot_id")).longValue(),
                        loc.get(0), (String) o.get("qty")), SEED_BY);
            }
            return n;
        });
    }

    /**
     * LOT 이 없고 발행된 데모 지시(64310-DM100)가 있는 회사에만: 공급사 자재 LOT(용접 너트·와이어) → 생산 LOT 으로 투입,
     * 생산 LOT 하나를 둘로 분할(재포장). LOT 추적 화면에서 정·역추적이 보이게. 넣은 LOT 수
     */
    public int seedLots(String comp) {
        return runner.call(comp, () -> {
            Integer lotN = jdbc.queryForObject("select count(*) from lot_master", Integer.class);
            List<Long> wo = jdbc.queryForList("""
                    select w.wo_id from prd_wo w join mst_item i on i.item_id = w.item_id
                     where i.item_cd = '64310-DM100' and w.status = 'RELEASED' order by w.wo_id limit 1""", Long.class);
            Integer sup = jdbc.queryForObject("select count(*) from mst_cust where cust_cd in ('P003', 'P007') and use_yn = 'Y'",
                    Integer.class);
            if (lotN == null || lotN > 0 || wo.isEmpty() || sup == null || sup < 2) {
                return 0;
            }
            java.time.Instant now = clock.instant();
            long nut = lots.receiveAs("NUT-M8-WELD", new java.math.BigDecimal("10000"), "P003", "IB-2610-118", SEED_BY);
            long wire = lots.receiveAs("WIRE-ER70S-1.2", new java.math.BigDecimal("200"), "P007", "BV-WR-0457", SEED_BY);
            long fg = lots.createProduced(wo.get(0), new java.math.BigDecimal("400"), now, SEED_BY);
            lots.consume(nut, fg, new java.math.BigDecimal("1608"), wo.get(0), null, null, null, "SCAN", now, SEED_BY);
            lots.consume(wire, fg, new java.math.BigDecimal("20"), wo.get(0), null, null, null, "KEY", now, SEED_BY);
            return 3;
        });
    }

    /**
     * 지시가 없고 데모 수주·라우팅이 있는 회사에만: 프런트 사이드 멤버 LH 지시(수주 행 연결, 내일 시작) → 발행(스냅샷),
     * 사이드 멤버 INR 지시(계획 상태). 화면과 같은 검증·채번·발행 길(WoService). 넣은 지시 수
     */
    public int seedWorkOrders(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer wos = jdbc.queryForObject("select count(*) from prd_wo", Integer.class);
            List<Long> line = jdbc.queryForList("""
                    select l.line_id from sal_order_line l join mst_item i on i.item_id = l.item_id
                     where i.item_cd = '64310-DM100' and l.status = 'OPEN' order by l.due_date, l.line_id limit 1""", Long.class);
            Integer routings = jdbc.queryForObject("""
                    select count(*) from mst_routing r join mst_item i on i.item_id = r.item_id
                     where i.item_cd in ('64310-DM100', '64311-DM100') and r.plant_cd = ? and r.status = 'RELEASED'""",
                    Integer.class, plant);
            if (wos == null || wos > 0 || line.isEmpty() || routings == null || routings < 2) {
                return 0;
            }
            String start = clock.today().plusDays(1).toString();
            long w1 = workOrders.createAs(new WoForm(plant, "64310-DM100", "1200", start, clock.today().plusDays(3).toString(),
                    null, null, line.get(0), 3, "데모 — 수주 연결(가상)", null), SEED_BY);
            workOrders.releaseAs(w1, SEED_BY);
            workOrders.createAs(new WoForm(plant, "64311-DM100", "2400", start, start, null, null, null, 5,
                    "데모 — 반제품 선행 지시(가상)", null), SEED_BY);
            return 2;
        });
    }

    /** 데모 수주: 고객, 발주 번호 접미, 수주일(오늘 - 일), 행 {품목, 수량, 납기(오늘 + 일)} */
    record DemoOrder(String cust, String po, int daysAgo, Object[][] lines) {
    }

    /** 데모 내시(S001 주 단위, 다음 주 월요일부터): 버전별 품목 → 주차별 수량(null = 행 없음) */
    static final Integer[][][] FORECAST = {
            {{1200, 1200, 1300, 1300, 1100, 1000}, {1200, 1200, 1300, 1300, 1100, null}},
            {{1200, 1400, 1250, 1300, 900, 0}, {1200, 1400, 1250, 1300, null, 950}}};

    /**
     * 수주가 없고 데모 고객(S001·S002)·완제품이 있는 회사에만: 수주 3건(진행·완료 섞음) + S001 내시 1차·2차
     * (2차에서 늘어난·줄어든·사라진·새로 생긴 수요가 비교 화면에 보이게). 날짜는 회사 오늘 기준. 넣은 수주 수
     */
    public int seedSales(String comp) {
        return runner.call(comp, () -> {
            Integer orders = jdbc.queryForObject("select count(*) from sal_order", Integer.class);
            Integer custs = jdbc.queryForObject(
                    "select count(*) from mst_cust where cust_cd in ('S001', 'S002') and use_yn = 'Y'", Integer.class);
            Integer fgs = jdbc.queryForObject("select count(*) from mst_item where item_cd in "
                    + "('64310-DM100', '64320-DM100', '65110-DM200', '88010-DM300') and use_yn = 'Y'", Integer.class);
            if (orders == null || orders > 0 || custs == null || custs < 2 || fgs == null || fgs < 4) {
                return 0;
            }
            LocalDate today = clock.today();
            String ym = today.format(DateTimeFormatter.ofPattern("yyMM"));
            List<DemoOrder> plan = List.of(
                    new DemoOrder("S001", "GM-PO-" + ym + "-011", 12, new Object[][] {
                        {"64310-DM100", 1200, 18}, {"64320-DM100", 1200, 18}, {"65110-DM200", 800, 25}}),
                    new DemoOrder("S001", "GM-PO-" + ym + "-014", 3, new Object[][] {
                        {"64310-DM100", 600, 32}, {"64320-DM100", 600, 32}}),
                    new DemoOrder("S002", "HG-2026-0457", 20, new Object[][] {
                        {"88010-DM300", 3000, 5}, {"88010-DM300", 2500, 40}}));
            for (DemoOrder o : plan) {
                Long custId = jdbc.queryForObject("select cust_id from mst_cust where cust_cd = ?", Long.class, o.cust());
                String no = numbering.nextOrDefault("SAL_ORDER", "수주 번호", "SO{yyMM}-{seq:4}", "MONTH");
                Long orderId = jdbc.queryForObject("""
                        insert into sal_order (comp_cd, order_no, cust_id, cust_po_no, order_date, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?) returning order_id""", Long.class,
                        comp, no, custId, o.po(), today.minusDays(o.daysAgo()), SEED_BY, SEED_BY);
                int lineNo = 10;
                for (Object[] l : o.lines()) {
                    jdbc.update("""
                            insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                            values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?, ?, ?, ?)""",
                            comp, orderId, lineNo, l[0], l[1], today.plusDays((Integer) l[2]), SEED_BY, SEED_BY);
                    lineNo += 10;
                }
            }
            // S002 첫 행은 납품 끝(마감), 둘째 행은 고객 요청 취소 → 수주 완료
            jdbc.update("""
                    update sal_order_line set status = case when line_no = 10 then 'DONE' else 'CANCELLED' end,
                           cancel_reason = case when line_no = 10 then null else '고객 사양 변경으로 취소(가상)' end,
                           row_ver = row_ver + 1, updated_by = ?
                     where order_id = (select order_id from sal_order where cust_po_no = 'HG-2026-0457')""", SEED_BY);
            jdbc.update("update sal_order set status = 'CLOSED', row_ver = row_ver + 1, updated_by = ? where cust_po_no = 'HG-2026-0457'",
                    SEED_BY);
            Long s001 = jdbc.queryForObject("select cust_id from mst_cust where cust_cd = 'S001'", Long.class);
            LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            String[] items = {"64310-DM100", "64320-DM100"};
            for (int rev = 0; rev < FORECAST.length; rev++) {
                Long revId = jdbc.queryForObject("""
                        insert into sal_forecast_rev (comp_cd, cust_id, rev_no, received_at, source, remark, created_by)
                        values (?, ?, ?, now() - make_interval(days => ?), 'MANUAL', '주간 내시(가상)', ?) returning rev_id""",
                        Long.class, comp, s001, rev + 1, (FORECAST.length - 1 - rev) * 7, SEED_BY);
                for (int i = 0; i < items.length; i++) {
                    for (int w = 0; w < FORECAST[rev][i].length; w++) {
                        if (FORECAST[rev][i][w] == null) {
                            continue;
                        }
                        jdbc.update("""
                                insert into sal_forecast_line (comp_cd, rev_id, item_id, bucket_type, bucket_start, qty, firm_yn)
                                values (?, ?, (select item_id from mst_item where item_cd = ?), 'WEEK', ?, ?, ?)""",
                                comp, revId, items[i], monday.plusWeeks(w), FORECAST[rev][i][w], w < 2 ? "Y" : "N");
                    }
                }
            }
            return plan.size();
        });
    }

    /**
     * 근무조가 없고 데모 공정(SPOT·DRAW)이 있는 회사에만: 주간·야간 2교대, 자격 3개(스폿 용접은 차단 요구), 작업자 6명.
     * 취득일은 회사 시간대 오늘 기준 — 한 명은 용접 자격이 만료돼 게이트 미리보기에서 막히는 것이 보이게. 넣은 작업자 수
     */
    public int seedWorkforce(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer sh = jdbc.queryForObject("select count(*) from mst_shift", Integer.class);
            Integer procs = jdbc.queryForObject("select count(*) from mst_proc where proc_cd in ('SPOT', 'DRAW')", Integer.class);
            Integer pl = jdbc.queryForObject("select count(*) from sys_plant where plant_cd = ?", Integer.class, plant);
            if (sh == null || sh > 0 || procs == null || procs < 2 || pl == null || pl == 0) {
                return 0;
            }
            jdbc.update("""
                    insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, day_offset, break_min, sort_no,
                                           created_by, updated_by)
                    values (?, ?, 'D', '주간', '08:00', '20:00', 0, 60, 10, ?, ?), (?, ?, 'N', '야간', '20:00', '08:00', 0, 60, 20, ?, ?)""",
                    comp, plant, SEED_BY, SEED_BY, comp, plant, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_qual (comp_cd, qual_cd, qual_nm, valid_months, sort_no, created_by, updated_by)
                    values (?, 'WELD-SPOT', '스폿 용접 기능', 24, 10, ?, ?), (?, 'PRESS-SAFE', '프레스 안전교육', 12, 20, ?, ?),
                           (?, 'CRANE', '천장 크레인 운전', null, 30, ?, ?)""",
                    comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_proc_qual_req (comp_cd, proc_id, qual_id, gate_mode, created_by, updated_by)
                    select ?, p.proc_id, q.qual_id, m.mode, ?, ?
                      from (values ('SPOT', 'WELD-SPOT', 'BLOCK'), ('DRAW', 'PRESS-SAFE', 'WARN')) as m(proc_cd, qual_cd, mode)
                      join mst_proc p on p.proc_cd = m.proc_cd join mst_qual q on q.qual_cd = m.qual_cd""", comp, SEED_BY, SEED_BY);
            String[][] ws = {{"E1001", "김용접", "차체1팀"}, {"E1002", "이스폿", "차체1팀"}, {"E1003", "박프레스", "프레스팀"},
                {"E1004", "최프레스", "프레스팀"}, {"E1005", "정반장", "생산관리"}, {"E1006", "한신입", "차체1팀"}};
            for (String[] w : ws) {
                jdbc.update("""
                        insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, dept_nm, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?)""", comp, w[0], w[1], plant, w[2], SEED_BY, SEED_BY);
            }
            // 보유: (사번, 자격, 오늘 - n일 취득, 만료는 유효 개월로) — E1002 용접은 만료, E1006 은 자격 없음
            Object[][] held = {{"E1001", "WELD-SPOT", 200}, {"E1002", "WELD-SPOT", 800}, {"E1003", "PRESS-SAFE", 100},
                {"E1004", "PRESS-SAFE", 30}, {"E1005", "WELD-SPOT", 300}, {"E1005", "PRESS-SAFE", 60}, {"E1005", "CRANE", 900}};
            for (Object[] h : held) {
                jdbc.update("""
                        insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, expires_on, created_by, updated_by)
                        select ?, w.worker_id, q.qual_id, d.a,
                               case when q.valid_months is null then null
                                    else (d.a + make_interval(months => q.valid_months) - interval '1 day')::date end, ?, ?
                          from mst_worker w, mst_qual q,
                               (select (now() at time zone coalesce((select time_zone from sys_comp where comp_cd = ?), 'Asia/Seoul'))::date
                                       - ?::int as a) d
                         where w.worker_cd = ? and q.qual_cd = ?""", comp, SEED_BY, SEED_BY, comp, h[2], h[0], h[1]);
            }
            return ws.length;
        });
    }

    /** 품목 클래스가 없고 데모 원자재·설비 클래스가 있는 회사에만: 코일 클래스(두께·폭·강종) + 프레스 최대 톤수. 넣은 속성 수 */
    public int seedClassProps(String comp) {
        return runner.call(comp, () -> {
            Integer c = jdbc.queryForObject("select count(*) from mst_item_class", Integer.class);
            Integer rm = jdbc.queryForObject("select count(*) from mst_item where item_cd = 'RM-SPFC590-1.6'", Integer.class);
            Integer press = jdbc.queryForObject("select count(*) from mst_equip_class where class_cd = 'PRESS'", Integer.class);
            if (c == null || c > 0 || rm == null || rm == 0 || press == null || press == 0) {
                return 0;
            }
            Long coil = jdbc.queryForObject("""
                    insert into mst_item_class (comp_cd, class_cd, class_nm, sort_no, created_by, updated_by)
                    values (?, 'COIL', '강판 코일', 10, ?, ?) returning class_id""", Long.class, comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_item_class (comp_cd, class_cd, class_nm, sort_no, created_by, updated_by)
                    values (?, 'FASTENER', '체결 부품', 20, ?, ?)""", comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, unit_nm, scale, options,
                                                required_yn, sort_no, created_by, updated_by)
                    values (?, 'ITEM', ?, 'THICKNESS', '두께', 'NUM', 'mm', 2, null, 'Y', 10, ?, ?),
                           (?, 'ITEM', ?, 'WIDTH', '폭', 'NUM', 'mm', 0, null, 'N', 20, ?, ?),
                           (?, 'ITEM', ?, 'GRADE', '강종', 'LIST', null, null, '["SPFC590","SAPH440","SGARC340"]'::jsonb, 'Y', 30, ?, ?),
                           (?, 'ITEM', ?, 'MILL_CERT', '밀시트 필수', 'BOOL', null, null, null, 'N', 40, ?, ?)""",
                    comp, coil, SEED_BY, SEED_BY, comp, coil, SEED_BY, SEED_BY, comp, coil, SEED_BY, SEED_BY, comp, coil, SEED_BY,
                    SEED_BY);
            for (String[] x : new String[][] {{"RM-SPFC590-1.6", "1.6", "1250", "SPFC590"}, {"RM-SAPH440-2.3", "2.3", "1219", "SAPH440"},
                {"RM-SGARC340-1.2", "1.2", "1100", "SGARC340"}}) {
                jdbc.update("""
                        update mst_item set item_class_id = ?, attrs = jsonb_build_object('THICKNESS', ?::numeric, 'WIDTH', ?::numeric,
                               'GRADE', ?::text, 'MILL_CERT', true)
                         where item_cd = ?""", coil, x[1], x[2], x[3], x[0]);
            }
            Long pressId = jdbc.queryForObject("select class_id from mst_equip_class where class_cd = 'PRESS'", Long.class);
            jdbc.update("""
                    insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, unit_nm, scale, required_yn,
                                                sort_no, created_by, updated_by)
                    values (?, 'EQUIP', ?, 'MAX_TON', '최대 톤수', 'NUM', 'ton', 0, 'Y', 10, ?, ?),
                           (?, 'EQUIP', ?, 'BED_SIZE', '베드 크기', 'TEXT', 'mm', null, 'N', 20, ?, ?)""",
                    comp, pressId, SEED_BY, SEED_BY, comp, pressId, SEED_BY, SEED_BY);
            jdbc.update("update mst_equip set attrs = '{\"MAX_TON\": 1200, \"BED_SIZE\": \"4500 x 2000\"}' where equip_cd = 'PR-1200'");
            return 6;
        });
    }

    /** 외부 시스템이 없고 데모 품목이 있는 회사에만: 가상 ERP 1 + 코드 매핑 + 품목 외부 코드. 넣은 외부 품번 수 */
    public int seedExt(String comp) {
        return runner.call(comp, () -> {
            Integer s = jdbc.queryForObject("select count(*) from mst_ext_system", Integer.class);
            Integer i = jdbc.queryForObject("select count(*) from mst_item where item_cd = '64311-DM100'", Integer.class);
            if (s == null || s > 0 || i == null || i == 0) {
                return 0;
            }
            Long sys = jdbc.queryForObject("""
                    insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, remark, created_by, updated_by)
                    values (?, 'ERP-DEMO', '가상 ERP', 'ERP', '데모 — 실제 ERP 아님', ?, ?) returning system_id""",
                    Long.class, comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into if_code_map (comp_cd, system_id, code_type, ext_code, int_code, created_by, updated_by)
                    values (?, ?, 'UOM', '개', 'EA', ?, ?), (?, ?, 'UOM', 'kg', 'KG', ?, ?),
                           (?, ?, 'ITEM_TYPE', '제품', 'FG', ?, ?), (?, ?, 'ITEM_TYPE', '원재료', 'RM', ?, ?)""",
                    comp, sys, SEED_BY, SEED_BY, comp, sys, SEED_BY, SEED_BY, comp, sys, SEED_BY, SEED_BY, comp, sys, SEED_BY,
                    SEED_BY);
            return jdbc.update("""
                    insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, external_nm,
                                                created_by, updated_by)
                    select ?, ?, 'ITEM', item_id, 'E' || lpad(row_number() over (order by item_cd)::text, 5, '0'), item_nm, ?, ?
                      from mst_item where item_type in ('FG', 'SFG')""", comp, sys, SEED_BY, SEED_BY);
        });
    }

    /** 불량 유형: 상위, 코드, 이름 (차체 부품 공장 가상) */
    static final String[][] DEFECTS = {
            {"APPEARANCE", "SCRATCH", "긁힘"}, {"APPEARANCE", "DENT", "찍힘"}, {"APPEARANCE", "BURR", "버(Burr)"},
            {"DIMENSION", "HOLE-POS", "홀 위치 불량"}, {"DIMENSION", "FLANGE", "플랜지 치수 불량"},
            {"MATERIAL", "CRACK", "크랙(터짐)"}, {"MATERIAL", "WRINKLE", "주름"},
            {"WELD", "SPOT-MISS", "스폿 누락"}, {"WELD", "SPATTER", "스패터"}, {"WELD", "WELD-STR", "용접 강도 미달"},
            {"ASSEMBLY", "NUT-MISS", "너트 누락"}, {"LABEL", "LABEL-ERR", "라벨 오표기"}};

    /** 특성: 유형, 품목(제품) 또는 공정 코드, 번호, 이름, 데이터 유형, 단위, 자릿수, 하한, 공칭, 상한, 특별특성 */
    static final String[][] CHARS = {
            {"PRODUCT", "64311-DM100", "1", "홀 지름 A", "VARIABLE", "mm", "2", "9.95", "10.00", "10.05", "SC"},
            {"PRODUCT", "64311-DM100", "2", "홀 위치 X", "VARIABLE", "mm", "2", "-0.30", "0.00", "0.30", "NONE"},
            {"PRODUCT", "64311-DM100", "3", "플랜지 높이", "VARIABLE", "mm", "1", "24.5", "25.0", "25.5", "NONE"},
            {"PRODUCT", "64311-DM100", "4", "크랙·주름 없음", "ATTRIBUTE", null, "0", null, null, null, "SC"},
            {"PRODUCT", "64310-DM100", "1", "용접 너트 인발 강도", "VARIABLE", "kN", "1", "4.0", null, null, "CC"},
            {"PRODUCT", "64310-DM100", "2", "스폿 개수", "ATTRIBUTE", null, "0", null, null, null, "SC"},
            {"PROCESS", "SPOT", "1", "용접 전류", "VARIABLE", "kA", "1", "8.5", "9.0", "9.5", "NONE"},
            {"PROCESS", "SPOT", "2", "가압력", "VARIABLE", "kN", "1", "2.9", "3.2", "3.5", "NONE"}};

    /** 불량 유형이 없고 공정·데모 품목이 있는 회사에만. 특성·동의어도 함께. 넣은 특성 수 */
    public int seedQuality(String comp) {
        return runner.call(comp, () -> {
            Integer d = jdbc.queryForObject("select count(*) from qms_defect", Integer.class);
            Integer p = jdbc.queryForObject("select count(*) from mst_proc where proc_cd = 'SPOT'", Integer.class);
            Integer i = jdbc.queryForObject("select count(*) from mst_item where item_cd = '64311-DM100'", Integer.class);
            if (d == null || d > 0 || p == null || p == 0 || i == null || i == 0) {
                return 0;
            }
            for (int k = 0; k < DEFECTS.length; k++) {
                jdbc.update("""
                        insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, sort_no, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?)""", comp, DEFECTS[k][1], DEFECTS[k][2], DEFECTS[k][0], (k + 1) * 10, SEED_BY,
                        SEED_BY);
            }
            for (String[] c : CHARS) {
                boolean product = "PRODUCT".equals(c[0]);
                jdbc.update("""
                        insert into qms_char (comp_cd, char_type, item_id, proc_id, char_no, char_nm, data_type, unit_nm, scale,
                                              lsl, nominal, usl, special_cls, source, drawing_no, drawing_rev, sort_no,
                                              created_by, updated_by)
                        values (?, ?, (select item_id from mst_item where item_cd = ?), (select proc_id from mst_proc where proc_cd = ?),
                                ?, ?, ?, ?, ?::smallint, ?::numeric, ?::numeric, ?::numeric, ?, ?, ?, ?, ?::int, ?, ?)""",
                        comp, c[0], product ? c[1] : null, product ? ("64310-DM100".equals(c[1]) ? "SPOT" : "DRAW") : c[1], c[2],
                        c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], product ? "DRAWING" : "SPEC",
                        product ? c[1] + "-DWG" : null, product ? "B" : null, Integer.parseInt(c[2]) * 10, SEED_BY, SEED_BY);
            }
            jdbc.update("""
                    insert into mst_term_alias (comp_cd, term_cd, alias, lang, created_by, updated_by)
                    values (?, 'DEFECT_RATE', 'B급률', 'ko', ?, ?), (?, 'SCRAP_QTY', '폐기량', 'ko', ?, ?),
                           (?, 'CYCLE_TIME', '택트', 'ko', ?, ?), (?, 'DEFECT_RATE', 'tỷ lệ lỗi', 'vi', ?, ?)""",
                    comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY);
            return CHARS.length;
        });
    }

    /**
     * 샘플링 계획이 없고 데모 특성·코일 클래스가 있는 회사에만 (P3 QMS Q1, docs/29). 넣은 검사계획 수.
     * AQL 표는 **손으로 만든 가짜 표 DEMO-FAKE**(문자 A~D, 수치도 표준과 다름) — 표준 원문을 시드로 넣지 않는다(D3).
     * 검사계획 3건: 드로잉 공정검사(확정 → 그 뒤 특성 "홀 위치 X" 규격을 바꿔 "새 리비전 필요" 배지가 보이게), 완성품 출하검사(작성 중),
     * 코일 클래스 수입검사(검사 공정 IQC 의 공정 특성, 확정 — 계획에는 공정을 두지 않는다: WMS 입고 검사는 공정 없이 계획을 찾는다, B3).
     */
    public int seedQms(String comp) {
        return runner.call(comp, () -> {
            Integer s = jdbc.queryForObject("select count(*) from qms_sampling_plan", Integer.class);
            Integer c = jdbc.queryForObject("""
                    select count(*) from qms_char ch join mst_item i on i.item_id = ch.item_id where i.item_cd = '64311-DM100'""",
                    Integer.class);
            Integer coil = jdbc.queryForObject("select count(*) from mst_item_class where class_cd = 'COIL'", Integer.class);
            Integer draw = jdbc.queryForObject("select count(*) from mst_proc where proc_cd = 'DRAW'", Integer.class);
            if (s == null || s > 0 || c == null || c < 4 || coil == null || coil == 0 || draw == null || draw == 0) {
                return 0;
            }
            for (Object[] l : new Object[][] {{"A", 1L, 50L}, {"B", 51L, 500L}, {"C", 501L, 5000L}, {"D", 5001L, null}}) {
                jdbc.update("""
                        insert into qms_aql_table (comp_cd, std_cd, insp_level, lot_from, lot_to, code_letter, created_by, updated_by)
                        values (?, 'DEMO-FAKE', 'II', ?, ?, ?, ?, ?)""", comp, l[1], l[2], l[0], SEED_BY, SEED_BY);
            }
            for (Object[] r : new Object[][] {{"A", 4, 0}, {"B", 16, 0}, {"C", 40, 1}, {"D", 100, 2}}) {
                jdbc.update("""
                        insert into qms_aql_plan_row (comp_cd, std_cd, severity, code_letter, aql, sample_size, ac, re, created_by, updated_by)
                        values (?, 'DEMO-FAKE', 'NORMAL', ?, 1.0, ?, ?, ?, ?, ?)""", comp, r[0], r[1], r[2], (int) r[2] + 1, SEED_BY, SEED_BY);
            }
            jdbc.update("""
                    insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, pct, std_cd, insp_level, severity, aql, ac, re,
                                                   sort_no, remark, created_by, updated_by)
                    values (?, 'S-ALL', '전수 검사', 'ALL', null, null, null, null, null, null, 0, 1, 10, null, ?, ?),
                           (?, 'S-5', '고정 5개', 'FIXED', 5, null, null, null, null, null, 0, 1, 20, null, ?, ?),
                           (?, 'S-PCT10', '로트의 10%', 'PERCENT', null, 10, null, null, null, null, 0, 1, 30, null, ?, ?),
                           (?, 'S-AQL1', 'AQL 1.0 보통 II (가짜 표)', 'AQL', null, null, 'DEMO-FAKE', 'II', 'NORMAL', 1.0, null, null, 40,
                            '데모용 가짜 AQL 표 — 실제 운영은 회사가 표준을 보고 올린 표로', ?, ?)""",
                    comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, sort_no, created_by, updated_by)
                    select ?, 'IQC', '수입 검사', 'INSPECT', 5, ?, ? where not exists (select 1 from mst_proc where proc_cd = 'IQC')""",
                    comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, scale, special_cls, source, sort_no,
                                          created_by, updated_by)
                    select ?, 'PROCESS', proc_id, v.no, v.nm, 'ATTRIBUTE', 0, 'NONE', 'SPEC', v.sort, ?, ?
                      from mst_proc, (values ('1', '외관(녹·긁힘·찍힘 없음)', 10), ('2', '밀시트 강종·두께 일치', 20)) v(no, nm, sort)
                     where proc_cd = 'IQC'""", comp, SEED_BY, SEED_BY);
            String today = "(now() at time zone 'Asia/Seoul')::date";
            // 드로잉 계획은 60일 전부터 유효 — QMS 데모 검사(QmsDemoSeeder)가 지난 30일 날짜로 이 계획을 찾는다
            Long draw1 = jdbc.queryForObject("""
                    insert into qms_insp_plan (comp_cd, insp_type, item_id, proc_id, rev, valid_from, change_reason, created_by, updated_by)
                    select ?, 'PROCESS', i.item_id, p.proc_id, 'A0', """ + today + """
                     - 60
                    , '최초 등록(데모)', ?, ?
                      from mst_item i, mst_proc p where i.item_cd = '64311-DM100' and p.proc_cd = 'DRAW' returning insp_plan_id""",
                    Long.class, comp, SEED_BY, SEED_BY);
            planLines(comp, draw1, "64311-DM100", null, new String[][] {
                    {"1", "GO_NOGO", "S-5", "EVERY_LOT", "핀 게이지 GO/NO-GO", "전수 선별 후 금형 점검"},
                    {"2", "CMM", "S-5", "START", "3차원 측정기 — 기준 홀 기준", "초품 재측정, 금형 위치 보정"},
                    {"3", "HEIGHT", "S-AQL1", "EVERY_LOT", "하이트 게이지 3점", "로트 보류 후 재검사"},
                    {"4", "VISUAL", "S-ALL", "EVERY_LOT", "육안(조명 1000lx 이상)", "격리 · 불량 유형 크랙/주름 등록"}});
            release(draw1);
            Long fin = jdbc.queryForObject("""
                    insert into qms_insp_plan (comp_cd, insp_type, item_id, rev, valid_from, change_reason, created_by, updated_by)
                    select ?, 'FINAL', item_id, 'A0', """ + today + """
                    , '작성 중(데모)', ?, ? from mst_item where item_cd = '64310-DM100' returning insp_plan_id""",
                    Long.class, comp, SEED_BY, SEED_BY);
            planLines(comp, fin, "64310-DM100", null, new String[][] {
                    {"1", "TENSILE", "S-5", "EVERY_LOT", "너트 인발 시험", "로트 보류 · 용접 조건 점검"},
                    {"2", "VISUAL", "S-ALL", "EVERY_LOT", "스폿 개수 확인 지그", "누락 개소 재용접"}});
            Long rcv = jdbc.queryForObject("""
                    insert into qms_insp_plan (comp_cd, insp_type, item_class_id, rev, valid_from, change_reason, created_by, updated_by)
                    select ?, 'RECEIVE', c.class_id, 'A0', """ + today + """
                    , '코일 공통 수입검사(데모)', ?, ?
                      from mst_item_class c where c.class_cd = 'COIL' returning insp_plan_id""",
                    Long.class, comp, SEED_BY, SEED_BY);
            planLines(comp, rcv, null, "IQC", new String[][] {
                    {"1", "VISUAL", "S-AQL1", "EVERY_LOT", "코일 바깥 감김 육안", "협력사 반품 · 시정조치 요구"},
                    {"2", "VISUAL", "S-ALL", "EVERY_LOT", "밀시트와 라벨 대조", "입고 보류"}});
            release(rcv);
            // 확정 뒤 도면 개정으로 특성 규격이 바뀐 상황 — 확정본 행은 그대로, 화면에 "특성 규격과 다름 — 새 리비전 필요"
            jdbc.update("""
                    update qms_char set lsl = -0.25, usl = 0.25, drawing_rev = 'C', updated_by = ?, updated_at = now()
                     where char_no = '2' and item_id = (select item_id from mst_item where item_cd = '64311-DM100')""", SEED_BY);
            return 3;
        });
    }

    /** 검사계획 행: 특성 번호(품목 특성이면 품목 코드, 아니면 공정 특성), 계측기, 샘플링, 주기, 방법, 이탈 시 조치 */
    private void planLines(String comp, long planId, String itemCd, String procCd, String[][] rows) {
        int no = 10;
        for (String[] r : rows) {
            jdbc.update("""
                    insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, freq_type,
                                                    method_nm, reaction_plan, created_by, updated_by)
                    select ?, ?, ?, ch.char_id, ?, s.sampling_plan_id, ?, ?, ?, ?, ?
                      from qms_char ch, qms_sampling_plan s
                     where ch.char_no = ? and s.plan_cd = ?
                       and ((cast(? as varchar) is not null and ch.item_id = (select item_id from mst_item where item_cd = ?))
                            or (cast(? as varchar) is not null and ch.item_id is null
                                and ch.proc_id = (select proc_id from mst_proc where proc_cd = ?)))""",
                    comp, planId, no, r[1], r[3], r[4], r[5], SEED_BY, SEED_BY, r[0], r[2], itemCd, itemCd, procCd, procCd);
            no += 10;
        }
    }

    /**
     * 교정 기록이 하나도 없고 데모 게이지 GG-CAL-01 이 있는 회사에만 (P3 QMS Q2a, docs/29). 넣은 기록 수.
     * 400일 전(주기 365, 그 뒤 기록에 덮임)·300일 전 사내 합격 2건 → 다음 교정일 65일 뒤(사용 가능), 클래스 CALIPER.
     * 최근 교정일은 교정 트리거가 300일 전으로 맞춘다
     */
    public int seedQmsGauge(String comp) {
        return runner.call(comp, () -> {
            Integer n = jdbc.queryForObject("select count(*) from qms_gauge_calib", Integer.class);
            Long gauge = jdbc.query("select asset_id from mst_phys_asset where asset_cd = 'GG-CAL-01' and asset_type = 'GAUGE' and use_yn = 'Y'",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (n == null || n > 0 || gauge == null) {
                return 0;
            }
            jdbc.update("update mst_phys_asset set gauge_class = 'CALIPER', updated_by = ?, updated_at = now() where asset_id = ?",
                    SEED_BY, gauge);
            for (int[] d : new int[][] {{400, 1}, {300, 2}}) {
                jdbc.update("""
                        insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cert_no, cycle_days, created_by)
                        values (?, ?, (now() at time zone 'Asia/Seoul')::date - ?, 'OK', 'INTERNAL', ?, 365, ?)""",
                        comp, gauge, d[0], "CAL-DEMO-" + d[1], SEED_BY);
            }
            return 2;
        });
    }

    private void release(long planId) {
        jdbc.update("""
                update qms_insp_plan set status = 'RELEASED', released_at = now(), released_by = ?, row_ver = row_ver + 1,
                                         updated_by = ? where insp_plan_id = ?""", SEED_BY, SEED_BY, planId);
    }

    /** 공정: 코드, 이름, 분류, 외주 (가상) */
    static final String[][] PROCS = {
            {"BLANK", "블랭킹", "PRESS", "N"},
            {"DRAW", "드로잉·트림", "PRESS", "N"},
            {"SPOT", "스폿 용접", "WELD", "N"},
            {"CO2", "CO2 용접", "WELD", "N"},
            {"EDP", "전착 도장(외주)", "PAINT", "Y"},
            {"INSP", "최종 검사", "INSPECT", "N"}};

    /** 라우팅 단계: 품목, 확정 여부, 단계[순번, 공정, 작업장 노드(null = 외주), 사이클타임 초, 캐비티, 준비 분] */
    record Route(String item, boolean released, String[][] steps) {
    }

    static final List<Route> ROUTES = List.of(
            new Route("64311-DM100", true, new String[][] {{"10", "BLANK", "PR-L1", "6", "2", "30"},
                    {"20", "DRAW", "PR-L1", "8", "1", "30"}}),
            new Route("64310-DM100", true, new String[][] {{"10", "SPOT", "WD-L1-C1", "45", "1", "10"},
                    {"20", "SPOT", "WD-L1-C2", "40", "1", "10"}, {"30", "EDP", null, null, "1", "0"},
                    {"40", "INSP", "WD-L1", "20", "1", "0"}}),
            new Route("65110-DM200", false, new String[][] {{"10", "CO2", "WD-L2-C1", "60", "1", "15"}}));

    /** 공정이 없고 장비 계층·데모 품목이 있는 회사에만. 스폿 단계에 용접 조건(전류·통전 시간). 넣은 라우팅 수 */
    public int seedRoutings(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer procs = jdbc.queryForObject("select count(*) from mst_proc", Integer.class);
            Integer nodes = jdbc.queryForObject("select count(*) from mst_equip_hier where plant_cd = ? and node_cd = 'WD-L1-C1'",
                    Integer.class, plant);
            Integer items = jdbc.queryForObject("select count(*) from mst_item where item_cd = ?", Integer.class,
                    ROUTES.get(0).item());
            if (procs == null || procs > 0 || nodes == null || nodes == 0 || items == null || items == 0) {
                return 0;
            }
            for (int i = 0; i < PROCS.length; i++) {
                String[] x = PROCS[i];
                jdbc.update("""
                        insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, sort_no, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""", comp, x[0], x[1], x[2], x[3], (i + 1) * 10, SEED_BY, SEED_BY);
            }
            for (Route rt : ROUTES) {
                Long rid = jdbc.queryForObject("""
                        insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, change_reason, created_by, updated_by)
                        select ?, item_id, ?, 'A0', (now() at time zone 'Asia/Seoul')::date, '최초 등록(데모)', ?, ?
                          from mst_item where item_cd = ? returning routing_id""", Long.class, comp, plant, SEED_BY, SEED_BY,
                        rt.item());
                for (String[] st : rt.steps()) {
                    Long sid = jdbc.queryForObject("""
                            insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, units_per_cycle,
                                                          setup_min, created_by, updated_by)
                            values (?, ?, ?::int, (select proc_id from mst_proc where proc_cd = ?),
                                    (select node_id from mst_equip_hier where plant_cd = ? and node_cd = ?), ?::numeric, ?::int,
                                    ?::numeric, ?, ?) returning step_id""", Long.class, comp, rid, st[0], st[1], plant, st[2],
                            st[3], st[4], st[5], SEED_BY, SEED_BY);
                    if ("SPOT".equals(st[1])) {
                        jdbc.update("""
                                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, unit_nm, target_val, lsl, usl,
                                                               sort_no, created_by, updated_by)
                                values (?, ?, 'WELD_CURRENT', '용접 전류', 'kA', 9.0, 8.5, 9.5, 10, ?, ?),
                                       (?, ?, 'WELD_TIME', '통전 시간', 'ms', 300, 280, 320, 20, ?, ?),
                                       (?, ?, 'TIP_FORCE', '가압력', 'kN', 3.2, 2.9, 3.5, 30, ?, ?)""",
                                comp, sid, SEED_BY, SEED_BY, comp, sid, SEED_BY, SEED_BY, comp, sid, SEED_BY, SEED_BY);
                    }
                }
                if (rt.released()) {
                    jdbc.update("""
                            update mst_routing set status = 'RELEASED', released_at = now(), released_by = ?, row_ver = row_ver + 1
                             where routing_id = ?""", SEED_BY, rid);
                }
            }
            return ROUTES.size();
        });
    }

    /** 설비: 코드, 이름, 클래스, 위치 노드 코드(null = 보관), 제조사 (가상) */
    static final String[][] EQUIPS = {
            {"PR-1200", "1200톤 서보 프레스", "PRESS", "PR-L1", "가상프레스"},
            {"SPOT-101", "스폿 용접 로봇 1", "WELD", "WD-L1-C1", "가상로봇"},
            {"SPOT-102", "스폿 용접 로봇 2", "WELD", "WD-L1-C2", "가상로봇"},
            {"CO2-201", "CO2 용접 로봇", "WELD", "WD-L2-C1", "가상로봇"},
            {"SPOT-SPARE", "예비 스폿 건", "WELD", null, "가상로봇"}};

    /** 설비가 없고 장비 계층이 있는 회사에만. 금형 1개는 프레스에 설치(이력) */
    public int seedEquips(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer e = jdbc.queryForObject("select count(*) from mst_equip", Integer.class);
            Integer n = jdbc.queryForObject("select count(*) from mst_equip_hier where plant_cd = ?", Integer.class, plant);
            if (e == null || e > 0 || n == null || n == 0) {
                return 0;
            }
            jdbc.update("""
                    insert into mst_equip_class (comp_cd, class_cd, class_nm, sort_no, created_by, updated_by)
                    values (?, 'PRESS', '프레스', 1, ?, ?), (?, 'WELD', '용접기', 2, ?, ?)""", comp, SEED_BY, SEED_BY, comp, SEED_BY,
                    SEED_BY);
            for (String[] x : EQUIPS) {
                jdbc.update("""
                        insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, maker, created_by, updated_by)
                        values (?, ?, ?, ?, (select class_id from mst_equip_class where class_cd = ?),
                                (select node_id from mst_equip_hier where plant_cd = ? and node_cd = ?), ?, ?, ?)""",
                        comp, plant, x[0], x[1], x[2], plant, x[3], x[4], SEED_BY, SEED_BY);
            }
            jdbc.update("""
                    insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, item_id, life_shots, created_by, updated_by)
                    select ?, 'MD-64311', 'MOLD', '사이드 멤버 INR 프레스 금형', item_id, 500000, ?, ? from mst_item where item_cd = '64311-DM100'
                    union all
                    select ?, 'MD-64312', 'MOLD', '사이드 멤버 OTR 프레스 금형', item_id, 500000, ?, ? from mst_item where item_cd = '64312-DM100'""",
                    comp, SEED_BY, SEED_BY, comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, created_by, updated_by)
                    values (?, 'JG-WD-L1', 'JIG', '사이드 멤버 용접 지그', ?, ?)""", comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, calib_cycle_days, last_calib_date, created_by, updated_by)
                    values (?, 'GG-CAL-01', 'GAUGE', '디지털 버니어 캘리퍼스', 365,
                            (now() at time zone 'Asia/Seoul')::date - 300, ?, ?)""", comp, SEED_BY, SEED_BY);
            jdbc.update("""
                    insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by)
                    select ?, a.asset_id, e.equip_id, now() - interval '3 days', ?
                      from mst_phys_asset a, mst_equip e where a.asset_cd = 'MD-64311' and e.equip_cd = 'PR-1200'""", comp, SEED_BY);
            return EQUIPS.length;
        });
    }

    /** 유형, 코드, 이름, 상위 코드(null = 공장 바로 아래) — 차체 공장: 프레스·차체 구역, 원자재·완제품 창고(가상) */
    static final String[][] NODES = {
            {"AREA", "PRESS", "프레스동", null},
            {"LINE", "PR-L1", "1200톤 탠덤 라인", "PRESS"},
            {"AREA", "BODY", "차체동", null},
            {"LINE", "WD-L1", "사이드 멤버 용접 1라인", "BODY"},
            {"CELL", "WD-L1-C1", "스폿 셀 1", "WD-L1"},
            {"CELL", "WD-L1-C2", "스폿 셀 2", "WD-L1"},
            {"LINE", "WD-L2", "크로스 멤버 용접 2라인", "BODY"},
            {"CELL", "WD-L2-C1", "CO2 셀 1", "WD-L2"},
            {"WH", "BODY-LS", "차체 라인사이드", "BODY"},
            {"WH", "RM-WH", "원자재 창고", null},
            {"LOC", "RM-A01", "코일 랙 A01", "RM-WH"},
            {"LOC", "RM-A01-01", "A01 1단", "RM-A01"},
            {"LOC", "RM-A01-02", "A01 2단", "RM-A01"},
            {"WH", "FG-WH", "완제품 창고", null},
            {"LOC", "FG-A01", "완제품 랙 A01", "FG-WH"},
            {"LOC", "FG-B01", "출하 대기 B01", "FG-WH"}};

    /** 그 공장에 노드가 없을 때만. 공장이 없으면 0 */
    public int seedEquipHier(String comp, String plant) {
        return runner.call(comp, () -> {
            Integer p = jdbc.queryForObject("select count(*) from sys_plant where plant_cd = ?", Integer.class, plant);
            Integer n = jdbc.queryForObject("select count(*) from mst_equip_hier where plant_cd = ?", Integer.class, plant);
            if (p == null || p == 0 || n == null || n > 0) {
                return 0;
            }
            for (String[] x : NODES) {
                jdbc.update("""
                        insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                        values (?, ?, (select node_id from mst_equip_hier where plant_cd = ? and node_cd = ?), ?, ?, ?, ?, ?)""",
                        comp, plant, plant, x[3], x[0], x[1], x[2], SEED_BY, SEED_BY);
            }
            return NODES.length;
        });
    }

    /**
     * 완제품 창고 칸 용도 — FG-B01 은 이름대로 출하 대기(STAGING) 칸(피킹 목적지, 생산 투입·입고 제외), 생산 입고는 일반 칸 FG-A01.
     * 계층을 먼저 넣은 기존 회사도 다시 기동하면 맞춰지게 seedEquipHier 와 따로 둔다: FG-A01 은 FG-WH 가 있고 없을 때만,
     * FG-B01 속성은 칸이 있고 속성 행이 없고 재고가 없을 때만(사용자가 바꾼 속성·쌓인 재고는 건드리지 않는다 — DS-1). 바꾼 건수
     */
    public int seedFgLocs(String comp, String plant) {
        return runner.call(comp, () -> {
            int n = jdbc.update("""
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    select ?, ?, w.node_id, 'LOC', 'FG-A01', '완제품 랙 A01', ?, ?
                      from mst_equip_hier w
                     where w.plant_cd = ? and w.node_cd = 'FG-WH' and w.node_type = 'WH' and w.use_yn = 'Y'
                       and not exists (select 1 from mst_equip_hier x where x.plant_cd = ? and x.node_cd = 'FG-A01')""",
                    comp, plant, SEED_BY, SEED_BY, plant, plant);
            // 재고가 있는 칸은 용도를 바꾸지 않는다 — 시드는 사용자 데이터를 건드리지 않는다(DS-1). 재고는 상태와 무관하게 센다
            List<BigDecimal> held = jdbc.queryForList("""
                    select coalesce(sum(s.qty), 0) from mst_equip_hier h join inv_stock s on s.node_id = h.node_id
                     where h.plant_cd = ? and h.node_cd = 'FG-B01'
                       and not exists (select 1 from wms_loc_attr a where a.node_id = h.node_id)""",
                    BigDecimal.class, plant);
            if (!held.isEmpty() && held.get(0).signum() > 0) {
                log.info("데모 데이터: 회사 {} 공장 {} FG-B01 에 재고 {} 이 있어 출하 대기(STAGING) 속성을 넣지 않음 — 옮긴 뒤 다시 기동하면 넣는다",
                        comp, plant, held.get(0).stripTrailingZeros().toPlainString());
                return n;
            }
            n += jdbc.update("""
                    insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by)
                    select h.node_id, ?, 'STAGING', ?, ?
                      from mst_equip_hier h
                     where h.plant_cd = ? and h.node_cd = 'FG-B01' and h.node_type = 'LOC' and h.use_yn = 'Y'
                       and not exists (select 1 from wms_loc_attr a where a.node_id = h.node_id)""",
                    comp, SEED_BY, SEED_BY, plant);
            return n;
        });
    }

    /** 품목이 없는 회사에만 넣고 넣은 품목 수를 돌려준다. 회사가 없으면 0. */
    public int seedItems(String comp) {
        return runner.call(comp, () -> {
            Integer exists = jdbc.queryForObject("select count(*) from sys_comp where comp_cd = ?", Integer.class, comp);
            Integer items = jdbc.queryForObject("select count(*) from mst_item", Integer.class);
            if (exists == null || exists == 0 || items == null || items > 0) {
                return 0;
            }
            for (Item it : ITEMS) {
                jdbc.update("""
                        insert into mst_item (comp_cd, item_cd, item_nm, spec, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                              net_mass_kg, hs_cd, use_yn, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, ?)""",
                        comp, it.cd(), it.nm(), it.spec(), it.type(), it.proc(), it.unit(), it.lot(), it.mass(), it.hs(),
                        it.useYn(), SEED_BY, SEED_BY);
            }
            return ITEMS.size();
        });
    }

    /** 모품목, 확정 여부, 행[자품목, 소요량, 손실률] — 프레스품 ← 강판, ASSY ← 프레스품·너트·와이어 (가상 구성) */
    record Bom(String parent, boolean released, String[][] lines) {
    }

    static final List<Bom> BOMS = List.of(
            new Bom("64311-DM100", true, new String[][] {{"RM-SPFC590-1.6", "2.6", "3"}}),
            new Bom("64312-DM100", true, new String[][] {{"RM-SPFC590-1.6", "2.9", "3"}}),
            new Bom("64313-DM100", true, new String[][] {{"RM-SAPH440-2.3", "0.45", "5"}}),
            new Bom("64310-DM100", true, new String[][] {{"64311-DM100", "1", "0"}, {"64312-DM100", "1", "0"},
                    {"64313-DM100", "2", "0"}, {"NUT-M8-WELD", "4", "0.5"}, {"WIRE-ER70S-1.2", "0.05", "0"}}),
            new Bom("65111-DM200", true, new String[][] {{"RM-SGARC340-1.2", "1.8", "3"}}),
            new Bom("65110-DM200", false, new String[][] {{"65111-DM200", "1", "0"}, {"NUT-M8-WELD", "4", "0.5"},
                    {"BOLT-M10X25", "2", "0"}}));

    /** BOM 이 없고 데모 품목이 있는 회사에만. 강판 코일 환산(1 ROLL = 8,500 KG) 도 함께. 넣은 BOM 수 */
    public int seedBoms(String comp) {
        return runner.call(comp, () -> {
            Integer boms = jdbc.queryForObject("select count(*) from mst_bom", Integer.class);
            Integer items = jdbc.queryForObject("select count(*) from mst_item where item_cd = ?", Integer.class,
                    BOMS.get(0).parent());
            if (boms == null || boms > 0 || items == null || items == 0) {
                return 0;
            }
            jdbc.update("""
                    insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by)
                    select ?, item_id, 'ROLL', unit_cd, 8500, ?, ? from mst_item where item_cd = 'RM-SPFC590-1.6'""",
                    comp, SEED_BY, SEED_BY);
            for (Bom b : BOMS) {
                Long bomId = jdbc.queryForObject("""
                        insert into mst_bom (comp_cd, item_id, rev, valid_from, change_reason, created_by, updated_by)
                        select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date, '최초 등록(데모)', ?, ?
                          from mst_item where item_cd = ? returning bom_id""", Long.class, comp, SEED_BY, SEED_BY,
                        b.parent());
                for (int i = 0; i < b.lines().length; i++) {
                    String[] l = b.lines()[i];
                    jdbc.update("""
                            insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, scrap_pct, issue_method,
                                                      created_by, updated_by)
                            select ?, ?, ?, item_id, ?::numeric, unit_cd, ?::numeric,
                                   case lot_ctrl_yn when 'Y' then 'MANUAL' else 'BACKFLUSH' end, ?, ?
                              from mst_item where item_cd = ?""", comp, bomId, (i + 1) * 10, l[1], l[2], SEED_BY,
                            SEED_BY, l[0]);
                }
                if (b.released()) {
                    jdbc.update("""
                            update mst_bom set status = 'RELEASED', released_at = now(), released_by = ?, row_ver = row_ver + 1
                             where bom_id = ?""", SEED_BY, bomId);
                }
            }
            return BOMS.size();
        });
    }

    /** 거래처가 없는 회사에만 넣고 넣은 거래처 수를 돌려준다. 회사가 없으면 0. */
    public int seed(String comp) {
        return runner.call(comp, () -> {
            Integer exists = jdbc.queryForObject("select count(*) from sys_comp where comp_cd = ?", Integer.class, comp);
            Integer custs = jdbc.queryForObject("select count(*) from mst_cust", Integer.class);
            if (exists == null || exists == 0 || custs == null || custs > 0) {
                return 0;
            }
            for (int i = 0; i < CUSTS.size(); i++) {
                Cust c = CUSTS.get(i);
                Long id = jdbc.queryForObject("""
                        insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, biz_no, ceo_nm, tel, email, addr, remark,
                                              use_yn, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning cust_id""", Long.class,
                        comp, c.cd(), c.nm(), c.type(), bizNo(i), c.ceo(), "055-000-" + String.format("%04d", 1000 + i),
                        c.cd().toLowerCase() + "@example.com", c.area() + " (가상 주소)", c.remark(), c.useYn(),
                        SEED_BY, SEED_BY);
                // 담당자 1~2명, 첫 번째가 주 담당
                int count = 1 + i % 2;
                for (int k = 0; k < count; k++) {
                    String[] role = CONTACTS[(i + k) % CONTACTS.length];
                    jdbc.update("""
                            insert into mst_cust_contact (comp_cd, cust_id, contact_nm, dept_nm, tel, email, main_yn,
                                                          created_by, updated_by)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            comp, id, c.nm().substring(0, 2) + " " + role[0] + " 담당", role[1],
                            "010-0000-" + String.format("%04d", i * 10 + k), "contact" + (i * 10 + k) + "@example.com",
                            k == 0 ? "Y" : "N", SEED_BY, SEED_BY);
                }
            }
            return CUSTS.size();
        });
    }

    /** 발급되지 않는 000 대역에서 국세청 검증식(가중치 1,3,7,1,3,7,1,3,5)을 맞춘 번호 */
    static String bizNo(int seq) {
        String body = "000" + String.format("%02d", 81 + seq % 10) + String.format("%04d", 1000 + seq * 37);
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (body.charAt(i) - '0') * w[i];
        }
        sum += ((body.charAt(8) - '0') * 5) / 10;
        return body + (10 - sum % 10) % 10;
    }
}
