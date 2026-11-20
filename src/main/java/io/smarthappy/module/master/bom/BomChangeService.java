package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomChangeDtos.CompareHead;
import io.smarthappy.module.master.bom.BomChangeDtos.CompareResult;
import io.smarthappy.module.master.bom.BomChangeDtos.CompareRow;
import io.smarthappy.module.master.bom.BomChangeDtos.CopyRequest;
import io.smarthappy.module.master.bom.BomChangeDtos.Edge;
import io.smarthappy.module.master.bom.BomChangeDtos.LlcRow;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceDone;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplacePreview;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceRequest;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceRow;
import io.smarthappy.module.master.bom.BomChangeDtos.Side;
import io.smarthappy.module.master.bom.BomChangeDtos.UseRow;
import io.smarthappy.module.master.bom.BomDtos.BomRow;
import io.smarthappy.module.master.bom.BomDtos.HeaderValue;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomDtos.LineRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * BOM 변경 도구 (P1 3c, docs/18 Q8·Q10·C4·C5).
 *
 * - 비교: 두 BOM 의 행을 (자품목, 행 유형, 투입 공정) 키로 짝짓는다(같은 키가 여럿이면 순번 순서대로). 순번만 바뀐 것은 변경이 아니다
 * - 복사: 다른 품목의 새 작성 중 BOM(대체 1)으로. 같은 품목이면 "새 리비전"을 쓴다
 * - 일괄 대체: X 를 쓰는 모품목마다 — 작성 중 BOM 이 X 를 쓰면 거기에, 아니면 기준일 유효 확정본을 복사한 새 리비전에 X → Y.
 *   확정은 하지 않는다(BOM 관리에서 확정 검사를 지나게). 실행은 미리보기를 다시 계산해 고른 대상이 그대로일 때만, 전부 아니면 전무
 * - 저레벨 코드: 미폐기 구조 간선을 한 번 읽어 위상 순서로 가장 긴 경로(경로 열거는 갈래가 많으면 폭발한다)
 */
@Service
@RequiredArgsConstructor
public class BomChangeService {

    static final String PROG_REPLACE = "mst-bom-replace";

    private final BomMapper mapper;
    private final BomService bomService;
    private final InboxService inbox;
    private final BizClock clock;

    // ------------------------------------------------------------------ 비교

    @Transactional(readOnly = true)
    public CompareResult compare(Long leftId, Long rightId) {
        if (leftId == null || rightId == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        BomRow l = head(leftId);
        BomRow r = head(rightId);
        Map<String, List<LineRow>> left = group(mapper.findLines(leftId));
        Map<String, List<LineRow>> right = group(mapper.findLines(rightId));
        List<CompareRow> rows = new ArrayList<>();
        int[] n = new int[4];   // added, removed, changed, same
        Set<String> keys = new java.util.LinkedHashSet<>(right.keySet());
        keys.addAll(left.keySet());
        for (String k : keys) {
            List<LineRow> a = left.getOrDefault(k, List.of());
            List<LineRow> b = right.getOrDefault(k, List.of());
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                LineRow x = i < a.size() ? a.get(i) : null;
                LineRow y = i < b.size() ? b.get(i) : null;
                LineRow any = y != null ? y : x;
                List<String> changed = x != null && y != null ? diff(x, y, l.getBaseQty(), r.getBaseQty()) : List.of();
                String status = x == null ? "ADDED" : y == null ? "REMOVED" : changed.isEmpty() ? "SAME" : "CHANGED";
                n["ADDED".equals(status) ? 0 : "REMOVED".equals(status) ? 1 : "CHANGED".equals(status) ? 2 : 3]++;
                rows.add(new CompareRow(status, any.getItemCd(), any.getItemNm(), any.getLineType(), any.getOpSeq(),
                        side(x), side(y), changed));
            }
        }
        // 오른쪽(새 쪽) 순번 순, 삭제된 행은 왼쪽 순번으로 그 자리에
        rows.sort(Comparator.comparing((CompareRow c) -> c.right() != null ? c.right().lineNo() : c.left().lineNo())
                .thenComparing(CompareRow::itemCd));
        return new CompareResult(compareHead(l), compareHead(r), n[0], n[1], n[2], n[3], rows);
    }

    private static Map<String, List<LineRow>> group(List<LineRow> lines) {
        Map<String, List<LineRow>> m = new LinkedHashMap<>();
        lines.forEach(x -> m.computeIfAbsent(x.getItemId() + "|" + x.getLineType() + "|" + x.getOpSeq(),
                k -> new ArrayList<>()).add(x));
        return m;
    }

    /**
     * 바뀐 칸. 수량은 기준 수량당으로 본다(x.qty / xBase ≠ y.qty / yBase, 나눗셈 없이 교차 곱) — 기준 수량만 1 → 10 으로 바뀐 것도
     * 소요 변경이고, 기준·수량을 비례로 바꾼 것은 변경이 아니다(QA 3c #4). 고정 수량 행은 기준 수량과 무관해 수량 그대로
     */
    static List<String> diff(LineRow x, LineRow y, BigDecimal xBase, BigDecimal yBase) {
        List<String> c = new ArrayList<>();
        boolean fixed = "Y".equals(x.getFixedYn()) && "Y".equals(y.getFixedYn());
        boolean qtyChanged = fixed || xBase == null || yBase == null ? x.getQty().compareTo(y.getQty()) != 0
                : x.getQty().multiply(yBase).compareTo(y.getQty().multiply(xBase)) != 0;
        if (qtyChanged) {
            c.add("qty");
        }
        if (!Objects.equals(x.getUnitCd(), y.getUnitCd())) {
            c.add("unitCd");
        }
        if (x.getScrapPct().compareTo(y.getScrapPct()) != 0) {
            c.add("scrapPct");
        }
        if (!Objects.equals(x.getFixedYn(), y.getFixedYn())) {
            c.add("fixedYn");
        }
        if (!Objects.equals(x.getIssueMethod(), y.getIssueMethod())) {
            c.add("issueMethod");
        }
        if (!Objects.equals(x.getAltGrp(), y.getAltGrp()) || !Objects.equals(x.getAltPriority(), y.getAltPriority())
                || !decEq(x.getAltPct(), y.getAltPct())) {
            c.add("alt");
        }
        if (!Objects.equals(x.getPosition(), y.getPosition())) {
            c.add("position");
        }
        return c;
    }

    private static boolean decEq(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Side side(LineRow x) {
        return x == null ? null : new Side(x.getLineNo(), x.getQty(), x.getUnitCd(), x.getScrapPct(), x.getFixedYn(),
                x.getIssueMethod(), x.getAltGrp(), x.getAltPriority(), x.getAltPct(), x.getPosition());
    }

    private static CompareHead compareHead(BomRow b) {
        return new CompareHead(b.getBomId(), b.getItemCd(), b.getItemNm(), b.getRev(), b.getStatus(), b.getBaseQty());
    }

    // ------------------------------------------------------------------ 다른 품목으로 복사

    @Transactional
    public long copyTo(long bomId, CopyRequest req) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_BOM_COPY");
        BomRow src = head(bomId);
        FieldErrors e = FieldErrors.create();
        ItemLookup target = activeItem(req == null ? null : req.itemCd(), "itemCd", e);
        LocalDate from = BomService.date(req == null ? null : req.validFrom(), "validFrom", e);
        if (target != null && target.getItemId() == src.getItemId()) {
            e.add("itemCd", "SAME_ITEM_BOM");
        }
        e.throwIfAny();
        if (mapper.findDraftId(target.getItemId(), 1) != null) {
            throw new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
        }
        HeaderValue v = new HeaderValue(target.getItemId(), 1, bomService.nextRev(target.getItemId(), 1), src.getBaseQty(),
                from == null ? clock.today() : from, null, cut("복사: " + src.getItemCd() + " " + src.getRev(), 500),
                src.getRemark(), null);
        try {
            long id = mapper.insertHeader(v, me.loginId());
            mapper.copyLines(bomId, id, me.loginId());
            return id;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
        } catch (DataIntegrityViolationException ex) {
            throw cycleOr(ex, target.getItemCd());
        }
    }

    // ------------------------------------------------------------------ 일괄 대체

    @Transactional(readOnly = true)
    public ReplacePreview replacePreview(String fromItemCd, String toItemCd, String validFrom) {
        return preview(fromItemCd, toItemCd, validFrom);
    }

    @Transactional
    public List<ReplaceDone> replace(ReplaceRequest req) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_BOM_REPLACE");
        if (req == null || req.bomIds() == null || req.bomIds().isEmpty() || req.bomIds().size() > 1000
                || req.bomIds().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors ce = FieldErrors.create();
        String changeNo = trimToNull(req.changeNo());
        String changeReason = trimToNull(req.changeReason());
        if (changeNo != null && changeNo.length() > 30) {
            ce.add("changeNo", "TOO_LONG");
        }
        if (changeReason != null && changeReason.length() > 500) {
            ce.add("changeReason", "TOO_LONG");
        }
        ce.throwIfAny();
        ReplacePreview p = preview(req.fromItemCd(), req.toItemCd(), req.validFrom());
        Map<Long, ReplaceRow> bySource = new HashMap<>();
        p.rows().stream().filter(x -> !"SKIP".equals(x.getAction())).forEach(x -> bySource.put(x.getSourceBomId(), x));
        if (!bySource.keySet().containsAll(req.bomIds())) {
            // 미리보기 뒤 대상이 바뀌었다(확정·작성 중 생김·폐기) — 다시 미리보기
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        long fromId = mapper.findItemByCd(p.fromItemCd()).getItemId();
        long toId = mapper.findItemByCd(p.toItemCd()).getItemId();
        LocalDate from = LocalDate.parse(p.validFrom());
        List<ReplaceDone> done = new ArrayList<>();
        for (Long srcId : new java.util.LinkedHashSet<>(req.bomIds())) {
            ReplaceRow row = bySource.get(srcId);
            long target;
            String rev;
            try {
                if ("DRAFT".equals(row.getAction())) {
                    target = srcId;
                    rev = row.getSourceRev();
                } else {
                    BomRow src = head(srcId);
                    rev = bomService.nextRev(src.getItemId(), src.getAltNo());
                    String reason = changeReason != null ? changeReason
                            : cut("일괄 대체: " + p.fromItemCd() + " → " + p.toItemCd(), 500);
                    target = mapper.insertHeader(new HeaderValue(src.getItemId(), src.getAltNo(), rev, src.getBaseQty(),
                            from, changeNo, reason, src.getRemark(), null), me.loginId());
                    mapper.copyLines(srcId, target, me.loginId());
                }
                int n = mapper.replaceItem(target, fromId, toId, me.loginId());
                mapper.touchDraft(target, me.loginId());
                done.add(new ReplaceDone(row.getParentCd(), row.getParentNm(), target, rev, row.getAction(), n,
                        head(target).getRowVer()));
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            } catch (DataIntegrityViolationException ex) {
                throw cycleOr(ex, row.getParentCd());
            }
        }
        return done;
    }

    private ReplacePreview preview(String fromItemCd, String toItemCd, String validFrom) {
        FieldErrors e = FieldErrors.create();
        ItemLookup x = item(fromItemCd, "fromItemCd", e);
        ItemLookup y = activeItem(toItemCd, "toItemCd", e);
        LocalDate d = BomService.date(validFrom, "validFrom", e);
        LocalDate today = clock.today();
        if (d != null && d.isBefore(today)) {
            e.add("validFrom", "PAST");
        }
        if (x != null && y != null && x.getItemId() == y.getItemId()) {
            e.add("toItemCd", "SAME_ITEM_BOM");
        }
        e.throwIfAny();
        LocalDate on = d == null ? today : d;
        // 모품목·대체 번호별로 묶는다 (쿼리가 같은 묶음 안에서 시작일 최신순)
        Map<String, List<UseRow>> byParent = new LinkedHashMap<>();
        mapper.bomsUsing(x.getItemId(), y.getItemId())
                .forEach(u -> byParent.computeIfAbsent(u.getParentId() + "|" + u.getAltNo(), k -> new ArrayList<>()).add(u));
        List<ReplaceRow> rows = new ArrayList<>();
        for (List<UseRow> uses : byParent.values()) {
            UseRow first = uses.get(0);
            ReplaceRow r = new ReplaceRow();
            r.setParentId(first.getParentId());
            r.setAltNo(first.getAltNo());
            r.setParentCd(first.getParentCd());
            r.setParentNm(first.getParentNm());
            UseRow draft = uses.stream().filter(u -> "DRAFT".equals(u.getStatus())).findFirst().orElse(null);
            UseRow eff = uses.stream().filter(u -> "RELEASED".equals(u.getStatus()) && !u.getValidFrom().isAfter(on)
                    && (u.getValidTo() == null || on.isBefore(u.getValidTo()))).findFirst().orElse(null);
            UseRow src = draft != null ? draft : eff;
            if (src != null) {
                r.setSourceBomId(src.getBomId());
                r.setSourceRev(src.getRev());
                r.setSourceStatus(src.getStatus());
                r.setLineCnt(src.getLineCnt());
                r.setUnitWarn(src.isUnitWarn());
            }
            if (mapper.wouldCycle(first.getParentId(), y.getItemId())) {
                r.setAction("SKIP");
                r.setReason("CYCLE");
            } else if (draft != null) {
                r.setAction("DRAFT");
            } else if (mapper.findDraftId(first.getParentId(), first.getAltNo()) != null) {
                r.setAction("SKIP");
                r.setReason("DRAFT_OTHER");     // 작성 중 BOM 이 있는데 X 를 쓰지 않음 — 그 작성 중을 먼저 정리
            } else if (eff == null) {
                r.setAction("SKIP");
                r.setReason("NO_EFFECTIVE");    // 기준일에 유효한 확정본이 X 를 쓰지 않음(미래·과거 리비전만)
            } else if (!eff.getValidFrom().isBefore(on)) {
                r.setAction("SKIP");
                r.setReason("SAME_DAY");        // 확정본이 기준일에 시작 — 새 리비전을 확정할 때 자동 마감 불가
            } else if (!mapper.findReleasedOverlaps(first.getParentId(), first.getAltNo(), on.plusDays(1), null,
                    eff.getBomId()).isEmpty()) {
                // 기준일 뒤에 시작하는 확정본이 있음 — 새 리비전은 그것과 겹쳐 확정할 수 없고, 그 리비전은 X 를 계속 쓴다(QA 3c #3)
                r.setAction("SKIP");
                r.setReason("FUTURE_REV");
            } else {
                r.setAction("NEW_REV");
                r.setNewRev(bomService.nextRev(first.getParentId(), first.getAltNo()));
            }
            rows.add(r);
        }
        return new ReplacePreview(x.getItemCd(), x.getItemNm(), y.getItemCd(), y.getItemNm(), on.toString(), rows);
    }

    // ------------------------------------------------------------------ 저레벨 코드

    /** 구조에 나오는 품목의 저레벨 코드(0 = 최상위). 순환이 있으면(비정상 데이터) 그 품목은 -1 */
    @Transactional(readOnly = true)
    public List<LlcRow> llc() {
        Map<Long, Integer> llc = computeLlc(mapper.edges());
        if (llc.isEmpty()) {
            return List.of();
        }
        List<LlcRow> rows = mapper.itemsByIds(llc.keySet());
        rows.forEach(r -> r.setLlc(llc.get(r.getItemId())));
        rows.sort(Comparator.comparingInt(LlcRow::getLlc).thenComparing(LlcRow::getItemCd));
        return rows;
    }

    @Transactional(readOnly = true)
    public Integer llcOf(long itemId) {
        return computeLlc(mapper.edges()).get(itemId);
    }

    /** 위상 정렬(Kahn) + 가장 긴 경로. O(품목 + 간선) */
    static Map<Long, Integer> computeLlc(List<Edge> edges) {
        Map<Long, List<Long>> kids = new HashMap<>();
        Map<Long, Integer> indeg = new HashMap<>();
        for (Edge ed : edges) {
            kids.computeIfAbsent(ed.getParentId(), k -> new ArrayList<>()).add(ed.getChildId());
            indeg.merge(ed.getChildId(), 1, Integer::sum);
            indeg.putIfAbsent(ed.getParentId(), 0);
        }
        Map<Long, Integer> level = new HashMap<>();
        ArrayDeque<Long> todo = new ArrayDeque<>();
        indeg.forEach((n, d) -> {
            if (d == 0) {
                todo.add(n);
                level.put(n, 0);
            }
        });
        Map<Long, Integer> left = new HashMap<>(indeg);
        while (!todo.isEmpty()) {
            long n = todo.poll();
            for (long c : kids.getOrDefault(n, List.of())) {
                level.merge(c, level.get(n) + 1, Math::max);
                if (left.merge(c, -1, Integer::sum) == 0) {
                    todo.add(c);
                }
            }
        }
        Set<Long> cyclic = new HashSet<>();
        left.forEach((n, d) -> {
            if (d > 0) {
                cyclic.add(n);
            }
        });
        cyclic.forEach(n -> level.put(n, -1));
        return level;
    }

    // ------------------------------------------------------------------ 공통

    private BomRow head(long bomId) {
        BomRow b = mapper.findById(bomId);
        if (b == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return b;
    }

    private ItemLookup item(String cd, String field, FieldErrors e) {
        if (cd == null || cd.isBlank()) {
            e.add(field, "REQUIRED");
            return null;
        }
        ItemLookup it = mapper.findItemByCd(cd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            e.add(field, "NOT_FOUND");
        }
        return it;
    }

    private ItemLookup activeItem(String cd, String field, FieldErrors e) {
        ItemLookup it = item(cd, field, e);
        if (it != null && !"Y".equals(it.getUseYn())) {
            e.add(field, "INACTIVE");
            return null;
        }
        return it;
    }

    private static RuntimeException cycleOr(DataIntegrityViolationException ex, String where) {
        if (PgErrors.constraint(ex).filter("ck_mst_bom_cycle"::equals).isPresent()) {
            return new BusinessException(HbErrorCode.BOM_CYCLE_AT, where);
        }
        return ex;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
