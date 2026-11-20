package io.smarthappy.module.srm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.smarthappy.module.demo.DemoSeedSupport;
import io.smarthappy.module.demo.DemoSeedSupport.SeedClock;
import io.smarthappy.module.qms.NcrDtos;
import io.smarthappy.module.qms.NcrService;
import io.smarthappy.module.srm.ack.AckDtos.AckForm;
import io.smarthappy.module.srm.ack.AckDtos.AckLineForm;
import io.smarthappy.module.srm.ack.AckService;
import io.smarthappy.module.srm.asn.AsnDtos;
import io.smarthappy.module.srm.asn.AsnDtos.AsnForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineForm;
import io.smarthappy.module.srm.asn.AsnService;
import io.smarthappy.module.srm.claim.ClaimDtos;
import io.smarthappy.module.srm.claim.ClaimDtos.AnswerForm;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimForm;
import io.smarthappy.module.srm.claim.ClaimService;
import io.smarthappy.module.srm.eval.EvalDtos;
import io.smarthappy.module.srm.eval.EvalDtos.BandForm;
import io.smarthappy.module.srm.eval.EvalDtos.EvalForm;
import io.smarthappy.module.srm.eval.EvalDtos.GradeForm;
import io.smarthappy.module.srm.eval.EvalDtos.ItemForm;
import io.smarthappy.module.srm.eval.EvalDtos.ScoreForm;
import io.smarthappy.module.srm.eval.EvalDtos.ScoresForm;
import io.smarthappy.module.srm.eval.EvalDtos.TmplForm;
import io.smarthappy.module.srm.eval.EvalService;
import io.smarthappy.module.srm.eval.EvalTmplService;
import io.smarthappy.module.srm.po.PoDtos;
import io.smarthappy.module.srm.po.PoDtos.PoForm;
import io.smarthappy.module.srm.po.PoDtos.PoLineForm;
import io.smarthappy.module.srm.po.PoService;
import io.smarthappy.module.srm.supplier.SourcePriceService;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierForm;
import io.smarthappy.module.srm.supplier.SupplierService;
import io.smarthappy.module.wms.inv.InvDtos.RcvForm;
import io.smarthappy.module.wms.inv.MaterialReceiptService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * SRM 데모 관통 흐름 (docs/31 "데모 둘러보기") — 화면과 같은 서비스 길로, 지난 60일에 흩뿌린다:
 * 협력사 등록·인증서·공급 품목·단가(SupplierService·SourcePriceService) → 발주 작성·확정·발행(PoService) → 납기 회신(AckService) →
 * 코일 협력사는 ASN 보내기(AsnService) → 구매 입고(WMS MaterialReceiptService — SRM 입고 게이트·재고 원장·LOT 이 한 길) → 다 받은 발주 마감 →
 * 입고 LOT 부적합(QMS NcrService) → 협력사 클레임 보내기·회신·종결(ClaimService) → 평가 템플릿 확정·평가(EvalTmplService·EvalService).
 *
 * <ul>
 * <li>회사에 발주가 하나라도 있으면 아무것도 하지 않는다(사용자 데이터를 덮지 않음). 전부 한 트랜잭션 — 실패하면 하나도 남지 않는다</li>
 * <li>기준정보 데모(DemoDataSeeder)의 구매 거래처 P001·P002·P003·P007, 원자재·체결류 품목, 원자재 창고 칸(RM-A01-01·02)이 없으면 건너뛴다</li>
 * <li>결재: 데모 회사에 발주 결재 기준 금액(SRM_PO_APPROVAL_MIN_AMT)이 없으면 3천만 원으로 둔다 — 보통 발주는 결재 없이 확정, 큰 발주 하나는
 *     "결재 상신 대기"(작성 중)로 남겨 결재 흐름을 화면에서 해 보게 한다</li>
 * <li>지난 날짜는 시드 시계(DemoSeedSupport) — 발주일·회신일·입고 발생 시각·채번 연월이 그날. DB now() 칸(발행 시각·감사 칸)은 실제 넣은 시각</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SrmDemoFlow {

    static final String PLANT = "P1";
    /** 데모 회사 결재 기준 금액(원) — 회사 값이 없을 때만 */
    static final String APPROVAL_MIN_AMT = "30000000";

    /** 협력사: 코드, 인증 유형, 인증 번호, 만료(오늘 + 일), 공급 품목 {품목, 협력사 품번, 리드타임(일), 단가} */
    record Sup(String cd, String certType, String certNo, int expiresIn, String[][] items) {
    }

    static final List<Sup> SUPS = List.of(
            new Sup("P001", "IATF16949", "IATF-0457821", 410, new String[][] {
                    {"RM-SPFC590-1.6", "DD-SPFC590-16", "10", "1150"}, {"RM-SAPH440-2.3", "DD-SAPH440-23", "10", "980"}}),
            new Sup("P002", "ISO9001", "KS-Q-9001-3312", 300, new String[][] {
                    {"RM-SGARC340-1.2", "BR-GA340-12", "7", "1320"}}),
            new Sup("P003", "IATF16949", "IATF-0391177", 30, new String[][] {
                    {"NUT-M8-WELD", "IE-WN-M8", "5", "45"}, {"BOLT-M10X25", "IE-FB-M1025", "5", "120"}}),
            new Sup("P007", "ISO9001", "KS-Q-9001-5520", 220, new String[][] {
                    {"WIRE-ER70S-1.2", "BV-ER70S6-12", "5", "3800"}, {"TIP-M6-1.2", "BV-TIP-M6", "5", "900"}}));

    /** 발주의 끝 상태 */
    enum Fate {
        /** 다 받음(lateDays 만큼 늦게) */
        RECEIVED,
        /** 다 받고 마감 */
        CLOSED,
        /** 첫 행을 80%만 받음 */
        PARTIAL,
        /** ASN 은 보냈는데 도착 예정일이 지나도 안 옴 */
        ASN_LATE,
        /** 회신은 왔는데 납기가 지나도 안 옴 */
        OVERDUE,
        /** 발행만(회신 대기) */
        ISSUED,
        /** 작성 중 — 결재 기준 금액 이상이라 상신 대기 */
        DRAFT
    }

    /** 발주: 며칠 전, 협력사, 행 {품목, 수량}, 늦게 받은 일수, 회신 약속을 늦춘 일수, 끝 상태 */
    record PoPlan(int daysAgo, String sup, String[][] lines, int lateDays, int promiseLate, Fate fate) {
    }

    static final List<PoPlan> POS = List.of(
            new PoPlan(58, "P001", new String[][] {{"RM-SPFC590-1.6", "8000"}}, 0, 0, Fate.CLOSED),
            new PoPlan(55, "P003", new String[][] {{"NUT-M8-WELD", "20000"}, {"BOLT-M10X25", "5000"}}, 0, 0, Fate.CLOSED),
            new PoPlan(52, "P002", new String[][] {{"RM-SGARC340-1.2", "6000"}}, 3, 0, Fate.RECEIVED),
            new PoPlan(49, "P007", new String[][] {{"WIRE-ER70S-1.2", "400"}, {"TIP-M6-1.2", "2000"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(45, "P001", new String[][] {{"RM-SAPH440-2.3", "5000"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(41, "P003", new String[][] {{"NUT-M8-WELD", "20000"}}, 2, 0, Fate.RECEIVED),
            new PoPlan(38, "P002", new String[][] {{"RM-SGARC340-1.2", "6000"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(34, "P001", new String[][] {{"RM-SPFC590-1.6", "8000"}}, 0, 0, Fate.CLOSED),
            new PoPlan(30, "P007", new String[][] {{"WIRE-ER70S-1.2", "400"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(27, "P003", new String[][] {{"NUT-M8-WELD", "15000"}, {"BOLT-M10X25", "5000"}}, 0, 0, Fate.PARTIAL),
            new PoPlan(23, "P002", new String[][] {{"RM-SGARC340-1.2", "6000"}}, 4, 4, Fate.RECEIVED),
            new PoPlan(19, "P001", new String[][] {{"RM-SPFC590-1.6", "8000"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(15, "P007", new String[][] {{"TIP-M6-1.2", "2000"}}, 0, 0, Fate.RECEIVED),
            new PoPlan(12, "P001", new String[][] {{"RM-SAPH440-2.3", "5000"}}, 0, 0, Fate.ASN_LATE),
            new PoPlan(6, "P003", new String[][] {{"BOLT-M10X25", "8000"}}, 0, 0, Fate.OVERDUE),
            new PoPlan(3, "P002", new String[][] {{"RM-SGARC340-1.2", "6000"}}, 0, 0, Fate.ISSUED),
            new PoPlan(1, "P001", new String[][] {{"RM-SPFC590-1.6", "30000"}}, 0, 0, Fate.DRAFT));

    /** ASN 을 보내는 협력사(코일 — 운송 전 통지 계약) */
    static final List<String> ASN_SUPS = List.of("P001", "P002");

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final DemoSeedSupport support;
    private final PolicyService policies;
    private final SupplierService suppliers;
    private final SourcePriceService sources;
    private final PoService pos;
    private final AckService acks;
    private final AsnService asns;
    private final MaterialReceiptService receipts;
    private final NcrService ncrs;
    private final ClaimService claims;
    private final EvalTmplService tmpls;
    private final EvalService evals;

    /** 만든 발주 수(이미 있거나 기준정보가 없으면 0). user = 실행자(회사 관리자) */
    public int seed(String comp, LoginUser user) {
        return DemoSeedSupport.as(user, () -> runner.call(comp, () -> {
            if (count("select count(*) from srm_po") > 0 || !ready()) {
                return 0;
            }
            ZoneId zone = clock.zone();
            LocalDate today = clock.today();
            return support.withClock(c -> run(c, user, zone, today));
        }));
    }

    /** 기준정보 데모가 있는가 — 협력사 거래처 4곳·공급 품목 전부·원자재 창고 칸 2개 */
    private boolean ready() {
        List<String> custs = SUPS.stream().map(Sup::cd).toList();
        List<String> items = SUPS.stream().flatMap(s -> java.util.Arrays.stream(s.items())).map(i -> i[0]).toList();
        return count("select count(*) from mst_cust where cust_type in ('PURCHASE', 'BOTH') and use_yn = 'Y' and cust_cd = any(?)",
                (Object) custs.toArray(String[]::new)) == custs.size()
                && count("select count(*) from mst_item where use_yn = 'Y' and item_cd = any(?)", (Object) items.toArray(String[]::new))
                        == items.size()
                && count("select count(*) from mst_equip_hier where plant_cd = ? and node_cd in ('RM-A01-01', 'RM-A01-02') and use_yn = 'Y'",
                        PLANT) == 2;
    }

    private int run(SeedClock c, LoginUser u, ZoneId zone, LocalDate today) {
        // 1) 협력사 기준정보 — 90일 전에 등록한 것으로
        c.set(today.minusDays(90), LocalTime.of(9, 0), zone);
        if (count("select count(*) from sys_policy where policy_key = 'SRM_PO_APPROVAL_MIN_AMT' and plant_cd = '*'") == 0) {
            policies.set("SRM_PO_APPROVAL_MIN_AMT", null, APPROVAL_MIN_AMT, u.loginId());
        }
        for (Sup s : SUPS) {
            register(s, u, today);
        }

        // 2) 발주 → 회신 → ASN → 입고 → 마감 (날짜 순으로 하루씩 — 같은 날 여러 단계면 시각을 나눔)
        List<long[]> made = new ArrayList<>();       // {poId, planIndex}
        for (int i = 0; i < POS.size(); i++) {
            PoPlan p = POS.get(i);
            made.add(new long[] {po(c, p, i, zone, today), i});
        }

        // 3) 부적합 → 클레임 (7번 발주 코일 녹 = 회신·종결, 10번 발주 너트 나사산 = 회신 대기)
        claimClosed(c, made.get(6)[0], zone, today.minusDays(POS.get(6).daysAgo()).plusDays(lead("P002") + 1));
        claimOpen(c, made.get(9)[0], zone, today.minusDays(POS.get(9).daysAgo()).plusDays(lead("P003") + 2));

        // 4) 평가 — 템플릿은 90일 전 확정, 평가 기간 = 지난 60일 ~ 오늘
        c.set(today.minusDays(90), LocalTime.of(14, 0), zone);
        template(today.minusDays(90));
        c.reset();
        evaluate("P001", today, "90", "납기·품질 안정 — 코일 측면 녹 재발 방지 포장 유지", true);
        evaluate("P002", today, "70", "녹 클레임 1건(종결) · 납기 지연 2건 — 납기 과락이면 거래 보류 검토 제안", true);
        evaluate("P003", today, "75", "나사산 클레임 회신 대기 — 회신 뒤 확정", false);
        return POS.size();
    }

    // ------------------------------------------------------------------ 협력사

    private void register(Sup s, LoginUser u, LocalDate today) {
        long custId = id("select cust_id from mst_cust where cust_cd = ?", s.cd());
        suppliers.create(new SupplierForm(custId, u.loginId(), null, "KRW", "KR", null, null, null, null,
                "데모 — " + s.certType() + " 인증 협력사", null));
        suppliers.addCert(custId, new CertForm(s.certType(), s.certNo(), "가상 인증기관", today.minusYears(2).toString(),
                today.plusDays(s.expiresIn()).toString(), "Y", s.expiresIn() <= 60 ? "갱신 심사 일정 확인 필요(데모)" : null, null));
        int prio = 1;
        for (String[] it : s.items()) {
            sources.addSource(custId, new SourceForm(it[0], it[1], null, Integer.parseInt(it[2]), null, null, prio++, "Y", null, null));
            sources.addPrice(custId, new PriceForm(it[0], "KRW", it[3], today.minusDays(120).toString(), null, "Y", "데모 연간 단가", null));
        }
    }

    // ------------------------------------------------------------------ 발주 한 건

    private long po(SeedClock c, PoPlan p, int idx, ZoneId zone, LocalDate today) {
        LocalDate day = today.minusDays(p.daysAgo());
        int lead = lead(p.sup());
        LocalDate due = day.plusDays(lead);
        c.set(day, LocalTime.of(9, 10), zone);
        List<PoLineForm> lines = new ArrayList<>();
        for (String[] l : p.lines()) {
            lines.add(new PoLineForm(null, l[0], l[1], null, due.toString(), null, null));
        }
        long poId = pos.create(new PoForm(p.sup(), PLANT, "RM-WH", null, "데모 발주 " + (idx + 1), null, lines));
        if (p.fate() == Fate.DRAFT) {
            return poId;
        }
        pos.confirm(poId, new PoDtos.ActionForm(poVer(poId), null));
        c.set(day, LocalTime.of(9, 30), zone);
        pos.issue(poId, new PoDtos.ActionForm(poVer(poId), null));
        if (p.fate() == Fate.ISSUED) {
            return poId;
        }

        // 다음 날 회신(약속 = 요구 납기, promiseLate 면 늦춘 약속 — 화면에 LATE 경고)
        LocalDate ackOn = day.plusDays(1).isAfter(today) ? today : day.plusDays(1);
        c.set(ackOn, LocalTime.of(11, 0), zone);
        List<Map<String, Object>> poLines = poLines(poId);
        List<AckLineForm> promised = new ArrayList<>();
        for (Map<String, Object> l : poLines) {
            promised.add(new AckLineForm(((Number) l.get("po_line_id")).longValue(), due.plusDays(p.promiseLate()).toString(),
                    ((BigDecimal) l.get("qty")).toPlainString(), null));
        }
        acks.record(poId, new AckForm(0, ackOn.toString(), contact(p.sup()), false, null,
                p.promiseLate() > 0 ? "코일 슬리팅 설비 점검으로 " + p.promiseLate() + "일 늦게 납품" : null, promised));

        LocalDate arrive = due.plusDays(p.lateDays());
        Map<Long, Long> asnLine = Map.of();
        if (ASN_SUPS.contains(p.sup())) {
            LocalDate ship = arrive.minusDays(1);
            c.set(ship, LocalTime.of(16, 0), zone);
            asnLine = asn(poId, p, poLines, ship, p.fate() == Fate.ASN_LATE ? due : arrive);
        }
        if (p.fate() == Fate.ASN_LATE || p.fate() == Fate.OVERDUE) {
            return poId;
        }

        // 입고(도착일 10시) — 발주 행마다, PARTIAL 은 첫 행 80%
        c.set(arrive, LocalTime.of(10, 0), zone);
        int n = 0;
        for (Map<String, Object> l : poLines) {
            long lineId = ((Number) l.get("po_line_id")).longValue();
            BigDecimal qty = (BigDecimal) l.get("qty");
            if (p.fate() == Fate.PARTIAL && n == 0) {
                qty = qty.multiply(new BigDecimal("0.8")).stripTrailingZeros();
            }
            String itemCd = (String) l.get("item_cd");
            String node = itemCd.startsWith("RM-") ? "RM-A01-01" : "RM-A01-02";
            String req = "DEMO-SRM-" + (idx + 1) + "-" + (++n);
            receipts.receive(new RcvForm(PLANT, nodeId(node), null, qty, "AVAILABLE", null, supplierLot(p, arrive, n),
                    arrive.atTime(10, 0).toString(), "데모 구매 입고", "KEY", req, null, null, null, null, lineId,
                    asnLine.get(lineId)));
        }
        if (p.fate() == Fate.CLOSED) {
            c.set(arrive.plusDays(1), LocalTime.of(9, 0), zone);
            pos.close(poId, new PoDtos.ActionForm(poVer(poId), null));
        }
        return poId;
    }

    /** ASN 만들기·보내기 — 반환 = 발주 행 id → ASN 행 id */
    private Map<Long, Long> asn(long poId, PoPlan p, List<Map<String, Object>> poLines, LocalDate ship, LocalDate eta) {
        List<AsnLineForm> lines = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> l : poLines) {
            lines.add(new AsnLineForm(((Number) l.get("po_line_id")).longValue(), ((BigDecimal) l.get("qty")).toPlainString(),
                    supplierLot(p, eta, ++n), ship.minusDays(2).toString(), null, "2", null));
        }
        long asnId = asns.create(new AsnForm(p.sup(), PLANT, ship.toString(), eta.toString(), "DN-" + p.sup() + "-" + ship.toString().replace("-", ""),
                "가상 운송 — 차량 82가 " + (1000 + poId % 9000), null, null, lines));
        asns.send(asnId, new AsnDtos.ActionForm(ver("srm_asn", "asn_id", asnId), null));
        Map<Long, Long> out = new java.util.HashMap<>();
        jdbc.queryForList("select po_line_id, asn_line_id from srm_asn_line where asn_id = ?", asnId)
                .forEach(r -> out.put(((Number) r.get("po_line_id")).longValue(), ((Number) r.get("asn_line_id")).longValue()));
        return out;
    }

    private static String supplierLot(PoPlan p, LocalDate on, int n) {
        return p.sup() + "-" + on.toString().substring(2).replace("-", "") + "-" + n;
    }

    // ------------------------------------------------------------------ 부적합 → 클레임

    /** 코일 녹(도금 손상) — 보내기 → 회신(5일 뒤) → 종결(7일 뒤) */
    private void claimClosed(SeedClock c, long poId, ZoneId zone, LocalDate found) {
        c.set(found, LocalTime.of(13, 0), zone);
        long ncr = ncr(poId, "SCRATCH", "300", "입고 코일 외측 녹 발생 — 도금층 손상 300kg(측면 포장 찢김)");
        long claim = claims.create(new ClaimForm("P002", "CAPA", "코일 외측 녹(도금 손상)", "녹 발생 원인과 포장·운송 대책을 회신 바랍니다(8D).",
                found.plusDays(7).toString(), null, null, List.of(ncr), null));
        claims.send(claim, new ClaimDtos.ActionForm(ver("srm_claim", "claim_id", claim), null));
        c.set(found.plusDays(5), LocalTime.of(15, 0), zone);
        claims.answer(claim, new AnswerForm(ver("srm_claim", "claim_id", claim), "적재 시 측면 보호재 누락 — 우천 운송 중 침수",
                "측면 보호재 이중화 + 방수 커버 의무화, 출하 전 사진 점검표 추가", found.plusDays(5).toString()));
        c.set(found.plusDays(7), LocalTime.of(10, 0), zone);
        claims.close(claim, new ClaimDtos.ActionForm(ver("srm_claim", "claim_id", claim), null));
    }

    /** 너트 나사산 — 비용 청구 포함, 보낸 뒤 회신 대기 */
    private void claimOpen(SeedClock c, long poId, ZoneId zone, LocalDate found) {
        c.set(found, LocalTime.of(14, 0), zone);
        long ncr = ncr(poId, "DENT", "120", "용접 너트 나사산 찌그러짐 — 탭 게이지 NG 120EA(선별)");
        long claim = claims.create(new ClaimForm("P003", "BOTH", "용접 너트 나사산 찌그러짐", "선별 공수 비용 청구와 함께 원인·대책 회신 바랍니다.",
                found.plusDays(7).toString(), "350000", "KRW", List.of(ncr), null));
        claims.send(claim, new ClaimDtos.ActionForm(ver("srm_claim", "claim_id", claim), null));
    }

    /** 그 발주로 받은 첫 LOT 에 부적합 — 불량 코드가 없으면 첫 사용 중 불량 */
    private long ncr(long poId, String defectCd, String qty, String desc) {
        String lotNo = jdbc.queryForObject("""
                select l.lot_no from srm_po_receipt r join srm_po_line pl on pl.po_line_id = r.po_line_id
                  join lot_master l on l.lot_id = r.lot_id
                 where pl.po_id = ? and r.lot_id is not null order by pl.line_no, r.receipt_id limit 1""", String.class, poId);
        List<Long> d = jdbc.queryForList("""
                select defect_id from qms_defect where use_yn = 'Y' order by (defect_cd = ?) desc, defect_cd limit 1""",
                Long.class, defectCd);
        return ncrs.create(new NcrDtos.CreateForm(lotNo, "OTHER", null, d.isEmpty() ? null : d.get(0), new BigDecimal(qty), desc));
    }

    // ------------------------------------------------------------------ 평가

    private void template(LocalDate from) {
        List<BandForm> ppm = List.of(new BandForm("0", "100"), new BandForm("500", "80"), new BandForm("2000", "50"),
                new BandForm(null, "0"));
        long id = tmpls.create(new TmplForm("SQ-BASE", "협력사 정기 평가(데모)", from.toString(), null, "데모 — 반기 평가 기본형", null,
                List.of(new ItemForm("납기", "납기 준수율", "100", "30", "60", "AUTO", "OTD_RATE", "입고일 ≤ 요구 납기 비율", null),
                        new ItemForm("납기", "납기 회신율", "100", "15", null, "AUTO", "ACK_RATE", "발행 뒤 기한 안 회신 비율", null),
                        new ItemForm("품질", "입고 PPM", "100", "30", "50", "AUTO", "RECV_PPM", "수입검사 불합격 PPM 구간", ppm),
                        new ItemForm("체계", "인증 유효", "100", "10", null, "AUTO", "CERT_VALID", "평가 끝 날 인증서 유효", null),
                        new ItemForm("협조", "협조도", "100", "15", null, "MANUAL", null, "클레임 대응·자료 제출", null)),
                List.of(new GradeForm("A", "90", "N"), new GradeForm("B", "80", "N"), new GradeForm("C", "60", "N"),
                        new GradeForm("D", "0", "Y"))));
        tmpls.release(id, new EvalDtos.ActionForm(ver("srm_eval_tmpl", "tmpl_id", id), null));
    }

    /** 평가 하나 — 자동 계산이 안 된 항목(근거 없음)은 manual 점수, confirm 이면 확정 */
    private void evaluate(String sup, LocalDate today, String manual, String remark, boolean confirm) {
        long id = evals.create(new EvalForm(sup, "SQ-BASE", today.minusDays(60).toString(), today.toString(), null));
        List<ScoreForm> scores = new ArrayList<>();
        for (Map<String, Object> s : jdbc.queryForList("select item_id, score, auto_yn from srm_eval_score where eval_id = ?", id)) {
            if (s.get("score") == null) {
                scores.add(new ScoreForm(((Number) s.get("item_id")).longValue(), manual, "데모 — 평가자 입력"));
            }
        }
        evals.saveScores(id, new ScoresForm(ver("srm_eval", "eval_id", id), remark, scores));
        if (confirm) {
            evals.confirm(id, new EvalDtos.ActionForm(ver("srm_eval", "eval_id", id), null));
        }
    }

    // ------------------------------------------------------------------ 읽기 도우미

    private static int lead(String sup) {
        return SUPS.stream().filter(s -> s.cd().equals(sup)).findFirst().map(s -> Integer.parseInt(s.items()[0][2])).orElse(7);
    }

    private String contact(String sup) {
        return jdbc.queryForObject("select cust_nm from mst_cust where cust_cd = ?", String.class, sup) + " 영업 담당";
    }

    private List<Map<String, Object>> poLines(long poId) {
        return jdbc.queryForList("""
                select l.po_line_id, l.qty, i.item_cd from srm_po_line l join mst_item i on i.item_id = l.item_id
                 where l.po_id = ? order by l.line_no""", poId);
    }

    private long nodeId(String nodeCd) {
        return id("select node_id from mst_equip_hier where plant_cd = ? and node_cd = ?", PLANT, nodeCd);
    }

    private int poVer(long poId) {
        return ver("srm_po", "po_id", poId);
    }

    private int ver(String table, String key, long id) {
        Integer v = jdbc.queryForObject("select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
        return v == null ? 0 : v;
    }

    private long id(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
