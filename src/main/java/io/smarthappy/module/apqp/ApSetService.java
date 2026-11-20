package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.ApqpInputs.blank;
import static io.smarthappy.module.apqp.ApqpInputs.fieldError;
import static io.smarthappy.module.apqp.ApqpInputs.me;
import static io.smarthappy.module.apqp.ApqpInputs.text;
import static io.smarthappy.module.apqp.ApqpInputs.upper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.ApCopyForm;
import io.smarthappy.module.apqp.ApqpDtos.ApRuleForm;
import io.smarthappy.module.apqp.ApqpDtos.ApRuleRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetForm;
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetView;
import io.smarthappy.module.apqp.ApqpDtos.Coverage;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * AP 규칙표 (apqp-ap, docs/41 4장·P3). 회사가 입력한다 — 핸드북 표는 시드하지 않는다(저작권).
 * - 사용(Y) = 1,000 조합을 빠짐·겹침 없이 덮는 표만. 사용 중인 표의 규칙을 바꿔도 덮임이 깨지면 거부
 * - 확정 FMEA 가 쓴 표의 규칙은 바꿀 수 없다(그 AP 의 근거) — 복사해서 새 표로. 이름·비고·사용 여부는 바꿀 수 있다
 * - 규칙이 바뀌면 이 표를 쓰는 작성 중 FMEA 의 AP 를 다시 계산한다
 */
@Service
@RequiredArgsConstructor
public class ApSetService {

    static final String PROG = "apqp-ap";
    static final int MAX_RULES = 200;
    private static final Pattern SET_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");

    private final ApqpRepository repo;
    private final FmeaCalc calc;
    private final InboxService inbox;

    @Transactional(readOnly = true)
    public List<ApSetRow> list(boolean useOnly) {
        return repo.findApSets(useOnly);
    }

    @Transactional(readOnly = true)
    public ApSetView get(long setId) {
        ApSetRow s = found(setId);
        List<ApRuleRow> rules = repo.findApRules(setId);
        return new ApSetView(s, rules, ApRules.of(rules).coverage());
    }

    @Transactional
    public long create(ApSetForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_AP_SET_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = upper(in.setCd());
        if (cd == null) {
            e.add("setCd", "REQUIRED");
        } else if (!SET_CD.matcher(cd).matches()) {
            e.add("setCd", "FORMAT");
        }
        String nm = text(in.setNm(), "setNm", 100, true, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        String use = useYn(in.useYn(), e);
        List<ApRuleRow> rules = rules(in.rules() == null ? List.of() : in.rules(), e);
        e.throwIfAny();
        requireCoverage(use, rules);
        long id;
        try {
            id = repo.insertApSet(cd, nm, remark, use, "N", me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("setCd", "DUPLICATE");
        }
        insertRules(id, rules, me.loginId());
        return id;
    }

    @Transactional
    public void update(long setId, ApSetForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        repo.lockApSet(setId);
        ApSetRow cur = found(setId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.setCd()) && !cur.getSetCd().equals(upper(in.setCd()))) {
            e.add("setCd", "FIXED");
        }
        String nm = text(in.setNm(), "setNm", 100, true, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        String use = useYn(in.useYn(), e);
        List<ApRuleRow> rules = in.rules() == null ? null : rules(in.rules(), e);
        e.throwIfAny();
        if (rules != null && cur.getReleasedCnt() > 0 && !same(rules, repo.findApRules(setId))) {
            throw new BusinessException(HbErrorCode.APQP_AP_SET_USED);
        }
        requireCoverage(use, rules != null ? rules : repo.findApRules(setId));
        if (repo.updateApSet(setId, nm, remark, use, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (rules != null && !same(rules, repo.findApRules(setId))) {
            repo.deleteApRules(setId);
            insertRules(setId, rules, me.loginId());
            // 이 표를 쓰는 작성 중 FMEA 의 AP 다시 계산(확정본은 위에서 막았다)
            repo.findDraftFmeasBySet(setId).forEach(calc::recalc);
        }
    }

    /** 표째 복사(확정 FMEA 가 쓴 표를 고쳐 쓰려면) — 사용 안 함으로 시작 */
    @Transactional
    public long copy(long setId, ApCopyForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_AP_SET_COPY");
        ApSetRow src = found(setId);
        FieldErrors e = FieldErrors.create();
        String cd = in == null ? null : upper(in.setCd());
        if (cd == null) {
            e.add("setCd", "REQUIRED");
        } else if (!SET_CD.matcher(cd).matches()) {
            e.add("setCd", "FORMAT");
        }
        String nm = text(in == null || blank(in.setNm()) ? src.getSetNm() : in.setNm(), "setNm", 100, true, e);
        e.throwIfAny();
        long id;
        try {
            id = repo.insertApSet(cd, nm, src.getRemark(), "N", src.getSampleYn(), me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("setCd", "DUPLICATE");
        }
        insertRules(id, repo.findApRules(setId), me.loginId());
        return id;
    }

    private ApSetRow found(long setId) {
        ApSetRow s = repo.findApSet(setId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return s;
    }

    private static String useYn(String v, FieldErrors e) {
        String u = blank(v) ? "N" : v.strip();
        if (!"Y".equals(u) && !"N".equals(u)) {
            e.add("useYn", "CODE");
        }
        return u;
    }

    private static void requireCoverage(String use, List<ApRuleRow> rules) {
        if ("Y".equals(use)) {
            Coverage c = ApRules.of(rules).coverage();
            if (!c.complete()) {
                throw new BusinessException(HbErrorCode.APQP_AP_INCOMPLETE, c.missing(), c.overlap());
            }
        }
    }

    /** 규칙 행 검증 — 범위 1~10·시작 ≤ 끝·H/M/L, 행마다 rowKey = 순번 */
    private static List<ApRuleRow> rules(List<ApRuleForm> in, FieldErrors e) {
        if (in.size() > MAX_RULES) {
            e.add("rules", "APQP_TOO_MANY_RULES", MAX_RULES);
            return List.of();
        }
        List<ApRuleRow> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            ApRuleForm f = in.get(i);
            FieldErrors.RowScope r = e.row(String.valueOf(i + 1));
            if (f == null) {
                r.add("ap", "REQUIRED");
                continue;
            }
            ApRuleRow x = new ApRuleRow();
            x.setSeq(i + 1);
            x.setSevFrom(range(f.sevFrom(), "sevFrom", r));
            x.setSevTo(range(f.sevTo(), "sevTo", r));
            x.setOccFrom(range(f.occFrom(), "occFrom", r));
            x.setOccTo(range(f.occTo(), "occTo", r));
            x.setDetFrom(range(f.detFrom(), "detFrom", r));
            x.setDetTo(range(f.detTo(), "detTo", r));
            if (x.getSevFrom() > x.getSevTo()) {
                r.add("sevTo", "APQP_FROM_TO");
            }
            if (x.getOccFrom() > x.getOccTo()) {
                r.add("occTo", "APQP_FROM_TO");
            }
            if (x.getDetFrom() > x.getDetTo()) {
                r.add("detTo", "APQP_FROM_TO");
            }
            String ap = upper(f.ap());
            if (ap == null) {
                r.add("ap", "REQUIRED");
            } else if (!ApqpInputs.AP.contains(ap)) {
                r.add("ap", "CODE");
            }
            x.setAp(ap);
            out.add(x);
        }
        return out;
    }

    private static int range(Integer v, String field, FieldErrors.RowScope r) {
        if (v == null) {
            r.add(field, "REQUIRED");
            return 1;
        }
        if (v < 1 || v > 10) {
            r.add(field, "RANGE", 1, 10);
            return 1;
        }
        return v;
    }

    private void insertRules(long setId, List<ApRuleRow> rules, String by) {
        int seq = 1;
        for (ApRuleRow r : rules) {
            repo.insertApRule(setId, seq++, r, by);
        }
    }

    private static boolean same(List<ApRuleRow> a, List<ApRuleRow> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ApRuleRow x = a.get(i);
            ApRuleRow y = b.get(i);
            if (x.getSevFrom() != y.getSevFrom() || x.getSevTo() != y.getSevTo() || x.getOccFrom() != y.getOccFrom()
                    || x.getOccTo() != y.getOccTo() || x.getDetFrom() != y.getDetFrom() || x.getDetTo() != y.getDetTo()
                    || !x.getAp().equals(y.getAp())) {
                return false;
            }
        }
        return true;
    }
}
