package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskRow;
import io.smarthappy.module.pms.WbsMoves.Move;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.pms.WbsTree.Rollup;
import io.smarthappy.platform.common.web.BusinessException;

/** WBS 순서·번호·요약 파생·위치 바꾸기 — DB 없는 표 기반 검산 (docs/32 3장) */
class WbsTreeTest {

    private static TaskRow t(long id, Long parent, int seq, String start, String end, int pct, String status, String actStart,
            String actEnd) {
        TaskRow r = new TaskRow();
        r.setTaskId(id);
        r.setParentId(parent);
        r.setSeq(seq);
        r.setTaskType("TASK");
        r.setTaskNm("t" + id);
        r.setPlanStart(LocalDate.parse(start));
        r.setPlanEnd(LocalDate.parse(end));
        r.setProgressPct(pct);
        r.setStatus(status);
        r.setActStart(actStart == null ? null : LocalDate.parse(actStart));
        r.setActEnd(actEnd == null ? null : LocalDate.parse(actEnd));
        r.setIsSummary("N");
        return r;
    }

    private static List<Placed<TaskRow>> order(List<TaskRow> rows) {
        return WbsTree.order(rows, TaskRow::getTaskId, TaskRow::getParentId, TaskRow::getSeq);
    }

    @Test
    void 순서는_전위_형제는_seq_그다음_id_번호는_조회때_계산() {
        List<TaskRow> rows = List.of(
                t(5, null, 20, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(1, null, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(3, 1L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(2, 1L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),   // 같은 seq → id 순
                t(4, 3L, 5, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null));
        List<Placed<TaskRow>> p = order(rows);
        assertThat(p).extracting(x -> x.row().getTaskId()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(p).extracting(Placed::wbsNo).containsExactly("1", "1.1", "1.2", "1.2.1", "2");
        assertThat(p).extracting(Placed::lvl).containsExactly(1, 2, 2, 3, 1);
        assertThat(p).extracting(Placed::hasKids).containsExactly(true, false, true, false, false);
        assertThat(WbsTree.maxLevel(p)).isEqualTo(3);
    }

    @Test
    void 부모가_없는_행과_순환은_잃지도_무한반복하지도_않는다() {
        List<TaskRow> rows = List.of(
                t(1, 99L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),   // 없는 부모 → 뿌리
                t(2, 3L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),    // 2 ↔ 3 순환(직접 SQL)
                t(3, 2L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null));
        List<Placed<TaskRow>> p = order(rows);
        assertThat(p).extracting(x -> x.row().getTaskId()).contains(1L);
        assertThat(p.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void 요약_파생_손검산_기간가중_내림_취소제외_완료는_모두_끝나야() {
        // 10일 40% + 30일 0% + 1일(마일스톤) 0% → (400) / 41 = 9.75 → 9, 하나라도 시작 → 진행 중
        List<TaskRow> kids = new ArrayList<>(List.of(
                t(2, 1L, 10, "2026-03-01", "2026-03-10", 40, "IN_PROGRESS", "2026-03-02", null),
                t(3, 1L, 20, "2026-03-11", "2026-04-09", 0, "NOT_STARTED", null, null),
                t(4, 1L, 30, "2026-04-10", "2026-04-10", 0, "NOT_STARTED", null, null)));
        Rollup r = WbsTree.combine(kids);
        assertThat(r.planStart()).isEqualTo("2026-03-01");
        assertThat(r.planEnd()).isEqualTo("2026-04-10");
        assertThat(r.progressPct()).isEqualTo(9);
        assertThat(r.status()).isEqualTo("IN_PROGRESS");
        assertThat(r.actStart()).isEqualTo("2026-03-02");
        assertThat(r.actEnd()).isNull();
        // 남은 둘을 취소 → 진척·상태에서 빠짐(계획 기간에는 남는다), 첫째가 완료면 요약도 완료
        kids.set(0, t(2, 1L, 10, "2026-03-01", "2026-03-10", 100, "DONE", "2026-03-02", "2026-03-09"));
        kids.set(1, t(3, 1L, 20, "2026-03-11", "2026-04-09", 30, "CANCELLED", "2026-03-12", null));
        kids.set(2, t(4, 1L, 30, "2026-04-10", "2026-04-10", 0, "CANCELLED", null, null));
        r = WbsTree.combine(kids);
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.progressPct()).isEqualTo(100);
        assertThat(r.actEnd()).isEqualTo("2026-03-09");
        assertThat(r.planEnd()).isEqualTo("2026-04-10");
        // 모두 취소 = 취소, 아무도 시작 안 함 = 시작 전
        kids.set(0, t(2, 1L, 10, "2026-03-01", "2026-03-10", 0, "CANCELLED", null, null));
        assertThat(WbsTree.combine(kids).status()).isEqualTo("CANCELLED");
        assertThat(WbsTree.combine(List.of(t(9, 1L, 1, "2026-03-01", "2026-03-02", 0, "NOT_STARTED", null, null))).status())
                .isEqualTo("NOT_STARTED");
        // 99.x% 라도 끝나지 않았으면 99 를 넘지 않는다
        r = WbsTree.combine(List.of(t(2, 1L, 10, "2026-03-01", "2026-03-01", 100, "DONE", "2026-03-01", "2026-03-01"),
                t(3, 1L, 20, "2026-03-02", "2026-12-31", 99, "IN_PROGRESS", "2026-03-02", null)));
        assertThat(r.progressPct()).isEqualTo(99);
    }

    @Test
    void 요약_계획_공수는_취소_아닌_자식_합_모두_비면_null_진척_가중은_그대로() {
        TaskRow a = t(2, 1L, 10, "2026-03-01", "2026-03-10", 50, "IN_PROGRESS", "2026-03-02", null);
        TaskRow b = t(3, 1L, 20, "2026-03-11", "2026-03-11", 0, "NOT_STARTED", null, null);
        TaskRow c = t(4, 1L, 30, "2026-03-12", "2026-03-20", 0, "CANCELLED", null, null);
        assertThat(WbsTree.combine(List.of(a, b, c)).planHours()).isNull();          // 아무도 없음 = null(0 이 아님)
        a.setPlanHours(new java.math.BigDecimal("10.5"));
        b.setPlanHours(new java.math.BigDecimal("2"));
        c.setPlanHours(new java.math.BigDecimal("100"));                              // 취소 = 빠짐
        Rollup r = WbsTree.combine(List.of(a, b, c));
        assertThat(r.planHours()).isEqualByComparingTo("12.5");
        // 진척은 계획 기간 가중 그대로(10일 50% + 1일 0% → 500/11 = 45) — 공수 가중으로 바꾸지 않음(docs/48 R-2)
        assertThat(r.progressPct()).isEqualTo(45);
        assertThat(WbsTree.sameHours(new java.math.BigDecimal("2.0"), new java.math.BigDecimal("2"))).isTrue();
        assertThat(WbsTree.sameHours(null, java.math.BigDecimal.ZERO)).isFalse();
    }

    @Test
    void 아래에서_위로_여러_단_파생_바뀐_행만_돌려준다() {
        List<TaskRow> rows = List.of(
                t(1, null, 10, "2000-01-01", "2000-01-01", 0, "NOT_STARTED", null, null),
                t(2, 1L, 10, "2000-01-01", "2000-01-01", 0, "NOT_STARTED", null, null),
                t(3, 2L, 10, "2026-05-01", "2026-05-04", 100, "DONE", "2026-05-01", "2026-05-03"),
                t(4, null, 20, "2026-06-01", "2026-06-01", 0, "NOT_STARTED", null, null));
        List<Placed<TaskRow>> p = order(rows);
        assertThat(WbsTree.rollupInPlace(p)).containsExactlyInAnyOrder(1L, 2L);
        TaskRow top = p.get(0).row();
        assertThat(top.getIsSummary()).isEqualTo("Y");
        assertThat(top.getPlanStart()).isEqualTo("2026-05-01");
        assertThat(top.getStatus()).isEqualTo("DONE");
        assertThat(WbsTree.rollupInPlace(p)).isEmpty();          // 두 번째는 바뀐 것 없음
        Rollup proj = WbsTree.project(p);
        // 4일 100% + 1일 0% → 80, 하나 시작 → 진행 중
        assertThat(proj.progressPct()).isEqualTo(80);
        assertThat(proj.status()).isEqualTo("IN_PROGRESS");
        assertThat(proj.planEnd()).isEqualTo("2026-06-01");
        assertThat(WbsTree.project(List.of())).isNull();
    }

    @Test
    void 템플릿_요약_일정은_하위의_최소_최대_마일스톤은_하루() {
        List<TmplTaskRow> rows = new ArrayList<>();
        rows.add(tt(1, null, 10, "TASK", 0, 1));
        rows.add(tt(2, 1L, 10, "TASK", -30, 10));    // -30 ~ -21
        rows.add(tt(3, 1L, 20, "MILESTONE", 0, 0));  // 0 ~ 0
        List<Placed<TmplTaskRow>> p = WbsTree.order(rows, TmplTaskRow::getTtId, TmplTaskRow::getParentId, TmplTaskRow::getSeq);
        WbsTree.templateSpans(p);
        assertThat(p.get(0).row().getStartDay()).isEqualTo(-30);
        assertThat(p.get(0).row().getEndDay()).isEqualTo(0);
        assertThat(p.get(0).row().getIsSummary()).isEqualTo("Y");
        assertThat(p.get(1).row().getEndDay()).isEqualTo(-21);
        assertThat(p.get(2).row().getStartDay()).isEqualTo(0);
        assertThat(p.get(2).row().getEndDay()).isEqualTo(0);
    }

    private static TmplTaskRow tt(long id, Long parent, int seq, String type, int offset, int dur) {
        TmplTaskRow r = new TmplTaskRow();
        r.setTtId(id);
        r.setParentId(parent);
        r.setSeq(seq);
        r.setTaskType(type);
        r.setTaskNm("tt" + id);
        r.setOffsetDays(offset);
        r.setDurationDays(dur);
        return r;
    }

    @Test
    void 위아래_들여쓰기_내어쓰기_계획과_못_움직이는_경우() {
        List<TaskRow> rows = List.of(
                t(1, null, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(2, 1L, 10, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(3, 1L, 20, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null),
                t(4, null, 20, "2026-01-01", "2026-01-01", 0, "NOT_STARTED", null, null));
        List<Placed<TaskRow>> p = order(rows);
        // 3 위로 → 3, 2 순
        assertThat(plan(p, 3, "UP")).containsExactly(new Move(3, 1L, 10), new Move(2, 1L, 20));
        // 3 들여쓰기 → 2 의 첫 자식
        assertThat(plan(p, 3, "INDENT")).containsExactly(new Move(3, 2L, 10));
        // 2 내어쓰기 → 1 바로 뒤 뿌리(1, 2, 4 순)
        assertThat(plan(p, 2, "OUTDENT")).containsExactly(new Move(1, null, 10), new Move(2, null, 20), new Move(4, null, 30));
        // 맨 위에서 위로, 첫째 들여쓰기, 뿌리 내어쓰기, 모르는 동작
        for (Object[] c : new Object[][] {{1L, "UP"}, {2L, "INDENT"}, {4L, "OUTDENT"}, {4L, "DOWN"}}) {
            assertThatThrownBy(() -> plan(p, (Long) c[0], (String) c[1])).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> plan(p, 2, "SIDEWAYS")).isInstanceOf(BusinessException.class);
        // 들여쓰기 대상이 자식을 못 가지면(마일스톤 등) 거부
        assertThatThrownBy(() -> WbsMoves.plan(p, TaskRow::getTaskId, TaskRow::getParentId, 4, "INDENT", x -> false))
                .isInstanceOf(BusinessException.class);
        // 끼워 넣기: 2 뒤 → 2(10) 새(20) 3(30)
        WbsMoves.Slot s = WbsMoves.slot(p, TaskRow::getTaskId, TaskRow::getParentId, TaskRow::getSeq, 1L, 2L);
        assertThat(s.seq()).isEqualTo(20);
        assertThat(s.renumber()).containsExactly(new Move(2, 1L, 10), new Move(3, 1L, 30));
        assertThat(WbsMoves.slot(p, TaskRow::getTaskId, TaskRow::getParentId, TaskRow::getSeq, 1L, null).seq()).isEqualTo(30);
    }

    private static List<Move> plan(List<Placed<TaskRow>> p, long id, String op) {
        return WbsMoves.plan(p, TaskRow::getTaskId, TaskRow::getParentId, id, op, x -> true);
    }
}
