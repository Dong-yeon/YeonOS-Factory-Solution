package io.smarthappy.module.pms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import io.smarthappy.module.pms.WbsTree.Placed;

/**
 * WBS 위치 바꾸기 (docs/32 PM1) — 위·아래(형제 안 순서), 들여쓰기(바로 위 형제의 마지막 자식), 내어쓰기(부모 바로 뒤 형제).
 * DB 를 모르는 순수 함수: 바꿀 행의 (부모, 순번) 목록을 돌려주고 저장은 서비스가 한다. 순번은 형제 묶음을 10, 20, … 으로 다시 매긴다
 */
final class WbsMoves {

    private WbsMoves() {
    }

    record Move(long id, Long parentId, int seq) {
    }

    /** 어디에 넣을지: 부모 + 형제 안 순번. afterId 가 있으면 그 형제 바로 뒤(형제 순번 다시 매김), 없으면 맨 끝 */
    record Slot(Long parentId, int seq, List<Move> renumber) {
    }

    static <T> Slot slot(List<Placed<T>> placed, Function<T, Long> id, Function<T, Long> parent, ToIntFunction<T> seq,
            Long parentId, Long afterId) {
        List<T> sibs = siblings(placed, parent, parentId);
        if (afterId == null) {
            int max = 0;
            for (T t : sibs) {
                max = Math.max(max, seq.applyAsInt(t));
            }
            return new Slot(parentId, max + 10, List.of());
        }
        int at = indexOf(sibs, id, afterId);
        if (at < 0) {
            throw PmsInputs.fieldError("afterId", "NOT_FOUND");
        }
        // after 뒤에 빈 자리를 만들고 거기에 넣는다
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < sibs.size(); i++) {
            int s = (i <= at ? i + 1 : i + 2) * 10;
            moves.add(new Move(id.apply(sibs.get(i)), parentId, s));
        }
        return new Slot(parentId, (at + 2) * 10, moves);
    }

    /**
     * @param canHaveKids 들여쓰기 때 새 부모가 될 수 있는지(마일스톤·게이트·실적 있는 잎은 안 됨)
     * @return 바꿀 행들(대상 포함). 움직일 수 없으면 400 칸 오류 op
     */
    static <T> List<Move> plan(List<Placed<T>> placed, Function<T, Long> id, Function<T, Long> parent, long target, String op,
            Predicate<T> canHaveKids) {
        T row = null;
        for (Placed<T> p : placed) {
            if (id.apply(p.row()) == target) {
                row = p.row();
            }
        }
        if (row == null) {
            throw PmsInputs.fieldError("op", "NOT_FOUND");
        }
        Long pid = parent.apply(row);
        List<T> sibs = siblings(placed, parent, pid);
        int i = indexOf(sibs, id, target);
        switch (op == null ? "" : op) {
            case "UP", "DOWN" -> {
                int j = "UP".equals(op) ? i - 1 : i + 1;
                if (j < 0 || j >= sibs.size()) {
                    throw PmsInputs.fieldError("op", "PMS_CANNOT_MOVE");
                }
                List<T> re = new ArrayList<>(sibs);
                re.set(i, sibs.get(j));
                re.set(j, row);
                return renumber(re, id, pid, List.of());
            }
            case "INDENT" -> {
                if (i == 0) {
                    throw PmsInputs.fieldError("op", "PMS_CANNOT_MOVE");
                }
                T newParent = sibs.get(i - 1);
                if (!canHaveKids.test(newParent)) {
                    throw PmsInputs.fieldError("op", "PMS_NO_CHILDREN");
                }
                Long np = id.apply(newParent);
                List<T> kids = siblings(placed, parent, np);
                List<Move> moves = new ArrayList<>(renumber(kids, id, np, List.of()));
                moves.add(new Move(target, np, (kids.size() + 1) * 10));
                return moves;
            }
            case "OUTDENT" -> {
                if (pid == null) {
                    throw PmsInputs.fieldError("op", "PMS_CANNOT_MOVE");
                }
                T par = null;
                for (Placed<T> p : placed) {
                    if (Objects.equals(id.apply(p.row()), pid)) {
                        par = p.row();
                    }
                }
                Long gp = par == null ? null : parent.apply(par);
                List<T> gsibs = new ArrayList<>(siblings(placed, parent, gp));
                int at = indexOf(gsibs, id, pid);
                gsibs.add(at + 1, row);
                return renumber(gsibs, id, gp, List.of());
            }
            default -> throw PmsInputs.fieldError("op", "CODE");
        }
    }

    static <T> List<T> siblings(List<Placed<T>> placed, Function<T, Long> parent, Long parentId) {
        List<T> out = new ArrayList<>();
        for (Placed<T> p : placed) {
            if (Objects.equals(parent.apply(p.row()), parentId)) {
                out.add(p.row());
            }
        }
        return out;
    }

    private static <T> int indexOf(List<T> sibs, Function<T, Long> id, long target) {
        for (int i = 0; i < sibs.size(); i++) {
            if (id.apply(sibs.get(i)) == target) {
                return i;
            }
        }
        return -1;
    }

    private static <T> List<Move> renumber(List<T> rows, Function<T, Long> id, Long parentId, List<Move> extra) {
        List<Move> out = new ArrayList<>(extra);
        for (int i = 0; i < rows.size(); i++) {
            out.add(new Move(id.apply(rows.get(i)), parentId, (i + 1) * 10));
        }
        return out;
    }
}
