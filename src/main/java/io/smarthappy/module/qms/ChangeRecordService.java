package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.qms.ChangeEffectCalc.AttrStats;
import io.smarthappy.module.qms.ChangeEffectCalc.VarStats;
import io.smarthappy.module.qms.ChangeRecordDtos.CharCmp;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectChar;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectCompare;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectForm;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectRow;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectView;
import io.smarthappy.module.qms.ChangeRecordDtos.ItemCmp;
import io.smarthappy.module.qms.ChangeRecordDtos.ItemCounts;
import io.smarthappy.module.qms.ChangeRecordDtos.ItemSide;
import io.smarthappy.module.qms.ChangeRecordDtos.MeasPoint;
import io.smarthappy.module.qms.ChangeRecordDtos.RecordsView;
import io.smarthappy.module.qms.ChangeRecordDtos.RevertForm;
import io.smarthappy.module.qms.ChangeRecordDtos.RevertRow;
import io.smarthappy.module.qms.ChangeRecordDtos.RevertView;
import io.smarthappy.module.qms.ChangeRecordDtos.VoidForm;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/**
 * 4M 임시 변경 복귀 확인 · 변경 효과 확인 (docs/29_P3 51장, docs/66 (나)·(다)).
 *
 * - 둘 다 적용(RELEASED) 뒤의 판정 기록 — ECO 마다 살아 있는 기록 하나, 정정 = 무효(사유) 뒤 다시(트리거가 최종)
 * - 복귀는 임시(TEMP) ECO 만. 표시 끝(PLM)은 바꾸지 않고 차이를 경고로만(Q4-2)
 * - 효과 비교 표는 읽기만(Q4-3 — 검사 강화 없음), 판정은 사람
 * - 서명: 정책 QMS_ESIGN_REQUIRED(기본 N) — 대상 qms_change + eco_id, 정규 글자에 기록 id(Q4-6, 51.2 RV-7)
 */
@Service
public class ChangeRecordService {

    static final Set<String> REVERT_TYPES = Set.of("RESTORED", "SUPERSEDED");
    static final Set<String> RESULTS = Set.of("OK", "NG");
    static final int DEFAULT_N = 30;
    static final int DEFAULT_DAYS = 30;
    /** 복귀 기한 후보 상한(계약 한 번 500 × 10) — 넘는 회사는 앞쪽만 센다 */
    static final int CANDIDATE_MAX = 5000;

    private final ChangeRecordMapper mapper;
    private final ChangeMapper changes;
    private final ObjectProvider<EcoLookup> ecoLookup;
    private final BizClock clock;
    private final QmsESign esign;

    public ChangeRecordService(ChangeRecordMapper mapper, ChangeMapper changes, ObjectProvider<EcoLookup> ecoLookup, BizClock clock,
            QmsESign esign) {
        this.mapper = mapper;
        this.changes = changes;
        this.ecoLookup = ecoLookup;
        this.clock = clock;
        this.esign = esign;
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public RecordsView records(long ecoId) {
        EcoView v = eco(ecoId);
        var head = changes.findHead(ecoId);
        List<RevertRow> reverts = mapper.findReverts(ecoId);
        List<EffectRow> effects = mapper.findEffects(ecoId);
        RevertRow liveR = reverts.stream().filter(r -> "ACTIVE".equals(r.getStatus())).findFirst().orElse(null);
        EffectRow liveE = effects.stream().filter(r -> "ACTIVE".equals(r.getStatus())).findFirst().orElse(null);
        boolean temp = "TEMP".equals(v.changeKind());
        boolean released = released(v);
        RevertView rv = new RevertView(temp, temp && released && head != null, head == null ? null : head.getRevertCond(), v.releasedOn(),
                v.markUntil(), due(v, liveR != null), markGap(liveR, v.markUntil()), liveR, reverts);
        EffectView ev = new EffectView(released && head != null, v.releasedOn(), liveE, effects);
        Set<String> hashes = hashes(v.ecoNo(), reverts, effects);
        return new RecordsView(ecoId, v.ecoNo(), head != null, rv, ev, esign.required(),
                esign.list(QmsESign.CHANGE, ecoId).stream().map(s -> new QmsESign.SignView(s.esignId(), s.signerNm(), s.meaningCd(),
                        s.authMethod(), s.signedAt(), hashes.contains(s.contentHash()))).toList());
    }

    /** 기록 무결성 점검(docs/52 Y1) — 4M 세부의 복귀·효과 기록 하나하나가 서명 내용 */
    @Transactional(readOnly = true)
    public java.util.Optional<Set<String>> signHashes(long ecoId) {
        var head = changes.findHead(ecoId);
        if (head == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(hashes(head.getEcoNo(), mapper.findReverts(ecoId), mapper.findEffects(ecoId)));
    }

    private static Set<String> hashes(String ecoNo, List<RevertRow> reverts, List<EffectRow> effects) {
        Set<String> out = new HashSet<>();
        reverts.forEach(r -> out.add(ESignService.hash(QmsESign.revert(ecoNo, r))));
        effects.forEach(r -> out.add(ESignService.hash(QmsESign.effect(ecoNo, r))));
        return out;
    }

    /** 복귀 상태 — 임시 아님·적용 전 null / 기록됨 DONE / 표시 끝 지남 OVERDUE / 그 밖 DUE (홈 카드와 같은 판정) */
    String due(EcoView v, boolean hasLive) {
        if (!"TEMP".equals(v.changeKind()) || !released(v)) {
            return null;
        }
        if (hasLive) {
            return "DONE";
        }
        return v.markUntil() != null && v.markUntil().isBefore(clock.today()) ? "OVERDUE" : "DUE";
    }

    /** 살아 있는 복귀일 vs 지금 표시 끝 — 저장하지 않는다(51.2 RV-4) */
    static String markGap(RevertRow live, LocalDate markUntil) {
        if (live == null || markUntil == null) {
            return null;
        }
        int c = live.getRevertedOn().compareTo(markUntil);
        return c < 0 ? "EARLY" : c > 0 ? "LATE" : null;
    }

    /**
     * 복귀 기한 지난 임시 변경 — 4M 세부가 있는 TEMP·RELEASED ECO 중 살아 있는 복귀 기록이 없고 표시 끝 &lt; 오늘(회사 날짜).
     * 홈 카드·목록 필터가 같은 함수(51.2 RV-5). 순서 = 표시 끝·eco_id. 트랜잭션 안에서 부른다
     */
    public List<EcoView> overdueTemps(LocalDate today) {
        List<Long> ids = mapper.revertCandidates(CANDIDATE_MAX);
        List<EcoView> out = new ArrayList<>();
        EcoLookup lookup = EcoLookup.of(ecoLookup);
        for (int i = 0; i < ids.size(); i += EcoLookup.MAX_IDS) {
            for (EcoView v : lookup.ecos(ids.subList(i, Math.min(ids.size(), i + EcoLookup.MAX_IDS)))) {
                if ("TEMP".equals(v.changeKind()) && released(v) && v.markUntil() != null && v.markUntil().isBefore(today)) {
                    out.add(v);
                }
            }
        }
        out.sort(Comparator.comparing(EcoView::markUntil).thenComparing(EcoView::ecoId));
        return out;
    }

    // ------------------------------------------------------------------ 복귀 기록

    @Transactional
    public long recordRevert(long ecoId, RevertForm in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        EcoView v = eco(ecoId);
        FieldErrors e = FieldErrors.create();
        String type = VisibleText.isBlank(in.revertType()) ? null : in.revertType().strip();
        if (type == null) {
            e.add("revertType", "REQUIRED");
        } else if (!REVERT_TYPES.contains(type)) {
            e.add("revertType", "CODE");
        }
        LocalDate on = date(in.revertedOn(), "revertedOn", e);
        String note = VisibleText.reason(in.note(), "note", 1, 1000, e);
        e.throwIfAny();
        if (!"TEMP".equals(v.changeKind()) || !released(v) || changes.findHead(ecoId) == null) {
            throw new BusinessException(HbErrorCode.QMS_REVERT_STATE);
        }
        if (v.releasedOn() != null && on.isBefore(v.releasedOn())) {
            e.add("revertedOn", "BEFORE_RELEASED");
        } else if (on.isAfter(clock.today())) {
            e.add("revertedOn", "FUTURE");
        }
        e.throwIfAny();
        if (mapper.findReverts(ecoId).stream().anyMatch(r -> "ACTIVE".equals(r.getStatus()))) {
            throw new BusinessException(HbErrorCode.QMS_REVERT_STATE);
        }
        long id = mapper.insertRevert(ecoId, type, on, note, me.loginId());
        RevertRow saved = mapper.findRevert(id);
        esign.sign(in.esignToken(), QmsESign.APPROVE, QmsESign.CHANGE, ecoId, QmsESign.revert(v.ecoNo(), saved));
        return id;
    }

    @Transactional
    public void voidRevert(long revertId, VoidForm in) {
        LoginUser me = SamplingService.me();
        RevertRow r = mapper.findRevert(revertId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String reason = voidReason(in);
        if (!"ACTIVE".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_REVERT_STATE);
        }
        if (mapper.voidRevert(revertId, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 효과 판정

    @Transactional
    public long recordEffect(long ecoId, EffectForm in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        int n = n(in.n());
        int days = days(in.days());
        EcoView v = eco(ecoId);
        FieldErrors e = FieldErrors.create();
        String result = VisibleText.isBlank(in.result()) ? null : in.result().strip();
        if (result == null) {
            e.add("result", "REQUIRED");
        } else if (!RESULTS.contains(result)) {
            e.add("result", "CODE");
        }
        String note = VisibleText.reason(in.note(), "note", 1, 2000, e);
        e.throwIfAny();
        if (!released(v) || v.releasedOn() == null || changes.findHead(ecoId) == null) {
            throw new BusinessException(HbErrorCode.QMS_EFFECT_STATE);
        }
        if (mapper.findEffects(ecoId).stream().anyMatch(r -> "ACTIVE".equals(r.getStatus()))) {
            throw new BusinessException(HbErrorCode.QMS_EFFECT_STATE);
        }
        long id = mapper.insertEffect(ecoId, result, note, v.releasedOn(), n, days, me.loginId());
        EffectRow saved = mapper.findEffect(id);
        esign.sign(in.esignToken(), QmsESign.APPROVE, QmsESign.CHANGE, ecoId, QmsESign.effect(v.ecoNo(), saved));
        return id;
    }

    @Transactional
    public void voidEffect(long effectId, VoidForm in) {
        LoginUser me = SamplingService.me();
        EffectRow r = mapper.findEffect(effectId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String reason = voidReason(in);
        if (!"ACTIVE".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_EFFECT_STATE);
        }
        if (mapper.voidEffect(effectId, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 비교 표

    /** 변경 전후 비교(읽기만, 51.3) — 적용 전이면 ready = false 빈 표 */
    @Transactional(readOnly = true)
    public EffectCompare compare(long ecoId, Integer nIn, Integer daysIn) {
        int n = n(nIn);
        int days = days(daysIn);
        EcoView v = eco(ecoId);
        if (!released(v) || v.releasedOn() == null) {
            return new EffectCompare(false, null, n, days, false, 0, List.of(), List.of());
        }
        LocalDate baseDate = v.releasedOn();
        OffsetDateTime base = baseDate.atStartOfDay(clock.zone()).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime end = base.plusDays(days);
        boolean partial = end.isAfter(now);
        OffsetDateTime afterTo = partial ? now : end;
        int elapsed = (int) Math.max(0, Math.min(days, ChronoUnit.DAYS.between(baseDate, clock.today())));

        List<CharCmp> chars = new ArrayList<>();
        for (EffectChar c : mapper.effectChars(ecoId)) {
            // 제품 특성은 그 품목, 공정 특성은 ECO 대상 품목(없으면 그 공정의 모든 품목) — 51.3 EF-2
            List<Long> items = c.getItemId() != null ? List.of(c.getItemId()) : v.itemIds();
            List<MeasPoint> before = mapper.meas(c.getCharId(), c.getDataType(), items, base, false, n);
            List<MeasPoint> after = afterTo.isAfter(base) ? mapper.meas(c.getCharId(), c.getDataType(), items, base, true, n) : List.of();
            if ("ATTRIBUTE".equals(c.getDataType())) {
                chars.add(new CharCmp(c.getLineNo(), c.getCharId(), c.getRefLabel(), c.getCharNm(), c.getDataType(), null, null,
                        c.getScale(), c.getUnitNm(), null, null, attr(before), attr(after)));
            } else {
                chars.add(new CharCmp(c.getLineNo(), c.getCharId(), c.getRefLabel(), c.getCharNm(), c.getDataType(), c.getLsl(), c.getUsl(),
                        c.getScale(), c.getUnitNm(), var(before, c), var(after, c), null, null));
            }
        }
        List<ItemCmp> items = new ArrayList<>();
        if (!v.itemIds().isEmpty()) {
            Map<Long, ItemCounts> b = mapper.itemCounts(v.itemIds(), base.minusDays(days), base).stream()
                    .collect(Collectors.toMap(ItemCounts::getItemId, Function.identity()));
            for (ItemCounts a : mapper.itemCounts(v.itemIds(), base, afterTo.isAfter(base) ? afterTo : base)) {
                items.add(new ItemCmp(a.getItemId(), a.getItemCd(), a.getItemNm(), a.getUnitCd(), side(b.get(a.getItemId())), side(a)));
            }
        }
        return new EffectCompare(true, baseDate, n, days, partial, elapsed, chars, items);
    }

    private static VarStats var(List<MeasPoint> pts, EffectChar c) {
        return ChangeEffectCalc.variable(pts.stream().map(MeasPoint::getValue).toList(), c.getLsl(), c.getUsl());
    }

    private static AttrStats attr(List<MeasPoint> pts) {
        return ChangeEffectCalc.attribute(pts.size(), (int) pts.stream().filter(p -> "N".equals(p.getOkYn())).count());
    }

    private static ItemSide side(ItemCounts c) {
        if (c == null) {
            return new ItemSide(0, 0, null, 0, "0");
        }
        BigDecimal q = c.getNcrQty() == null ? BigDecimal.ZERO : c.getNcrQty();
        return new ItemSide(c.getInspCnt(), c.getInspNg(), ChangeEffectCalc.rate(c.getInspNg(), c.getInspCnt()), c.getNcrCnt(),
                q.signum() == 0 ? "0" : q.stripTrailingZeros().toPlainString());
    }

    // ------------------------------------------------------------------ 공통

    private EcoView eco(long ecoId) {
        return EcoLookup.of(ecoLookup).eco(ecoId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
    }

    private static boolean released(EcoView v) {
        return "RELEASED".equals(v.status());
    }

    private static int n(Integer v) {
        int x = v == null ? DEFAULT_N : v;
        if (x < 5 || x > 200) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return x;
    }

    private static int days(Integer v) {
        int x = v == null ? DEFAULT_DAYS : v;
        if (x < 1 || x > 180) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return x;
    }

    private static String voidReason(VoidForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        return reason;
    }

    private static LocalDate date(String v, String field, FieldErrors e) {
        if (VisibleText.isBlank(v)) {
            e.add(field, "REQUIRED");
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v.strip());
            if (d.getYear() < 2000 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }
}
