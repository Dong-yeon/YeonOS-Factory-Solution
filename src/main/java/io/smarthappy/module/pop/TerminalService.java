package io.smarthappy.module.pop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.PopDtos.ActivationCode;
import io.smarthappy.module.pop.PopDtos.NodeOption;
import io.smarthappy.module.pop.PopDtos.PlantOption;
import io.smarthappy.module.pop.PopDtos.RevokeForm;
import io.smarthappy.module.pop.PopDtos.TerminalForm;
import io.smarthappy.module.pop.PopDtos.TerminalPage;
import io.smarthappy.module.pop.PopDtos.TerminalRow;
import io.smarthappy.module.pop.PopDtos.TerminalSort;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * POP 단말 관리(사무 화면 sys-terminal, docs/25 4.6) — 등록·수정·등록 코드 다시 발급·폐기.
 *
 * 등록 코드(영문 대문자·숫자 10자, 헷갈리는 I·O·0·1 제외, 24시간)는 만든 응답에서 한 번만 보이고 해시만 저장한다.
 * 다시 발급하면 기존 단말 키가 바로 무효가 된다. 폐기는 되돌리지 않는다(사유 필수).
 */
@Service
@RequiredArgsConstructor
public class TerminalService {

    public static final String PROG = "sys-terminal";
    /** 수집 장치(6단계) — 등록 코드 대신 API 키 */
    public static final String COLLECTOR = "COLLECTOR";
    static final Duration ACTIVATION_TTL = Duration.ofHours(24);
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 10;
    private static final Pattern TERMINAL_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PopMapper mapper;
    private final BizClock clock;
    private final PolicyService policies;
    private final io.smarthappy.platform.excel.ExcelExportService excel;
    private final org.springframework.context.MessageSource messages;

    @Transactional(readOnly = true)
    public List<TerminalRow> list() {
        return mapper.findTerminals();
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 대상(조회 조건 없음) + 서버 페이징·정렬(허용 목록 {@link TerminalSort}). 기본 = 공장 → 단말 코드 */
    @Transactional(readOnly = true)
    public TerminalPage grid(Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, TerminalSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("plantCd,asc", "terminalCd,asc"));
        return new TerminalPage(mapper.findTerminalPage(pq), pq.isWithTotal() ? mapper.countTerminals() : null);
    }

    static final java.util.Map<String, String> STATE_KEYS = java.util.Map.of("ACTIVE", "js.terminal.active", "WAITING", "js.terminal.waiting",
            "EXPIRED", "js.terminal.expired", "KEY_MISSING", "js.terminal.keyMissing", "REVOKED", "js.terminal.revoked");

    /** 단말 grid 엑셀(docs/25 4.40) — 상태·작업장은 화면과 같은 이름, 폐기는 늘 끝(grid 와 같은 순서) */
    @Transactional(readOnly = true)
    public long export(PopDtos.TerminalExport req, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        PopDtos.TerminalExport r = req == null ? new PopDtos.TerminalExport(null, null) : req;
        List<ColumnSpec> cols = io.smarthappy.module.production.MesExport.named(ColumnSpec.selectExportable(GRID_COLUMNS, r.columns()),
                java.util.Map.of("state", "stateNm", "nodeRef", "nodeRefNm"));
        PageQuery pq = PageQuery.sortOnly(r.sort(), TerminalSort.values(), List.of("plantCd,asc", "terminalCd,asc"));
        String whole = messages.getMessage("js.terminal.wholePlant", null, "-", org.springframework.context.i18n.LocaleContextHolder.getLocale());
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.sysTerminal", cols,
                mapper.countTerminals(), List.of()), w -> mapper.findTerminalPage(pq).forEach(t -> {
                    t.setStateNm(io.smarthappy.module.production.MesExport.name(messages, STATE_KEYS, t.getState()));
                    t.setNodeRefNm(t.getNodeRef().isEmpty() ? whole : t.getNodeRef());
                    w.accept(t);
                }));
    }

    /** grid 열(화면 단말 표와 같은 칸) — 상태는 화면이 이름·배지로(GridOptions.format·status) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("terminalCd", "terminal.col.terminalCd", 130).withSort(),
            ColumnSpec.text("terminalNm", "terminal.col.terminalNm", 160).withSort().withFlex(),
            ColumnSpec.code("terminalType", "terminal.col.terminalType", "TERMINAL_TYPE", 100).withSort().withPriority(3),
            ColumnSpec.text("plantCd", "terminal.col.plantCd", 70).withSort().withPriority(3),
            ColumnSpec.text("nodeRef", "terminal.col.node", 150).withPriority(3),
            ColumnSpec.text("state", "terminal.col.state", 120),
            ColumnSpec.of("lastSeenAt", "terminal.col.lastSeen", ColumnType.DATETIME, 140).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public TerminalRow get(long id) {
        TerminalRow r = mapper.findTerminal(id);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<PlantOption> plantOptions() {
        return mapper.plantOptions();
    }

    @Transactional(readOnly = true)
    public List<NodeOption> nodeOptions(String plantCd) {
        return plantCd == null || plantCd.isBlank() ? List.of() : mapper.nodeOptions(plantCd.strip());
    }

    @Transactional
    public ActivationCode create(TerminalForm in) {
        LoginUser me = me();
        TerminalForm f = validate(in, true);
        boolean collector = COLLECTOR.equals(f.terminalType());
        String code = collector ? null : newCode();
        Instant expires = collector ? null : clock.instant().plus(ACTIVATION_TTL);
        try {
            long id = mapper.insertTerminal(f, collector ? null : activationHash(code), expires, me.loginId());
            if (collector) {
                return new ActivationCode(id, issueKey(id), null);
            }
            return new ActivationCode(id, display(code), expires.atOffset(ZoneOffset.UTC));
        } catch (DuplicateKeyException e) {
            throw fieldError("terminalCd", "DUPLICATE");
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long id, TerminalForm in) {
        LoginUser me = me();
        TerminalForm f = validate(in, false);
        if (f.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TerminalRow t = get(id);
        // 수집 장치(Bearer API 키)와 POP·PDA(쿠키 키)는 키 쓰임이 달라 서로 바꾸지 않는다 — 새로 등록
        if (COLLECTOR.equals(t.getTerminalType()) != COLLECTOR.equals(f.terminalType())) {
            throw fieldError("terminalType", "FIXED");
        }
        try {
            if (mapper.updateTerminal(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** 새 등록 코드 — 기존 단말 키는 즉시 무효(분실·교체) */
    @Transactional
    public ActivationCode reissue(long id, Integer rowVer) {
        LoginUser me = me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TerminalRow t = get(id);
        if (COLLECTOR.equals(t.getTerminalType())) {
            // 수집 장치: 등록 코드 없이 새 API 키 — 기존 키는 바로 무효
            if (t.getRevokedAt() != null || t.getRowVer() != rowVer) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            return new ActivationCode(id, issueKey(id), null);
        }
        String code = newCode();
        Instant expires = clock.instant().plus(ACTIVATION_TTL);
        if (mapper.reissue(id, activationHash(code), expires, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return new ActivationCode(id, display(code), expires.atOffset(ZoneOffset.UTC));
    }

    @Transactional
    public void revoke(long id, RevokeForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String reason = VisibleText.strip(in.reason());   // 보이지 않는 글자(U+200B·NBSP)만이면 null — QA R4-a
        if (reason == null) {
            throw fieldError("reason", "REQUIRED");
        }
        if (reason.codePointCount(0, reason.length()) > 500) {
            throw fieldError("reason", "TOO_LONG");
        }
        get(id);
        if (mapper.revoke(id, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 수집 장치 API 키 — {@code 회사.id.키}, 한 번만 보이고 해시만 저장(5a 단말 키와 같은 형식) */
    private String issueKey(long id) {
        String key = PopAuthService.newKey();
        if (mapper.setKey(id, sha256(key), me().loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return TenantContext.requireCompCd() + "." + id + "." + key;
    }

    private TerminalForm validate(TerminalForm in, boolean creating) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = in.terminalCd() == null ? "" : in.terminalCd().strip().toUpperCase(Locale.ROOT);
        if (creating) {
            if (cd.isEmpty()) {
                e.add("terminalCd", "REQUIRED");
            } else if (!TERMINAL_CD.matcher(cd).matches()) {
                e.add("terminalCd", "FORMAT");
            }
        }
        String nm = in.terminalNm() == null ? "" : in.terminalNm().strip();
        if (nm.isEmpty()) {
            e.add("terminalNm", "REQUIRED");
        } else if (nm.length() > 100) {
            e.add("terminalNm", "TOO_LONG");
        }
        String plant = in.plantCd() == null ? "" : in.plantCd().strip();
        if (plant.isEmpty()) {
            e.add("plantCd", "REQUIRED");
        } else if (!"Y".equals(mapper.plantUseYn(plant))) {
            e.add("plantCd", "NO_SUCH_CODE");
        }
        String type = in.terminalType() == null || in.terminalType().isBlank() ? "POP"
                : in.terminalType().strip().toUpperCase(Locale.ROOT);
        if (!type.equals("POP") && !type.equals("PDA") && !type.equals(COLLECTOR)) {
            e.add("terminalType", "FORMAT");
        }
        String remark = in.remark() == null || in.remark().isBlank() ? null : in.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG");
        }
        e.throwIfAny();
        return new TerminalForm(cd, nm, plant, in.nodeId(), type, remark, in.rowVer());
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_sys_terminal_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_sys_terminal_node", "fk_sys_terminal_node_plant" -> fieldError("nodeId", "NO_SUCH_CODE");
            default -> e;
        };
    }

    static String newCode() {
        StringBuilder b = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            b.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return b.toString();
    }

    /** 화면 표시: 5자씩 끊는다(ABCDE-FGHJK) */
    static String display(String code) {
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    /** 사람이 친 등록 코드 → 비교용 (공백·하이픈 제거, 대문자). 형식이 아니면 null */
    static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (c.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < c.length(); i++) {
            if (CODE_ALPHABET.indexOf(c.charAt(i)) < 0) {
                return null;
            }
        }
        return c;
    }

    static String activationHash(String code) {
        return sha256("activation:" + code);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
