package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotTraceQuery;
import io.smarthappy.module.production.link.LotTraceQuery.TraceResult;
import io.smarthappy.module.production.link.LotTraceQuery.TraceRow;
import io.smarthappy.module.qms.LotTraceDtos.TraceForm;
import io.smarthappy.module.qms.LotTraceDtos.TraceView;
import io.smarthappy.module.wms.link.ShipQuery;
import io.smarthappy.module.wms.link.ShipQuery.LotShipment;
import io.smarthappy.module.wms.link.ShipQuery.LotStockRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;

/**
 * docs/57 출하 추적(리콜) 화면 qms-lot-trace — 계보(MES LotTraceQuery)·출하·재고(WMS ShipQuery)를 이어 고객별 요약·출하 목록(뿌리별·경로)·남은 재고,
 * 잘림 표시·찾지 못한 번호, 입력 검사, 권한(SEARCH·EXCEL)·엑셀, A사로 B사. 계약 자체(역분개·순환·순 수량)는 MES·WMS 계약 시험이 덮는다.
 * LOT 별 QMS 일(37장 — 열린 NCR·걸린 봉쇄, 취소 제외, A사로 B사).
 * 역추적(docs/29_P3 32장): 원인 LOT 줄(깊이·경로 ←·연결 종류·출처·공급사·leaf)·공급사별 요약·남은 원인 LOT, 출하는 부르지 않음.
 */
class LotTraceTest extends QmsTestBase {

    static final String T = "/api/qms/lot-trace";

    @Autowired
    LotLookup lotLookup;

    @Autowired
    ExcelExportService excel;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    BizClock clock;

    @Autowired
    QmsLotWorkMapper qmsLotWorkMapper;

    long a;
    long b;
    long c;

    @BeforeEach
    void traceSetUp() {
        accounts.grant(comp, userId, "QA", "qms-lot-trace", "SEARCH", "EXCEL");
        a = lot(comp, "LT-A");
        b = lot(comp, "LT-B");
        c = lot(comp, "LT-C");
    }

    long lot(String co, String no) {
        return accounts.query(co, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""", Long.class, co, no);
    }

    LotTraceService service(LotTraceQuery tq, ShipQuery sq) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("lots", lotLookup);
        if (tq != null) {
            bf.registerSingleton("trace", tq);
        }
        if (sq != null) {
            bf.registerSingleton("ship", sq);
        }
        return new LotTraceService(bf.getBeanProvider(LotLookup.class), bf.getBeanProvider(LotTraceQuery.class), bf.getBeanProvider(ShipQuery.class),
                excel, clock, qmsLotWorkMapper);
    }

    TraceView trace(LotTraceService s, List<String> nos, Integer depth) {
        return trace(s, new TraceForm(nos, depth));
    }

    TraceView trace(LotTraceService s, TraceForm f) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return runner.call(comp, () -> tx.execute(st -> s.trace(f)));
    }

    static LotShipment ship(long lot, long shipId, String shipNo, long cust, String custCd, String qty, String... pallets) {
        return new LotShipment(lot, shipId, shipNo, "P1", cust, custCd, custCd + " 고객", OffsetDateTime.parse("2026-10-20T10:00:00+09:00"), 1L,
                new BigDecimal(qty), "EA", List.of(pallets), List.of());
    }

    /** 계보: A → B(투입) → C(투입), 깊이 받은 대로 — truncated 를 정해 돌려준다 */
    LotTraceQuery chain(boolean truncated, List<Integer> depths) {
        return new LotTraceQuery() {
            @Override
            public TraceResult forward(Collection<Long> ids, int maxDepth) {
                depths.add(maxDepth);
                List<TraceRow> rows = new ArrayList<>();
                if (ids.contains(a)) {
                    rows.add(new TraceRow(a, a, 0, null, null, false));
                    rows.add(new TraceRow(a, b, 1, a, "CONSUME", false));
                    rows.add(new TraceRow(a, c, 2, b, "CONSUME", false));
                }
                if (ids.contains(b)) {
                    rows.add(new TraceRow(b, b, 0, null, null, false));
                    rows.add(new TraceRow(b, c, 1, b, "CONSUME", false));
                }
                return new TraceResult(rows, truncated);
            }

            @Override
            public TraceResult backward(Collection<Long> ids, int maxDepth) {
                return TraceResult.EMPTY;
            }
        };
    }

    ShipQuery ships() {
        return new ShipQuery() {
            @Override
            public java.util.Map<Long, BigDecimal> shippedByLine(Collection<Long> ids) {
                return java.util.Map.of();
            }

            @Override
            public List<LotShipment> byLots(Collection<Long> ids) {
                List<LotShipment> out = new ArrayList<>();
                if (ids.contains(b)) {
                    out.add(ship(b, 11, "SH-2", 900, "C-X", "5", "PLT-1"));
                    out.add(ship(b, 12, "SH-3", 901, "C-Y", "2"));
                }
                if (ids.contains(c)) {
                    out.add(ship(c, 10, "SH-1", 900, "C-X", "10"));
                }
                return out;
            }

            @Override
            public List<LotStockRow> lotStock(Collection<Long> ids) {
                return ids.contains(a) ? List.of(new LotStockRow(a, "P1", 5L, "P1/WH1/A-01", "QC", "QC", new BigDecimal("40"))) : List.of();
            }
        };
    }

    @Test
    void 계보로_아래_LOT_까지_고객별_요약_출하_뿌리별_경로_남은_재고_잘림_찾지_못한_번호() {
        List<Integer> depths = new ArrayList<>();
        TraceView v = trace(service(chain(true, depths), ships()), List.of(" LT-A ", "LT-A", "LT-B", "NOPE-1"), null);
        assertThat(depths).containsExactly(10);                         // 깊이 기본 10
        assertThat(v.roots()).extracting(LotTraceDtos.RootLot::lotNo).containsExactly("LT-A", "LT-B");
        assertThat(v.unknownLotNos()).containsExactly("NOPE-1");
        assertThat(v.lotCnt()).isEqualTo(3);
        assertThat(v.truncated()).isTrue();
        // 고객별: C-X = 출하 2(SH-1·SH-2)·LOT 2(B·C)·15 EA / C-Y = 1·1·2 EA — 출하는 LOT 기준 한 번만 센다(뿌리 둘이 B·C 에 닿아도)
        assertThat(v.customers()).extracting(cs -> cs.custCd() + ":" + cs.shipCnt() + ":" + cs.lotCnt() + ":"
                + cs.qty().get(0).qty().stripTrailingZeros().toPlainString() + cs.qty().get(0).unitCd())
                .containsExactly("C-X:2:2:15EA", "C-Y:1:1:2EA");
        // 출하 줄은 뿌리별로 다시 묶고 경로를 붙인다
        assertThat(v.shipments()).extracting(l -> l.getRootLotNo() + "|" + l.getLotNo() + "|" + l.getDepth() + "|" + l.getPath() + "|" + l.getShipNo()
                + "|" + l.getPallets()).containsExactly(
                        "LT-A|LT-B|1|LT-A → LT-B|SH-2|PLT-1", "LT-A|LT-B|1|LT-A → LT-B|SH-3|", "LT-A|LT-C|2|LT-A → LT-B → LT-C|SH-1|",
                        "LT-B|LT-B|0|LT-B|SH-2|PLT-1", "LT-B|LT-B|0|LT-B|SH-3|", "LT-B|LT-C|1|LT-B → LT-C|SH-1|");
        assertThat(v.stock()).extracting(k -> k.lotNo() + "|" + k.path() + "|" + k.locUsage() + "|" + k.stockStatus() + "|"
                + k.qty().stripTrailingZeros().toPlainString()).containsExactly("LT-A|P1/WH1/A-01|QC|QC|40");
        // 봉쇄 바로가기용 LOT 날 = LOT 이 생긴 날(회사 시간대 — 봉쇄 후보 기간과 같은 기준, 33장)
        assertThat(v.stock().get(0).lotDate()).isEqualTo(accounts.query(comp,
                "select (occurred_at at time zone ?)::date from lot_master where lot_id = ?", java.time.LocalDate.class,
                runner.call(comp, () -> clock.zone().getId()), a));
        assertThat(v.stock().get(0).itemCd()).isEqualTo("FG1");
        // 깊이 0 은 그대로 넘기고, 다 못 찾으면 빈 결과
        trace(service(chain(false, depths), ships()), List.of("LT-A"), 0);
        assertThat(depths).containsExactly(10, 0);
        TraceView none = trace(service(chain(false, depths), ships()), List.of("NOPE-2"), 3);
        assertThat(none.roots()).isEmpty();
        assertThat(none.unknownLotNos()).containsExactly("NOPE-2");
        // MES·WMS 없는 설치본(NONE): 뿌리 자신만, 출하·재고 없음
        TraceView bare = trace(service(null, null), List.of("LT-A"), null);
        assertThat(bare.lotCnt()).isEqualTo(1);
        assertThat(bare.shipments()).isEmpty();
        assertThat(bare.truncated()).isFalse();
    }

    @Test
    void 역추적은_원인_LOT_출처_공급사_남은_원인_LOT_을_보이고_출하는_부르지_않는다() {
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'SUP1', 'SUP1 공급사', 'PURCHASE', 't', 't')",
                comp);
        long sl = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, supplier_cust_id, supplier_lot_no, occurred_at, created_by, updated_by)
                select ?, 'LT-S', i.item_id, 'RECEIVED', 100, 'EA', c.cust_id, 'SL-77', now(), 't', 't'
                  from mst_item i, mst_cust c where i.item_cd = 'FG1' and c.cust_cd = 'SUP1' returning lot_id""", Long.class, comp);
        List<String> called = new ArrayList<>();
        // 계보(자식 → 부모): C ← B(투입) ← A(투입), C ← S(투입, 공급사 LOT)
        LotTraceQuery back = new LotTraceQuery() {
            @Override
            public TraceResult forward(Collection<Long> ids, int maxDepth) {
                called.add("forward");
                return TraceResult.EMPTY;
            }

            @Override
            public TraceResult backward(Collection<Long> ids, int maxDepth) {
                called.add("backward:" + maxDepth);
                return new TraceResult(List.of(new TraceRow(c, c, 0, null, null, false), new TraceRow(c, b, 1, c, "CONSUME", false),
                        new TraceRow(c, a, 2, b, "CONSUME", false), new TraceRow(c, sl, 1, c, "CONSUME", false)), true);
            }
        };
        ShipQuery noShip = new ShipQuery() {
            @Override
            public java.util.Map<Long, BigDecimal> shippedByLine(Collection<Long> ids) {
                return java.util.Map.of();
            }

            @Override
            public List<LotShipment> byLots(Collection<Long> ids) {
                called.add("byLots");
                return List.of();
            }

            @Override
            public List<LotStockRow> lotStock(Collection<Long> ids) {
                return ids.contains(sl) ? List.of(new LotStockRow(sl, "P1", 5L, "P1/WH1/R-01", "STORAGE", "AVAILABLE", new BigDecimal("60"))) : List.of();
            }
        };
        TraceView v = trace(service(back, noShip), new TraceForm(List.of("LT-C"), 4, "BACKWARD"));
        assertThat(called).containsExactly("backward:4");                // 정방향·출하 조회 없음
        assertThat(v.direction()).isEqualTo("BACKWARD");
        assertThat(v.truncated()).isTrue();
        assertThat(v.lotCnt()).isEqualTo(4);
        // 뿌리 → 깊이 → 번호 순, 경로는 ←, leaf = 더 거슬러 올라간 LOT 없음(A·S)
        assertThat(v.origins()).extracting(l -> l.getLotNo() + "|" + l.getDepth() + "|" + l.getPath() + "|" + l.getRelType() + "|" + l.getOrigin()
                + "|" + l.getSupplierCd() + "|" + l.getSupplierLotNo() + "|" + l.isLeaf()).containsExactly(
                        "LT-C|0|LT-C|null|RECEIVED|null|null|false",
                        "LT-B|1|LT-C ← LT-B|CONSUME|RECEIVED|null|null|false",
                        "LT-S|1|LT-C ← LT-S|CONSUME|RECEIVED|SUP1|SL-77|true",
                        "LT-A|2|LT-C ← LT-B ← LT-A|CONSUME|RECEIVED|null|null|true");
        // 공급사별: SUP1 1개(공급사 LOT SL-77), 공급사 없는 입고 3개는 맨 뒤 한 줄
        assertThat(v.suppliers()).extracting(x -> x.supplierCd() + ":" + x.lotCnt() + ":" + x.lotNos() + ":" + x.supplierLotNos())
                .containsExactly("SUP1:1:[LT-S]:[SL-77]", "null:3:[LT-A, LT-B, LT-C]:[]");
        assertThat(v.customers()).isEmpty();
        assertThat(v.shipments()).isEmpty();
        assertThat(v.stock()).extracting(k -> k.lotNo() + "|" + k.qty().stripTrailingZeros().toPlainString()).containsExactly("LT-S|60");
        // 정방향 결과는 origins·suppliers 가 빈 목록, 방향 비우면 FORWARD
        TraceView fwd = trace(service(chain(false, new ArrayList<>()), ships()), new TraceForm(List.of("LT-A"), null, " "));
        assertThat(fwd.direction()).isEqualTo("FORWARD");
        assertThat(fwd.origins()).isEmpty();
        assertThat(fwd.suppliers()).isEmpty();
        // MES 없는 설치본(NONE): 뿌리 자신만 원인 LOT 줄 하나(leaf)
        TraceView bare = trace(service(null, null), new TraceForm(List.of("LT-A"), null, "BACKWARD"));
        assertThat(bare.origins()).extracting(l -> l.getLotNo() + "|" + l.getDepth() + "|" + l.isLeaf()).containsExactly("LT-A|0|true");
    }

    @Test
    void LOT_마다_열린_NCR_과_걸린_봉쇄를_보이고_취소된_NCR_은_빼며_A사로_B사는_안_보인다() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-contain", "SEARCH", "SAVE");
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-LT', '버', 'APPEARANCE', 't', 't')""", comp);
        long defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-LT'", Long.class);
        String ncrBody = ",\"source\":\"CLAIM\",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"클레임\"}";
        long open = id(postJson("/api/qms/ncrs", "{\"lotNo\":\"LT-B\"" + ncrBody));
        long gone = id(postJson("/api/qms/ncrs", "{\"lotNo\":\"LT-C\"" + ncrBody));
        int rv = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, gone);
        postJson("/api/qms/ncrs/" + gone + "/cancel", "{\"rowVer\":" + rv + ",\"reason\":\"중복\"}").andExpect(status().isOk());
        long contain = id(postJson("/api/qms/contains", "{\"itemCd\":\"FG1\",\"fromDate\":" + q(today.minusDays(1)) + ",\"toDate\":" + q(today)
                + ",\"ncrNo\":null,\"reason\":\"리콜\",\"lotIds\":[" + a + "]}"));
        String ncrNo = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, open);
        String containNo = accounts.query(comp, "select contain_no from qms_contain where contain_id = ?", String.class, contain);

        TraceView v = trace(service(chain(false, new ArrayList<>()), ships()), List.of("LT-A"), null);
        // A(봉쇄 중)·B(열린 NCR), C 는 취소된 NCR 만이라 없음 — LOT 번호 순
        assertThat(v.qms()).extracting(w -> w.lotNo() + "|" + w.openNcrNos() + "|" + w.containNos())
                .containsExactly("LT-A|[]|[" + containNo + "]", "LT-B|[" + ncrNo + "]|[]");
        // 역추적도 같은 정보(남은 원인 LOT 판단)
        TraceView back = trace(service(null, null), new TraceForm(List.of("LT-B"), null, "BACKWARD"));
        assertThat(back.qms()).extracting(LotTraceDtos.LotQms::lotNo).containsExactly("LT-B");
        // B사: 같은 LOT 번호를 만들어도 A사 NCR·봉쇄는 안 보인다(RLS)
        String other = accounts.newComp("QW");
        fixtures(other);
        lot(other, "LT-B");
        TransactionTemplate tx = new TransactionTemplate(txm);
        TraceView b = runner.call(other, () -> tx.execute(st -> service(null, null).trace(new TraceForm(List.of("LT-B"), null))));
        assertThat(b.roots()).hasSize(1);
        assertThat(b.qms()).isEmpty();
    }

    @Test
    void 계보가_500개를_넘으면_LOT_조회_출하_재고를_500개씩_나눠_부른다() {
        // 계보 1,201 LOT(뿌리 A + 가짜 id 1,200) — 계약은 500 넘게 받으면 IllegalArgumentException 이라 나눠 불러야 한다
        List<Integer> sizes = new ArrayList<>();
        LotTraceQuery wide = new LotTraceQuery() {
            @Override
            public TraceResult forward(Collection<Long> ids, int maxDepth) {
                List<TraceRow> rows = new ArrayList<>();
                rows.add(new TraceRow(a, a, 0, null, null, false));
                for (long i = 1; i <= 1200; i++) {
                    rows.add(new TraceRow(a, 9_000_000_000L + i, 1, a, "SPLIT", false));
                }
                return new TraceResult(rows, false);
            }

            @Override
            public TraceResult backward(Collection<Long> ids, int maxDepth) {
                return TraceResult.EMPTY;
            }
        };
        ShipQuery strict = new ShipQuery() {
            @Override
            public java.util.Map<Long, BigDecimal> shippedByLine(Collection<Long> ids) {
                return java.util.Map.of();
            }

            @Override
            public List<LotShipment> byLots(Collection<Long> ids) {
                if (ids.size() > ShipQuery.LOTS_MAX) {
                    throw new IllegalArgumentException("too many");
                }
                sizes.add(ids.size());
                return List.of();
            }

            @Override
            public List<LotStockRow> lotStock(Collection<Long> ids) {
                if (ids.size() > ShipQuery.LOTS_MAX) {
                    throw new IllegalArgumentException("too many");
                }
                return List.of();
            }
        };
        TraceView v = trace(service(wide, strict), List.of("LT-A"), 1);
        assertThat(v.lotCnt()).isEqualTo(1201);
        assertThat(sizes).containsExactly(500, 500, 201);
        assertThat(LotTraceService.chunks(java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList(), 500))
                .extracting(List::size).containsExactly(500, 500, 1);
    }

    @Test
    void 입력_검사() {
        LotTraceService s = service(null, null);
        for (TraceForm bad : List.of(new TraceForm(List.of(), null), new TraceForm(java.util.Arrays.asList(" ", null), null),
                new TraceForm(java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> "L" + i).toList(), null),
                new TraceForm(List.of("x".repeat(61)), null), new TraceForm(List.of("LT-A"), 11), new TraceForm(List.of("LT-A"), -1))) {
            Throwable t = catchThrowable(() -> trace(s, bad.lotNos(), bad.depth()));
            assertThat(t).as(String.valueOf(bad)).isInstanceOf(BusinessException.class);
        }
        assertThat(((BusinessException) catchThrowable(() -> trace(s, List.of("LT-A"), 11))).fieldErrors().get(0).field()).isEqualTo("depth");
        for (String dir : new String[] {"SIDEWAYS", "backward", "FORWARD "}) {
            Throwable t = catchThrowable(() -> trace(s, new TraceForm(List.of("LT-A"), null, dir)));
            assertThat(((BusinessException) t).fieldErrors().get(0).field()).as(dir).isEqualTo("direction");
        }
    }

    @Test
    void 권한_엑셀_A사로_B사() throws Exception {
        String body = "{\"lotNos\":[\"LT-A\"],\"depth\":2}";
        postJson(T, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.roots[0].lotNo").value("LT-A"))
                .andExpect(jsonPath("$.data.truncated").value(false));
        mvc.perform(json(post(T + "/export"), body).with(admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/vnd.openxmlformats")));
        postJson(T, "{\"lotNos\":[],\"depth\":2}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lotNos"));
        String backBody = "{\"lotNos\":[\"LT-A\"],\"depth\":2,\"direction\":\"BACKWARD\"}";
        postJson(T, backBody).andExpect(status().isOk()).andExpect(jsonPath("$.data.direction").value("BACKWARD"))
                .andExpect(jsonPath("$.data.origins[0].lotNo").value("LT-A")).andExpect(jsonPath("$.data.origins[0].origin").value("RECEIVED"));
        mvc.perform(json(post(T + "/export"), backBody).with(admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/vnd.openxmlformats")));
        postJson(T, "{\"lotNos\":[\"LT-A\"],\"direction\":\"UP\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("direction"));
        // 조회만 있는 사용자는 엑셀 403, 권한 없는 사용자는 조회도 403
        long viewer = accounts.newUser(comp, "lt1", PW);
        accounts.grant(comp, viewer, "QV", "qms-lot-trace", "SEARCH");
        mvc.perform(json(post(T), body).with(accounts.as(comp, viewer, "lt1"))).andExpect(status().isOk());
        mvc.perform(json(post(T + "/export"), body).with(accounts.as(comp, viewer, "lt1"))).andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "lt2", PW);
        mvc.perform(json(post(T), body).with(accounts.as(comp, none, "lt2"))).andExpect(status().isForbidden());
        // B사: 같은 번호의 A사 LOT 은 찾지 못한 번호로
        String other = accounts.newComp("QL");
        fixtures(other);
        long bu = accounts.newUser(other, "ql1", PW);
        accounts.grant(other, bu, "QA", "qms-lot-trace", "SEARCH");
        mvc.perform(json(post(T), body).with(accounts.as(other, bu, "ql1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roots.length()").value(0)).andExpect(jsonPath("$.data.unknownLotNos[0]").value("LT-A"));
        mvc.perform(json(post(T), backBody).with(accounts.as(other, bu, "ql1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origins.length()").value(0)).andExpect(jsonPath("$.data.unknownLotNos[0]").value("LT-A"));
    }
}
