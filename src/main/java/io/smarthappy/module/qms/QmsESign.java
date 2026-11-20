package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.esign.ESignService.Signature;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import jakarta.servlet.http.HttpSession;

/**
 * QMS 판정 지점의 전자서명 (docs/29_P3 12.10) — 플랫폼 {@link ESignService} 를 정책·정규 글자와 묶는다.
 *
 * - 정책 QMS_ESIGN_REQUIRED = Y 면 토큰 필수(없으면 403 — 서명 필수 응답은 모듈 공통, docs/36 18장), N 이어도 토큰을 주면 서명
 * - 정규 글자 = 줄바꿈으로 이은 "키=값"(순서 고정, 값의 \ · 줄바꿈 · | 는 이스케이프) — 같은 내용이면 같은 해시
 */
@Component
public class QmsESign {

    static final String NCR = "qms_ncr";
    static final String GAUGE = "qms_gauge";
    /** 4M 세부(eco_id) — 복귀·효과 기록의 서명 대상 (docs/29_P3 51.2 RV-7) */
    static final String CHANGE = "qms_change";
    static final String AUTHOR = "AUTHOR";
    static final String APPROVE = "APPROVE";
    static final String REVIEW = "REVIEW";

    /** 상세에 싣는 서명 — current = 지금 내용 해시와 같음(서명 뒤 안 바뀜) */
    public record SignView(long esignId, String signerNm, String meaningCd, String authMethod, java.time.OffsetDateTime signedAt,
            boolean current) {
        static SignView of(Signature s, String hash) {
            return new SignView(s.esignId(), s.signerNm(), s.meaningCd(), s.authMethod(), s.signedAt(), s.contentHash().equals(hash));
        }
    }

    private final ESignService esign;
    private final PolicyService policies;

    public QmsESign(ESignService esign, PolicyService policies) {
        this.esign = esign;
        this.policies = policies;
    }

    public boolean required() {
        return policies.bool(Policies.QMS_ESIGN_REQUIRED);
    }

    /** 업무 트랜잭션 안에서 — 토큰이 없으면 정책 Y 일 때만 거부 */
    void sign(String token, String meaning, String targetType, long targetId, String canonical) {
        if (token == null || token.isBlank()) {
            if (required()) {
                throw new BusinessException(HbErrorCode.QMS_ESIGN_REQUIRED);
            }
            return;
        }
        esign.consume(session(), token, meaning, targetType, targetId, ESignService.hash(canonical));
    }

    List<Signature> list(String targetType, long targetId) {
        return esign.list(targetType, targetId);
    }

    private static HttpSession session() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest().getSession(false) : null;
    }

    // ------------------------------------------------------------------ 정규 글자

    static String ncr(NcrDtos.NcrRow n, List<NcrDtos.DispRow> disps) {
        Canon c = new Canon("qms_ncr").kv("ncrNo", n.getNcrNo()).kv("lotNo", n.getLotNo()).kv("itemCd", n.getItemCd())
                .kv("defectCd", n.getDefectCd()).kv("ncQty", num(n.getNcQty())).kv("unitCd", n.getUnitCd())
                .kv("description", n.getDescription());
        for (NcrDtos.DispRow d : disps) {
            c.raw("disp", d.getLineNo() + "|" + esc(d.getDispType()) + "|" + num(d.getQty()) + "|" + esc(d.getReworkLotNo()) + "|"
                    + esc(d.getNote()));
        }
        return c.toString();
    }

    static String calib(String assetCd, GaugeDtos.CalibRow r) {
        return new Canon("qms_gauge_calib").kv("calibId", String.valueOf(r.getCalibId())).kv("assetCd", assetCd)
                .kv("calibDate", String.valueOf(r.getCalibDate())).kv("result", r.getResult()).kv("limitNote", r.getLimitNote())
                .kv("agency", r.getAgency()).kv("custCd", r.getCustCd()).kv("certNo", r.getCertNo())
                .kv("cycleDays", String.valueOf(r.getCycleDays())).kv("remark", r.getRemark()).toString();
    }

    static String revert(String ecoNo, ChangeRecordDtos.RevertRow r) {
        return new Canon("qms_change_revert").kv("revertId", String.valueOf(r.getRevertId())).kv("ecoNo", ecoNo)
                .kv("revertType", r.getRevertType()).kv("revertedOn", String.valueOf(r.getRevertedOn())).kv("note", r.getNote()).toString();
    }

    static String effect(String ecoNo, ChangeRecordDtos.EffectRow r) {
        return new Canon("qms_change_effect").kv("effectId", String.valueOf(r.getEffectId())).kv("ecoNo", ecoNo)
                .kv("result", r.getResult()).kv("baseDate", String.valueOf(r.getBaseDate())).kv("basisN", String.valueOf(r.getBasisN()))
                .kv("basisDays", String.valueOf(r.getBasisDays())).kv("note", r.getNote()).toString();
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("|", "\\|");
    }

    private static final class Canon {
        private final StringBuilder sb;

        Canon(String head) {
            sb = new StringBuilder(head);
        }

        Canon kv(String k, String v) {
            return raw(k, esc(v));
        }

        /** 값이 이미 이스케이프됨(여러 칸을 | 로 이은 값) */
        Canon raw(String k, String v) {
            sb.append('\n').append(k).append('=').append(v);
            return this;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
