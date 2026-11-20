package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

import io.smarthappy.module.master.equip.AssetDtos.AssetForm;
import io.smarthappy.module.master.equip.AssetService;
import io.smarthappy.module.master.item.ItemDtos.ItemForm;
import io.smarthappy.module.master.item.ItemDtos.ItemRow;
import io.smarthappy.module.master.item.ItemService;
import io.smarthappy.module.production.MesDemoFlow;
import io.smarthappy.module.qms.CapaDtos.CapaForm;
import io.smarthappy.module.qms.CopqDtos.CostForm;
import io.smarthappy.module.qms.GaugeDtos.CalibForm;
import io.smarthappy.module.qms.GaugeDtos.ProfileForm;
import io.smarthappy.module.qms.InspDtos.MeasEntry;
import io.smarthappy.module.qms.InspDtos.MeasForm;
import io.smarthappy.module.qms.NcrDtos.DispLine;
import io.smarthappy.module.qms.NcrDtos.SaveForm;
import io.smarthappy.module.qms.QmsDtos.HeaderForm;
import io.smarthappy.module.qms.QmsDtos.LineItem;
import io.smarthappy.module.qms.QmsDtos.LinesForm;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * QMS 데모 이력 (docs/29_P3 46장, docs/61 10장 "데모") — MES 데모 이력({@link MesDemoFlow})이 만든 LOT 위에 품질 현황판(C2)·SPC 를 채울 기록을
 * 화면과 같은 서비스 길로 넣는다. MES 이력 뒤에 돈다.
 * <ul>
 * <li>기준: 인장 시험기 GG-TNS-01(교정 150일 전), 완성품 출하검사 계획(작성 중 A0 의 육안 샘플링을 S-5 로 마저 쓰고 오늘부터 확정), 표준단가 2품목(비어 있으면)</li>
 * <li>최종검사 30건: 용접 ASSY 완제품 LOT 을 지난 90일에 고르게 — 발생·측정 시각은 그 LOT 의 날({@link InspService#createAt}·{@link InspService#measureAt}),
 *     판정 시각은 지금. 25건째 뒤 인발 강도 X̄-R 고정 한계를 정하고, 그 뒤 2건은 관리 한계 밖(규격 안) → SPC 위반 이벤트(하나는 확인, 하나는 열림).
 *     3건은 육안 NG</li>
 * <li>부적합 18건: 검사 NG 3 + 공정 13 + 기타 2. 발생 시각은 지난 90일({@link NcrService#createAt}), 13건 종결(폐기·재작업·선별), 4건 열림, 1건 취소</li>
 * <li>8D 3건: 고객 클레임(VERIFIED, 결재 정책이 꺼져 있으면 종결)·내부 크랙(ANALYZED)·내부 스패터(OPEN), 발생일은 과거</li>
 * <li>COPQ 수기 비용: 지난 3달 + 이번 달 예방·평가·외부(·재작업 인건비) — 생산 폐기 줄은 MES 실적에서 계산(단가 이력이 지금부터라 지난 달은 "단가 없음")</li>
 * </ul>
 * 최종검사·부적합·8D 가 하나라도 있으면 아무것도 하지 않는다. 판정·종결 시각(judged_at·closed_at)은 DB now() 라 지금 — 발생 쪽만 과거다. 한 트랜잭션.
 */
@Service
@RequiredArgsConstructor
public class QmsDemoFlow {

    static final String GAUGE_CD = "GG-TNS-01";
    static final String FG_ITEM = "64310-DM100";
    static final String PRESS_ITEM = "64311-DM100";
    static final int INSP_N = 30;
    static final int LIMIT_AFTER = 25;

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final PolicyService policies;
    private final AssetService assets;
    private final GaugeCalibService gauges;
    private final ItemService items;
    private final InspPlanService plans;
    private final InspService insps;
    private final SpcService spc;
    private final SpcEventService spcEvents;
    private final NcrService ncrs;
    private final CapaService capas;
    private final CopqService copq;

    /** LOT 하나 — 번호·양·만든 시각 */
    record Lot(long lotId, String lotNo, BigDecimal qty, Instant at) {
    }

    /** 반환 = 만든 최종검사 수(건너뛰면 0) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, () -> run(user)));
    }

    private int run(LoginUser u) {
        List<Lot> fg = lots(FG_ITEM, true);
        List<Lot> press = lots(PRESS_ITEM, false);
        if (fg.size() < INSP_N || press.size() < 10 || used()
                || count("select count(*) from qms_insp_plan where insp_type = 'FINAL' and item_id = (select item_id from mst_item where item_cd = ?)",
                        FG_ITEM) == 0) {
            return 0;
        }
        ZoneId z = clock.zone();
        Random r = new Random(20261104L);
        long gauge = gauge();
        finalPlan();
        prices();

        List<Lot> picked = spread(fg, INSP_N);
        List<String[]> ngInsp = new ArrayList<>();   // {검사 번호, LOT 번호, 발생 시각}
        long charId = jdbc.queryForObject("""
                select ch.char_id from qms_char ch join mst_item i on i.item_id = ch.item_id where i.item_cd = ? and ch.char_no = '1'""",
                Long.class, FG_ITEM);
        long itemId = jdbc.queryForObject("select item_id from mst_item where item_cd = ?", Long.class, FG_ITEM);
        List<Long> spcIds = new ArrayList<>();
        for (int k = 0; k < picked.size(); k++) {
            Lot l = picked.get(k);
            OffsetDateTime at = l.at().plus(40, ChronoUnit.MINUTES).atZone(z).toOffsetDateTime();
            long id = insps.createAt(new InspDtos.CreateForm(l.lotNo(), "FINAL", null, l.qty().setScale(0, RoundingMode.CEILING).longValue(),
                    "데모 이력"), at);
            boolean ng = k == 7 || k == 16 || k == 27;
            // 26·28번째(한계 뒤 2·4번째)는 평균이 내려앉음 — 규격(4.0 kN 이상) 안이지만 관리 한계 밖
            double mean = k == 26 || k == 28 ? 4.62 : 5.30;
            for (Map<String, Object> line : jdbc.queryForList("""
                    select insp_line_id, data_type, gauge_class, sample_size from qms_insp_line where insp_id = ? order by line_no""", id)) {
                long lineId = ((Number) line.get("insp_line_id")).longValue();
                int n = ((Number) line.get("sample_size")).intValue();
                boolean variable = "VARIABLE".equals(line.get("data_type"));
                boolean visual = "VISUAL".equals(line.get("gauge_class"));
                List<MeasEntry> es = new ArrayList<>();
                for (int s = 1; s <= n; s++) {
                    if (variable) {
                        BigDecimal v = BigDecimal.valueOf(mean + r.nextGaussian() * 0.12).setScale(1, RoundingMode.HALF_UP);
                        es.add(new MeasEntry(s, v, null, visual ? null : gauge));
                    } else {
                        es.add(new MeasEntry(s, null, ng && s == 2 ? "N" : "Y", visual ? null : gauge));
                    }
                }
                insps.measureAt(id, new MeasForm(lineId, es), at.plusMinutes(5));
            }
            insps.judge(id, new InspDtos.ActionForm(rv("qms_insp", "insp_id", id), null));
            if (ng) {
                ngInsp.add(new String[] {jdbc.queryForObject("select insp_no from qms_insp where insp_id = ?", String.class, id), l.lotNo(),
                        at.plusHours(1).toString()});
            }
            if (k + 1 == LIMIT_AFTER) {
                spc.createLimit(new SpcDtos.LimitForm(itemId, charId, "FINAL", null, LIMIT_AFTER, null, List.of("WE1", "WE2"),
                        "데모 — 첫 25 부분군으로 고정"));
            }
            if (k >= LIMIT_AFTER) {
                spcIds.addAll(jdbc.queryForList("""
                        select e.event_id from qms_spc_event e join qms_insp_line l on l.insp_line_id = e.insp_line_id
                         where l.insp_id = ? order by e.event_id""", Long.class, id));
            }
        }
        if (!spcIds.isEmpty()) {
            spcEvents.act(spcIds.get(0), new SpcEventDtos.ActForm("ACK", null, null, null, null));
        }

        ncrsAndCapa(u, z, r, ngInsp, fg, press);
        costs();
        return picked.size();
    }

    /** 최종검사·부적합·8D 가 있으면 이미 쓰는 회사 */
    private boolean used() {
        return count("select (select count(*) from qms_insp where insp_type = 'FINAL') + (select count(*) from qms_ncr) + (select count(*) from qms_capa)") > 0;
    }

    /** MES 데모 이력 지시가 만든 LOT(용접 ASSY 는 마지막 공정 양품 = 완제품) — 만든 시각 순 */
    private List<Lot> lots(String itemCd, boolean lastOp) {
        return jdbc.query("""
                select m.lot_id, m.lot_no, m.qty_created, m.occurred_at from lot_master m
                  join prd_wo w on w.wo_id = m.wo_id join mst_item i on i.item_id = m.item_id
                 where i.item_cd = ? and w.remark = ? and m.origin = 'PRODUCED' and m.occurred_at < now() - interval '1 day'
                   and (not ? or exists (select 1 from prd_result r join prd_wo_proc p on p.wo_proc_id = r.wo_proc_id
                                          where r.lot_id = m.lot_id
                                            and p.op_seq = (select max(op_seq) from prd_wo_proc x where x.wo_id = w.wo_id)))
                 order by m.occurred_at, m.lot_id""",
                (rs, i) -> new Lot(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getTimestamp(4).toInstant()),
                itemCd, MesDemoFlow.REMARK, lastOp);
    }

    /** n 개를 고르게(처음·끝 포함) */
    static <T> List<T> spread(List<T> all, int n) {
        List<T> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            out.add(all.get((int) Math.round(k * (all.size() - 1) / (double) (n - 1))));
        }
        return out;
    }

    // ------------------------------------------------------------------ 기준

    /** 인장 시험기 — 없으면 자산 + 계측기 종류 TENSILE + 사내 교정(150일 전, 주기 365) */
    private long gauge() {
        List<Long> found = jdbc.queryForList("select asset_id from mst_phys_asset where asset_cd = ?", Long.class, GAUGE_CD);
        long id = found.isEmpty() ? assets.createAsset(new AssetForm(GAUGE_CD, "GAUGE", "만능 인장 시험기(데모)", "UTM-DEMO-01", null, null, 365,
                null, "Y", "데모 — 용접 너트 인발 강도", null)) : found.get(0);
        if (found.isEmpty()) {
            gauges.updateProfile(id, new ProfileForm("TENSILE", 365, rv("mst_phys_asset", "asset_id", id)));
            gauges.addCalib(id, new CalibForm(clock.today().minusDays(150).toString(), "OK", null, "INTERNAL", null, "CAL-DEMO-T1", 365,
                    "데모 — 사내 교정", null));
        }
        return id;
    }

    /** 완성품 출하검사 계획: 작성 중이면 육안 행 샘플링을 S-5 로(전수 S-ALL 은 LOT 마다 수백 개), 유효 시작 오늘로 고쳐 확정 */
    private void finalPlan() {
        List<Map<String, Object>> draft = jdbc.queryForList("""
                select p.insp_plan_id, p.row_ver, p.rev from qms_insp_plan p join mst_item i on i.item_id = p.item_id
                 where p.insp_type = 'FINAL' and i.item_cd = ? and p.status = 'DRAFT' order by p.insp_plan_id limit 1""", FG_ITEM);
        if (draft.isEmpty()) {
            return;
        }
        long id = ((Number) draft.get(0).get("insp_plan_id")).longValue();
        plans.update(id, new HeaderForm("FINAL", FG_ITEM, null, null, 0, (String) draft.get(0).get("rev"), clock.today().toString(), null,
                "데모 이력 — 샘플링 마저 정하고 확정", null, rv("qms_insp_plan", "insp_plan_id", id)));
        List<LineItem> lines = jdbc.query("""
                select l.line_id, l.line_no, l.char_id, l.method_nm, l.gauge_class, s.plan_cd, l.freq_type, l.freq_n, l.reaction_plan, l.remark
                  from qms_insp_plan_line l join qms_sampling_plan s on s.sampling_plan_id = l.sampling_plan_id
                 where l.insp_plan_id = ? order by l.line_no""",
                (rs, i) -> new LineItem("L" + rs.getLong(1), rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        "S-ALL".equals(rs.getString(6)) ? "S-5" : rs.getString(6), rs.getString(7), (Integer) rs.getObject(8), rs.getString(9),
                        rs.getString(10)), id);
        plans.saveLines(id, rv("qms_insp_plan", "insp_plan_id", id), new LinesForm(lines));
        plans.release(id, new QmsDtos.ActionRequest(rv("qms_insp_plan", "insp_plan_id", id), null, null));
    }

    /** 표준단가가 비어 있는 데모 품목에만(ERP 가 품목을 주는 회사는 건너뜀) — COPQ 금액 */
    private void prices() {
        if (items.erpPull()) {
            return;
        }
        for (String[] p : new String[][] {{FG_ITEM, "18500"}, {PRESS_ITEM, "6200"}}) {
            List<Long> id = jdbc.queryForList("select item_id from mst_item where item_cd = ? and std_price is null", Long.class, p[0]);
            if (id.isEmpty()) {
                continue;
            }
            ItemRow i = items.detail(id.get(0));
            items.update(id.get(0), new ItemForm(i.getItemCd(), i.getItemNm(), i.getSpec(), i.getItemType(), i.getProcType(), i.getUnitCd(),
                    i.getLotCtrlYn(), i.getNetMassKg() == null ? null : i.getNetMassKg().toPlainString(), i.getHsCd(), i.getRemark(),
                    i.getUseYn(), p[1], i.getRowVer()));
        }
    }

    // ------------------------------------------------------------------ 부적합·8D

    /** 공정 부적합: 불량 유형(MES 파레토와 같은 쏠림), 수량 범위, 처분 */
    static final String[][] PROCESS_NCR = {
            {"SPOT-MISS", "FG", "스폿 누락 — 지그 클램프 센서 오감지 구간"}, {"CRACK", "PRESS", "드로잉 코너 R 크랙"},
            {"SPOT-MISS", "FG", "스폿 누락 — 티칭 포인트 이탈"}, {"WRINKLE", "PRESS", "플랜지 주름 — 블랭크 홀더 압력 저하"},
            {"SPATTER", "FG", "스패터 부착 — 팁 드레싱 주기 초과"}, {"CRACK", "PRESS", "드로잉 크랙 — 소재 로트 연신율 하한"},
            {"SPOT-MISS", "FG", "스폿 누락 — 작업자 재작업 누락"}, {"BURR", "PRESS", "트림 버 — 트림 날 마모"},
            {"HOLE-POS", "FG", "너트 위치 편차 — 지그 핀 마모"}, {"WRINKLE", "PRESS", "주름 — 금형 윤활 부족"},
            {"SPATTER", "FG", "스패터 — 냉각수 유량 부족"}, {"SPOT-MISS", "FG", "스폿 누락 — 로봇 경로 알람 뒤 재가동"},
            {"CRACK", "PRESS", "크랙 — 금형 R 마모(8D 연결)"}};

    private void ncrsAndCapa(LoginUser u, ZoneId z, Random r, List<String[]> ngInsp, List<Lot> fg, List<Lot> press) {
        List<long[]> made = new ArrayList<>();   // {ncr_id, 처분 방식 번호}
        for (String[] x : ngInsp) {
            long id = ncrs.createAt(new NcrDtos.CreateForm(x[1], "INSP", x[0], defect("SPOT-MISS"), new BigDecimal(3 + r.nextInt(6)),
                    "출하검사 육안 NG — 스폿 개수 지그 확인에서 누락"), OffsetDateTime.parse(x[2]));
            made.add(new long[] {id, 0});
        }
        for (int k = 0; k < PROCESS_NCR.length; k++) {
            String[] p = PROCESS_NCR[k];
            List<Lot> pool = "FG".equals(p[1]) ? fg : press;
            Lot l = pool.get((int) Math.round((k + 0.5) * (pool.size() - 1) / PROCESS_NCR.length));
            long id = ncrs.createAt(new NcrDtos.CreateForm(l.lotNo(), "PROCESS", null, defect(p[0]), new BigDecimal(10 + r.nextInt(50)),
                    p[2]), l.at().plus(30, ChronoUnit.MINUTES).atZone(z).toOffsetDateTime());
            made.add(new long[] {id, k % 3 + 1});
        }
        for (int k = 0; k < 2; k++) {
            Lot l = press.get(press.size() - 1 - k * 3);
            long id = ncrs.createAt(new NcrDtos.CreateForm(l.lotNo(), "OTHER", null, defect(k == 0 ? "DENT" : "SCRATCH"), new BigDecimal(5 + k * 7),
                    k == 0 ? "지게차 운반 중 찍힘" : "적재 랙 긁힘 — 보호재 누락"), l.at().plus(2, ChronoUnit.HOURS).atZone(z).toOffsetDateTime());
            made.add(new long[] {id, 2});
        }
        // 오래된 것부터 13건 종결, 그다음 1건 취소(중복 등록), 가장 최근 4건 열림(하나는 처분 일부만)
        made.sort((a, b) -> Long.compare(occurred(a[0]), occurred(b[0])));
        for (int k = 0; k < made.size(); k++) {
            long id = made.get(k)[0];
            if (k < 13) {
                dispose(id, (int) made.get(k)[1], true);
                ncrs.close(id, rv("qms_ncr", "ncr_id", id), null);
            } else if (k == 13) {
                ncrs.cancel(id, new NcrDtos.CancelForm(rv("qms_ncr", "ncr_id", id), "같은 건 중복 등록 — 앞 부적합으로 처리"));
            } else if (k == made.size() - 1) {
                dispose(id, 1, false);
            }
        }
        capas(u, made);
    }

    /** 처분: 0 = 폐기, 1 = 선별 후 일부 폐기, 2 = 재작업, 3 = 재작업 + 폐기. whole = 수량 합까지(아니면 절반만 — 아직 정하는 중) */
    private void dispose(long id, int kind, boolean whole) {
        Map<String, Object> n = jdbc.queryForMap("select defect_id, nc_qty, description, row_ver from qms_ncr where ncr_id = ?", id);
        BigDecimal q = (BigDecimal) n.get("nc_qty");
        BigDecimal part = q.divide(BigDecimal.valueOf(3), 0, RoundingMode.DOWN).max(BigDecimal.ONE).min(q);
        List<DispLine> ds = new ArrayList<>();
        if (!whole) {
            ds.add(new DispLine("d1", "SORT", part, "선별 중", null));
        } else if (kind == 0 || part.compareTo(q) >= 0) {
            ds.add(new DispLine("d1", "SCRAP", q, "전량 폐기", null));
        } else if (kind == 1) {
            ds.add(new DispLine("d1", "SORT", q.subtract(part), "선별 합격분 사용", null));
            ds.add(new DispLine("d2", "SCRAP", part, "선별 불합격 폐기", null));
        } else if (kind == 2) {
            ds.add(new DispLine("d1", "REWORK", q, "재용접·사상 후 재검", null));
        } else {
            ds.add(new DispLine("d1", "REWORK", q.subtract(part), "재작업", null));
            ds.add(new DispLine("d2", "SCRAP", part, "재작업 불가분 폐기", null));
        }
        ncrs.save(id, new SaveForm(((Number) n.get("row_ver")).intValue(), ((Number) n.get("defect_id")).longValue(), q,
                (String) n.get("description"), ds));
    }

    private void capas(LoginUser u, List<long[]> made) {
        LocalDate today = clock.today();
        String claimNcr = jdbc.queryForObject("select ncr_no from qms_ncr where ncr_id = ?", String.class, made.get(0)[0]);
        long a = capas.create(new CapaForm("CUSTOMER_CLAIM", "S001", FG_ITEM, "고객 라인 스폿 누락 유출 2건(데모)", today.minusDays(45).toString(),
                u.userId(), "품질·생산·보전 3인", "고객 차체 라인에서 프런트 사이드 멤버 스폿 누락 2개 발견(18점 중 16점)",
                "재고·운송 중 전수 선별 1,240개 — 추가 1개 발견, 격리", "지그 클램프 센서 오감지로 로봇이 2점 건너뜀 + 출하검사 육안 누락",
                "센서 교체·감지 거리 표준화, 스폿 개수 카운터 인터록 추가", "4주 출하분 누락 0건, 카운터 이력 확인",
                "용접 셀 전체 같은 센서 수평 전개, PFMEA·관리계획서 개정", null, List.of(), claimNcr, null));
        for (int k = 0; k < 4; k++) {
            capas.advance(a, new DocDtos.ActionForm(rv("qms_capa", "capa_id", a)));
        }
        if (!policies.bool(Policies.QMS_DOC_APPROVAL)) {
            capas.update(a, capaForm(a, "효과 확인 끝 — 종결(데모)"));
            capas.close(a, new DocDtos.ActionForm(rv("qms_capa", "capa_id", a)));
        }
        long b = capas.create(new CapaForm("INTERNAL", null, PRESS_ITEM, "드로잉 크랙 반복(데모)", today.minusDays(20).toString(), u.userId(),
                "생산기술·금형", "사이드 멤버 INR 드로잉 코너 크랙 주 2~3회", "해당 금형 생산분 전수 확인, 의심 LOT 보류",
                "금형 코너 R 마모 0.4 mm — 수리 주기 초과", null, null, null, null, List.of(), null, null));
        capas.advance(b, new DocDtos.ActionForm(rv("qms_capa", "capa_id", b)));
        capas.advance(b, new DocDtos.ActionForm(rv("qms_capa", "capa_id", b)));
        capas.create(new CapaForm("INTERNAL", null, FG_ITEM, "스패터 부착 증가(데모)", today.minusDays(4).toString(), u.userId(), "생산·보전",
                "스폿 2 셀 스패터 부착 불량 주간 2배", null, null, null, null, null, null, List.of(), null, null));
    }

    /** 지금 행 그대로 + D8 만 채운 수정 폼 */
    private CapaForm capaForm(long id, String d8) {
        Map<String, Object> c = jdbc.queryForMap("""
                select c.source, (select cust_cd from mst_cust where cust_id = c.cust_id) as cust_cd,
                       (select item_cd from mst_item where item_id = c.item_id) as item_cd, c.title, c.occurred_on::text as occurred_on,
                       c.owner_user_id, c.d1_team, c.d2_problem, c.d3_contain, c.d4_cause, c.d5_action, c.d6_verify, c.d7_prevent,
                       (select ncr_no from qms_ncr where ncr_id = c.ncr_id) as ncr_no, c.row_ver
                  from qms_capa c where c.capa_id = ?""", id);
        return new CapaForm((String) c.get("source"), (String) c.get("cust_cd"), (String) c.get("item_cd"), (String) c.get("title"),
                (String) c.get("occurred_on"), ((Number) c.get("owner_user_id")).longValue(), (String) c.get("d1_team"), (String) c.get("d2_problem"),
                (String) c.get("d3_contain"), (String) c.get("d4_cause"), (String) c.get("d5_action"), (String) c.get("d6_verify"),
                (String) c.get("d7_prevent"), d8, List.of(), (String) c.get("ncr_no"), ((Number) c.get("row_ver")).intValue());
    }

    // ------------------------------------------------------------------ COPQ

    /** 지난 3달 + 이번 달 수기 비용: 예방·평가 매달, 외부(클레임 선별·운송)는 8D 달, 재작업 인건비 */
    private void costs() {
        YearMonth now = YearMonth.from(clock.today());
        YearMonth claim = YearMonth.from(clock.today().minusDays(45));
        for (int m = 3; m >= 0; m--) {
            YearMonth ym = now.minusMonths(m);
            String mm = ym.toString();
            copq.createCost(new CostForm(mm, "PREVENTION", "PFMEA·관리계획서 검토 회의", String.valueOf(280000 + m * 20000), "데모", null));
            copq.createCost(new CostForm(mm, "APPRAISAL", "출하검사 인건비", String.valueOf(1850000 - m * 50000), "데모", null));
            copq.createCost(new CostForm(mm, "INTERNAL", "재작업 인건비(재용접·사상)", String.valueOf(420000 + (m % 2) * 160000), "데모", null));
            if (m == 2) {
                copq.createCost(new CostForm(mm, "APPRAISAL", "계측기 외부 교정", "260000", "데모", null));
            }
            if (ym.equals(claim)) {
                copq.createCost(new CostForm(mm, "EXTERNAL", "고객 클레임 선별(1,240개)", "1240000", "데모 — 8D 연결", null));
                copq.createCost(new CostForm(mm, "EXTERNAL", "긴급 대체 납품 운송", "185000", "데모", null));
            }
        }
    }

    // ------------------------------------------------------------------ 도우미

    private long defect(String cd) {
        List<Long> id = jdbc.queryForList("select defect_id from qms_defect where defect_cd = ? and use_yn = 'Y'", Long.class, cd);
        return id.isEmpty() ? jdbc.queryForObject("select min(defect_id) from qms_defect where use_yn = 'Y'", Long.class) : id.get(0);
    }

    private long occurred(long ncrId) {
        return jdbc.queryForObject("select occurred_at from qms_ncr where ncr_id = ?", java.sql.Timestamp.class, ncrId).getTime();
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
