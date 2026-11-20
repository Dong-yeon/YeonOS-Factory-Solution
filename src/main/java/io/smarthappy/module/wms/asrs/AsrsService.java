package io.smarthappy.module.wms.asrs;

import java.math.BigDecimal;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentLine;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.EventRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.InReqForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.LineRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.OutLine;
import io.smarthappy.module.wms.asrs.AsrsDtos.OutReqForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqView;
import io.smarthappy.module.wms.asrs.AsrsDtos.UrgentForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.UrgentStats;
import io.smarthappy.module.wms.asrs.AsrsDtos.WesContent;
import io.smarthappy.module.wms.asrs.AsrsDtos.WesLine;
import io.smarthappy.module.wms.asrs.AsrsDtos.WesRequest;
import io.smarthappy.module.wms.label.PrinterAddress;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;

/**
 * 자동창고 화면(wms-asrs) — 자동창고·용기 유형·용기 마스터, 용기 내용 정의, 입고·출고 요청·보내기·긴급 표시 (docs/65 23.1·23.2·25.1·25.2).
 * 대사는 {@link AsrsReconService}.
 */
@Service
public class AsrsService {

    static final String PROG = "wms-asrs";
    static final String TOPIC = AsrsWesSender.TOPIC;
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern CTR_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Set<String> STATUSES = Set.of("AVAILABLE", "QC", "HOLD", "BLOCKED");
    static final int MAX_LINES = 200;

    private final AsrsMapper mapper;
    private final InboxService inbox;
    private final OutboxService outbox;
    private final BizClock clock;
    private final boolean allowLoopback;

    public AsrsService(AsrsMapper mapper, InboxService inbox, OutboxService outbox, BizClock clock,
            @Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.outbox = outbox;
        this.clock = clock;
        this.allowLoopback = allowLoopback;
    }

    // ================================================================== 자동창고

    @Transactional(readOnly = true)
    public List<AsrsRow> asrsList(String plantCd) {
        return mapper.findAsrs(VisibleText.isBlank(plantCd) ? null : plantCd.strip());
    }

    @Transactional
    public long createAsrs(AsrsForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("WMS_ASRS_CREATE");
        AsrsForm f = validateAsrs(in, null);
        try {
            return mapper.insertAsrs(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void updateAsrs(long id, AsrsForm in) {
        LoginUser me = me();
        AsrsRow cur = mapper.findAsrsById(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        AsrsForm f = validateAsrs(in, cur);
        try {
            if (mapper.updateAsrs(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private AsrsForm validateAsrs(AsrsForm in, AsrsRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = cur != null ? cur.getPlantCd() : VisibleText.isBlank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        String cd = cur != null ? cur.getAsrsCd() : VisibleText.isBlank(in.asrsCd()) ? null : in.asrsCd().strip().toUpperCase(Locale.ROOT);
        Long store = cur != null ? Long.valueOf(cur.getStoreNodeId()) : in.storeNodeId();
        if (cur != null) {
            if ((in.plantCd() != null && !in.plantCd().strip().equalsIgnoreCase(cur.getPlantCd()))
                    || (in.asrsCd() != null && !in.asrsCd().strip().equalsIgnoreCase(cur.getAsrsCd()))
                    || (in.storeNodeId() != null && in.storeNodeId() != cur.getStoreNodeId())) {
                e.add("asrsCd", "FIXED");
            }
        } else {
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            }
            if (cd == null) {
                e.add("asrsCd", "REQUIRED");
            } else if (!CODE.matcher(cd).matches()) {
                e.add("asrsCd", "FORMAT");
            }
            node(store, "storeNodeId", plant, "AUTO_STORE", e);
        }
        String nm = VisibleText.isBlank(in.asrsNm()) ? null : in.asrsNm().strip();
        if (nm == null) {
            e.add("asrsNm", "REQUIRED");
        } else if (nm.length() > 100) {
            e.add("asrsNm", "TOO_LONG", 100);
        }
        node(in.inNodeId(), "inNodeId", plant, null, e);
        node(in.outNodeId(), "outNodeId", plant, null, e);
        if (store != null && (store.equals(in.inNodeId()) || store.equals(in.outNodeId()))) {
            e.add("inNodeId", "SAME_AS_STORE");                       // 투입구·출구는 자동창고 밖 칸
        }
        if (in.terminalId() == null) {
            e.add("terminalId", "REQUIRED");
        } else if (!mapper.deviceActive(in.terminalId())) {
            e.add("terminalId", "NOT_FOUND");
        }
        String conn = VisibleText.isBlank(in.connMode()) ? "DIRECT" : in.connMode().strip();
        String url = VisibleText.isBlank(in.wesUrl()) ? null : in.wesUrl().strip();
        if (!"DIRECT".equals(conn) && !"EDGE".equals(conn)) {
            e.add("connMode", "CODE");
        } else if ("DIRECT".equals(conn)) {
            String p = urlProblem(url);
            if (p != null) {
                e.add("wesUrl", p);
            }
        } else {
            url = null;                                                 // EDGE 는 서버가 밀지 않는다
        }
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        return new AsrsForm(plant, cd, nm, store, in.inNodeId(), in.outNodeId(), in.terminalId(), conn, url, use, remark, in.rowVer());
    }

    /** WES 주소 — http(s), 사설 대역 IP 글자 또는 이름(보낼 때마다 다시 해석·검사) */
    String urlProblem(String url) {
        if (url == null) {
            return "REQUIRED";
        }
        if (url.length() > 300 || !url.matches("^https?://[A-Za-z0-9.:\\-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$")) {
            return "FORMAT";
        }
        URI u = URI.create(url);
        int port = u.getPort() < 0 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort();
        PrinterAddress.Problem p = PrinterAddress.checkStatic(u.getHost(), port, null, allowLoopback);
        return p == null ? null : p.name();
    }

    private void node(Long id, String field, String plant, String usage, FieldErrors e) {
        if (id == null) {
            e.add(field, "REQUIRED");
            return;
        }
        Map<String, Object> n = mapper.nodeInfo(id);
        if (n == null || !"Y".equals(n.get("useYn")) || (plant != null && !plant.equals(n.get("plantCd")))) {
            e.add(field, "NOT_FOUND");                                  // 다른 회사 노드는 RLS 로 안 보인다
        } else if (usage != null && !usage.equals(n.get("usage"))) {
            e.add(field, "USAGE_" + usage);                            // 가상 로케이션은 로케이션 속성 용도 AUTO_STORE
        }
    }

    // ================================================================== 용기 유형·용기

    @Transactional(readOnly = true)
    public List<CtrTypeRow> types() {
        return mapper.findTypes();
    }

    @Transactional
    public long saveType(Long id, CtrTypeForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CtrTypeRow cur = id == null ? null : mapper.findType(id);
        if (id != null && cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getTypeCd() : VisibleText.isBlank(in.typeCd()) ? null : in.typeCd().strip().toUpperCase(Locale.ROOT);
        if (cur == null && (cd == null || !CODE.matcher(cd).matches())) {
            e.add("typeCd", cd == null ? "REQUIRED" : "FORMAT");
        }
        if (cur != null && in.typeCd() != null && !in.typeCd().strip().equalsIgnoreCase(cur.getTypeCd())) {
            e.add("typeCd", "FIXED");
        }
        String nm = VisibleText.isBlank(in.typeNm()) ? null : in.typeNm().strip();
        if (nm == null || nm.length() > 100) {
            e.add("typeNm", nm == null ? "REQUIRED" : "TOO_LONG", 100);
        }
        range(in.lengthMm(), "lengthMm", 50, 5000, e);
        range(in.widthMm(), "widthMm", 50, 5000, e);
        range(in.heightMm(), "heightMm", 10, 5000, e);
        int slots = in.slotCnt() == null ? 1 : in.slotCnt();
        if (slots < 1 || slots > 64) {
            e.add("slotCnt", "RANGE", 1, 64);
        }
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        CtrTypeForm f = new CtrTypeForm(cd, nm, in.lengthMm(), in.widthMm(), in.heightMm(), slots, use, in.rowVer());
        try {
            if (cur == null) {
                inbox.claimRequestKey("WMS_CTR_TYPE_CREATE");
                return mapper.insertType(f, me.loginId());
            }
            if (in.rowVer() == null || mapper.updateType(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private static void range(Integer v, String field, int min, int max, FieldErrors e) {
        if (v == null) {
            e.add(field, "REQUIRED");
        } else if (v < min || v > max) {
            e.add(field, "RANGE", min, max);
        }
    }

    @Transactional(readOnly = true)
    public List<CtrRow> ctrs(Long asrsId, String status, String q) {
        String like = VisibleText.isBlank(q) ? null : "%" + q.strip().toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
        return mapper.findCtrs(asrsId, VisibleText.isBlank(status) ? null : status.strip(), like);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ctr(long id) {
        CtrRow c = mapper.findCtr(id);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ctr", c);
        m.put("contents", mapper.contents(id));
        m.put("events", mapper.eventsOfCtr(id));
        return m;
    }

    @Transactional
    public long saveCtr(Long id, CtrForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CtrRow cur = id == null ? null : mapper.findCtr(id);
        if (id != null && cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getCtrCd() : VisibleText.isBlank(in.ctrCd()) ? null : in.ctrCd().strip().toUpperCase(Locale.ROOT);
        if (cur == null) {
            if (cd == null || !CTR_CD.matcher(cd).matches()) {
                e.add("ctrCd", cd == null ? "REQUIRED" : "FORMAT");
            }
            if (in.asrsId() == null || mapper.findAsrsById(in.asrsId()) == null) {
                e.add("asrsId", in.asrsId() == null ? "REQUIRED" : "NOT_FOUND");
            }
        } else if ((in.ctrCd() != null && !in.ctrCd().strip().equalsIgnoreCase(cur.getCtrCd()))
                || (in.asrsId() != null && in.asrsId() != cur.getAsrsId())) {
            e.add("ctrCd", "FIXED");
        }
        CtrTypeRow t = in.typeId() == null ? null : mapper.findType(in.typeId());
        if (t == null) {
            e.add("typeId", in.typeId() == null ? "REQUIRED" : "NOT_FOUND");
        } else if (!"Y".equals(t.getUseYn())) {
            e.add("typeId", "INACTIVE");
        }
        if (cur != null && t != null && t.getTypeId() != cur.getTypeId() && !"EMPTY".equals(cur.getStatus())) {
            e.add("typeId", "CTR_NOT_EMPTY");                          // 칸 수가 바뀌면 내용이 칸 밖이 된다
        }
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        CtrForm f = new CtrForm(cur == null ? in.asrsId() : Long.valueOf(cur.getAsrsId()), cd, in.typeId(), use, in.rowVer());
        try {
            if (cur == null) {
                inbox.claimRequestKey("WMS_CTR_CREATE");
                return mapper.insertCtr(f, me.loginId());
            }
            if (in.rowVer() == null || mapper.updateCtr(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 용기 내용 정의(전체 덮어쓰기 — 빠진 칸은 비워짐, docs/65 8.2). 밖에 있는 용기(EMPTY·LOADED)만, 입고 요청 라인이 걸린 용기는 거절.
     * 담을 재고는 그 자동창고의 투입구 노드에 있어야 한다(원장 그대로 — 반영은 랙에 넣은 시점). 같은 칸 같은 품목 다른 LOT 은 경고만.
     */
    @Transactional
    public List<String> defineContents(long ctrId, ContentForm in) {
        LoginUser me = me();
        CtrRow c = mapper.lockCtr(ctrId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null || in.rowVer() != c.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"EMPTY".equals(c.getStatus()) && !"LOADED".equals(c.getStatus())) {
            throw new BusinessException(HbErrorCode.CONFLICT);                 // 자동창고 안·격리 용기는 사람이 고치지 않는다(WES 가 주인)
        }
        if (mapper.hasActiveLine(ctrId)) {
            throw new BusinessException(HbErrorCode.CONFLICT);                 // 입고 요청이 걸림 — 시작 뒤에는 거절
        }
        CtrTypeRow t = mapper.findType(c.getTypeId());
        AsrsRow a = mapper.findAsrsById(c.getAsrsId());
        List<ContentLine> lines = in.lines() == null ? List.of() : in.lines();
        if (lines.size() > 200) {
            throw fieldError("lines", "TOO_MANY_ROWS", 200);
        }
        FieldErrors e = FieldErrors.create();
        List<Object[]> rows = new ArrayList<>();
        Map<String, BigDecimal> need = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ContentLine l = lines.get(i);
            String row = String.valueOf(i);
            if (l == null) {
                e.row(row).add("slotNo", "REQUIRED");
                continue;
            }
            int slot = l.slotNo() == null ? 0 : l.slotNo();
            if (slot < 1 || slot > t.getSlotCnt()) {
                e.row(row).add("slotNo", "RANGE", 1, t.getSlotCnt());
            }
            Long itemId = VisibleText.isBlank(l.itemCd()) ? null : mapper.itemIdByCd(l.itemCd().strip().toUpperCase(Locale.ROOT));
            if (itemId == null) {
                e.row(row).add("itemCd", VisibleText.isBlank(l.itemCd()) ? "REQUIRED" : "NOT_FOUND");
            }
            Long lotId = null;
            if (itemId != null && !VisibleText.isBlank(l.lotNo())) {
                lotId = mapper.lotIdByNo(itemId, l.lotNo().strip());
                if (lotId == null) {
                    e.row(row).add("lotNo", "NOT_FOUND");
                }
            }
            String status = VisibleText.isBlank(l.stockStatus()) ? "AVAILABLE" : l.stockStatus().strip();
            if (!STATUSES.contains(status)) {
                e.row(row).add("stockStatus", "CODE");
            }
            BigDecimal qty = l.qty();
            if (qty == null || qty.signum() <= 0) {
                e.row(row).add("qty", qty == null ? "REQUIRED" : "RANGE", ">0", "");
            } else if (qty.stripTrailingZeros().scale() > 6) {
                e.row(row).add("qty", "SCALE", 6);
            }
            if (itemId != null && qty != null && qty.signum() > 0) {
                String key = itemId + ":" + lotId + ":" + status;
                if (!seen.add(slot + ":" + key)) {
                    e.row(row).add("itemCd", "DUPLICATE");
                }
                need.merge(key, qty, BigDecimal::add);
                rows.add(new Object[] {slot, itemId, lotId, status, qty});
            }
        }
        e.throwIfAny();
        // 담을 재고가 투입구에 있는가(품목·LOT·상태별 합)
        for (Map.Entry<String, BigDecimal> n : need.entrySet()) {
            String[] k = n.getKey().split(":");
            long itemId = Long.parseLong(k[0]);
            Long lotId = "null".equals(k[1]) ? null : Long.valueOf(k[1]);
            BigDecimal have = mapper.stockAt(itemId, a.getInNodeId(), lotId, k[2]);
            if (have.compareTo(n.getValue()) < 0) {
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);
            }
        }
        mapper.deleteContents(ctrId);
        for (Object[] r : rows) {
            mapper.insertContent(ctrId, (Integer) r[0], (Long) r[1], (Long) r[2], (String) r[3], (BigDecimal) r[4], me.loginId());
        }
        mapper.setCtrStatus(ctrId, rows.isEmpty() ? "EMPTY" : "LOADED", null, me.loginId());
        CtrRow after = mapper.findCtr(ctrId);
        return after.isMixedLot() ? List.of("MIXED_LOT") : List.of();
    }


    // ================================================================== 입고 요청

    @Transactional
    public long createIn(InReqForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("WMS_ASRS_IN");
        if (in == null || in.asrsId() == null) {
            throw fieldError("asrsId", "REQUIRED");
        }
        AsrsRow a = mapper.findAsrsById(in.asrsId());
        if (a == null || !"Y".equals(a.getUseYn())) {
            throw fieldError("asrsId", a == null ? "NOT_FOUND" : "INACTIVE");
        }
        List<String> cds = in.ctrCds() == null ? List.of() : in.ctrCds();
        if (cds.isEmpty() || cds.size() > MAX_LINES) {
            throw fieldError("ctrCds", cds.isEmpty() ? "REQUIRED" : "TOO_MANY_ROWS", MAX_LINES);
        }
        FieldErrors e = FieldErrors.create();
        List<Long> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cds.size(); i++) {
            String cd = cds.get(i) == null ? "" : cds.get(i).strip().toUpperCase(Locale.ROOT);
            CtrRow c = cd.isEmpty() ? null : mapper.lockCtrByCd(cd);
            if (c == null || c.getAsrsId() != a.getAsrsId()) {
                e.row(String.valueOf(i)).add("ctrCd", "NOT_FOUND");
            } else if (!seen.add(cd)) {
                e.row(String.valueOf(i)).add("ctrCd", "DUPLICATE");
            } else if (!"LOADED".equals(c.getStatus())) {
                e.row(String.valueOf(i)).add("ctrCd", "CTR_NOT_LOADED");     // 용기 정의를 먼저(8.2)
            } else {
                ids.add(c.getCtrId());
            }
        }
        e.throwIfAny();
        String remark = VisibleText.optional(in.remark(), "remark", 500, FieldErrors.create());
        long reqId = mapper.insertReq(a.getAsrsId(), "IN", UUID.randomUUID(), remark, me.loginId());
        try {
            for (int i = 0; i < ids.size(); i++) {
                mapper.insertLine(reqId, i + 1, ids.get(i), null, null, null, me.loginId());
            }
        } catch (DataIntegrityViolationException ex) {
            if ("uq_wms_asrs_req_line_ctr".equals(PgErrors.constraint(ex).orElse(null))) {
                throw fieldError("ctrCds", "CTR_IN_REQUEST");                  // 같은 용기가 다른 요청에 걸려 있다
            }
            throw ex;
        }
        return reqId;
    }

    /** 보내기 — DIRECT = 아웃박스로 WES 주소에 POST, EDGE = 엣지가 당겨 갈 큐(QUEUED) */
    @Transactional
    public String send(long reqId, Integer rowVer) {
        LoginUser me = me();
        ReqRow r = mapper.lockReq(reqId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (rowVer == null || rowVer != r.getRowVer() || !"DRAFT".equals(r.getSendState())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        AsrsRow a = mapper.findAsrsById(r.getAsrsId());
        WesRequest body = wesRequest(r, a);
        if ("EDGE".equals(a.getConnMode())) {
            mapper.setSendState(reqId, "QUEUED", null, me.loginId());
            event(a.getAsrsId(), reqId, null, null, "SENT", null, null, "QUEUED for edge", "USER");
            return "QUEUED";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", a.getWesUrl());
        payload.put("reqKey", r.getReqKey().toString());
        payload.put("body", body);
        UUID msg = outbox.publish(TOPIC, "ASRS:" + r.getReqKey(), payload);
        mapper.setSendState(reqId, "SENT", msg, me.loginId());
        event(a.getAsrsId(), reqId, null, null, "SENT", null, null, "outbox " + msg, "USER");
        return "SENT";
    }

    /** 취소 — 보내기 전(DRAFT·QUEUED)만. 보낸 뒤는 WES 가 주인(시작 뒤 거절, 8.2) */
    @Transactional
    public void cancel(long reqId, Integer rowVer) {
        LoginUser me = me();
        ReqRow r = mapper.lockReq(reqId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (rowVer == null || rowVer != r.getRowVer() || !("DRAFT".equals(r.getSendState()) || "QUEUED".equals(r.getSendState()))) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.cancelLines(reqId, me.loginId());
        mapper.setSendState(reqId, "CANCELLED", null, me.loginId());
        event(r.getAsrsId(), reqId, null, null, "CANCELLED", null, null, null, "USER");
    }

    WesRequest wesRequest(ReqRow r, AsrsRow a) {
        List<WesLine> lines = new ArrayList<>();
        for (LineRow l : mapper.lines(r.getReqId())) {
            if (!"NOT_STARTED".equals(l.getStatus())) {
                continue;
            }
            List<WesContent> cs = new ArrayList<>();
            if (l.getCtrId() != null) {
                for (ContentRow c : mapper.contents(l.getCtrId())) {
                    cs.add(new WesContent(c.getSlotNo(), c.getItemCd(), c.getLotNo(), c.getQty().stripTrailingZeros()));
                }
            }
            lines.add(new WesLine(l.getLineNo(), l.getCtrCd(), cs, l.getItemCd(), l.getLotNo(), l.getQty()));
        }
        // 우선순위는 긴급만 — 일반 출고는 칸 자체를 보내지 않는다(우선순위가 병렬을 순차로 바꾼 실측, Q-E4-4)
        return new WesRequest(r.getReqKey(), r.getReqType(), a.getAsrsCd(), "Y".equals(r.getUrgentYn()) ? "URGENT" : null, lines);
    }

    // ================================================================== 출고 (docs/65 25.1)

    /**
     * 출고 요청 — 라인 = 품목 + 선택 LOT(없으면 WES 가 고름, Q-E4-3) + 수량. 가상 로케이션 AVAILABLE − 격리 용기 − 다른 열린 출고 라인이
     * 모자라면 409(원장 그대로). 용기 고르기·순서는 WES.
     */
    @Transactional
    public long createOut(OutReqForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("WMS_ASRS_OUT");
        if (in == null || in.asrsId() == null) {
            throw fieldError("asrsId", "REQUIRED");
        }
        AsrsRow a = mapper.findAsrsById(in.asrsId());
        if (a == null || !"Y".equals(a.getUseYn())) {
            throw fieldError("asrsId", a == null ? "NOT_FOUND" : "INACTIVE");
        }
        List<OutLine> ls = in.lines() == null ? List.of() : in.lines();
        if (ls.isEmpty() || ls.size() > MAX_LINES) {
            throw fieldError("lines", ls.isEmpty() ? "REQUIRED" : "TOO_MANY_ROWS", MAX_LINES);
        }
        FieldErrors e = FieldErrors.create();
        List<Object[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Long, BigDecimal> perItem = new LinkedHashMap<>();
        for (int i = 0; i < ls.size(); i++) {
            OutLine l = ls.get(i);
            String row = String.valueOf(i);
            if (l == null) {
                e.row(row).add("itemCd", "REQUIRED");
                continue;
            }
            Long itemId = VisibleText.isBlank(l.itemCd()) ? null : mapper.itemIdByCd(l.itemCd().strip().toUpperCase(Locale.ROOT));
            if (itemId == null) {
                e.row(row).add("itemCd", VisibleText.isBlank(l.itemCd()) ? "REQUIRED" : "NOT_FOUND");
            }
            Long lotId = null;
            if (itemId != null && !VisibleText.isBlank(l.lotNo())) {
                lotId = mapper.lotIdByNo(itemId, l.lotNo().strip());
                if (lotId == null) {
                    e.row(row).add("lotNo", "NOT_FOUND");
                }
            }
            BigDecimal qty = l.qty();
            if (qty == null || qty.signum() <= 0) {
                e.row(row).add("qty", qty == null ? "REQUIRED" : "RANGE", ">0", "");
            } else if (qty.stripTrailingZeros().scale() > 6) {
                e.row(row).add("qty", "SCALE", 6);
            }
            if (itemId != null && qty != null && qty.signum() > 0) {
                if (!seen.add(itemId + ":" + lotId)) {
                    e.row(row).add("itemCd", "DUPLICATE");
                }
                perItem.merge(itemId, qty, BigDecimal::add);
                rows.add(new Object[] {itemId, lotId, qty});
            }
        }
        e.throwIfAny();
        // 쓸 수 있는 수량 — 라인(LOT 지정이면 그 LOT)마다 + 품목 합
        for (Object[] r : rows) {
            if (r[1] != null && mapper.outFree(a.getAsrsId(), a.getStoreNodeId(), (Long) r[0], (Long) r[1]).compareTo((BigDecimal) r[2]) < 0) {
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);
            }
        }
        for (Map.Entry<Long, BigDecimal> n : perItem.entrySet()) {
            if (mapper.outFree(a.getAsrsId(), a.getStoreNodeId(), n.getKey(), null).compareTo(n.getValue()) < 0) {
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);
            }
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, FieldErrors.create());
        long reqId = mapper.insertReq(a.getAsrsId(), "OUT", UUID.randomUUID(), remark, me.loginId());
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            mapper.insertLine(reqId, i + 1, null, (Long) r[0], (Long) r[1], (BigDecimal) r[2], me.loginId());
        }
        return reqId;
    }

    /**
     * 긴급 표시(Q-E4-4) — 보내기 전(DRAFT) 출고 요청만, APPROVE 권한(컨트롤러), 사유 2자 이상. 사건 URGENT(누가·언제·사유).
     * 표시는 되돌리지 않는다(DB 트리거 — 남용 기록이 지워지지 않게).
     */
    @Transactional
    public void markUrgent(long reqId, UrgentForm in) {
        LoginUser me = me();
        ReqRow r = mapper.lockReq(reqId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null || in.rowVer() != r.getRowVer() || !"DRAFT".equals(r.getSendState())
                || "Y".equals(r.getUrgentYn())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"OUT".equals(r.getReqType())) {
            throw fieldError("reqType", "URGENT_OUT_ONLY");
        }
        String reason = VisibleText.isBlank(in.reason()) ? null : in.reason().strip();
        if (reason == null || reason.length() < 2) {
            throw fieldError("reason", "REQUIRED");
        }
        if (reason.length() > 200) {
            throw fieldError("reason", "TOO_LONG", 200);
        }
        if (mapper.markUrgent(reqId, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        event(r.getAsrsId(), reqId, null, null, "URGENT", null, null, reason, "USER");
    }

    /** 긴급 남용 감시 — 기간(1~90일) 안 출고 요청 수·긴급 수·표시한 사람별 건수(사건 원장에서) */
    @Transactional(readOnly = true)
    public UrgentStats urgentStats(Integer days) {
        int d = days == null ? 7 : Math.max(1, Math.min(90, days));
        return new UrgentStats(d, mapper.outCount(d), mapper.urgentCount(d), mapper.urgentByUser(d));
    }

    @Transactional(readOnly = true)
    public List<ReqRow> reqs(Long asrsId, String sendState) {
        return mapper.findReqs(asrsId, VisibleText.isBlank(sendState) ? null : sendState.strip(), 300);
    }

    @Transactional(readOnly = true)
    public ReqView req(long id) {
        ReqRow r = mapper.findReq(id);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<EventRow> ev = mapper.eventsOfReq(id);
        return new ReqView(r, mapper.lines(id), ev);
    }

    // ================================================================== 공통

    void event(long asrsId, Long reqId, Long lineId, Long ctrId, String eventCd, String wes, String reason, String detail, String src) {
        mapper.insertEvent(asrsId, reqId, lineId, ctrId, eventCd, wes, reason, detail == null ? null : cut(detail, 500), null, null, src,
                clock.instant().atOffset(ZoneOffset.UTC), "USER".equals(src) ? "KEY" : "AUTO", cut(TenantContext.currentActor(), 50));
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        if (c == null) {
            return e;
        }
        return switch (c) {
            case "uq_wms_asrs_cd" -> fieldError("asrsCd", "DUPLICATE");
            case "uq_wms_asrs_terminal" -> fieldError("terminalId", "DUPLICATE");
            case "uq_wms_asrs_store" -> fieldError("storeNodeId", "DUPLICATE");
            case "uq_wms_ctr_type_cd" -> fieldError("typeCd", "DUPLICATE");
            case "uq_wms_ctr_cd" -> fieldError("ctrCd", "DUPLICATE");
            case "ck_wms_asrs_store_usage" -> fieldError("storeNodeId", "USAGE_AUTO_STORE");
            default -> e;
        };
    }

    static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }
}
