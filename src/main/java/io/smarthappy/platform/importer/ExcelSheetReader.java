package io.smarthappy.platform.importer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * 엑셀(xlsx) 스트리밍 읽기 — SAX(XSSFReader). 사용자 모델(XSSFWorkbook)은 큰 파일에 힙을 많이 쓴다(07 4.4).
 *
 * - 첫 시트 = 데이터(1행 헤더), 숨김 시트 "_meta" = 템플릿 코드·버전(A열 이름, B열 값)
 * - 숫자 셀은 **셀 서식을 무시하고 저장된 값**(서식이 소수 1자리여도 4.85) — 서식 반올림으로 값이 바뀌지 않게
 * - 숫자는 유효숫자 15자리(Excel 표시 정밀도)로 읽는다 — 수식 0.1*3 이 파일에 0.30000000000000004 로 저장돼도 0.3
 * - 날짜 서식 숫자는 yyyy-MM-dd, 수식은 계산된 값(파일에 저장된 결과), 빈 행은 건너뛴다
 * - 칸 위치는 셀 참조(r) 대로(셀 순서가 뒤바뀐 파일도), 데이터 행은 **헤더 폭 밖의 셀을 버린다**(XFD 셀 하나로 행당 16,384칸 → 힙 고갈 방지),
   헤더도 {@link #MAX_COLS} 열까지만
 * - 행 수 상한을 넘으면 읽기를 멈춘다(TooManyRows)
 */
final class ExcelSheetReader {

    static final String META_SHEET = "_meta";

    /** 헤더 칸 상한 — 템플릿 열은 수십 개 */
    static final int MAX_COLS = 256;

    private static final MathContext EXCEL_PRECISION = new MathContext(15);

    static {
        // 압축 폭탄: 압축 해제 비율(기본 1%)에 더해 한 항목 크기 상한
        ZipSecureFile.setMaxEntrySize(200L * 1024 * 1024);
    }

    private ExcelSheetReader() {
    }

    /** rows: [엑셀 행 번호(1부터), 셀 글자 목록(열 순서, 빈 칸 = "")] */
    record Sheet(Map<String, String> meta, List<String> header, List<Row> rows) {
    }

    record Row(int rowNo, List<String> cells) {
    }

    static final class TooManyRows extends RuntimeException {
        TooManyRows() {
            super(null, null, false, false);
        }
    }

    static Sheet read(Path file, int maxRows) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            Map<String, String> meta = new HashMap<>();
            Collector data = null;
            boolean first = true;
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String name = it.getSheetName();
                    if (first) {
                        data = new Collector(maxRows, true);
                        parse(in, styles, strings, data);
                        first = false;
                    } else if (META_SHEET.equals(name)) {
                        Collector m = new Collector(100, false);
                        parse(in, styles, strings, m);
                        for (Row r : m.rows) {
                            if (r.cells().size() >= 2) {
                                meta.put(r.cells().get(0), r.cells().get(1));
                            }
                        }
                    }
                }
            }
            if (data == null || data.rows.isEmpty()) {
                return new Sheet(meta, List.of(), List.of());
            }
            Row head = data.rows.get(0);
            return new Sheet(meta, head.cells(), data.rows.subList(1, data.rows.size()));
        } catch (TooManyRows e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 손상·xlsx 아님·암호 걸린 파일 등
            throw new IOException("xlsx 를 읽을 수 없습니다", e);
        }
    }

    private static void parse(InputStream in, StylesTable styles, ReadOnlySharedStringsTable strings, Collector c)
            throws Exception {
        XMLReader xml = XMLHelper.newXMLReader();   // 외부 엔터티 금지된 안전한 파서
        xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, c, new RawFormatter(), false));
        xml.parse(new InputSource(in));
    }

    /** 숫자는 저장된 값 그대로, 날짜 서식만 yyyy-MM-dd */
    static final class RawFormatter extends DataFormatter {
        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                LocalDate d = DateUtil.getLocalDateTime(value).toLocalDate();
                return d.toString();
            }
            return BigDecimal.valueOf(value).round(EXCEL_PRECISION).stripTrailingZeros().toPlainString();
        }
    }

    /** 행·셀을 모은다. 셀 참조(A1)의 열 위치에 넣고 사이 빈 칸을 채운다 */
    static final class Collector implements SheetContentsHandler {
        final List<Row> rows = new ArrayList<>();
        private final int maxRows;
        private final boolean headerWidth;
        private int maxCols = MAX_COLS;
        private List<String> cur;
        private int curRow;

        /** headerWidth: 첫 행(헤더)의 마지막 비지 않은 칸까지만 다음 행들의 칸으로 받는다 */
        Collector(int maxRows, boolean headerWidth) {
            this.maxRows = maxRows;
            this.headerWidth = headerWidth;
        }

        @Override
        public void startRow(int rowNum) {
            cur = new ArrayList<>();
            curRow = rowNum + 1;
        }

        @Override
        public void endRow(int rowNum) {
            if (cur.stream().anyMatch(s -> !s.isBlank())) {
                // 헤더 1행 + 데이터 maxRows 행까지
                if (rows.size() > maxRows) {
                    throw new TooManyRows();
                }
                if (headerWidth && rows.isEmpty()) {
                    int w = cur.size();
                    while (w > 0 && cur.get(w - 1).isBlank()) {
                        w--;
                    }
                    cur = new ArrayList<>(cur.subList(0, w));
                    maxCols = w;
                }
                rows.add(new Row(curRow, cur));
            }
        }

        @Override
        public void cell(String ref, String value, XSSFComment comment) {
            int col = ref == null ? cur.size() : new CellReference(ref).getCol();
            if (col >= maxCols) {
                return;
            }
            while (cur.size() <= col) {
                cur.add("");
            }
            cur.set(col, value == null ? "" : value);
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // 머리글·바닥글은 쓰지 않는다
        }
    }
}
