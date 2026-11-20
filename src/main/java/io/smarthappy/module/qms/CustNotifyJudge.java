package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 4M 고객 통지 판단 (docs/29_P3 52장 CN-3·CN-5·CN-6) — 순수 함수, 손 검산 시험(CustNotifyJudgeTest).
 *
 * - 대상 고객 = ECO 고객 칸만. 없으면 NO_CUSTOMER(경고 없음 — Q4-4)
 * - 4M 세부가 없으면 NO_4M(판단 불가 — 경고 없음)
 * - 요구 수준 = 4M 유형마다 맞는 CSR 요구 중 가장 큰 것(APPROVE &gt; NOTIFY) → 그중 가장 큰 것, 없으면 NONE
 * - 경고는 계산만(저장하지 않음): 승인 요구인데 ECO 고객 승인 꺼짐 / 통지 요구인데 PCN 없음(승인·적용 뒤) / 응답 기한 지난 SENT / 마지막 PCN 반려
 */
public final class CustNotifyJudge {

    public static final String NOTIFY = "NOTIFY";
    public static final String APPROVE = "APPROVE";
    public static final String NONE = "NONE";
    /** 저장하는 요구 값(NONE 은 화면 선택용) */
    public static final Set<String> LEVELS = Set.of(NOTIFY, APPROVE);

    public static final String NO_CUSTOMER = "NO_CUSTOMER";
    public static final String NO_4M = "NO_4M";
    public static final String JUDGED = "JUDGED";

    public static final String W_APPROVAL = "QMS_CUST_APPROVAL_NEEDED";
    public static final String W_PCN_MISSING = "QMS_CUST_PCN_MISSING";
    public static final String W_PCN_OVERDUE = "QMS_CUST_PCN_OVERDUE";
    public static final String W_PCN_REJECTED = "QMS_CUST_PCN_REJECTED";

    private CustNotifyJudge() {
    }

    /** 맞은 CSR 하나의 요구(이미 고객·사용·시작일·대상으로 거른 것) */
    public record Csr(long csrId, String csrNo, String csrRev, Map<String, String> notifyReq) {
    }

    /** 판단에 쓰는 PCN(그 고객 것만, 통지일 순) */
    public record Pcn(String pcnNo, LocalDate responseDue, String status) {
    }

    /** 요구 근거 한 줄 — 4M 유형 × CSR */
    public record Basis(String m4Type, String level, String csrNo, String csrRev) {
    }

    /**
     * @param state   NO_CUSTOMER · NO_4M · JUDGED
     * @param level   JUDGED 일 때 NONE·NOTIFY·APPROVE, 그 밖 null
     * @param overdue 응답 기한 지난 SENT PCN 번호
     */
    public record Result(String state, String level, List<Basis> basis, List<String> warnings, List<String> overdue) {
    }

    /**
     * @param custId             ECO 고객 칸(null = 없음)
     * @param m4Types            4M 세부의 유형(빈 목록 = 4M 세부 없음)
     * @param csrs               맞은 CSR
     * @param custApprovalStatus ECO 고객 승인 상태 — NA = "고객 승인 필요" 꺼짐
     * @param ecoStatus          DRAFT·PENDING·APPROVED·RELEASED·CANCELLED
     * @param pcns               그 고객의 PCN(통지일 순)
     * @param today              회사 날짜
     */
    public static Result judge(Long custId, List<String> m4Types, List<Csr> csrs, String custApprovalStatus, String ecoStatus,
            List<Pcn> pcns, LocalDate today) {
        if (custId == null) {
            return new Result(NO_CUSTOMER, null, List.of(), List.of(), List.of());
        }
        List<String> overdue = pcns.stream().filter(p -> "SENT".equals(p.status()) && p.responseDue() != null && p.responseDue().isBefore(today))
                .map(Pcn::pcnNo).toList();
        if (m4Types.isEmpty()) {
            return new Result(NO_4M, null, List.of(), overdue.isEmpty() ? List.of() : List.of(W_PCN_OVERDUE), overdue);
        }
        List<Basis> basis = new ArrayList<>();
        String level = NONE;
        for (String t : m4Types) {
            for (Csr c : csrs) {
                String req = c.notifyReq() == null ? null : c.notifyReq().get(t);
                if (req != null && LEVELS.contains(req)) {
                    basis.add(new Basis(t, req, c.csrNo(), c.csrRev()));
                    level = max(level, req);
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        if (APPROVE.equals(level) && "NA".equals(custApprovalStatus)) {
            warnings.add(W_APPROVAL);
        }
        boolean decided = "APPROVED".equals(ecoStatus) || "RELEASED".equals(ecoStatus);
        if (!NONE.equals(level) && pcns.isEmpty() && decided) {
            warnings.add(W_PCN_MISSING);
        }
        if (!overdue.isEmpty()) {
            warnings.add(W_PCN_OVERDUE);
        }
        if (!pcns.isEmpty() && "REJECTED".equals(pcns.get(pcns.size() - 1).status())) {
            warnings.add(W_PCN_REJECTED);
        }
        return new Result(JUDGED, level, basis, warnings, overdue);
    }

    static String max(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String level) {
        return APPROVE.equals(level) ? 2 : NOTIFY.equals(level) ? 1 : 0;
    }
}
