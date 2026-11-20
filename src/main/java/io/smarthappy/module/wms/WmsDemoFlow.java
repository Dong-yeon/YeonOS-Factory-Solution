package io.smarthappy.module.wms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import io.smarthappy.module.wms.count.CountDtos;
import io.smarthappy.module.wms.count.CountService;
import io.smarthappy.module.wms.inv.CustRtnDtos;
import io.smarthappy.module.wms.inv.CustRtnService;
import io.smarthappy.module.wms.inv.InvDtos.MoveForm;
import io.smarthappy.module.wms.inv.InvDtos.RcvForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.MaterialReceiptService;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.module.wms.loccheck.LocCheckDtos;
import io.smarthappy.module.wms.loccheck.LocCheckService;
import io.smarthappy.module.wms.ship.ShipDtos;
import io.smarthappy.module.wms.ship.ShipService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * WMS 데모 이력 (docs/30 12.20, docs/61 10장 "데모") — 재고 현황판(C3)의 재고 연령·상태 구성과 창고 화면을 채울 기록을 화면과 같은 서비스 길로 넣는다.
 * MES 데모 이력(완제품 입고) 뒤에 돈다.
 * <ul>
 * <li>자재 입고 9건: 지난 200일에 걸쳐(연령 구간 0~30·31~90·91~180·180+ 가 모두 생기게) — 입고 화면처럼 발생 시각을 받는 길({@link MaterialReceiptService#receive}),
 *     마지막 코일 1건은 QC 상태</li>
 * <li>이동 3건(과거): 라인사이드 불출 2 + 같은 칸 상태 변경(가용 → 보류) 1</li>
 * <li>출하 3건(지금 — 출하 피킹·확정은 지금 시각만 받는다): 확정 1(완제품 오래된 LOT 부터) · 피킹 중 1 · 작성 1</li>
 * <li>고객 반품 1건(지금): 확정한 출하의 LOT 12개 품질 반품 → 보류 재고 + QMS 고객 클레임 부적합(포트)</li>
 * <li>정위치 점검 1건(오늘 — 점검은 오늘 머리만): 원자재 칸 하나를 스캔해 일치·다른 칸·없음이 섞이게, 다른 칸은 정정, 없음은 확인</li>
 * <li>순환 실사 1건(지금): 원자재 칸 — 한 LOT 만 차이(-0.3%, 허용 5% 안) → 확정 시 실사 조정</li>
 * </ul>
 * 자재 입고·출하·실사·정위치 점검·고객 반품이 하나라도 있으면 아무것도 하지 않는다. 출하·반품은 완제품 재고와 열린 수주 행이 있을 때만. 한 트랜잭션.
 */
@Service
@RequiredArgsConstructor
public class WmsDemoFlow {

    static final String PLANT = "P1";
    static final String FG_ITEM = "64310-DM100";

    /** 자재 입고: 며칠 전, 품목, 수량, 공급사, 공급사 LOT, 위치, 상태 */
    static final Object[][] RECEIPTS = {
            {200, "RM-SAPH440-2.3", "6200", "P001", "DD-HR-2604-11", "RM-A01-02", "AVAILABLE"},
            {130, "RM-SGARC340-1.2", "5400", "P001", "DD-GA-2606-03", "RM-A01-02", "AVAILABLE"},
            {75, "RM-SPFC590-1.6", "8500", "P001", "DD-CL-2608-21", "RM-A01-01", "AVAILABLE"},
            {60, "NUT-M8-WELD", "20000", "P003", "IB-2609-044", "RM-A01-01", "AVAILABLE"},
            {45, "WIRE-ER70S-1.2", "400", "P007", "BV-WR-0391", "RM-A01-01", "AVAILABLE"},
            {21, "RM-SPFC590-1.6", "8500", "P001", "DD-CL-2610-02", "RM-A01-01", "AVAILABLE"},
            {12, "NUT-M8-WELD", "20000", "P003", "IB-2610-102", "RM-A01-01", "AVAILABLE"},
            {5, "RM-SPFC590-1.6", "8400", "P001", "DD-CL-2610-19", "RM-A01-01", "QC"},
            {1, "WIRE-ER70S-1.2", "400", "P007", "BV-WR-0466", "RM-A01-02", "AVAILABLE"}};

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final MaterialReceiptService receipts;
    private final StockService stock;
    private final ShipService ships;
    private final CustRtnService returns;
    private final LocCheckService locChecks;
    private final CountService counts;

    /** 반환 = 넣은 원장·문서 건수(건너뛰면 0) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, this::run));
    }

    private int run() {
        Map<String, Long> nodes = new java.util.HashMap<>();
        jdbc.query("""
                select node_cd, node_id from mst_equip_hier where plant_cd = ? and use_yn = 'Y'
                   and node_cd in ('RM-A01-01', 'RM-A01-02', 'BODY-LS', 'FG-A01', 'FG-B01')""", rs -> {
                    nodes.put(rs.getString(1), rs.getLong(2));
                }, PLANT);
        if (nodes.size() < 5 || used() || count("""
                select count(*) from mst_item where use_yn = 'Y' and item_cd in ('RM-SAPH440-2.3', 'RM-SGARC340-1.2', 'RM-SPFC590-1.6',
                                                                              'NUT-M8-WELD', 'WIRE-ER70S-1.2')""") < 5
                || count("select count(*) from mst_cust where use_yn = 'Y' and cust_cd in ('P001', 'P003', 'P007')") < 3) {
            return 0;
        }
        LocalDate today = clock.today();
        int n = 0;
        List<String> lots = new ArrayList<>();
        for (Object[] r : RECEIPTS) {
            LocalDate d = today.minusDays((Integer) r[0]);
            lots.add(receipts.receive(new RcvForm(PLANT, nodes.get((String) r[5]), item((String) r[1]), new BigDecimal((String) r[2]),
                    (String) r[6], (String) r[3], (String) r[4], local(d, 9, 30), "데모 이력 — 자재 입고", "KEY", req(), null, null, null, null,
                    null, null)).lotNo());
            n++;
        }
        // 이동: 코일(75일 전 입고) 1,700 KG·너트(60일 전) 5,000 EA 라인사이드 불출, 아연도금 코일 2,000 KG 표면 녹으로 보류
        n += move("RM-SPFC590-1.6", lots.get(2), nodes.get("RM-A01-01"), "AVAILABLE", nodes.get("BODY-LS"), "AVAILABLE", "1700",
                today.minusDays(70), "프레스 라인사이드 불출");
        n += move("NUT-M8-WELD", lots.get(3), nodes.get("RM-A01-01"), "AVAILABLE", nodes.get("BODY-LS"), "AVAILABLE", "5000",
                today.minusDays(55), "용접 라인사이드 불출");
        n += move("RM-SGARC340-1.2", lots.get(1), nodes.get("RM-A01-02"), "AVAILABLE", nodes.get("RM-A01-02"), "BLOCKED", "2000",
                today.minusDays(100), "바깥 감김 표면 녹 — 협력사 판정까지 보류");

        n += shipAndReturn(nodes);
        n += locCheck(nodes, lots);
        n += cycleCount(nodes);
        return n;
    }

    /** 자재 입고·출하·실사·정위치 점검·고객 반품이 있으면 이미 쓰는 회사 */
    private boolean used() {
        return count("""
                select (select count(*) from inv_trx where trx_type in ('RCV_MAT', 'RCV_PO')) + (select count(*) from wms_ship)
                     + (select count(*) from wms_count) + (select count(*) from wms_loc_check) + (select count(*) from wms_cust_rtn)""") > 0;
    }

    private int move(String itemCd, String lotNo, long from, String fromStatus, long to, String toStatus, String qty, LocalDate d, String remark) {
        boolean lotCtrl = "Y".equals(jdbc.queryForObject("select lot_ctrl_yn from mst_item where item_cd = ?", String.class, itemCd));
        stock.move(new MoveForm(PLANT, item(itemCd), from, fromStatus, to, toStatus, new BigDecimal(qty), "데모 이력 — " + remark,
                local(d, 14, 0), null, "KEY", req(), lotCtrl ? lotNo : null));
        return 1;
    }

    // ------------------------------------------------------------------ 출하·반품

    /** 확정 1(600) · 피킹 중 1(300 중 일부) · 작성 1 — 완제품 가용 재고(보류 LOT 제외, 오래된 LOT 부터)와 S001 열린 수주 행이 있을 때만 */
    private int shipAndReturn(Map<String, Long> nodes) {
        List<Long> lines = jdbc.queryForList("""
                select l.line_id from sal_order_line l join sal_order o on o.order_id = l.order_id join mst_cust c on c.cust_id = o.cust_id
                  join mst_item i on i.item_id = l.item_id
                 where c.cust_cd = 'S001' and i.item_cd = ? and l.status = 'OPEN' order by l.due_date, l.line_id""", Long.class, FG_ITEM);
        List<Map<String, Object>> avail = jdbc.queryForList("""
                select m.lot_no, trim_scale(s.qty)::text as qty from inv_stock s join lot_master m on m.lot_id = s.lot_id
                 where s.item_id = ? and s.node_id = ? and s.stock_status = 'AVAILABLE' and s.qty > 0
                   and not exists (select 1 from lot_hold h where h.lot_id = m.lot_id and h.released_at is null)
                 order by m.occurred_at, m.lot_id""", item(FG_ITEM), nodes.get("FG-A01"));
        if (lines.isEmpty() || avail.size() < 4) {
            return 0;
        }
        String today = clock.today().toString();
        long s1 = ships.create(new ShipDtos.CreateForm(PLANT, "S001", nodes.get("FG-B01"), today, "데모 — 확정",
                List.of(new ShipDtos.LineForm(lines.get(0), new BigDecimal("600")))));
        long l1 = shipLine(s1);
        List<ShipDtos.PickReq> picks = new ArrayList<>();
        BigDecimal left = new BigDecimal("600");
        int k = 0;
        while (left.signum() > 0 && k < avail.size()) {
            BigDecimal q = new BigDecimal((String) avail.get(k).get("qty")).min(left);
            picks.add(new ShipDtos.PickReq(l1, nodes.get("FG-A01"), (String) avail.get(k).get("lot_no"), q));
            left = left.subtract(q);
            k++;
        }
        if (left.signum() > 0) {
            return 0;
        }
        ships.pick(s1, new ShipDtos.PickForm(rv("wms_ship", "ship_id", s1), req(), picks));
        ships.confirm(s1, new ShipDtos.ConfirmForm(rv("wms_ship", "ship_id", s1), req(), null, null, null));

        long s2 = ships.create(new ShipDtos.CreateForm(PLANT, "S001", nodes.get("FG-B01"), clock.today().plusDays(1).toString(), "데모 — 피킹 중",
                List.of(new ShipDtos.LineForm(lines.get(0), new BigDecimal("300")))));
        Map<String, Object> next = avail.get(k);
        ships.pick(s2, new ShipDtos.PickForm(rv("wms_ship", "ship_id", s2), req(), List.of(new ShipDtos.PickReq(shipLine(s2), nodes.get("FG-A01"),
                (String) next.get("lot_no"), new BigDecimal((String) next.get("qty")).min(new BigDecimal("120"))))));
        ships.create(new ShipDtos.CreateForm(PLANT, "S001", nodes.get("FG-B01"), clock.today().plusDays(3).toString(), "데모 — 작성",
                List.of(new ShipDtos.LineForm(lines.get(lines.size() - 1), new BigDecimal("200")))));

        // 고객 반품: 확정한 출하의 첫 LOT 에서 12개, 품질(스폿 누락 의심) — 보류 재고 + 고객 클레임 부적합
        Long lotId = jdbc.queryForObject("select lot_id from lot_master where lot_no = ?", Long.class, picks.get(0).lotNo());
        returns.receive(new CustRtnDtos.CreateForm(PLANT, l1, lotId, new BigDecimal("12"), "QUALITY", nodes.get("FG-A01"),
                "데모 — 고객 라인에서 스폿 누락 의심 12개 반송", null, "KEY", req()));
        return 4;
    }

    private long shipLine(long shipId) {
        return jdbc.queryForObject("select ship_line_id from wms_ship_line where ship_id = ? order by line_no limit 1", Long.class, shipId);
    }

    // ------------------------------------------------------------------ 정위치 점검·실사

    /** RM-A01-02: 열연 코일 LOT(일치)·와이어(품목, 일치)·너트 LOT(장부는 RM-A01-01 → 다른 칸) 스캔, 아연도금 코일은 안 스캔(없음) → 다른 칸 정정·없음 확인 */
    private int locCheck(Map<String, Long> nodes, List<String> lots) {
        long node = nodes.get("RM-A01-02");
        LocCheckDtos.View v = locChecks.start(PLANT, node, null);
        long checkId = v.head().getCheckId();
        for (String text : List.of("LOT:" + lots.get(0), "ITEM:WIRE-ER70S-1.2", "LOT:" + lots.get(6))) {
            locChecks.scan(checkId, PLANT, text, stock.scan(new ScanForm(PLANT, text)), req(), null, null);
        }
        LocCheckDtos.View done = locChecks.done(checkId, PLANT);
        for (LocCheckDtos.Line l : done.lines()) {
            if ("MISPLACED".equals(l.getResult())) {
                locChecks.correct(l.getLineId(), new LocCheckDtos.CorrectForm(nodes.get("RM-A01-01"), "AVAILABLE", new BigDecimal("2000"),
                        "데모 — 너트 한 상자가 코일 랙에 — 장부를 이 칸으로"));
            } else if ("MISSING".equals(l.getResult())) {
                locChecks.confirm(l.getLineId(), new LocCheckDtos.ConfirmForm("크레인으로 프레스 라인 옆 임시 적치 확인 — 다음 실사에서 정리"));
            }
        }
        return 1;
    }

    /** RM-A01-01 순환 실사: 센 수량 = 장부, 너트(12일 전 입고) LOT 만 -60(-0.3%) → 확정(실사 조정 원장) */
    private int cycleCount(Map<String, Long> nodes) {
        long id = counts.create(new CountDtos.CreateForm(PLANT, "CYCLE", nodes.get("RM-A01-01"), null, "N", "데모 — 원자재 랙 A01 1단 순환 실사"));
        counts.start(id, rv("wms_count", "count_id", id));
        List<CountDtos.CountRow> rows = new ArrayList<>();
        Long nut = item("NUT-M8-WELD");
        boolean diffDone = false;
        for (Map<String, Object> l : jdbc.queryForList("""
                select cl.line_id, cl.item_id, cl.lot_id, coalesce((select s.qty from inv_stock s
                                                                    where s.item_id = cl.item_id and s.node_id = cl.node_id
                                                                      and s.stock_status = cl.stock_status
                                                                      and s.lot_id is not distinct from cl.lot_id), 0) as book
                  from wms_count_line cl where cl.count_id = ? order by cl.line_id desc""", id)) {
            BigDecimal book = (BigDecimal) l.get("book");
            boolean diff = !diffDone && nut.equals(((Number) l.get("item_id")).longValue()) && book.compareTo(new BigDecimal("1000")) > 0;
            diffDone |= diff;
            rows.add(new CountDtos.CountRow(((Number) l.get("line_id")).longValue(),
                    (diff ? book.subtract(new BigDecimal("60")) : book).stripTrailingZeros().toPlainString(), null));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        counts.enter(id, new CountDtos.CountsForm(rv("wms_count", "count_id", id), rows));
        counts.submit(id, rv("wms_count", "count_id", id));
        counts.post(id, rv("wms_count", "count_id", id));
        return 1;
    }

    // ------------------------------------------------------------------ 도우미

    private Long item(String cd) {
        return jdbc.queryForObject("select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    /** 화면 입력과 같은 공장 현지 "yyyy-MM-ddTHH:mm" */
    private static String local(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).toString();
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** 표 이름·키 칸은 이 클래스 상수만 — 사용자 입력 아님 */
    private int rv(String table, String key, long id) {
        return jdbc.queryForObject("select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
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
