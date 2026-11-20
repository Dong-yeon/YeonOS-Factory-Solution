package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.qms.CustNotifyJudge.Csr;
import io.smarthappy.module.qms.CustNotifyJudge.Pcn;
import io.smarthappy.module.qms.CustNotifyJudge.Result;

/** 4M 고객 통지 판단 손 검산 (docs/29_P3 52장 CN-3·CN-5·CN-6) — DB 없음 */
class CustNotifyJudgeTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    static final Csr ALL = new Csr(1, "CSR-A", "A", Map.of("MACHINE", "NOTIFY", "MAN", "NOTIFY"));
    static final Csr ITEM = new Csr(2, "CSR-B", "2", Map.of("MACHINE", "APPROVE", "MATERIAL", "NOTIFY"));

    @Test
    void 고객_없음이면_판단도_경고도_없다() {
        Result r = CustNotifyJudge.judge(null, List.of("MACHINE"), List.of(ITEM), "NA", "RELEASED", List.of(), TODAY);
        assertThat(r.state()).isEqualTo(CustNotifyJudge.NO_CUSTOMER);
        assertThat(r.level()).isNull();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void 사M_없음이면_판단_불가_경고는_PCN_기한만() {
        Result r = CustNotifyJudge.judge(7L, List.of(), List.of(ITEM), "NA", "RELEASED",
                List.of(new Pcn("P-1", TODAY.minusDays(1), "SENT")), TODAY);
        assertThat(r.state()).isEqualTo(CustNotifyJudge.NO_4M);
        assertThat(r.warnings()).containsExactly(CustNotifyJudge.W_PCN_OVERDUE);
        assertThat(r.overdue()).containsExactly("P-1");
    }

    @Test
    void 유형마다_가장_큰_요구_그중_가장_큰_것_근거는_유형X_CSR() {
        // MACHINE: ALL NOTIFY · ITEM APPROVE → APPROVE / METHOD: 요구 없음
        Result r = CustNotifyJudge.judge(7L, List.of("MACHINE", "METHOD"), List.of(ALL, ITEM), "PENDING", "DRAFT", List.of(), TODAY);
        assertThat(r.state()).isEqualTo(CustNotifyJudge.JUDGED);
        assertThat(r.level()).isEqualTo("APPROVE");
        assertThat(r.basis()).extracting(b -> b.m4Type() + ":" + b.level() + ":" + b.csrNo())
                .containsExactly("MACHINE:NOTIFY:CSR-A", "MACHINE:APPROVE:CSR-B");
        assertThat(r.warnings()).as("고객 승인 켜짐(PENDING)·작성 중 → PCN 없음도 경고 아님").isEmpty();
        // 통지만
        assertThat(CustNotifyJudge.judge(7L, List.of("MAN"), List.of(ALL, ITEM), "NA", "DRAFT", List.of(), TODAY).level()).isEqualTo("NOTIFY");
        // 요구 없음
        Result none = CustNotifyJudge.judge(7L, List.of("ENV"), List.of(ALL, ITEM), "NA", "RELEASED", List.of(), TODAY);
        assertThat(none.level()).isEqualTo("NONE");
        assertThat(none.warnings()).as("요구 없으면 PCN 없음 경고도 없음").isEmpty();
    }

    @Test
    void 경고_승인꺼짐_PCN없음은_승인적용뒤만_기한지남_마지막반려() {
        Result a = CustNotifyJudge.judge(7L, List.of("MACHINE"), List.of(ITEM), "NA", "PENDING", List.of(), TODAY);
        assertThat(a.warnings()).containsExactly(CustNotifyJudge.W_APPROVAL);
        Result b = CustNotifyJudge.judge(7L, List.of("MATERIAL"), List.of(ITEM), "NA", "APPROVED", List.of(), TODAY);
        assertThat(b.warnings()).containsExactly(CustNotifyJudge.W_PCN_MISSING);
        // 기한 당일은 지남 아님, 응답한 PCN 은 기한이 지나도 아님
        Result c = CustNotifyJudge.judge(7L, List.of("MATERIAL"), List.of(ITEM), "NA", "RELEASED",
                List.of(new Pcn("P-1", TODAY.minusDays(3), "ACK"), new Pcn("P-2", TODAY, "SENT"), new Pcn("P-3", TODAY.minusDays(1), "SENT")), TODAY);
        assertThat(c.overdue()).containsExactly("P-3");
        assertThat(c.warnings()).containsExactly(CustNotifyJudge.W_PCN_OVERDUE);
        Result d = CustNotifyJudge.judge(7L, List.of("MACHINE"), List.of(ITEM), "APPROVED", "RELEASED",
                List.of(new Pcn("P-1", TODAY, "APPROVED"), new Pcn("P-2", TODAY, "REJECTED")), TODAY);
        assertThat(d.warnings()).containsExactly(CustNotifyJudge.W_PCN_REJECTED);
        assertThat(CustNotifyJudge.max("APPROVE", "NOTIFY")).isEqualTo("APPROVE");
        assertThat(CustNotifyJudge.max("NONE", "NOTIFY")).isEqualTo("NOTIFY");
    }
}
