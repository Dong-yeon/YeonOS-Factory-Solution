package io.smarthappy.module.master.workforce;

import static io.smarthappy.module.master.workforce.ShiftService.blank;
import static io.smarthappy.module.master.workforce.ShiftService.fieldError;
import static io.smarthappy.module.master.workforce.ShiftService.me;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkforceDtos.BadgeForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.PinForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualValue;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerSort;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import lombok.RequiredArgsConstructor;

/**
 * 작업자 관리 (mst-worker) — P2 1단계, docs/25 4.2·D3.
 * 작업자는 로그인 계정이 아니다. 배지는 해시만 저장하고 응답에 내보내지 않는다(설정 여부만). 자격 보유는 갱신 = 새 행.
 */
@Service
@RequiredArgsConstructor
public class WorkerService {

    static final String PROG = "mst-worker";
    static final Pattern WORKER_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,19}$");
    /** 배지에 담기는 글자 — 바코드·RFID 가 내는 인쇄 가능한 ASCII */
    /** POP PIN: 숫자 4~8자리 */
    public static final Pattern PIN = Pattern.compile("^[0-9]{4,8}$");
    public static final Pattern BADGE = Pattern.compile("^[\\x21-\\x7E]{4,64}$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private final WorkforceMapper mapper;
    private final UserDirectory users;
    private final InboxService inbox;
    private final BizClock clock;
    private final PasswordEncoder passwordEncoder;
    private final PolicyService policies;

    /** 그리드 기본 정렬 = 기존 목록 순서(사번) */
    static final List<String> DEFAULT_SORT = List.of("workerCd,asc");

    /** 배지는 지정 여부만(값·해시는 내보내지 않는다) — 글자는 화면이 붙인다(format) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("workerCd", "worker.col.workerCd", 100).withSort().withPriority(1),
            ColumnSpec.text("workerNm", "worker.col.workerNm", 120).withSort().withPriority(1).withFlex().withMinWidth(90),
            ColumnSpec.text("plantNm", "worker.col.plantCd", 110).withSort().withPriority(2),
            ColumnSpec.text("deptNm", "worker.col.deptNm", 120).withSort().withPriority(2),
            ColumnSpec.text("badgeSet", "worker.col.badge", 60).withPriority(3),
            ColumnSpec.of("qualCnt", "worker.col.qualCnt", ColumnSpec.ColumnType.INT, 80).withSort().withPriority(3),
            ColumnSpec.of("useYn", "worker.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record Page(List<WorkerRow> list, Long total) {
    }

    @Transactional(readOnly = true)
    public List<WorkerRow> list(String plantCd, String keyword, String useYn) {
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)
                || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String p = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        String k = blank(keyword) ? null : "%" + like(keyword.strip()) + "%";
        return mapper.findWorkers(p, k, blank(useYn) ? null : useYn, clock.today());
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건 + 페이지·정렬·건수(기존 목록의 2000건 상한 없음) */
    @Transactional(readOnly = true)
    public Page grid(String plantCd, String keyword, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, WorkerSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)
                || keyword != null && keyword.strip().length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String p = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        String k = blank(keyword) ? null : "%" + like(keyword.strip()) + "%";
        String yn = blank(useYn) ? null : useYn;
        return new Page(mapper.findWorkerPage(p, k, yn, clock.today(), pq), pq.isWithTotal() ? mapper.countWorkers(p, k, yn) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public WorkerRow get(long workerId) {
        WorkerRow w = mapper.findWorker(workerId, clock.today());
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return w;
    }

    @Transactional
    public long create(WorkerForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_WORKER_CREATE");
        WorkerValue f = validate(in, null);
        try {
            return mapper.insertWorker(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long workerId, WorkerForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        WorkerRow cur = get(workerId);
        WorkerValue f = validate(in, cur);
        try {
            if (mapper.updateWorker(workerId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** 배지 지정(비우면 지우기). 같은 회사 안에서 다른 작업자가 쓰는 배지는 DUPLICATE — 누구 것인지는 알려 주지 않는다 */
    @Transactional
    public void setBadge(long workerId, BadgeForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        get(workerId);
        String hash = null;
        if (in.badge() != null && !in.badge().isEmpty()) {
            String b = in.badge().strip();
            if (!BADGE.matcher(b).matches()) {
                throw fieldError("badge", "FORMAT");
            }
            hash = badgeHash(TenantContext.requireCompCd(), b);
        }
        try {
            if (mapper.updateBadge(workerId, hash, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw fieldError("badge", "DUPLICATE");
        }
    }

    /**
     * POP PIN 지정(비우면 지우기) — 숫자 4~8자리, BCrypt. 지정·지우기 때 틀린 횟수·잠금도 푼다(docs/25 4.6).
     * 값은 응답·로그·감사에 남지 않는다(감사는 pin_hash 가림)
     */
    @Transactional
    public void setPin(long workerId, PinForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        get(workerId);
        String hash = null;
        if (in.pin() != null && !in.pin().isEmpty()) {
            if (!PIN.matcher(in.pin()).matches()) {
                throw fieldError("pin", "FORMAT");
            }
            hash = passwordEncoder.encode(in.pin());
        }
        if (mapper.updatePin(workerId, hash, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** sha256(회사코드 + ':' + 배지) hex — 회사가 다르면 같은 배지도 다른 값 */
    public static String badgeHash(String compCd, String badge) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((compCd + ":" + badge).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 자격 보유

    @Transactional(readOnly = true)
    public List<WorkerQualRow> quals(long workerId) {
        get(workerId);
        LocalDate today = clock.today();
        List<WorkerQualRow> rows = mapper.findWorkerQuals(workerId);
        rows.forEach(r -> r.setState(state(r, today)));
        return rows;
    }

    /** 오늘 기준 상태 — 게이트(QualGate)와 같은 판정 */
    static String state(WorkerQualRow r, LocalDate day) {
        if (r.getRevokedOn() != null && !r.getRevokedOn().isAfter(day)) {
            return "REVOKED";
        }
        if (r.getAcquiredOn().isAfter(day)) {
            return "NOT_YET";
        }
        if (r.getExpiresOn() != null && r.getExpiresOn().isBefore(day)) {
            return "EXPIRED";
        }
        return "VALID";
    }

    @Transactional
    public long addQual(long workerId, WorkerQualForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_WORKER_QUAL_ADD");
        get(workerId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        QualRow q = null;
        if (blank(in.qualCd())) {
            e.add("qualCd", "REQUIRED");
        } else {
            String cd = in.qualCd().strip().toUpperCase(Locale.ROOT);
            q = cd.length() > 20 ? null : mapper.findQualByCd(cd);
            if (q == null) {
                e.add("qualCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(q.getUseYn())) {
                e.add("qualCd", "INACTIVE");
            }
        }
        LocalDate acquired = date(in.acquiredOn(), "acquiredOn", true, e);
        LocalDate expires = date(in.expiresOn(), "expiresOn", false, e);
        if (acquired != null && acquired.isAfter(clock.today())) {
            e.add("acquiredOn", "FUTURE");
        }
        if (expires == null && acquired != null && q != null && q.getValidMonths() != null && e.isEmpty()) {
            expires = acquired.plusMonths(q.getValidMonths()).minusDays(1);   // 유효 개월의 마지막 날까지
        }
        if (acquired != null && expires != null && expires.isBefore(acquired)) {
            e.add("expiresOn", "BEFORE_ACQUIRED");
        }
        String cert = text(in.certNo(), "certNo", 50, false, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return mapper.insertWorkerQual(workerId, new WorkerQualValue(q.getQualId(), acquired, expires, null, cert, remark, null),
                me.loginId());
    }

    /** 만료일 고치기·취소(revokedOn)·증서 번호·비고. 자격·취득일은 바꾸지 않는다(갱신은 새 행) */
    @Transactional
    public void updateQual(long workerId, long wqId, WorkerQualForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        WorkerQualRow cur = mapper.findWorkerQual(wqId);
        if (cur == null || cur.getWorkerId() != workerId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        if (!blank(in.qualCd()) && !in.qualCd().strip().toUpperCase(Locale.ROOT).equals(cur.getQualCd())) {
            e.add("qualCd", "FIXED");
        }
        LocalDate acq = date(in.acquiredOn(), "acquiredOn", false, e);
        if (acq != null && !acq.equals(cur.getAcquiredOn())) {
            e.add("acquiredOn", "FIXED");
        }
        LocalDate expires = date(in.expiresOn(), "expiresOn", false, e);
        LocalDate revoked = date(in.revokedOn(), "revokedOn", false, e);
        if (expires != null && expires.isBefore(cur.getAcquiredOn())) {
            e.add("expiresOn", "BEFORE_ACQUIRED");
        }
        if (revoked != null && revoked.isBefore(cur.getAcquiredOn())) {
            e.add("revokedOn", "BEFORE_ACQUIRED");
        }
        if (revoked != null && revoked.isAfter(clock.today())) {
            e.add("revokedOn", "FUTURE");
        }
        String cert = text(in.certNo(), "certNo", 50, false, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        if (mapper.updateWorkerQual(wqId, new WorkerQualValue(cur.getQualId(), cur.getAcquiredOn(), expires, revoked, cert,
                remark, in.rowVer()), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 검증

    private WorkerValue validate(WorkerForm in, WorkerRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getWorkerCd() : blank(in.workerCd()) ? null : in.workerCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && !blank(in.workerCd()) && !cur.getWorkerCd().equals(in.workerCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("workerCd", "FIXED");
        } else if (cd == null) {
            e.add("workerCd", "REQUIRED");
        } else if (!WORKER_CD.matcher(cd).matches()) {
            e.add("workerCd", "FORMAT");
        }
        String nm = text(in.workerNm(), "workerNm", 50, true, e);
        String plant = blank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        if (plant != null) {
            String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
            boolean same = cur != null && plant.equals(cur.getPlantCd());
            if (use == null) {
                e.add("plantCd", "NO_SUCH_CODE");
            } else if (!same && !"Y".equals(use)) {
                e.add("plantCd", "INACTIVE");
            }
        }
        String dept = text(in.deptNm(), "deptNm", 50, false, e);
        Long userId = null;
        if (!blank(in.loginId())) {
            String login = in.loginId().strip();
            UserRef u = users.byLogin(login);   // 회사 사용자만(공통 사용자 고르기) — 상태 무관(작업자 ↔ 계정 연결)
            userId = u == null ? null : u.userId();
            if (userId == null) {
                e.add("loginId", "NO_SUCH_CODE");
            }
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new WorkerValue(cd, nm, plant, dept, userId, use, remark, cur == null ? null : in.rowVer());
    }

    private static RuntimeException duplicate(DuplicateKeyException e) {
        String c = PgErrors.constraint(e).orElse("");
        return "uq_mst_worker_user".equals(c) ? fieldError("loginId", "DUPLICATE") : fieldError("workerCd", "DUPLICATE");
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_mst_worker_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_mst_worker_user", "fk_mst_worker_user_comp" -> fieldError("loginId", "NO_SUCH_CODE");
            default -> e;
        };
    }

    static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v.strip());
            if (d.getYear() < 1950 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
