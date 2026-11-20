package io.smarthappy.module.qms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.CopqDtos.CopqView;
import io.smarthappy.module.qms.CopqDtos.CostRow;
import io.smarthappy.module.qms.CopqDtos.ExportForm;
import io.smarthappy.module.qms.CopqDtos.ExportRow;
import io.smarthappy.module.qms.CopqDtos.LineRow;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * COPQ 엑셀(docs/29_P3 34장) — 조회(CopqService.summary)와 같은 달 범위·같은 계산으로 원장 줄과 수동 행을 한 시트에 쓴다.
 * 플랫폼 엑셀은 시트 하나라 월별 합계 시트는 따로 두지 않는다(구분·달 열로 피벗하면 화면 합계와 같다 — 금액 빈칸은 0원이 아님).
 * 순서 = 달 → 원장 먼저 → 구분(예방·평가·내부·외부) → 출처 → 품목·항목
 */
@Service
@RequiredArgsConstructor
public class CopqExportService {

    static final String LEDGER = "LEDGER";
    static final String MANUAL = "MANUAL";
    static final String[] SOURCES = {"NCR_SCRAP", "NCR_REWORK", "NCR_SORT", "RTN_SCRAP", "RTN_REWORK", "RTN_SORT", "PRD_SCRAP", "PRD_REWORK",
        "PRD_REPAIR", "PRD_RW_SCRAP"};

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.labels("kind", "qms.copq.col.kind", "qms.copq.kind.", 70, LEDGER, MANUAL),
            ColumnSpec.text("month", "qms.copq.col.month", 80),
            ColumnSpec.code("category", "qms.copq.col.category", "QMS_COPQ_CAT", 90),
            ColumnSpec.labels("source", "qms.copq.col.source", "qms.copq.src.", 130, SOURCES),
            ColumnSpec.text("itemCd", "qms.copq.col.itemCd", 120),
            ColumnSpec.text("itemNm", "qms.copq.col.itemNm", 180),
            ColumnSpec.dec("qty", "qms.copq.col.qty", 6, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "qms.copq.col.unit", 60),
            ColumnSpec.dec("stdPrice", "qms.copq.col.price", 6, 100).withMinScale(0),
            ColumnSpec.of("lineCnt", "qms.copq.col.lineCnt", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.dec("amount", "qms.copq.col.amount", 2, 120),
            ColumnSpec.labels("reason", "qms.copq.col.reason", "qms.copq.reason.", 160, "NO_PRICE", "UNIT"),
            ColumnSpec.text("ncrNos", "qms.copq.col.ncrNos", 200),
            ColumnSpec.text("costItem", "qms.copq.col.costItem", 160),
            ColumnSpec.text("note", "qms.copq.col.note", 200));

    private final CopqService copq;
    private final ExcelExportService excel;

    @Transactional(readOnly = true)
    public long export(ExportForm in, HttpServletResponse response) throws IOException {
        CopqView v = copq.summary(in == null ? null : in.fromMonth(), in == null ? null : in.toMonth());
        List<ExportRow> rows = rows(v);
        return excel.export(response, new ExportJob(CopqService.PROG, "prog.qmsCopq", COLUMNS, rows.size(), List.of("fromMonth", "toMonth")),
                rows::forEach);
    }

    /** 원장 줄 + 수동 행 → 엑셀 줄(정렬 포함) */
    static List<ExportRow> rows(CopqView v) {
        List<ExportRow> out = new ArrayList<>();
        for (LineRow l : v.lines()) {
            ExportRow r = new ExportRow();
            r.setKind(LEDGER);
            r.setMonth(l.getMonth());
            r.setCategory(l.getCategory());
            r.setSource(l.getSource());
            r.setItemCd(l.getItemCd());
            r.setItemNm(l.getItemNm());
            r.setQty(l.getQty());
            r.setUnitCd(l.getUnitCd());
            r.setStdPrice(l.getStdPrice());
            r.setLineCnt(l.getLineCnt());
            r.setAmount(l.getAmount());
            r.setReason(l.getReason());
            r.setNcrNos(l.getNcrNos() == null ? null : l.getNcrNos().replace(",", ", "));
            out.add(r);
        }
        for (CostRow c : v.costs()) {
            ExportRow r = new ExportRow();
            r.setKind(MANUAL);
            r.setMonth(c.getCostMonth());
            r.setCategory(c.getCategory());
            r.setAmount(c.getAmount());
            r.setCostItem(c.getCostItem());
            r.setNote(c.getNote());
            out.add(r);
        }
        out.sort(Comparator.comparing(ExportRow::getMonth)
                .thenComparing(r -> MANUAL.equals(r.getKind()))
                .thenComparing(r -> CopqService.CATEGORIES.indexOf(r.getCategory()))
                .thenComparing(ExportRow::getSource, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.getItemCd() != null ? r.getItemCd() : r.getCostItem(), Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }
}
