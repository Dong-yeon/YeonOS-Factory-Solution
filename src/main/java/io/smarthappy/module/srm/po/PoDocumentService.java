package io.smarthappy.module.srm.po;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 발주서 (SRM S4, docs/31 D7) — 엑셀(서식 고정, POI)과 인쇄 화면이 같은 자료를 쓴다.
 * 자료 = 발행본 스냅샷(srm_po_rev — 협력사에게 간 그대로). 발행 전이면 지금 내용을 같은 모양으로 만들어 "초안" 표시.
 * 서버 PDF 는 만들지 않는다(브라우저 인쇄 → PDF, D7). 엑셀 글자 칸은 수식 주입 방지(ExcelExportService.safeText).
 */
@Service
@RequiredArgsConstructor
public class PoDocumentService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PoMapper mapper;

    /** draft = 발행본이 아니라 지금 내용(발행 전) */
    public record PoDocument(long poId, String compNm, String status, int rev, boolean draft, Map<String, Object> snapshot) {
    }

    @Transactional(readOnly = true)
    public PoDocument document(long poId, Integer rev) {
        PoDtos.PoRow po = mapper.findPo(poId);
        if (po == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String compNm = String.valueOf(mapper.notifyInfo(poId).get("comp_nm"));
        String json;
        boolean draft = false;
        int docRev;
        if (rev != null) {
            json = mapper.revSnapshot(poId, rev);
            docRev = rev;
        } else {
            List<PoDtos.RevRow> revs = mapper.findRevs(poId);
            boolean issuedNow = List.of("ISSUED", "ACKED", "CLOSED").contains(po.getStatus())
                    || "CANCELLED".equals(po.getStatus()) && !revs.isEmpty();
            if (issuedNow && !revs.isEmpty()) {
                docRev = revs.get(0).getRev();
                json = mapper.revSnapshot(poId, docRev);
            } else {
                json = mapper.currentSnapshot(poId);
                docRev = po.getRev();
                draft = true;
            }
        }
        if (json == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Map<String, Object> snap = JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        return new PoDocument(poId, compNm, po.getStatus(), docRev, draft, snap);
    }

    /** 서식 엑셀 — 제목·발주처·협력사·머리 칸 → 품목 표 → 합계. 숫자는 글자 그대로(반올림·지수 표기 없음) */
    @SuppressWarnings("unchecked")
    public void writeExcel(PoDocument d, OutputStream out) throws IOException {
        Map<String, Object> po = (Map<String, Object>) d.snapshot().get("po");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.snapshot().get("lines");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("PO");
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 16);
            title.setFont(big);
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle num = wb.createCellStyle();
            num.setAlignment(HorizontalAlignment.RIGHT);
            int r = 0;
            text(sh.createRow(r++), 0, "발주서" + (d.draft() ? " (초안 — 발행 전)" : ""), title);
            r++;
            String[][] info = {
                    {"발주처", d.compNm()}, {"협력사", s(po, "custNm") + " (" + s(po, "custCd") + ")"},
                    {"발주 번호", s(po, "poNo") + (d.rev() > 0 ? "  개정 " + d.rev() : "")}, {"발주일", s(po, "poDate")},
                    {"납품 장소", s(po, "plantCd") + (po.get("whCd") == null ? "" : " / " + s(po, "whCd"))},
                    {"통화", s(po, "currencyCd")}, {"결제 조건", s(po, "payTermsCd")}, {"비고", s(po, "remark")}};
            for (String[] kv : info) {
                Row row = sh.createRow(r++);
                text(row, 0, kv[0], head);
                text(row, 1, kv[1], null);
            }
            r++;
            Row h = sh.createRow(r++);
            String[] cols = {"행", "품목 코드", "품명", "협력사 품번", "수량", "단위", "단가", "금액", "요구 납기"};
            for (int i = 0; i < cols.length; i++) {
                text(h, i, cols[i], head);
            }
            for (Map<String, Object> l : lines) {
                Row row = sh.createRow(r++);
                text(row, 0, s(l, "lineNo"), num);
                text(row, 1, s(l, "itemCd"), null);
                text(row, 2, s(l, "itemNm"), null);
                text(row, 3, s(l, "supplierItemCd"), null);
                text(row, 4, s(l, "qty"), num);
                text(row, 5, s(l, "unitCd"), null);
                text(row, 6, s(l, "unitPrice"), num);
                text(row, 7, s(l, "amt"), num);
                text(row, 8, s(l, "dueDate"), null);
            }
            Row tot = sh.createRow(r);
            text(tot, 6, "합계", head);
            text(tot, 7, s(po, "totalAmt") + " " + s(po, "currencyCd"), num);
            int[] widths = {6, 16, 30, 16, 12, 8, 14, 16, 12};
            for (int i = 0; i < widths.length; i++) {
                sh.setColumnWidth(i, widths[i] * 256);
            }
            wb.write(out);
        }
    }

    private static void text(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(ExcelExportService.safeText(v));
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
