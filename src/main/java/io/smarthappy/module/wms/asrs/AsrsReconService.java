package io.smarthappy.module.wms.asrs;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.asrs.AsrsDtos.AdjustIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.DiffRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.DismissIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.OurContent;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReconRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReconView;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapContent;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapCtr;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapView;
import io.smarthappy.module.wms.inv.InvDtos.AdjustForm;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자동창고 대사 + 바로 조정 (docs/65 25.3, 사용자 결정 Q-E4-1 2026-11-10 — 이 화면에서 APPROVE 로 바로 조정).
 *
 * <ul>
 * <li>WES 전체 용기 정의를 조각으로 받는다 — (snapKey, partNo) 재전송 = DUP, 마지막 조각이 다 모이면 비교</li>
 * <li>비교 = 용기·칸·품목·LOT 별 수량. 움직이는 중인 용기는 건너뛴다(헛 차이). 차이 용기 = 격리 RECON_DIFF</li>
 * <li>조정 = {@link StockService#adjust} 로만(원장 직접 INSERT 금지) — 재고 조정 한도·승인 정책(docs/46 11장, 결정 대기)이 생기면
 * 그 한 곳에 걸어 이 경로도 같은 정책을 탄다. 같은 차이는 한 번만(잠금·rowVer·끝 상태 고정·조정 요청 id 유일)</li>
 * </ul>
 */
@Service
public class AsrsReconService {

    static final String ADJ_REASON = "WMS_ADJ_REASON";
    static final Pattern SNAP_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    static final int PART_MAX_CTRS = 2000;
    static final int PART_MAX = 10000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AsrsMapper mapper;
    private final AsrsDeviceService device;
    private final StockService stock;
    private final BizClock clock;

    public AsrsReconService(AsrsMapper mapper, AsrsDeviceService device, StockService stock, BizClock clock) {
        this.mapper = mapper;
        this.device = device;
        this.stock = stock;
        this.clock = clock;
    }

    // ================================================================== 장비: 스냅숏 받기

    @Transactional
    public SnapView snapshot(TerminalPrincipal d, SnapIn in) {
        AsrsRow a = device.asrsOf(d);
        String key = in == null || in.snapKey() == null ? "" : in.snapKey().strip();
        if (!SNAP_KEY.matcher(key).matches()) {
            throw AsrsService.fieldError("snapKey", "FORMAT");
        }
        int partNo = in.partNo() == null ? 0 : in.partNo();
        if (partNo < 1 || partNo > PART_MAX) {
            throw AsrsService.fieldError("partNo", "RANGE", 1, PART_MAX);
        }
        List<SnapCtr> cs = in.containers() == null ? List.of() : in.containers();
        if (cs.size() > PART_MAX_CTRS) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, cs.size(), PART_MAX_CTRS);
        }
        ReconRow r = mapper.lockRecon(a.getAsrsId(), key);
        if (r == null) {
            mapper.insertRecon(a.getAsrsId(), key, actor());                    // 동시에 두 조각이 와도 하나(on conflict)
            r = mapper.lockRecon(a.getAsrsId(), key);
        }
        if ("DONE".equals(r.getStatus()) || mapper.partExists(r.getReconId(), partNo)) {
            return new SnapView("DUP", r.getReconId(), null, null);
        }
        mapper.insertPart(r.getReconId(), partNo, JSON.writeValueAsString(cs));
        mapper.setReconParts(r.getReconId(), Boolean.TRUE.equals(in.lastPart()) ? partNo : null);
        ReconRow now = mapper.lockRecon(a.getAsrsId(), key);
        if (now.getExpectedParts() == null || now.getPartCnt() < now.getExpectedParts()) {
            return new SnapView("RECEIVED", r.getReconId(), null, null);
        }
        return compare(a, now);
    }

    /** 다 모인 조각을 우리 사본과 비교 — 차이 줄 + 격리 + 사건 */
    private SnapView compare(AsrsRow a, ReconRow r) {
        // WES 쪽: 용기·칸·품목·LOT → 수량
        Map<String, BigDecimal> wes = new LinkedHashMap<>();
        Map<String, String[]> wesKey = new HashMap<>();
        for (String body : mapper.parts(r.getReconId())) {
            List<SnapCtr> cs = JSON.readValue(body, new TypeReference<List<SnapCtr>>() { });
            for (SnapCtr c : cs) {
                String ctr = c == null || c.ctrCd() == null ? null : c.ctrCd().strip().toUpperCase(Locale.ROOT);
                if (ctr == null || ctr.isEmpty() || c.contents() == null) {
                    continue;
                }
                for (SnapContent x : c.contents()) {
                    if (x == null || x.slot() == null || VisibleText.isBlank(x.itemCd()) || x.qty() == null || x.qty().signum() < 0) {
                        continue;
                    }
                    String item = x.itemCd().strip().toUpperCase(Locale.ROOT);
                    String lot = VisibleText.isBlank(x.lotNo()) ? "" : x.lotNo().strip();
                    String k = ctr + "|" + x.slot() + "|" + item + "|" + lot;
                    wes.merge(k, x.qty(), BigDecimal::add);
                    wesKey.putIfAbsent(k, new String[] {ctr, String.valueOf(x.slot()), item, lot});
                }
            }
        }
        // 우리 쪽: IN_SYSTEM·QUARANTINE 용기 사본
        Map<String, BigDecimal> ours = new LinkedHashMap<>();
        Map<String, OurContent> ourRow = new HashMap<>();
        Map<String, Set<String>> ourStatus = new HashMap<>();
        for (OurContent o : mapper.ourContents(a.getAsrsId())) {
            String k = o.getCtrCd() + "|" + o.getSlotNo() + "|" + o.getItemCd() + "|" + (o.getLotNo() == null ? "" : o.getLotNo());
            ours.merge(k, o.getQty(), BigDecimal::add);
            ourRow.putIfAbsent(k, o);
            ourStatus.computeIfAbsent(k, x -> new TreeSet<>()).add(o.getStockStatus());
        }
        Map<String, CtrRow> ctrs = new HashMap<>();
        for (CtrRow c : mapper.ctrsOf(a.getAsrsId())) {
            ctrs.put(c.getCtrCd(), c);
        }
        Set<Long> moving = new HashSet<>(mapper.movingCtrs(a.getAsrsId()));
        Set<String> keys = new TreeSet<>(wes.keySet());
        keys.addAll(ours.keySet());
        Set<String> seenCtr = new HashSet<>();
        Set<String> skippedCtr = new HashSet<>();
        Set<Long> diffCtr = new LinkedHashSet<>();
        int diffCnt = 0;
        for (String k : keys) {
            String[] p = wesKey.containsKey(k) ? wesKey.get(k) : k.split("\\|", -1);
            String ctrCd = p[0];
            CtrRow c = ctrs.get(ctrCd);
            if (c != null && moving.contains(c.getCtrId())) {
                skippedCtr.add(ctrCd);
                continue;
            }
            seenCtr.add(ctrCd);
            BigDecimal o = ours.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal w = wes.getOrDefault(k, BigDecimal.ZERO);
            OurContent or = ourRow.get(k);
            DiffRow d = new DiffRow();
            d.setReconId(r.getReconId());
            d.setAsrsId(a.getAsrsId());
            d.setCtrCd(ctrCd);
            d.setSlotNo(Integer.parseInt(p[1]));
            d.setItemCd(p[2]);
            d.setLotNo(p[3].isEmpty() ? null : p[3]);
            d.setOurQty(o);
            d.setWesQty(w);
            boolean known = c != null && ("IN_SYSTEM".equals(c.getStatus()) || "QUARANTINE".equals(c.getStatus()));
            if (or != null) {
                d.setItemId(or.getItemId());
                d.setLotId(or.getLotId());
            } else if (known) {
                Long itemId = mapper.itemIdByCd(p[2]);
                d.setItemId(itemId);
                if (itemId != null && d.getLotNo() != null) {
                    d.setLotId(mapper.lotIdByNo(itemId, d.getLotNo()));
                }
            }
            boolean unknown = !known || d.getItemId() == null || (d.getLotNo() != null && d.getLotId() == null);
            if (!unknown && o.compareTo(w) == 0) {
                continue;
            }
            d.setCtrId(c == null ? null : c.getCtrId());
            if (unknown) {
                d.setDiffKind("UNKNOWN");                                        // WES 만 아는 용기·품목·LOT — 조정 불가, 확인만
                d.setOurQty(BigDecimal.ZERO);
                d.setItemId(null);
                d.setLotId(null);
            } else {
                d.setDiffKind("QTY");
                Set<String> st = ourStatus.get(k);
                d.setStockStatus(st == null ? "AVAILABLE" : st.size() == 1 ? st.iterator().next() : null);
            }
            mapper.insertDiff(d);
            diffCnt++;
            if (c != null) {
                diffCtr.add(c.getCtrId());
            }
        }
        String by = actor();
        for (long ctrId : diffCtr) {
            CtrRow c = mapper.lockCtr(ctrId);
            if ("IN_SYSTEM".equals(c.getStatus())) {
                mapper.setCtrStatus(ctrId, "QUARANTINE", "RECON_DIFF", by);    // 출고 할당 제외 — 사람이 확인
            }
            event(a.getAsrsId(), ctrId, "RECON_DIFF", "RECON_DIFF", "recon #" + r.getReconId(), "SYSTEM");
        }
        seenCtr.removeAll(skippedCtr);
        mapper.finishRecon(r.getReconId(), seenCtr.size(), skippedCtr.size(), diffCnt);
        event(a.getAsrsId(), null, "RECON", null, "recon #" + r.getReconId() + " ctr " + seenCtr.size() + " skipped " + skippedCtr.size()
                + " diff " + diffCnt, a.getConnMode());
        return new SnapView("DONE", r.getReconId(), diffCnt, skippedCtr.size());
    }

    // ================================================================== 화면: 조회·조정·확인

    @Transactional(readOnly = true)
    public List<ReconRow> recons(Long asrsId) {
        return mapper.recons(asrsId, 100);
    }

    @Transactional(readOnly = true)
    public ReconView recon(long id) {
        ReconRow r = mapper.recon(id);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new ReconView(r, mapper.diffs(id));
    }

    /**
     * 차이 바로 조정(APPROVE — 컨트롤러). 원장 = {@link StockService#adjust}(ADJ_IN/ADJ_OUT, 가상 로케이션, 차이 = WES − 우리,
     * clientReqId = ASRS-RECON-{diffId}). 사본도 WES 값으로 맞추고, 용기의 열린 차이가 다 닫히면 격리 해제.
     */
    @Transactional
    public PostResult adjust(long diffId, AdjustIn in) {
        LoginUser me = me();
        DiffRow d = mapper.lockDiff(diffId);
        if (d == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null || in.rowVer() != d.getRowVer() || !"OPEN".equals(d.getStatus())) {
            throw new BusinessException(HbErrorCode.CONFLICT);                     // 같은 차이를 두 번 조정하지 않는다
        }
        if (!"QTY".equals(d.getDiffKind()) || d.getCtrId() == null || d.getItemId() == null || d.getStockStatus() == null) {
            throw AsrsService.fieldError("diffId", "NOT_ADJUSTABLE");             // 모르는 용기·품목, 상태가 섞인 칸 — 확인만
        }
        String reason = VisibleText.isBlank(in.reasonCd()) ? null : in.reasonCd().strip();
        if (reason == null) {
            throw AsrsService.fieldError("reasonCd", "REQUIRED");
        }
        if (!mapper.codeUsable(ADJ_REASON, reason)) {
            throw AsrsService.fieldError("reasonCd", "CODE");
        }
        CtrRow c = mapper.lockCtr(d.getCtrId());
        // 대사 뒤 사본이 바뀌었으면(그 사이 출고 등) 다시 대사
        if (mapper.contentQty(d.getCtrId(), d.getSlotNo(), d.getItemId(), d.getLotId()).compareTo(d.getOurQty()) != 0
                || !("IN_SYSTEM".equals(c.getStatus()) || "QUARANTINE".equals(c.getStatus()))) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        AsrsRow a = mapper.findAsrsById(d.getAsrsId());
        String remark = VisibleText.isBlank(in.remark()) ? null : in.remark().strip();
        String note = "ASRS recon #" + d.getReconId() + " " + d.getCtrCd() + " slot " + d.getSlotNo() + (remark == null ? "" : " — " + remark);
        String req = "ASRS-RECON-" + diffId;
        PostResult res = stock.adjust(new AdjustForm(a.getPlantCd(), a.getStoreNodeId(), d.getItemId(), d.getStockStatus(),
                d.getWesQty().subtract(d.getOurQty()), reason, AsrsService.cut(note, 500), null, null, "KEY", req, d.getLotNo()));
        // 사본을 WES 값으로
        ContentRow x = mapper.contentOf(d.getCtrId(), d.getSlotNo(), d.getItemId(), d.getLotId(), d.getStockStatus());
        if (x != null && d.getWesQty().signum() == 0) {
            mapper.deleteContent(x.getContentId());
        } else if (x != null) {
            mapper.setContentQty(x.getContentId(), d.getWesQty(), me.loginId());
        } else if (d.getWesQty().signum() > 0) {
            mapper.insertContent(d.getCtrId(), d.getSlotNo(), d.getItemId(), d.getLotId(), d.getStockStatus(), d.getWesQty(), me.loginId());
        }
        if (mapper.resolveDiff(diffId, "ADJUSTED", req, reason, AsrsService.cut(remark, 500), me.loginId(), d.getRowVer()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        event(d.getAsrsId(), d.getCtrId(), "ADJUSTED", null, "diff #" + diffId + " " + d.getOurQty().stripTrailingZeros().toPlainString() + " → "
                + d.getWesQty().stripTrailingZeros().toPlainString() + " (" + reason + ")", "USER");
        release(c, me.loginId());
        return res;
    }

    /** 우리 쪽이 맞다고 확인(APPROVE) — 원장 그대로, WES 정정은 사람. 비고 필수 */
    @Transactional
    public void dismiss(long diffId, DismissIn in) {
        LoginUser me = me();
        DiffRow d = mapper.lockDiff(diffId);
        if (d == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null || in.rowVer() != d.getRowVer() || !"OPEN".equals(d.getStatus())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String remark = VisibleText.isBlank(in.remark()) ? null : in.remark().strip();
        if (remark == null || remark.length() < 2) {
            throw AsrsService.fieldError("remark", "REQUIRED");
        }
        if (mapper.resolveDiff(diffId, "DISMISSED", null, null, AsrsService.cut(remark, 500), me.loginId(), d.getRowVer()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        event(d.getAsrsId(), d.getCtrId(), "DISMISSED", null, "diff #" + diffId + " — " + AsrsService.cut(remark, 400), "USER");
        if (d.getCtrId() != null) {
            release(mapper.lockCtr(d.getCtrId()), me.loginId());
        }
    }

    /** 대사 차이로 격리한 용기 — 열린 차이가 없으면 해제 */
    private void release(CtrRow c, String by) {
        if ("QUARANTINE".equals(c.getStatus()) && "RECON_DIFF".equals(c.getExcReason()) && mapper.openDiffCnt(c.getCtrId()) == 0) {
            mapper.setCtrStatus(c.getCtrId(), "IN_SYSTEM", null, by);
        }
    }

    private void event(long asrsId, Long ctrId, String eventCd, String reason, String detail, String src) {
        mapper.insertEvent(asrsId, null, null, ctrId, eventCd, null, reason, AsrsService.cut(detail, 500), null, null, src,
                clock.instant().atOffset(ZoneOffset.UTC), "USER".equals(src) ? "KEY" : "AUTO", actor());
    }

    private static String actor() {
        return AsrsService.cut(TenantContext.currentActor(), 50);
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
