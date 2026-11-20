package io.smarthappy.platform.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.metrics.HbMetrics;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 엑셀 내보내기 — 목록 엑셀의 유일한 경로 (docs/09 6장, G1).
 *
 * 함정: **요청 스레드의 회사 트랜잭션 안에서** 응답 스트림에 직접 쓴다. StreamingResponseBody·@Async 로 넘기면
 * 회사 범위가 없어 RLS 로 0건 파일이 "성공"으로 나간다. 그래서 MANDATORY.
 *
 * 순서: 동시 실행 자리(회사별 advisory lock) → statement_timeout → 건수 상한 → 헤더 → 행 스트리밍(SXSSF) → 출력 정보 시트.
 * 셀: 텍스트는 수식 주입 방지(`= + - @ TAB CR` 로 시작하면 앞에 '), 숫자·날짜는 타입 셀, 일시는 회사 시간대, 코드는 코드명.
 * 행은 getter 객체·레코드·Map 모두 되고({@link RowReader}), 값은 열 유형에 맞게 바꾼다({@link CellValues} — 글자로 온 수량·날짜도 타입 셀,
 * 못 바꾸는 값은 화면처럼 글자 그대로).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelExportService {

    static final int WINDOW_ROWS = 100;
    static final int MAX_CELL_CHARS = 32_767;
    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final JdbcTemplate jdbc;
    private final JobLock jobLock;
    private final PolicyService policies;
    private final CodeService codes;
    private final BizClock clock;
    private final MessageSource messages;
    private final HbMetrics metrics;

    /**
     * @param progCd        화면 (파일명·로그)
     * @param progNameKey   파일명에 쓸 화면 이름 키
     * @param columns       검증된 열 (ColumnSpec.selectExportable)
     * @param count         조건에 맞는 행 수 (같은 조건의 count 쿼리)
     * @param conditionKeys 조건 키 목록(값 제외) — 로그·출력 정보용
     */
    public record ExportJob(String progCd, String progNameKey, List<ColumnSpec> columns, long count,
            List<String> conditionKeys) {
    }

    /** 행을 하나씩 넘기는 쪽 (매퍼 Cursor 를 돌며 accept) */
    public interface RowWriter extends Consumer<Object> {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long export(HttpServletResponse response, ExportJob job, Consumer<RowWriter> rows) throws IOException {
        acquireSlot();
        long t0 = System.nanoTime();
        try {
            return write(response, job, rows);
        } finally {
            metrics.excelExport(java.time.Duration.ofNanos(System.nanoTime() - t0));   // 운영 지표 hb_excel_export_seconds(docs/52 Y2)
        }
    }

    private long write(HttpServletResponse response, ExportJob job, Consumer<RowWriter> rows) throws IOException {
        int timeoutSec = policies.intValue(Policies.EXCEL_EXPORT_TIMEOUT_SEC);
        jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, timeoutSec * 1000 + "");
        int max = policies.intValue(Policies.EXCEL_EXPORT_MAX_ROWS);
        if (job.count() > max) {
            AccessLogService.setDetail("rejected=TOO_MANY_ROWS;rows=" + job.count());
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, job.count(), max);
        }

        Locale locale = LocaleContextHolder.getLocale();
        ZoneId zone = clock.zone();
        // 코드·고정 값 → 글자 표 — 화면(hb.grid)과 같은 원본: CODE 는 사용 중지 포함 이름표(X4), LABEL 은 열 정의의 문구 키(X2)
        Map<String, Map<String, String>> codeNames = new HashMap<>();
        for (ColumnSpec c : job.columns()) {
            if (c.type() == ColumnType.CODE && c.codeGrp() != null) {
                codeNames.put(c.field(), codes.names(c.codeGrp(), locale));
            } else if (c.type() == ColumnType.LABEL) {
                codeNames.put(c.field(), c.labelMap(messages, locale));
            }
        }
        String progName = messages.getMessage(job.progNameKey(), null, job.progCd(), locale);
        String fileName = safeFileName(progName) + "_" + clock.now().format(FILE_TIME) + ".xlsx";

        response.setContentType(MEDIA_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");

        long written;
        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW_ROWS)) {
            wb.setCompressTempFiles(true);
            Styles styles = new Styles(wb);
            SXSSFSheet sheet = wb.createSheet(sheetName(progName));
            Row header = sheet.createRow(0);
            for (int i = 0; i < job.columns().size(); i++) {
                ColumnSpec c = job.columns().get(i);
                Cell cell = header.createCell(i);
                cell.setCellValue(messages.getMessage(c.titleKey(), null, c.field(), locale));
                cell.setCellStyle(styles.header);
                sheet.setColumnWidth(i, Math.min(255, Math.max(8, c.width() / 7)) * 256);
            }
            sheet.createFreezePane(0, 1);
            if (!job.columns().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, job.columns().size() - 1));
            }

            long[] n = {0};
            String yes = messages.getMessage("yn.Y", null, "Y", locale);
            String no = messages.getMessage("yn.N", null, "N", locale);
            rows.accept(bean -> {
                n[0]++;
                if (n[0] > max) {
                    // count 와 실제 사이 행이 늘어난 경우 — 상한을 넘겨 쓰지 않는다
                    throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, n[0], max);
                }
                RowReader w = RowReader.of(bean);
                Row r = sheet.createRow((int) n[0]);
                for (int i = 0; i < job.columns().size(); i++) {
                    ColumnSpec c = job.columns().get(i);
                    Object v = CellValues.coerce(c.type(), w.get(c.field()), zone);
                    if (v == null) {
                        continue;
                    }
                    Cell cell = r.createCell(i);
                    switch (c.type()) {
                        case INT -> {
                            if (v instanceof Number num) {
                                cell.setCellValue(num.doubleValue());
                                cell.setCellStyle(styles.integer);
                            } else {
                                cell.setCellValue(safeText(String.valueOf(v)));
                            }
                        }
                        case DEC -> {
                            if (v instanceof Number num) {
                                cell.setCellValue(num.doubleValue());
                                cell.setCellStyle(styles.decimal(c.scale(), c.minScale()));
                            } else {
                                cell.setCellValue(safeText(String.valueOf(v)));
                            }
                        }
                        case DATE -> {
                            if (v instanceof LocalDate d) {
                                cell.setCellValue(d);
                                cell.setCellStyle(styles.date);
                            } else {
                                cell.setCellValue(safeText(String.valueOf(v)));
                            }
                        }
                        case DATETIME -> {
                            if (v instanceof LocalDateTime dt) {
                                cell.setCellValue(dt);
                                cell.setCellStyle(styles.datetime);
                            } else {
                                cell.setCellValue(safeText(String.valueOf(v)));
                            }
                        }
                        case CODE -> cell.setCellValue(safeText(codeNames.getOrDefault(c.field(), Map.of())
                                .getOrDefault(String.valueOf(v), String.valueOf(v))));
                        case LABEL -> cell.setCellValue(safeText(ColumnSpec.labelText(
                                codeNames.getOrDefault(c.field(), Map.of()), String.valueOf(v))));
                        case YN -> cell.setCellValue("Y".equals(v) ? yes : "N".equals(v) ? no : safeText(String.valueOf(v)));
                        default -> cell.setCellValue(safeText(String.valueOf(v)));
                    }
                }
            });
            written = n[0];
            writeMeta(wb, styles, job, written, locale);
            OutputStream out = response.getOutputStream();
            wb.write(out);
            out.flush();
        }
        AccessLogService.setDetail("rows=" + written + ";cols=" + job.columns().size() + ";keys="
                + String.join(",", job.conditionKeys()));
        return written;
    }

    /** 회사별 동시 내보내기 자리 — 트랜잭션 잠금이라 끝나면 저절로 풀린다(다중 인스턴스에서도) */
    private void acquireSlot() {
        int slots = policies.intValue(Policies.EXCEL_EXPORT_CONCURRENT_PER_COMP);
        for (int i = 0; i < slots; i++) {
            if (jobLock.trySlot("excel-export-" + i)) {
                return;
            }
        }
        AccessLogService.setDetail("rejected=EXPORT_BUSY");
        metrics.excelBusy();
        throw new BusinessException(HbErrorCode.EXPORT_BUSY);
    }

    private void writeMeta(SXSSFWorkbook wb, Styles styles, ExportJob job, long rows, Locale locale) {
        SXSSFSheet meta = wb.createSheet(sheetName(messages.getMessage("excel.meta.sheet", null, "Info", locale)));
        String user = CurrentUser.get().map(u -> u.loginId()).orElse("-");
        String[][] items = {
                {"excel.meta.program", messages.getMessage(job.progNameKey(), null, job.progCd(), locale)},
                {"excel.meta.user", user},
                {"excel.meta.time", clock.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)},
                {"excel.meta.rows", String.valueOf(rows)},
                {"excel.meta.conditions", String.join(", ", job.conditionKeys())}};
        for (int i = 0; i < items.length; i++) {
            Row r = meta.createRow(i);
            Cell k = r.createCell(0);
            k.setCellValue(messages.getMessage(items[i][0], null, items[i][0], locale));
            k.setCellStyle(styles.header);
            r.createCell(1).setCellValue(safeText(items[i][1]));
        }
        meta.setColumnWidth(0, 20 * 256);
        meta.setColumnWidth(1, 60 * 256);
    }

    /**
     * 수식 주입 방지(OWASP CSV Injection) + XML 에 못 넣는 제어문자 제거 + 셀 길이 상한.
     * `=`, `+`, `-`, `@`, TAB, CR 로 시작하는 텍스트는 앞에 ' 를 붙인다 — 숫자 열의 음수는 숫자 셀이라 대상 아님
     */
    public static String safeText(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_CELL_CHARS));
        for (int i = 0; i < s.length() && b.length() < MAX_CELL_CHARS - 1; i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 && c != '\t' && c != '\n' && c != '\r' ? ' ' : c);
        }
        String t = b.toString();
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        return t;
    }

    static String safeFileName(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return cleaned.isEmpty() ? "export" : cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    /** 시트 이름 규칙: 31자, []:*?/\ 금지 */
    static String sheetName(String s) {
        String cleaned = s.replaceAll("[\\[\\]:*?/\\\\]", "_");
        return cleaned.isEmpty() ? "Sheet1" : cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private static final class Styles {
        final SXSSFWorkbook wb;
        final CellStyle header;
        final CellStyle integer;
        final CellStyle date;
        final CellStyle datetime;
        final Map<String, CellStyle> decimals = new HashMap<>();

        Styles(SXSSFWorkbook wb) {
            this.wb = wb;
            Font bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
            integer = format("#,##0");
            date = format("yyyy-mm-dd");
            datetime = format("yyyy-mm-dd hh:mm");
        }

        /** 끝 0 은 minScale 자리까지(최소 1 — "#,##0.##" 은 정수에 점이 남는다), 나머지는 # */
        CellStyle decimal(int scale, int minScale) {
            int max = Math.min(scale, 10);
            int min = Math.min(Math.max(minScale, 1), max);
            String f = max <= 0 ? "#,##0" : "#,##0." + "0".repeat(min) + "#".repeat(max - min);
            return decimals.computeIfAbsent(f, this::format);
        }

        private CellStyle format(String f) {
            CellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat(f));
            return st;
        }
    }
}
