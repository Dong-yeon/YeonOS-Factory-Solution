package io.smarthappy.module.plm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import io.smarthappy.platform.excel.ExcelExportService;

/**
 * 고객 제출용 엑셀 한 장의 서식 도우미 (docs/39 29장 통지서·31장 PPAP 요약서가 같이 쓴다 — 두 서식의 글꼴·테두리를 같게).
 * 위에서 아래로 행을 쌓는다. 모든 글자 칸은 safeText(수식 주입 방지·제어 문자), 빈 값은 빈 칸("-" 를 쓰지 않는다 — '- 가 된다)
 */
final class PlmSheet implements AutoCloseable {

    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final Sheet sh;
    private final CellStyle title;
    private final CellStyle head;
    private final CellStyle wrap;
    private final CellStyle box;
    private int r;

    PlmSheet(String sheetName) {
        sh = wb.createSheet(sheetName);
        title = wb.createCellStyle();
        Font big = wb.createFont();
        big.setBold(true);
        big.setFontHeightInPoints((short) 16);
        title.setFont(big);
        head = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        head.setFont(bold);
        head.setBorderBottom(BorderStyle.THIN);
        wrap = wb.createCellStyle();
        wrap.setWrapText(true);
        box = wb.createCellStyle();
        box.setBorderBottom(BorderStyle.THIN);
        box.setBorderTop(BorderStyle.THIN);
        box.setBorderLeft(BorderStyle.THIN);
        box.setBorderRight(BorderStyle.THIN);
    }

    /** 제목 한 줄 + 빈 줄 */
    void title(String text) {
        text(sh.createRow(r++), 0, text, title);
        r++;
    }

    void blank() {
        r++;
    }

    /** 머리 칸들 — {이름, 값} 한 행씩 */
    void info(String[][] rows) {
        for (String[] kv : rows) {
            Row row = sh.createRow(r++);
            text(row, 0, kv[0], head);
            text(row, 1, kv[1], null);
        }
    }

    /** 굵은 소제목 한 줄 */
    void heading(String text) {
        text(sh.createRow(r++), 0, text, head);
    }

    /** 여러 줄 글 — 0..lastCol 을 합치고 줄 수만큼 높이 */
    void paragraph(String text, int lastCol) {
        Row d = sh.createRow(r++);
        text(d, 0, text, wrap);
        sh.addMergedRegion(new CellRangeAddress(d.getRowNum(), d.getRowNum(), 0, lastCol));
        d.setHeightInPoints(Math.max(15, Math.min(400, 15 * lines(text))));
    }

    /** 표 머리 */
    void tableHead(String... cols) {
        Row h = sh.createRow(r++);
        for (int i = 0; i < cols.length; i++) {
            text(h, i, cols[i], head);
        }
    }

    /** 표 한 행 — wrapCol 칸만 줄바꿈(없으면 -1) */
    void tableRow(int wrapCol, String... vals) {
        Row row = sh.createRow(r++);
        for (int i = 0; i < vals.length; i++) {
            text(row, i, vals[i], i == wrapCol ? wrap : null);
        }
    }

    /** 고객이 적어 돌려주는 확인란 — 소제목 + 이름 칸과 빈 칸(1..lastCol)에 테두리 */
    void replyBox(String heading, int lastCol, String... labels) {
        heading(heading);
        for (String label : labels) {
            Row row = sh.createRow(r++);
            text(row, 0, label, box);
            for (int i = 1; i <= lastCol; i++) {
                text(row, i, "", box);
            }
        }
    }

    void widths(int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sh.setColumnWidth(i, chars[i] * 256);
        }
    }

    /**
     * 같은 내용이면 같은 바이트(33장 F2 — PCN 메일 통지서 사본을 SHA-256 으로 다시 쓰려고): 문서 속성의 만든 시각을 비우고,
     * zip 항목 시각을 고정한다(POI 는 둘 다 지금 시각을 넣는다)
     */
    byte[] bytes() throws IOException {
        wb.getProperties().getCoreProperties().getUnderlyingProperties().setCreatedProperty(java.util.Optional.empty());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        wb.write(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(raw.toByteArray()));
                java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(out)) {
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                java.util.zip.ZipEntry n = new java.util.zip.ZipEntry(e.getName());
                n.setTime(FIXED_TIME);
                zo.putNextEntry(n);
                in.transferTo(zo);
                zo.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** zip 항목 고정 시각 — 1980-01-01(zip 이 담을 수 있는 가장 이른 날) */
    private static final long FIXED_TIME = java.time.LocalDateTime.of(1980, 1, 1, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();

    @Override
    public void close() throws IOException {
        wb.close();
    }

    private static int lines(String s) {
        return s == null ? 1 : (int) s.chars().filter(c -> c == '\n').count() + 1;
    }

    private static void text(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(ExcelExportService.safeText(v == null ? "" : v));
        if (style != null) {
            c.setCellStyle(style);
        }
    }
}
