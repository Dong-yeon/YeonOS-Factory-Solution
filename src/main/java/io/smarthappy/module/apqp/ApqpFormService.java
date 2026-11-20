package io.smarthappy.module.apqp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.ActionRow;
import io.smarthappy.module.apqp.ApqpDtos.ChainRow;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaDetail;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.CpDtos.CpDetail;
import io.smarthappy.module.apqp.CpDtos.CpLineRow;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * PFMEA·관리계획서 엑셀 양식 (A4, docs/41 12장 X1~X8). 상세 자료(FmeaDetail·CpDetail)를 **우리 배치**로 다시 놓는다 —
 * 핸드북 양식의 칸 순서·문구·색은 쓰지 않는다(X2). id 로 그 리비전 그대로, 확정본이 아니면 제목·파일명·바닥글에 표시(X3).
 * 병합 없음(필터·정렬 되게), 공정 단계가 바뀌는 행에 굵은 위 테두리, A3 가로·폭 1장·머리 행 반복(X7). 글자 칸은 수식 주입 방지(safeText).
 * 결과 = 바이트(작은 문서 — 상한 X6) — A3-3(QMS 문서 배포)에서 파일로 저장할 때 그대로 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ApqpFormService {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FmeaService fmeas;
    private final CpService cps;
    private final ApqpFlowService flows;
    private final CodeService codes;
    private final PolicyService policies;
    private final MessageSource messages;
    private final BizClock clock;

    /** 내려받을 파일 — 이름은 `번호_rev n[_상태].xlsx` */
    public record Form(String fileName, byte[] content) {
    }

    // ------------------------------------------------------------------ PFMEA

    @Transactional(readOnly = true)
    public Form fmea(long fmeaId) throws IOException {
        FmeaDetail d = fmeas.get(fmeaId);
        FmeaRow f = d.fmea();
        List<RiskLine> risks = riskLines(d);
        limit(risks.size() + d.actions().size());
        Locale lo = LocaleContextHolder.getLocale();
        Map<String, String> m4 = names("APQP_M4", lo);
        Map<String, String> special = names("SPECIAL_CHAR", lo);
        Map<String, String> actionStatus = names("APQP_ACTION_STATUS", lo);
        String statusNm = names("APQP_FMEA_STATUS", lo).getOrDefault(f.getStatus(), f.getStatus());
        String no = f.getFmeaNo() + " rev " + f.getRev();
        // DFMEA(A5-2) — 같은 양식, 머리(BOM·도면 문서)와 구조 수준 글자만 다르고 공정 흐름 시트는 없다
        boolean df = FmeaService.DFMEA.equals(f.getFmeaType());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet(t("apqp.form.sheet.risk", lo));
            int r = title(sh, st, t(df ? "apqp.form.dfmea.title" : "apqp.form.fmea.title", lo), f.getStatus(), statusNm, lo);
            r = info(sh, st, r, lo, new String[][] {
                    {"apqp.form.no", no}, {"apqp.form.fmeaTitle", f.getTitle()},
                    {"apqp.form.item", join(f.getItemCd(), f.getItemNm())},
                    df ? new String[] {"apqp.form.bom", f.getBomText()} : new String[] {"apqp.form.routing", f.getRoutingText()},
                    {"apqp.form.project", f.getProjNo()}, {"apqp.form.team", f.getTeam()}, {"apqp.form.scope", f.getScope()},
                    df ? new String[] {"apqp.form.drawingDoc", f.getDocRevText()} : new String[] {"apqp.form.drawingRev", f.getDrawingRev()},
                    {"apqp.form.apSet", f.getApSetCd()},
                    {"apqp.form.status", statusNm}, {"apqp.form.releasedAt", day(f.getReleasedAt())}});
            String[] cols = {"apqp.form.col.no", df ? "apqp.form.col.subsystem" : "apqp.form.col.step",
                    df ? "apqp.form.col.subsystemFunc" : "apqp.form.col.stepFunc", "apqp.form.col.fe", "apqp.form.col.sev",
                    "apqp.form.col.fm", df ? "apqp.form.col.component" : "apqp.form.col.element", "apqp.form.col.fc",
                    "apqp.form.col.prevCtrl", "apqp.form.col.occ",
                    "apqp.form.col.detCtrl", "apqp.form.col.det", "apqp.form.col.ap", "apqp.form.col.special"};
            int head = r;
            header(sh.createRow(r++), st, cols, lo);
            Long lastStep = null;
            for (int i = 0; i < risks.size(); i++) {
                RiskLine x = risks.get(i);
                boolean top = lastStep != null && !Objects.equals(lastStep, x.stepNodeId);
                lastStep = x.stepNodeId;
                Row row = sh.createRow(r++);
                int c = 0;
                num(row, c++, i + 1, st.body(top));
                text(row, c++, x.step, st.body(top));
                text(row, c++, x.stepFunc, st.body(top));
                text(row, c++, x.fe, st.body(top));
                num(row, c++, x.risk.getSev(), st.body(top));
                text(row, c++, x.fm, st.body(top));
                text(row, c++, x.element == null ? "" : x.element + (x.m4 == null ? "" : " (" + m4.getOrDefault(x.m4, x.m4) + ")"), st.body(top));
                text(row, c++, x.fc, st.body(top));
                text(row, c++, x.risk.getPrevCtrl(), st.body(top));
                num(row, c++, x.risk.getOcc(), st.body(top));
                text(row, c++, x.risk.getDetCtrl(), st.body(top));
                num(row, c++, x.risk.getDet(), st.body(top));
                text(row, c++, x.risk.getAp(), st.ap(x.risk.getAp(), top));
                text(row, c, special(special, x.risk.getSpecialCls()), st.body(top));
            }
            widths(sh, 5, 16, 24, 24, 5, 24, 20, 24, 24, 5, 24, 5, 6, 10);
            print(sh, head, no, f.getStatus(), statusNm, lo);

            Sheet ac = wb.createSheet(t("apqp.form.sheet.action", lo));
            int a = title(ac, st, t("apqp.form.fmea.actions", lo) + " — " + no, f.getStatus(), statusNm, lo);
            String[] acols = {"apqp.form.col.riskNo", "apqp.form.col.actionKind", "apqp.form.col.action", "apqp.form.col.owner",
                    "apqp.form.col.due", "apqp.form.col.actionStatus", "apqp.form.col.doneOn", "apqp.form.col.evidence",
                    "apqp.form.col.newOcc", "apqp.form.col.newDet", "apqp.form.col.newAp"};
            int ahead = a;
            header(ac.createRow(a++), st, acols, lo);
            Map<Long, Integer> riskNo = new HashMap<>();
            for (int i = 0; i < risks.size(); i++) {
                riskNo.put(risks.get(i).risk.getRiskId(), i + 1);
            }
            List<ActionRow> actions = d.actions().stream()
                    .sorted(Comparator.comparing((ActionRow x) -> riskNo.getOrDefault(x.getRiskId(), Integer.MAX_VALUE))
                            .thenComparingLong(ActionRow::getActionId))
                    .toList();
            for (ActionRow x : actions) {
                Row row = ac.createRow(a++);
                int c = 0;
                Integer rn = riskNo.get(x.getRiskId());
                num(row, c++, rn, st.body(false));
                text(row, c++, t("js.apqp.action." + x.getActionKind(), lo), st.body(false));
                text(row, c++, x.getActionText(), st.body(false));
                text(row, c++, x.getOwnerNm(), st.body(false));
                text(row, c++, x.getDueDate() == null ? "" : x.getDueDate().toString(), st.body(false));
                text(row, c++, actionStatus.getOrDefault(x.getStatus(), x.getStatus()), st.body(false));
                text(row, c++, x.getDoneOn() == null ? "" : x.getDoneOn().toString(), st.body(false));
                text(row, c++, x.getEvidence(), st.body(false));
                num(row, c++, x.getNewOcc(), st.body(false));
                num(row, c++, x.getNewDet(), st.body(false));
                text(row, c, x.getNewAp(), st.ap(x.getNewAp(), false));
            }
            widths(ac, 6, 8, 40, 14, 11, 10, 11, 30, 6, 6, 6);
            print(ac, ahead, no, f.getStatus(), statusNm, lo);
            if (!df) {
                flowSheet(wb, st, flows.flow(fmeaId), special, no, statusNm, lo);
            }
            return new Form(fileName(f.getFmeaNo(), f.getRev(), f.getStatus()), bytes(wb));
        }
    }

    /**
     * "공정 흐름" 시트(A5-1 D3) — 화면과 같은 FlowView. 단계 한 행: op·공정·기호(우리 글자, 표준 기호 그림 아님)·표준 CT·특성(줄바꿈,
     * 특별특성 표시)·관리계획서 행·위험행·AP 높음. 관리계획서는 볼 권한이 있을 때만 이어 붙인다(화면과 같은 규칙)
     */
    private void flowSheet(XSSFWorkbook wb, Styles st, ApqpDtos.FlowView v, Map<String, String> special, String no, String statusNm,
            Locale lo) {
        Sheet fs = wb.createSheet(t("apqp.form.sheet.flow", lo));
        int r = title(fs, st, t("apqp.form.flow.title", lo) + " — " + no, v.status(), statusNm, lo);
        String cp = v.cpId() == null ? (v.cpHidden() ? t("apqp.form.flow.cpHidden", lo) : t("apqp.form.flow.cpNone", lo))
                : v.cpNo() + " rev " + v.cpRev();
        r = info(fs, st, r, lo, new String[][] {{"apqp.form.item", join(v.itemCd(), v.itemNm())},
                {"apqp.form.routing", v.routingText()}, {"apqp.form.flow.cp", cp}});
        String[] cols = {"apqp.form.col.op", "apqp.form.col.proc", "apqp.form.col.symbol", "apqp.form.col.ct", "apqp.form.col.chars",
                "apqp.form.col.cpLines", "apqp.form.col.risks", "apqp.form.col.high"};
        int head = r;
        header(fs.createRow(r++), st, cols, lo);
        for (ApqpDtos.FlowStep s : v.steps()) {
            Row row = fs.createRow(r++);
            int c = 0;
            num(row, c++, s.opSeq(), st.body(false));
            text(row, c++, join(s.procCd(), s.procNm()), st.body(false));
            text(row, c++, t("apqp.flow.sym." + s.symbol(), lo), st.body(false));
            text(row, c++, s.stdCtSec() == null ? "" : s.stdCtSec().stripTrailingZeros().toPlainString(), st.body(false));
            text(row, c++, s.chars().stream().map(x -> x.charNo() + " " + x.charNm()
                    + (x.special() ? " [" + special(special, x.specialCls()) + "]" : "")).collect(Collectors.joining("\n")), st.body(false));
            num(row, c++, v.cpId() == null ? null : s.cpLineCnt(), st.body(false));
            num(row, c++, s.riskCnt(), st.body(false));
            num(row, c, s.highCnt(), st.ap(s.highCnt() > 0 ? "H" : null, false));
        }
        if (v.unplaced() > 0) {
            text(fs.createRow(r + 1), 0, t("apqp.form.flow.unplaced", lo, v.unplaced()), null);
        }
        widths(fs, 6, 24, 10, 8, 40, 8, 8, 8);
        print(fs, head, no, v.status(), statusNm, lo);
    }

    /** 위험행 한 줄 — FM(공정 단계 기능의 고장) × FC(작업요소 기능의 고장), FE 는 사슬로 이은 것 전부 */
    private record RiskLine(RiskRow risk, Long stepNodeId, String step, String stepFunc, String fe, String fm, String element, String m4,
            String fc, int[] order) {
    }

    /** 순서 = 구조 트리 순(단계) → FM 기능·고장 순 → FC 순 */
    private static List<RiskLine> riskLines(FmeaDetail d) {
        Map<Long, Integer> nodeOrder = new HashMap<>();
        for (int i = 0; i < d.nodes().size(); i++) {
            nodeOrder.put(d.nodes().get(i).getNodeId(), i);
        }
        Map<Long, NodeRow> nodes = d.nodes().stream().collect(Collectors.toMap(NodeRow::getNodeId, Function.identity()));
        Map<Long, FuncRow> funcs = d.funcs().stream().collect(Collectors.toMap(FuncRow::getFuncId, Function.identity()));
        Map<Long, FailRow> fails = d.fails().stream().collect(Collectors.toMap(FailRow::getFailId, Function.identity()));
        Map<Long, List<Long>> feOf = d.chains().stream()
                .collect(Collectors.groupingBy(ChainRow::getFmId, Collectors.mapping(ChainRow::getFeId, Collectors.toList())));
        List<RiskLine> out = new ArrayList<>();
        for (RiskRow r : d.risks()) {
            FailRow fm = fails.get(r.getFmId());
            FailRow fc = fails.get(r.getFcId());
            FuncRow fmFunc = fm == null ? null : funcs.get(fm.getFuncId());
            FuncRow fcFunc = fc == null ? null : funcs.get(fc.getFuncId());
            NodeRow step = fmFunc == null ? null : nodes.get(fmFunc.getNodeId());
            NodeRow el = fcFunc == null ? null : nodes.get(fcFunc.getNodeId());
            String fe = feOf.getOrDefault(r.getFmId(), List.of()).stream().map(fails::get).filter(Objects::nonNull)
                    .map(x -> x.getFailText() + (x.getSeverity() == null ? "" : " (S" + x.getSeverity() + ")"))
                    .collect(Collectors.joining("\n"));
            String stepFunc = fmFunc == null ? "" : fmFunc.getFuncText()
                    + (fmFunc.getCharId() == null ? "" : "\n[" + fmFunc.getCharNo() + " " + fmFunc.getCharNm() + "]");
            int[] order = {step == null ? Integer.MAX_VALUE : nodeOrder.getOrDefault(step.getNodeId(), Integer.MAX_VALUE),
                    fmFunc == null ? 0 : fmFunc.getSeq(), fm == null ? 0 : fm.getSeq(),
                    el == null ? Integer.MAX_VALUE : nodeOrder.getOrDefault(el.getNodeId(), Integer.MAX_VALUE), fc == null ? 0 : fc.getSeq()};
            out.add(new RiskLine(r, step == null ? null : step.getNodeId(), stepLabel(step), stepFunc, fe, fm == null ? "" : fm.getFailText(),
                    el == null ? null : el.getNodeNm(), el == null ? null : el.getM4(), fc == null ? "" : fc.getFailText(), order));
        }
        out.sort((x, y) -> {
            for (int i = 0; i < x.order.length; i++) {
                int c = Integer.compare(x.order[i], y.order[i]);
                if (c != 0) {
                    return c;
                }
            }
            return Long.compare(x.risk.getRiskId(), y.risk.getRiskId());
        });
        return out;
    }

    private static String stepLabel(NodeRow n) {
        if (n == null) {
            return "";
        }
        return n.getOpSeq() == null ? n.getNodeNm() : n.getOpSeq() + " " + (n.getProcNm() == null ? n.getNodeNm() : n.getProcNm());
    }

    // ------------------------------------------------------------------ 관리계획서

    @Transactional(readOnly = true)
    public Form cp(long cpId) throws IOException {
        CpDetail d = cps.get(cpId);
        CpRow c = d.cp();
        limit(d.lines().size());
        Locale lo = LocaleContextHolder.getLocale();
        Map<String, String> special = names("SPECIAL_CHAR", lo);
        Map<String, String> method = names("APQP_CTRL_METHOD", lo);
        Map<String, String> gauge = names("GAUGE_CLASS", lo);
        Map<String, String> freq = names("INSP_FREQ", lo);
        String statusNm = names("APQP_FMEA_STATUS", lo).getOrDefault(c.getStatus(), c.getStatus());
        String phaseNm = names("APQP_CP_PHASE", lo).getOrDefault(c.getPhase(), c.getPhase());
        String no = c.getCpNo() + " rev " + c.getRev();
        // 작성 중이면 특성 마스터의 지금 값, 그 뒤는 확정 때 찍은 스냅샷(화면과 같은 규칙)
        boolean snap = !"DRAFT".equals(c.getStatus());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet(t("apqp.form.sheet.cp", lo));
            int r = title(sh, st, t("apqp.form.cp.title", lo) + " — " + phaseNm, c.getStatus(), statusNm, lo);
            r = info(sh, st, r, lo, new String[][] {
                    {"apqp.form.no", no}, {"apqp.form.phase", phaseNm}, {"apqp.form.fmeaTitle", c.getTitle()},
                    {"apqp.form.item", join(c.getItemCd(), c.getItemNm())}, {"apqp.form.routing", c.getRoutingText()},
                    {"apqp.form.basisFmea", c.getFmeaNo() == null ? null : c.getFmeaNo() + " rev " + c.getFmeaRev()},
                    {"apqp.form.project", c.getProjNo()}, {"apqp.form.team", c.getTeam()}, {"apqp.form.changeReason", c.getChangeReason()},
                    {"apqp.form.status", statusNm}, {"apqp.form.releasedAt", day(c.getReleasedAt())}});
            String[] cols = {"apqp.form.col.no", "apqp.form.col.step", "apqp.form.col.char", "apqp.form.col.special", "apqp.form.col.spec",
                    "apqp.form.col.ctrlMethod", "apqp.form.col.evalMethod", "apqp.form.col.gauge", "apqp.form.col.sample",
                    "apqp.form.col.freq", "apqp.form.col.reaction", "apqp.form.col.riskAp"};
            int head = r;
            header(sh.createRow(r++), st, cols, lo);
            String lastStep = null;
            for (CpLineRow l : d.lines()) {
                String step = l.getStepId() != null ? l.getOpSeq() + " " + nz(l.getProcNm()) : nz(l.getStepText());
                boolean top = lastStep != null && !lastStep.equals(step);
                lastStep = step;
                Row row = sh.createRow(r++);
                int k = 0;
                num(row, k++, l.getLineNo(), st.body(top));
                text(row, k++, step, st.body(top));
                text(row, k++, l.getCharId() != null ? l.getCharNo() + " " + l.getCharNm() : l.getCharText(), st.body(top));
                String sp = snap ? l.getSpecialCls() : (l.getSpecialCls() != null ? l.getSpecialCls() : l.getCharSpecial());
                text(row, k++, special(special, sp), st.body(top));
                text(row, k++, spec(snap ? l.getLsl() : l.getCharLsl(), snap ? l.getUsl() : l.getCharUsl(),
                        snap ? l.getUnitNm() : l.getCharUnit()), st.body(top));
                text(row, k++, method.getOrDefault(l.getCtrlMethod(), l.getCtrlMethod()), st.body(top));
                text(row, k++, l.getEvalMethod(), st.body(top));
                text(row, k++, l.getGaugeClass() == null ? "" : gauge.getOrDefault(l.getGaugeClass(), l.getGaugeClass()), st.body(top));
                text(row, k++, join(l.getSampleN() == null ? null : String.valueOf(l.getSampleN()), l.getSampleText()), st.body(top));
                String fq = l.getFreqType() == null ? "" : freq.getOrDefault(l.getFreqType(), l.getFreqType());
                text(row, k++, l.getFreqN() == null ? fq : fq.replace("N", String.valueOf(l.getFreqN())), st.body(top));
                text(row, k++, l.getReactionPlan(), st.body(top));
                text(row, k, l.getRiskAp(), st.ap(l.getRiskAp(), top));
            }
            widths(sh, 5, 18, 26, 10, 18, 14, 22, 12, 14, 14, 30, 6);
            print(sh, head, no, c.getStatus(), statusNm, lo);
            return new Form(fileName(c.getCpNo(), c.getRev(), c.getStatus()), bytes(wb));
        }
    }

    // ------------------------------------------------------------------ 공통

    /** X6 — 공통 엑셀 행 상한 */
    private void limit(long rows) {
        int max = policies.intValue(Policies.EXCEL_EXPORT_MAX_ROWS);
        if (rows > max) {
            AccessLogService.setDetail("rejected=TOO_MANY_ROWS;rows=" + rows);
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, rows, max);
        }
    }

    /** 제목 + (확정본이 아니면) 표시 줄 → 다음 행 번호 */
    private int title(Sheet sh, Styles st, String title, String status, String statusNm, Locale lo) {
        text(sh.createRow(0), 0, title, st.title);
        int r = 1;
        if (!"RELEASED".equals(status)) {
            text(sh.createRow(r++), 0, t("apqp.form.notReleased", lo, statusNm), st.warn);
        }
        text(sh.createRow(r++), 0, t("apqp.form.printedAt", lo, clock.now().format(AT)), st.muted);
        return r + 1;
    }

    private int info(Sheet sh, Styles st, int r, Locale lo, String[][] kv) {
        for (String[] e : kv) {
            Row row = sh.createRow(r++);
            text(row, 0, t(e[0], lo), st.label);
            text(row, 1, e[1], null);
        }
        return r + 1;
    }

    private void header(Row row, Styles st, String[] keys, Locale lo) {
        for (int i = 0; i < keys.length; i++) {
            text(row, i, t(keys[i], lo), st.head);
        }
    }

    /** X7 — A3 가로·폭 1장·머리 행 반복·틀 고정·바닥글(번호 rev · 상태 · 쪽) */
    private void print(Sheet sh, int headRow, String no, String status, String statusNm, Locale lo) {
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(true);
        ps.setPaperSize(PrintSetup.A3_PAPERSIZE);
        sh.setFitToPage(true);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 0);
        sh.setRepeatingRows(new CellRangeAddress(headRow, headRow, -1, -1));
        sh.createFreezePane(0, headRow + 1);
        sh.setAutobreaks(true);
        String foot = no + ("RELEASED".equals(status) ? "" : " — " + t("apqp.form.notReleased", lo, statusNm));
        sh.getFooter().setLeft(foot.replace("&", "&&"));
        sh.getFooter().setRight("&P / &N");
    }

    private static void widths(Sheet sh, int... w) {
        for (int i = 0; i < w.length; i++) {
            sh.setColumnWidth(i, w[i] * 256);
        }
    }

    private Map<String, String> names(String grp, Locale lo) {
        return codes.codes(grp, lo).stream().collect(Collectors.toMap(CodeService.CodeItem::code, CodeService.CodeItem::name, (a, b) -> a));
    }

    private String t(String key, Locale lo, Object... args) {
        return messages.getMessage(key, args, key, lo);
    }

    private String day(OffsetDateTime at) {
        return at == null ? "" : at.atZoneSameInstant(clock.zone()).toLocalDate().toString();
    }

    private static String special(Map<String, String> names, String code) {
        return code == null ? "" : names.getOrDefault(code, code);
    }

    private static String spec(BigDecimal lsl, BigDecimal usl, String unit) {
        if (lsl == null && usl == null) {
            return "";
        }
        return plain(lsl) + " ~ " + plain(usl) + (unit == null ? "" : " " + unit);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String join(String a, String b) {
        return List.of(nz(a), nz(b)).stream().filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 파일명 — 확정본이 아니면 상태 코드를 붙인다(X3) */
    static String fileName(String no, int rev, String status) {
        return no + "_rev" + rev + ("RELEASED".equals(status) ? "" : "_" + status) + ".xlsx";
    }

    private static byte[] bytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static void text(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(ExcelExportService.safeText(v == null ? "" : v));
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static void num(Row row, int col, Integer v, CellStyle style) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v);
        }
        c.setCellStyle(style);
    }

    /** 칸 모양 — 본문은 줄바꿈·위쪽 정렬·얇은 테두리, 단계가 바뀌는 행은 굵은 위 테두리. AP 는 H 빨강·M 노랑(우리 색, X8) */
    private static final class Styles {
        final CellStyle title;
        final CellStyle warn;
        final CellStyle muted;
        final CellStyle label;
        final CellStyle head;
        private final CellStyle body;
        private final CellStyle bodyTop;
        private final Map<String, CellStyle> ap = new HashMap<>();

        Styles(XSSFWorkbook wb) {
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 16);
            title = wb.createCellStyle();
            title.setFont(big);
            Font red = wb.createFont();
            red.setBold(true);
            red.setColor(IndexedColors.DARK_RED.getIndex());
            warn = wb.createCellStyle();
            warn.setFont(red);
            Font grey = wb.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            muted = wb.createCellStyle();
            muted.setFont(grey);
            Font bold = wb.createFont();
            bold.setBold(true);
            label = wb.createCellStyle();
            label.setFont(bold);
            head = wb.createCellStyle();
            head.setFont(bold);
            head.setWrapText(true);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            borders(head, BorderStyle.THIN);
            body = bodyStyle(wb, false);
            bodyTop = bodyStyle(wb, true);
            for (boolean top : new boolean[] {false, true}) {
                for (String[] e : new String[][] {{"H", "ROSE"}, {"M", "LIGHT_YELLOW"}}) {
                    CellStyle s = bodyStyle(wb, top);
                    s.setAlignment(HorizontalAlignment.CENTER);
                    s.setFillForegroundColor(IndexedColors.valueOf(e[1]).getIndex());
                    s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    ap.put(e[0] + top, s);
                }
            }
        }

        CellStyle body(boolean top) {
            return top ? bodyTop : body;
        }

        CellStyle ap(String v, boolean top) {
            return v == null ? body(top) : ap.getOrDefault(v + top, body(top));
        }

        private static CellStyle bodyStyle(XSSFWorkbook wb, boolean top) {
            CellStyle s = wb.createCellStyle();
            s.setWrapText(true);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            borders(s, BorderStyle.THIN);
            if (top) {
                s.setBorderTop(BorderStyle.MEDIUM);
            }
            return s;
        }

        private static void borders(CellStyle s, BorderStyle b) {
            s.setBorderTop(b);
            s.setBorderBottom(b);
            s.setBorderLeft(b);
            s.setBorderRight(b);
        }
    }
}
