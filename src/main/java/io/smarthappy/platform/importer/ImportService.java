package io.smarthappy.platform.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.importer.ImportTemplate.Existing;
import io.smarthappy.platform.importer.ImportTemplate.ImportColumn;
import io.smarthappy.platform.importer.ImportTemplate.ImportSession;
import io.smarthappy.platform.importer.ImportTemplate.Validated;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 엑셀 올리기 엔진 (P1 2단계, docs/17 3b · docs/07 4장).
 *
 * 올리기: 원본 보관(sys_file, 첨부 검사 그대로) → 트랜잭션 밖에서 SAX 읽기 → [트랜잭션] 템플릿 세션으로 검증·분류 → 스테이징.
 *        이 단계는 본 테이블·아웃박스에 쓰지 않는다(템플릿 세션의 insert/update 를 부르지 않음).
 * 실행: [한 트랜잭션] 상태 VALIDATED→DONE 조건부 UPDATE(두 번 눌러도 1건) → NEW insert · CHANGE update(검증 때 row_ver) → 대사.
 *        하나라도 그 사이 바뀌었으면 전체 롤백 409 IMPORT_STALE.
 * 권한: 템플릿의 화면 기준(조회 = 템플릿·미리보기, 저장 = 올리기·실행·취소). 작업은 올린 사람만 본다.
 */
@Service
public class ImportService {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int BATCH = 1000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public enum Mode {
        INSERT_ONLY, UPSERT
    }

    public record JobView(long jobId, String templateCd, String progCd, String mode, String status, String fileName,
            int total, int newCount, int changeCount, int sameCount, int errorCount, List<String> columns,
            List<String> ignoredColumns, Map<String, Object> result, int rowVer, OffsetDateTime createdAt) {
    }

    public record RowView(int rowNo, String status, String key, Map<String, Object> raw, List<ErrorView> errors) {
    }

    public record ErrorView(String field, String title, String code, String message) {
    }

    public record RowPage(List<RowView> list, long total) {
    }

    public record TemplateView(String code, int version, String progCd, String title, List<ColumnView> columns) {
    }

    public record ColumnView(String key, String title, boolean required) {
    }

    private final Map<String, ImportTemplate> templates;
    private final PermissionService permissions;
    private final PolicyService policies;
    private final FileService files;
    private final CodeService codes;
    private final AccessLogService accessLog;
    private final MessageSource messages;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImportService(ObjectProvider<ImportTemplate> templates, PermissionService permissions,
            PolicyService policies, FileService files, CodeService codes, AccessLogService accessLog,
            MessageSource messages, JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.templates = templates.orderedStream()
                .collect(Collectors.toMap(ImportTemplate::code, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("같은 템플릿 코드: " + a.code());
                }, LinkedHashMap::new));
        this.permissions = permissions;
        this.policies = policies;
        this.files = files;
        this.codes = codes;
        this.accessLog = accessLog;
        this.messages = messages;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    // ------------------------------------------------------------------ 템플릿

    public TemplateView templateInfo(String code, Locale locale) {
        ImportTemplate t = template(code);
        require(t, Action.SEARCH);
        return new TemplateView(t.code(), t.version(), t.progCd(), title(t, locale), t.columns().stream()
                .map(c -> new ColumnView(c.key(), msg(c.titleKey(), locale), c.required())).toList());
    }

    /** 빈 템플릿 파일: 데이터(헤더) · 안내 · 숨김 _meta(코드·버전) */
    public void templateFile(String code, Locale locale, HttpServletResponse response) throws IOException {
        ImportTemplate t = template(code);
        require(t, Action.SEARCH);
        Map<String, String> allowed = new HashMap<>();
        tx.executeWithoutResult(s -> t.columns().stream().filter(c -> c.codeGrp() != null).forEach(c ->
                allowed.put(c.key(), codes.codes(c.codeGrp(), locale).stream()
                        .map(ci -> ci.code() + " (" + ci.name() + ")").collect(Collectors.joining(", ")))));
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle head = boldStyle(wb);
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            Sheet data = wb.createSheet(sheetName(title(t, locale)));
            Row h = data.createRow(0);
            int i = 0;
            for (ImportColumn c : t.columns()) {
                var cell = h.createCell(i);
                cell.setCellValue(msg(c.titleKey(), locale) + (c.required() ? " *" : ""));
                cell.setCellStyle(head);
                data.setColumnWidth(i, 18 * 256);
                if (c.textCell()) {
                    data.setDefaultColumnStyle(i, text);    // 코드 열 — Excel 이 00123 을 123 으로 바꾸지 않게
                }
                i++;
            }
            data.createFreezePane(0, 1);
            Sheet guide = wb.createSheet(sheetName(msg("import.guide.sheet", locale)));
            String[] gh = {msg("import.guide.column", locale), msg("import.guide.required", locale),
                msg("import.guide.desc", locale), msg("import.guide.allowed", locale), msg("import.guide.example", locale)};
            Row g0 = guide.createRow(0);
            for (int k = 0; k < gh.length; k++) {
                var cell = g0.createCell(k);
                cell.setCellValue(gh[k]);
                cell.setCellStyle(head);
                guide.setColumnWidth(k, (k == 2 || k == 3 ? 50 : 18) * 256);
            }
            int r = 1;
            for (ImportColumn c : t.columns()) {
                Row g = guide.createRow(r++);
                g.createCell(0).setCellValue(msg(c.titleKey(), locale));
                g.createCell(1).setCellValue(c.required() ? "Y" : "");
                g.createCell(2).setCellValue(c.guideKey() == null ? "" : msg(c.guideKey(), locale));
                g.createCell(3).setCellValue(allowed.getOrDefault(c.key(), ""));
                g.createCell(4).setCellValue(c.example() == null ? "" : c.example());
            }
            Row note = guide.createRow(r + 1);
            note.createCell(0).setCellValue(msg("import.guide.note", locale));
            Sheet meta = wb.createSheet(ExcelSheetReader.META_SHEET);
            Row m0 = meta.createRow(0);
            m0.createCell(0).setCellValue("template");
            m0.createCell(1).setCellValue(t.code());
            Row m1 = meta.createRow(1);
            m1.createCell(0).setCellValue("version");
            m1.createCell(1).setCellValue(String.valueOf(t.version()));
            wb.setSheetVisibility(wb.getSheetIndex(meta), SheetVisibility.VERY_HIDDEN);
            attachment(response, title(t, locale) + "_" + msg("import.template.suffix", locale) + ".xlsx");
            try (OutputStream out = response.getOutputStream()) {
                wb.write(out);
            }
        }
    }

    // ------------------------------------------------------------------ 올리기

    public JobView upload(String code, String modeText, MultipartFile file, Locale locale) {
        ImportTemplate t = template(code);
        LoginUser me = me();
        require(t, Action.SAVE);
        Mode mode;
        try {
            mode = modeText == null || modeText.isBlank() ? Mode.INSERT_ONLY : Mode.valueOf(modeText);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(HbErrorCode.IMPORT_FILE);
        }
        int maxRows = tx.execute(s -> policies.intValue(Policies.IMPORT_MAX_ROWS));

        // 원본 보관 — 확장자·크기(실제 바이트)·시그니처 검사는 첨부와 같다. 대상 id 는 추측할 수 없게
        long fileId = files.upload(t.progCd(), "import-" + UUID.randomUUID(), file).fileId();

        ExcelSheetReader.Sheet sheet;
        Path temp = null;
        try {
            temp = Files.createTempFile("hb-import-", ".xlsx");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            sheet = ExcelSheetReader.read(temp, maxRows);
        } catch (ExcelSheetReader.TooManyRows e) {
            throw new BusinessException(HbErrorCode.IMPORT_TOO_MANY, maxRows);
        } catch (IOException e) {
            throw new BusinessException(HbErrorCode.IMPORT_FILE);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 업무와 무관
                }
            }
        }

        // 템플릿·버전 — 열 순서가 바뀐 옛 파일이 엉뚱한 열로 들어가는 사고 방지
        if (!t.code().equals(sheet.meta().get("template"))
                || !String.valueOf(t.version()).equals(sheet.meta().get("version"))) {
            throw new BusinessException(HbErrorCode.IMPORT_TEMPLATE);
        }
        Header header = mapHeader(t, sheet.header(), locale);
        if (sheet.rows().isEmpty()) {
            throw new BusinessException(HbErrorCode.IMPORT_EMPTY);
        }

        JobView view = tx.execute(s -> stage(t, mode, me, fileId, name, header, sheet.rows(), locale));
        accessLog.recordCurrent(AccessEvent.ACTION, t.progCd(), Action.SAVE.name(), Result.OK,
                "import-upload job=" + view.jobId() + " rows=" + view.total() + " error=" + view.errorCount());
        return view;
    }

    /** 헤더 → 열 키. 이름으로(한국어·영어·키, 끝의 * 무시), 모르는 열은 무시 목록 */
    record Header(Map<Integer, String> keyAt, List<String> keys, List<String> ignored) {
    }

    Header mapHeader(ImportTemplate t, List<String> header, Locale locale) {
        Map<String, String> byName = new HashMap<>();
        for (ImportColumn c : t.columns()) {
            byName.put(norm(c.key()), c.key());
            byName.put(norm(msg(c.titleKey(), Locale.KOREAN)), c.key());
            byName.put(norm(msg(c.titleKey(), Locale.ENGLISH)), c.key());
        }
        Map<Integer, String> keyAt = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String text = clean(header.get(i));
            if (text.isEmpty()) {
                continue;
            }
            String key = byName.get(norm(text));
            if (key == null) {
                ignored.add(text.length() > 50 ? text.substring(0, 50) : text);
            } else if (keyAt.containsValue(key)) {
                throw new BusinessException(HbErrorCode.IMPORT_HEADER, text);
            } else {
                keyAt.put(i, key);
            }
        }
        List<String> missing = t.columns().stream().filter(c -> c.required() && !keyAt.containsValue(c.key()))
                .map(c -> msg(c.titleKey(), locale)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(HbErrorCode.IMPORT_HEADER, String.join(", ", missing));
        }
        List<String> keys = t.columns().stream().map(ImportColumn::key).filter(keyAt::containsValue).toList();
        return new Header(keyAt, keys, ignored);
    }

    private record Staged(int rowNo, Map<String, String> raw, Validated v, List<FieldError> errors) {
    }

    private JobView stage(ImportTemplate t, Mode mode, LoginUser me, long fileId, String fileName, Header header,
            List<ExcelSheetReader.Row> rows, Locale locale) {
        ImportSession session = t.open();
        String keyField = t.columns().get(0).key();
        List<Staged> staged = new ArrayList<>(rows.size());
        Map<String, Integer> keyCount = new HashMap<>();
        for (ExcelSheetReader.Row r : rows) {
            Map<String, String> cells = new LinkedHashMap<>();
            header.keyAt().forEach((idx, key) -> cells.put(key, idx < r.cells().size() ? clean(r.cells().get(idx)) : ""));
            Validated v = session.validate(cells);     // 검증은 올린 글자 전체로(TOO_LONG·제어 문자 FORMAT)
            staged.add(new Staged(r.rowNo(), stored(cells), v, new ArrayList<>(v.errors())));
            if (v.key() != null) {
                keyCount.merge(v.key(), 1, Integer::sum);
            }
        }
        int nNew = 0;
        int nChange = 0;
        int nSame = 0;
        int nError = 0;
        List<Object[]> batch = new ArrayList<>();
        // 작업 먼저(행이 참조)
        Long jobId = jdbc.queryForObject("""
                insert into sys_import_job (comp_cd, template_cd, template_ver, prog_cd, mode, file_id, file_nm, col_keys,
                                            ignored_cols, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning job_id""", Long.class,
                t.code(), t.version(), t.progCd(), mode.name(), fileId, cut(fileName, 255),
                String.join(",", header.keys()), header.ignored().isEmpty() ? null : cut(String.join("\n", header.ignored()), 1000),
                me.loginId(), me.loginId());
        for (Staged st : staged) {
            String status;
            Long targetId = null;
            Integer targetVer = null;
            if (st.errors().isEmpty() && st.v().key() != null && keyCount.get(st.v().key()) > 1) {
                // 같은 키 2행 이상 — 어느 쪽이 맞는지 시스템이 정하지 않는다(둘 다 오류)
                st.errors().add(new FieldError(null, keyField, "FILE_DUPLICATE"));
            }
            if (!st.errors().isEmpty()) {
                status = "ERROR";
            } else {
                Existing ex = session.find(st.v().key());
                if (ex == null) {
                    status = "NEW";
                } else if (mode == Mode.INSERT_ONLY) {
                    st.errors().add(new FieldError(null, keyField, "EXISTS"));
                    status = "ERROR";
                } else {
                    targetId = ex.id();
                    targetVer = ex.rowVer();
                    // 변경 행의 빈 칸은 기존 값 유지 — 비교도 채운 칸만
                    status = filled(header.keys(), st.raw()).stream().allMatch(k -> java.util.Objects.equals(
                            ex.values().get(k), st.v().values().get(k))) ? "SAME" : "CHANGE";
                }
            }
            switch (status) {
                case "NEW" -> nNew++;
                case "CHANGE" -> nChange++;
                case "SAME" -> nSame++;
                default -> nError++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("raw", st.raw());
            data.put("val", "ERROR".equals(status) ? Map.of() : st.v().values());
            batch.add(new Object[] {jobId, st.rowNo(), status, st.v().key() == null ? null : cut(st.v().key(), 100),
                JSON.writeValueAsString(data), st.errors().isEmpty() ? null : JSON.writeValueAsString(errorsJson(st.errors())),
                targetId, targetVer});
            if (batch.size() >= BATCH) {
                insertRows(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            insertRows(batch);
        }
        jdbc.update("""
                update sys_import_job set cnt_total = ?, cnt_new = ?, cnt_change = ?, cnt_same = ?, cnt_error = ?
                 where job_id = ?""", staged.size(), nNew, nChange, nSame, nError, jobId);
        return loadView(jobId, locale);
    }

    private void insertRows(List<Object[]> batch) {
        jdbc.batchUpdate("""
                insert into sys_import_row (comp_cd, job_id, row_no, row_status, biz_key, data, errors, target_id, target_ver)
                values (hb_current_comp(), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""", batch);
    }

    private static List<Map<String, Object>> errorsJson(List<FieldError> errors) {
        return errors.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", e.field());
            m.put("code", e.code());
            m.put("args", e.args() == null ? List.of() : Arrays.stream(e.args()).map(String::valueOf).toList());
            return m;
        }).toList();
    }

    // ------------------------------------------------------------------ 미리보기

    public JobView job(long jobId, Locale locale) {
        return tx.execute(s -> {
            Job j = owned(jobId);
            require(template(j.templateCd()), Action.SEARCH);
            return loadView(jobId, locale);
        });
    }

    public RowPage rows(long jobId, String status, int page, int size, Locale locale) {
        if (status != null && !status.isBlank() && !Set.of("NEW", "CHANGE", "SAME", "ERROR").contains(status)
                || page < 1 || size < 1 || size > 500) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String st = status == null || status.isBlank() ? null : status;
        return tx.execute(s -> {
            Job j = owned(jobId);
            ImportTemplate t = template(j.templateCd());
            require(t, Action.SEARCH);
            Long total = jdbc.queryForObject("select count(*) from sys_import_row where job_id = ? and (?::varchar is null or row_status = ?)",
                    Long.class, jobId, st, st);
            List<RowView> list = jdbc.query("""
                    select row_no, row_status, biz_key, data::text as data, errors::text as errors from sys_import_row
                     where job_id = ? and (?::varchar is null or row_status = ?)
                     order by row_no limit ? offset ?""",
                    (rs, i) -> rowView(t, rs.getInt("row_no"), rs.getString("row_status"), rs.getString("biz_key"),
                            rs.getString("data"), rs.getString("errors"), locale),
                    jobId, st, st, size, (long) (page - 1) * size);
            return new RowPage(list, total == null ? 0 : total);
        });
    }

    /** 오류 행만 엑셀로 — 원래 열(올린 글자 그대로) + 행 번호 + 오류 내용 */
    public void errorReport(long jobId, Locale locale, HttpServletResponse response) throws IOException {
        tx.executeWithoutResult(s -> {
            Job j = owned(jobId);
            ImportTemplate t = template(j.templateCd());
            require(t, Action.SEARCH);
            List<String> keys = Arrays.asList(j.colKeys().split(","));
            Map<String, String> titles = t.columns().stream()
                    .collect(Collectors.toMap(ImportColumn::key, c -> msg(c.titleKey(), locale)));
            try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
                wb.setCompressTempFiles(true);
                CellStyle head = boldStyle(wb);
                Sheet sh = wb.createSheet(sheetName(msg("import.errors.sheet", locale)));
                Row h = sh.createRow(0);
                List<String> heads = new ArrayList<>();
                heads.add(msg("import.col.rowNo", locale));
                keys.forEach(k -> heads.add(titles.getOrDefault(k, k)));
                heads.add(msg("import.col.errors", locale));
                for (int i = 0; i < heads.size(); i++) {
                    var c = h.createCell(i);
                    c.setCellValue(heads.get(i));
                    c.setCellStyle(head);
                    sh.setColumnWidth(i, (i == heads.size() - 1 ? 60 : 16) * 256);
                }
                int[] r = {1};
                jdbc.query("""
                        select row_no, row_status, biz_key, data::text as data, errors::text as errors from sys_import_row
                         where job_id = ? and row_status = 'ERROR' order by row_no""", rs -> {
                    RowView v = rowView(t, rs.getInt("row_no"), rs.getString("row_status"), rs.getString("biz_key"),
                            rs.getString("data"), rs.getString("errors"), locale);
                    Row row = sh.createRow(r[0]++);
                    row.createCell(0).setCellValue(v.rowNo());
                    for (int i = 0; i < keys.size(); i++) {
                        Object val = v.raw().get(keys.get(i));
                        row.createCell(i + 1).setCellValue(val == null ? "" : ExcelExportService.safeText(String.valueOf(val)));
                    }
                    row.createCell(keys.size() + 1).setCellValue(ExcelExportService.safeText(v.errors().stream()
                            .map(e -> e.title() + ": " + e.message()).collect(Collectors.joining(" / "))));
                }, jobId);
                attachment(response, title(t, locale) + "_" + msg("import.errors.suffix", locale) + "_" + jobId + ".xlsx");
                try (OutputStream out = response.getOutputStream()) {
                    wb.write(out);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 실행·취소

    public JobView execute(long jobId, Integer rowVer, boolean skipErrors, Locale locale) {
        LoginUser me = me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        JobView view = tx.execute(s -> {
            Job j = owned(jobId);
            ImportTemplate t = template(j.templateCd());
            require(t, Action.SAVE);
            if (!"VALIDATED".equals(j.status())) {
                throw new BusinessException(HbErrorCode.IMPORT_STATE);
            }
            if (j.cntError() > 0 && !skipErrors) {
                throw new BusinessException(HbErrorCode.IMPORT_HAS_ERRORS, j.cntError());
            }
            // 두 번 눌러도·두 탭에서 동시에 눌러도 이 UPDATE 하나만 성공한다(뒤쪽은 행 잠금을 기다린 뒤 0건)
            int claimed = jdbc.update("""
                    update sys_import_job set status = 'DONE', executed_at = now(), executed_by = ?, row_ver = row_ver + 1,
                                              updated_at = now(), updated_by = ?
                     where job_id = ? and status = 'VALIDATED' and row_ver = ?""", me.loginId(), me.loginId(), jobId, rowVer);
            if (claimed == 0) {
                throw new BusinessException(HbErrorCode.IMPORT_STATE);
            }
            ImportSession session = t.openForExecute(jobId);
            long before = session.count();
            List<String> keys = Arrays.asList(j.colKeys().split(","));
            int[] done = {0, 0};
            List<Map<String, String>> pending = new ArrayList<>(BATCH);
            int[] pendingFirstRow = {0};
            Runnable flush = () -> {
                if (pending.isEmpty()) {
                    return;
                }
                try {
                    session.insertAll(pending, me.loginId());
                } catch (DuplicateKeyException e) {
                    // 검증 뒤 누군가 같은 코드를 만들었다 — 전체 롤백
                    throw new BusinessException(HbErrorCode.IMPORT_STALE, pendingFirstRow[0]);
                } catch (DataIntegrityViolationException e) {
                    throw staleOr(e, pendingFirstRow[0]);
                }
                done[0] += pending.size();
                pending.clear();
            };
            jdbc.query("""
                    select row_no, row_status, biz_key, data::text as data, target_id, target_ver from sys_import_row
                     where job_id = ? and row_status in ('NEW', 'CHANGE') order by row_no""", rs -> {
                Map<String, Object> data = JSON.readValue(rs.getString("data"), MAP);
                Map<String, String> val = strings(data.get("val"));
                if ("NEW".equals(rs.getString("row_status"))) {
                    if (pending.isEmpty()) {
                        pendingFirstRow[0] = rs.getInt("row_no");
                    }
                    pending.add(val);
                    if (pending.size() >= BATCH) {
                        flush.run();
                    }
                } else {
                    Existing ex = session.find(rs.getString("biz_key"));
                    int ver = rs.getInt("target_ver");
                    if (ex == null || ex.id() != rs.getLong("target_id") || ex.rowVer() != ver) {
                        throw new BusinessException(HbErrorCode.IMPORT_STALE, rs.getInt("row_no"));
                    }
                    Map<String, String> merged = new LinkedHashMap<>(ex.values());
                    // 파일에 있고 **채운** 열만 바꾼다 — 빈 칸이 기본값(Y 등)·null 로 기존 값을 덮지 않게
                    filled(keys, strings(data.get("raw"))).forEach(k -> merged.put(k, val.get(k)));
                    try {
                        if (session.update(ex.id(), ver, merged, me.loginId()) == 0) {
                            throw new BusinessException(HbErrorCode.IMPORT_STALE, rs.getInt("row_no"));
                        }
                    } catch (DataIntegrityViolationException e) {
                        throw staleOr(e, rs.getInt("row_no"));
                    }
                    done[1]++;
                }
            }, jobId);
            flush.run();
            long after = session.count();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileRows", j.cntTotal());
            result.put("inserted", done[0]);
            result.put("updated", done[1]);
            result.put("same", j.cntSame());
            result.put("errorsExcluded", j.cntError());
            result.put("before", before);
            result.put("after", after);
            // 대사: 파일 행 = 반영 + 같음 + 제외, 본 테이블 증가 = 넣은 수
            result.put("matched", j.cntTotal() == done[0] + done[1] + j.cntSame() + j.cntError()
                    && after - before == done[0]);
            jdbc.update("update sys_import_job set result = ?::jsonb where job_id = ?", JSON.writeValueAsString(result), jobId);
            return loadView(jobId, locale);
        });
        accessLog.recordCurrent(AccessEvent.ACTION, view.progCd(), Action.SAVE.name(), Result.OK,
                "import-execute job=" + jobId + " inserted=" + view.result().get("inserted") + " updated="
                        + view.result().get("updated"));
        return view;
    }

    public JobView cancel(long jobId, Integer rowVer, Locale locale) {
        LoginUser me = me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return tx.execute(s -> {
            Job j = owned(jobId);
            require(template(j.templateCd()), Action.SAVE);
            int n = jdbc.update("""
                    update sys_import_job set status = 'CANCELED', row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                     where job_id = ? and status = 'VALIDATED' and row_ver = ?""", me.loginId(), jobId, rowVer);
            if (n == 0) {
                throw new BusinessException(HbErrorCode.IMPORT_STATE);
            }
            return loadView(jobId, locale);
        });
    }

    // ------------------------------------------------------------------ 내부

    private record Job(long jobId, String templateCd, String status, int cntTotal, int cntSame, int cntError,
            String colKeys) {
    }

    /** 이 회사(RLS) + 올린 사람 것만. 아니면 404 (다른 사람 작업이 있는지도 알리지 않는다) */
    private Job owned(long jobId) {
        LoginUser me = me();
        List<Job> list = jdbc.query("""
                select job_id, template_cd, status, cnt_total, cnt_same, cnt_error, col_keys from sys_import_job
                 where job_id = ? and created_by = ?""",
                (rs, i) -> new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getString(7)), jobId, me.loginId());
        if (list.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return list.get(0);
    }

    private JobView loadView(long jobId, Locale locale) {
        RowMapper<JobView> m = (rs, i) -> {
            ImportTemplate t = templates.get(rs.getString("template_cd"));
            Map<String, String> titles = t == null ? Map.of() : t.columns().stream()
                    .collect(Collectors.toMap(ImportColumn::key, c -> msg(c.titleKey(), locale)));
            String ignored = rs.getString("ignored_cols");
            String result = rs.getString("result");
            return new JobView(rs.getLong("job_id"), rs.getString("template_cd"), rs.getString("prog_cd"),
                    rs.getString("mode"), rs.getString("status"), rs.getString("file_nm"), rs.getInt("cnt_total"),
                    rs.getInt("cnt_new"), rs.getInt("cnt_change"), rs.getInt("cnt_same"), rs.getInt("cnt_error"),
                    Arrays.stream(rs.getString("col_keys").split(",")).map(k -> titles.getOrDefault(k, k)).toList(),
                    ignored == null ? List.of() : List.of(ignored.split("\n")),
                    result == null ? Map.of() : JSON.readValue(result, MAP), rs.getInt("row_ver"),
                    rs.getObject("created_at", OffsetDateTime.class));
        };
        return jdbc.queryForObject("""
                select job_id, template_cd, prog_cd, mode, status, file_nm, cnt_total, cnt_new, cnt_change, cnt_same,
                       cnt_error, col_keys, ignored_cols, result::text as result, row_ver, created_at
                  from sys_import_job where job_id = ?""", m, jobId);
    }

    private RowView rowView(ImportTemplate t, int rowNo, String status, String key, String data, String errors,
            Locale locale) {
        Map<String, Object> d = JSON.readValue(data, MAP);
        // jsonb 는 키 순서를 바꿔 저장한다 → 템플릿 열 순서로 다시(화면·오류 엑셀이 열 순서대로 보여 준다)
        Map<String, Object> raw = new LinkedHashMap<>();
        if (d.get("raw") instanceof Map<?, ?> m) {
            t.columns().forEach(c -> {
                if (m.containsKey(c.key())) {
                    raw.put(c.key(), m.get(c.key()));
                }
            });
        }
        if (key == null && !t.columns().isEmpty() && raw.get(t.columns().get(0).key()) != null) {
            key = String.valueOf(raw.get(t.columns().get(0).key()));   // 형식 오류로 업무 키가 없으면 올린 글자 그대로
        }
        List<ErrorView> ev = new ArrayList<>();
        if (errors != null) {
            Map<String, String> titles = t.columns().stream()
                    .collect(Collectors.toMap(ImportColumn::key, c -> msg(c.titleKey(), locale)));
            for (Map<String, Object> e : JSON.readValue(errors, new TypeReference<List<Map<String, Object>>>() {
            })) {
                String field = (String) e.get("field");
                String code = (String) e.get("code");
                Object[] args = e.get("args") instanceof List<?> l ? l.toArray() : new Object[0];
                ev.add(new ErrorView(field, field == null ? "" : titles.getOrDefault(field, field), code,
                        messages.getMessage("valid." + code, args, code, locale)));
            }
        }
        return new RowView(rowNo, status, key, raw, ev);
    }

    private static Map<String, String> strings(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    ImportTemplate template(String code) {
        ImportTemplate t = code == null ? null : templates.get(code);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    private void require(ImportTemplate t, Action action) {
        if (!permissions.actions(t.progCd()).contains(action)) {
            accessLog.recordCurrent(AccessEvent.PERM_DENIED, t.progCd(), action.name(), Result.DENIED, "import");
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
    }

    private String title(ImportTemplate t, Locale locale) {
        return msg(t.titleKey(), locale);
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    /** 스테이징 원문 칸 상한 — 검증은 전체 글자로 하고, 저장(미리보기·오류 엑셀)은 잘라서(긴 셀 하나로 수 GB 증폭 방지) */
    static final int RAW_MAX = 1000;

    /** 스테이징에 남길 원문: 칸마다 {@link #RAW_MAX} 자 + "…", NUL 은 jsonb 가 못 받아 U+FFFD */
    static Map<String, String> stored(Map<String, String> cells) {
        Map<String, String> out = new LinkedHashMap<>();
        cells.forEach((k, v) -> {
            String s = v.indexOf('\u0000') >= 0 ? v.replace('\u0000', '\ufffd') : v;
            out.put(k, s.length() > RAW_MAX ? s.substring(0, RAW_MAX) + "…" : s);
        });
        return out;
    }

    /** 파일 열 중 이 행에서 비어 있지 않은 열 */
    private static List<String> filled(List<String> keys, Map<String, String> raw) {
        return keys.stream().filter(k -> raw.get(k) != null && !raw.get(k).isBlank()).toList();
    }

    /** 실행 중 참조 무결성(23503 — 검증 뒤 단위 사용 중지 등)은 행 번호와 함께 IMPORT_STALE, 그 밖은 그대로 */
    private static RuntimeException staleOr(DataIntegrityViolationException e, int rowNo) {
        return PgErrors.FOREIGN_KEY_VIOLATION.equals(PgErrors.sqlState(e).orElse(null))
                ? new BusinessException(HbErrorCode.IMPORT_STALE, rowNo) : e;
    }

    /** 앞뒤 공백·전각 공백·NBSP·BOM 정리 */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("^[\\s\\u3000\\u00a0\\ufeff]+|[\\s\\u3000\\u00a0\\ufeff]+$", "");
    }

    private static String norm(String s) {
        String c = clean(s);
        if (c.endsWith("*")) {
            c = clean(c.substring(0, c.length() - 1));
        }
        return c.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String sheetName(String s) {
        String c = s.replaceAll("[\\\\/?*\\[\\]:]", " ").strip();
        return c.isEmpty() ? "data" : (c.length() > 31 ? c.substring(0, 31) : c);
    }

    private static CellStyle boldStyle(Workbook wb) {
        Font f = wb.createFont();
        f.setBold(true);
        CellStyle s = wb.createCellStyle();
        s.setFont(f);
        return s;
    }

    private static void attachment(HttpServletResponse response, String fileName) {
        response.setContentType(MEDIA_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
