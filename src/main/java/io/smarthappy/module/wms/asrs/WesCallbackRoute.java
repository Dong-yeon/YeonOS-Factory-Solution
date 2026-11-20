package io.smarthappy.module.wms.asrs;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.LineRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqRow;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InventoryService;
import io.smarthappy.platform.messaging.InboundMessage;
import io.smarthappy.platform.messaging.InboundResult;
import io.smarthappy.platform.messaging.InboundRoute;
import io.smarthappy.platform.tenant.TenantContext;
import tools.jackson.databind.JsonNode;

/**
 * WES 콜백 수신 경로 WES_CALLBACK (docs/65 23.4) — E0 수신 저널(원문 먼저 저장 → 200, 처리는 비동기)이 부른다.
 *
 * <ul>
 * <li>업무 키 = reqKey + lineNo + ctrCd + status(상대 메시지 ID 금지 — 같은 콜백 297회·ID 매번 다름), 내용 = 수량·칸·오류(같은 키 다른 내용 = 충돌)</li>
 * <li>순서: 요청(reqKey) 단위로 줄 세움. 늦게 온 옛 상태는 되돌리지 않고 STALE</li>
 * <li>완료 + 오류 = 실패. 상태 코드를 범위로 묶지 않는다(아는 글자만)</li>
 * <li>입고 원장 반영은 STORED(로봇이 랙에 넣음) 때만, 같은 처리 트랜잭션에서 MOVE(투입구 → 자동창고 가상 로케이션)</li>
 * </ul>
 * 출고(OUT)는 {@link #handleOut} — 라인 단위 상태는 ctrCd "-", 원장은 출구 인계(HANDED_OVER) 때(docs/65 25.1).
 */
@Component
public class WesCallbackRoute implements InboundRoute {

    static final String ROUTE = "WES_CALLBACK";
    /** WES 입고 상태의 단계(늦게 온 옛 상태 판정) — 끝 상태는 FINAL */
    static final Map<String, Integer> IN_RANK = Map.of("ACCEPTED", 1, "NOT_STARTED", 1, "IN_PROGRESS", 2, "ARRIVED", 3,
            "STORED", 9, "FAILED", 9, "CANCELLED", 9);
    /** WES 출고 상태의 단계 — PICKED·HANDED_OVER 는 용기 단위, 나머지는 라인 단위(ctrCd = "-") */
    static final Map<String, Integer> OUT_RANK = Map.of("ACCEPTED", 1, "NOT_STARTED", 1, "IN_PROGRESS", 2, "PICKED", 3, "HANDED_OVER", 4,
            "COMPLETED", 9, "FAILED", 9, "CANCELLED", 9);

    private final AsrsMapper mapper;
    private final InventoryService inventory;

    public WesCallbackRoute(AsrsMapper mapper, InventoryService inventory) {
        this.mapper = mapper;
        this.inventory = inventory;
    }

    @Override
    public String routeCd() {
        return ROUTE;
    }

    @Override
    public List<String> keyFields() {
        return List.of("reqKey", "lineNo", "ctrCd", "status");
    }

    @Override
    public List<String> contentFields() {
        return List.of("qty", "slot", "errors");
    }

    @Override
    public String partField() {
        return "reqKey";
    }

    @Override
    public InboundResult handle(InboundMessage m) {
        JsonNode b = m.body();
        AsrsRow a = m.sourceId() == null ? null : mapper.findByTerminal(m.sourceId());
        if (a == null) {
            throw new Reject("NO_ASRS");                                       // 이 장치는 자동창고가 아니다
        }
        UUID key;
        try {
            key = UUID.fromString(text(b, "reqKey"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new Reject("REQ_KEY");
        }
        ReqRow r = mapper.lockReqByKey(a.getAsrsId(), key);
        if (r == null) {
            throw new Reject("UNKNOWN_REQ");                                   // 다른 자동창고·다른 회사 요청 포함
        }
        Integer lineNo = b.has("lineNo") && b.get("lineNo").canConvertToInt() ? b.get("lineNo").asInt() : null;
        LineRow l = lineNo == null ? null : mapper.lockLine(r.getReqId(), lineNo);
        if (l == null) {
            throw new Reject("UNKNOWN_LINE");
        }
        String status = text(b, "status") == null ? "" : text(b, "status").toUpperCase(Locale.ROOT);
        OffsetDateTime at = (m.occurredAt() == null ? m.receivedAt() : m.occurredAt()).atOffset(ZoneOffset.UTC);
        if ("OUT".equals(r.getReqType())) {
            return handleOut(a, r, l, b, status, m, at);
        }
        Integer rank = IN_RANK.get(status);
        if (rank == null) {
            event(a, r, l, "FAILED", status, "WES_ERROR", "unknown status", m, at, null);
            return InboundResult.rejected("STATUS");                           // 모르는 상태 — 사람이 본다
        }
        String ctrCd = text(b, "ctrCd");
        if (l.getCtrCd() == null || !l.getCtrCd().equals(ctrCd)) {
            event(a, r, l, "FAILED", status, "WRONG_ITEM", "ctr " + ctrCd + " ≠ " + l.getCtrCd(), m, at, null);
            return InboundResult.rejected("CTR_MISMATCH");                     // 요청한 그 용기가 아니다
        }
        boolean errors = b.has("errors") && b.get("errors").isArray() && !b.get("errors").isEmpty();
        Integer cur = l.getWesStatus() == null ? 0 : IN_RANK.getOrDefault(l.getWesStatus(), 0);
        boolean finished = "DONE".equals(l.getStatus()) || "FAILED".equals(l.getStatus()) || "CANCELLED".equals(l.getStatus());
        if (finished || rank < cur) {
            event(a, r, l, "STALE", status, null, "after " + l.getWesStatus(), m, at, null);
            return InboundResult.stale("OLDER");                               // 늦게 온 옛 상태 — 되돌리지 않는다
        }
        String by = cut(TenantContext.currentActor(), 50);
        // 완료 + 오류 = 실패(5.2-4)
        if ("FAILED".equals(status) || "CANCELLED".equals(status) || ("STORED".equals(status) && errors)) {
            String to = "CANCELLED".equals(status) ? "CANCELLED" : "FAILED";
            mapper.setLine(l.getLineId(), to, status, null, "WES_ERROR", by);
            event(a, r, l, "FAILED", status, "WES_ERROR", errors ? cut(b.get("errors").toString(), 400) : null, m, at, null);
            return InboundResult.done(to);
        }
        if (!"STORED".equals(status)) {
            mapper.setLine(l.getLineId(), rank <= 1 ? "NOT_STARTED" : "IN_PROGRESS", status, null, null, by);
            event(a, r, l, "STATUS", status, null, null, m, at, null);
            return InboundResult.done();                                       // 출입구 도착(ARRIVED)은 원장에 반영하지 않는다
        }
        return store(a, r, l, m, at, by);
    }

    /** 로봇이 랙에 넣음 — 원장 MOVE(투입구 → 가상 로케이션) + 용기 IN_SYSTEM, 한 트랜잭션 */
    private InboundResult store(AsrsRow a, ReqRow r, LineRow l, InboundMessage m, OffsetDateTime at, String by) {
        List<ContentRow> cs = mapper.contents(l.getCtrId());
        // 재고가 투입구에서 사라졌으면(정의 뒤 다른 이동) 원장을 깨지 않고 격리 — 사람이 확인(Q2: 덮어쓰지 않는다)
        for (ContentRow c : cs) {
            if (mapper.stockAt(c.getItemId(), a.getInNodeId(), c.getLotId(), c.getStockStatus()).compareTo(c.getQty()) < 0) {
                mapper.setLine(l.getLineId(), "FAILED", "STORED", null, "STOCK_SHORT", by);
                mapper.setCtrStatus(l.getCtrId(), "QUARANTINE", "STOCK_SHORT", by);
                event(a, r, l, "QUARANTINE", "STORED", "STOCK_SHORT", c.getItemCd() + " " + c.getQty().toPlainString(), m, at, null);
                return InboundResult.done("STOCK_SHORT");
            }
        }
        // 칸이 달라도 원장은 칸을 모른다(Q1) — 품목·LOT·상태별로 합쳐 이동 한 쌍씩(이동 묶음 = 두 행, 합 0)
        java.util.Map<String, ContentRow> merged = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> qty = new java.util.LinkedHashMap<>();
        for (ContentRow c : cs) {
            String k = c.getItemId() + ":" + c.getLotId() + ":" + c.getStockStatus();
            merged.putIfAbsent(k, c);
            qty.merge(k, c.getQty(), BigDecimal::add);
        }
        UUID grp = null;
        List<TrxReq> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (java.util.Map.Entry<String, ContentRow> en : merged.entrySet()) {
            ContentRow c = en.getValue();
            BigDecimal q = qty.get(en.getKey());
            UUID g = UUID.randomUUID();
            grp = grp == null ? g : grp;
            rows.add(new TrxReq("MOVE_OUT", c.getItemId(), a.getInNodeId(), c.getStockStatus(), q.negate(), g, "ASRS_IN", l.getLineId(),
                    null, l.getCtrCd(), at, "AUTO", null, c.getLotId()));
            rows.add(new TrxReq("MOVE_IN", c.getItemId(), a.getStoreNodeId(), c.getStockStatus(), q, g, "ASRS_IN", l.getLineId(),
                    null, l.getCtrCd(), at, "AUTO", null, c.getLotId()));
            total = total.add(q);
        }
        if (!rows.isEmpty()) {
            inventory.post(rows, "ASRS_IN:" + l.getLineId());
        }
        mapper.setLine(l.getLineId(), "DONE", "STORED", total, null, by);
        mapper.setCtrStatus(l.getCtrId(), "IN_SYSTEM", null, by);
        event(a, r, l, "POSTED", "STORED", null, rows.size() / 2 + " rows", m, at, grp);
        return InboundResult.done();
    }

    // ================================================================== 출고 (docs/65 25.1)

    /**
     * 출고 콜백. 라인 단위(ACCEPTED·IN_PROGRESS·COMPLETED·FAILED·CANCELLED)는 ctrCd = "-", 용기 단위(PICKED·HANDED_OVER)는 그 용기.
     * 원장은 HANDED_OVER(출구 인계) 때만. COMPLETED 인데 덜 집음 = 라인 SHORT(끝, 재요청은 사람 — Q-E4-2).
     */
    private InboundResult handleOut(AsrsRow a, ReqRow r, LineRow l, JsonNode b, String status, InboundMessage m, OffsetDateTime at) {
        Integer rank = OUT_RANK.get(status);
        if (rank == null) {
            event(a, r, l, null, "FAILED", status, "WES_ERROR", "unknown status", m, at, null);
            return InboundResult.rejected("STATUS");
        }
        String ctrCd = text(b, "ctrCd") == null ? "" : text(b, "ctrCd").toUpperCase(Locale.ROOT);
        boolean ctrLevel = "PICKED".equals(status) || "HANDED_OVER".equals(status);
        if (ctrLevel == "-".equals(ctrCd)) {
            event(a, r, l, null, "FAILED", status, "WRONG_ITEM", ctrLevel ? "container required" : "line status takes ctrCd -", m, at, null);
            return InboundResult.rejected("CTR_LEVEL");
        }
        if (isFinal(l.getStatus())) {
            event(a, r, l, null, "STALE", status, null, "after " + l.getStatus(), m, at, null);
            return InboundResult.stale("OLDER");
        }
        String by = cut(TenantContext.currentActor(), 50);
        boolean errors = b.has("errors") && b.get("errors").isArray() && !b.get("errors").isEmpty();
        if ("FAILED".equals(status) || "CANCELLED".equals(status) || ("COMPLETED".equals(status) && errors)) {
            String to = "CANCELLED".equals(status) ? "CANCELLED" : "FAILED";
            mapper.setLine(l.getLineId(), to, status, null, "WES_ERROR", by);
            event(a, r, l, null, "FAILED", status, "WES_ERROR", errors ? cut(b.get("errors").toString(), 400) : null, m, at, null);
            return InboundResult.done(to);
        }
        if ("COMPLETED".equals(status)) {
            if (l.getDoneQty().compareTo(l.getQty()) >= 0) {
                mapper.setLine(l.getLineId(), "DONE", status, null, null, by);
                event(a, r, l, null, "STATUS", status, null, null, m, at, null);
                return InboundResult.done();
            }
            // 덜 집고 완료 — 라인 SHORT 로 닫고 사건만(남은 수량 재요청은 사람)
            mapper.setLine(l.getLineId(), "SHORT", status, null, "SHORT", by);
            event(a, r, l, null, "SHORT", status, "SHORT", l.getDoneQty().stripTrailingZeros().toPlainString() + "/"
                    + l.getQty().stripTrailingZeros().toPlainString(), m, at, null);
            return InboundResult.done("SHORT");
        }
        int cur = l.getWesStatus() == null ? 0 : OUT_RANK.getOrDefault(l.getWesStatus(), 0);
        if (!ctrLevel) {
            if (rank < cur) {
                event(a, r, l, null, "STALE", status, null, "after " + l.getWesStatus(), m, at, null);
                return InboundResult.stale("OLDER");
            }
            mapper.setLine(l.getLineId(), rank <= 1 ? "NOT_STARTED" : "IN_PROGRESS", status, null, null, by);
            event(a, r, l, null, "STATUS", status, null, null, m, at, null);
            return InboundResult.done();
        }
        CtrRow c = mapper.lockCtrByCd(ctrCd);
        if (c == null || c.getAsrsId() != a.getAsrsId()) {
            event(a, r, l, null, "FAILED", status, "WRONG_ITEM", "unregistered " + cut(ctrCd, 40), m, at, null);
            return InboundResult.rejected("CTR_MISMATCH");
        }
        String wes = rank >= cur ? status : l.getWesStatus();                 // 라인 WES 상태는 앞으로만
        if ("PICKED".equals(status)) {
            mapper.setLine(l.getLineId(), "IN_PROGRESS", wes, null, null, by);
            event(a, r, l, c.getCtrId(), "STATUS", status, null, null, m, at, null);
            return InboundResult.done();
        }
        return handOver(a, r, l, c, b, wes, m, at, by);
    }

    /** 출구 인계 — 원장 MOVE(가상 로케이션 → 출구) + 사본 차감 + 라인 반영 수량. 맞지 않으면 원장 없이 격리(덮어쓰지 않는다, Q2) */
    private InboundResult handOver(AsrsRow a, ReqRow r, LineRow l, CtrRow c, JsonNode b, String wes, InboundMessage m, OffsetDateTime at,
            String by) {
        BigDecimal q = b.has("qty") && b.get("qty").isNumber() ? b.get("qty").decimalValue() : null;
        if (q == null || q.signum() <= 0 || q.stripTrailingZeros().scale() > 6) {
            event(a, r, l, c.getCtrId(), "FAILED", "HANDED_OVER", "OTHER", "qty", m, at, null);
            return InboundResult.rejected("QTY");
        }
        Integer slot = b.has("slot") && b.get("slot").canConvertToInt() ? b.get("slot").asInt() : null;
        if ("QUARANTINE".equals(c.getStatus())) {
            mapper.setLine(l.getLineId(), "IN_PROGRESS", wes, null, null, by);
            event(a, r, l, c.getCtrId(), "QUARANTINE", "HANDED_OVER", c.getExcReason(), "handed over from quarantined container " + q.toPlainString(),
                    m, at, null);
            return InboundResult.done("CTR_QUARANTINE");                          // 사람이 본다 — 원장 없음
        }
        if (q.compareTo(l.getQty().subtract(l.getDoneQty())) > 0) {
            return quarantine(a, r, l, c, "OVER_PICK", q.toPlainString() + " > " + l.getQty().subtract(l.getDoneQty()).toPlainString(), m, at, by);
        }
        // 사본에서 품목(지정 LOT)·칸 순으로 집는다
        List<ContentRow> cs = mapper.pickable(c.getCtrId(), l.getItemId(), l.getLotId(), slot);
        List<Object[]> takes = new ArrayList<>();
        BigDecimal left = q;
        for (ContentRow x : cs) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal t = x.getQty().min(left);
            takes.add(new Object[] {x, t});
            left = left.subtract(t);
        }
        if (left.signum() > 0) {
            return quarantine(a, r, l, c, "SHORT", "container has " + q.subtract(left).toPlainString() + " of " + q.toPlainString(), m, at, by);
        }
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        Map<String, ContentRow> keyRow = new LinkedHashMap<>();
        for (Object[] t : takes) {
            ContentRow x = (ContentRow) t[0];
            String k = x.getLotId() + ":" + x.getStockStatus();
            keyRow.putIfAbsent(k, x);
            merged.merge(k, (BigDecimal) t[1], BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> en : merged.entrySet()) {
            ContentRow x = keyRow.get(en.getKey());
            if (mapper.stockAt(l.getItemId(), a.getStoreNodeId(), x.getLotId(), x.getStockStatus()).compareTo(en.getValue()) < 0) {
                return quarantine(a, r, l, c, "STOCK_SHORT", "ledger short " + en.getValue().toPlainString(), m, at, by);
            }
        }
        UUID grp = null;
        List<TrxReq> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> en : merged.entrySet()) {
            ContentRow x = keyRow.get(en.getKey());
            UUID g = UUID.randomUUID();
            grp = grp == null ? g : grp;
            rows.add(new TrxReq("MOVE_OUT", l.getItemId(), a.getStoreNodeId(), x.getStockStatus(), en.getValue().negate(), g, "ASRS_OUT",
                    l.getLineId(), null, c.getCtrCd(), at, "AUTO", null, x.getLotId()));
            rows.add(new TrxReq("MOVE_IN", l.getItemId(), a.getOutNodeId(), x.getStockStatus(), en.getValue(), g, "ASRS_OUT", l.getLineId(),
                    null, c.getCtrCd(), at, "AUTO", null, x.getLotId()));
        }
        inventory.post(rows, "ASRS_OUT:" + l.getLineId() + ":" + c.getCtrId());
        for (Object[] t : takes) {
            ContentRow x = (ContentRow) t[0];
            BigDecimal rest = x.getQty().subtract((BigDecimal) t[1]);
            if (rest.signum() == 0) {
                mapper.deleteContent(x.getContentId());
            } else {
                mapper.setContentQty(x.getContentId(), rest, by);
            }
        }
        BigDecimal done = l.getDoneQty().add(q);
        mapper.setLine(l.getLineId(), done.compareTo(l.getQty()) >= 0 ? "DONE" : "IN_PROGRESS", wes, done, null, by);
        event(a, r, l, c.getCtrId(), "POSTED", "HANDED_OVER", null, q.toPlainString() + " (" + rows.size() / 2 + " pairs)", m, at, grp);
        return InboundResult.done();
    }

    private InboundResult quarantine(AsrsRow a, ReqRow r, LineRow l, CtrRow c, String reason, String detail, InboundMessage m, OffsetDateTime at,
            String by) {
        mapper.setLine(l.getLineId(), "IN_PROGRESS", l.getWesStatus(), null, reason, by);
        mapper.setCtrStatus(c.getCtrId(), "QUARANTINE", reason, by);
        event(a, r, l, c.getCtrId(), "QUARANTINE", "HANDED_OVER", reason, detail, m, at, null);
        return InboundResult.done(reason);
    }

    static boolean isFinal(String lineStatus) {
        return "DONE".equals(lineStatus) || "FAILED".equals(lineStatus) || "CANCELLED".equals(lineStatus) || "SHORT".equals(lineStatus);
    }

    private void event(AsrsRow a, ReqRow r, LineRow l, Long ctrId, String eventCd, String wes, String reason, String detail, InboundMessage m,
            OffsetDateTime at, UUID grp) {
        mapper.insertEvent(a.getAsrsId(), r.getReqId(), l.getLineId(), ctrId, eventCd,
                wes == null || !wes.matches("^[A-Z][A-Z0-9_]{0,19}$") ? null : wes, reason, detail, m.inboundId(), grp,
                a.getConnMode(), at, "AUTO", cut(TenantContext.currentActor(), 50));
    }

    private void event(AsrsRow a, ReqRow r, LineRow l, String eventCd, String wes, String reason, String detail, InboundMessage m,
            OffsetDateTime at, UUID grp) {
        mapper.insertEvent(a.getAsrsId(), r.getReqId(), l.getLineId(), l.getCtrId(), eventCd,
                wes == null || !wes.matches("^[A-Z][A-Z0-9_]{0,19}$") ? null : wes, reason, detail, m.inboundId(), grp,
                a.getConnMode(), at, "AUTO", cut(TenantContext.currentActor(), 50));
    }

    private static String text(JsonNode b, String f) {
        JsonNode v = b == null ? null : b.get(f);
        return v == null || v.isNull() ? null : v.asText().strip();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

}
