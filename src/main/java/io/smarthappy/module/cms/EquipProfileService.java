package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDtos.InstalledAsset;
import io.smarthappy.module.cms.CmsDtos.ProfileExport;
import io.smarthappy.module.cms.CmsDtos.ProfileForm;
import io.smarthappy.module.cms.CmsDtos.ProfilePage;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.module.cms.CmsDtos.ProfileSort;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비 프로필 (cms-equip-profile) — docs/33 C1·D15. 설비 1:1(중요도 A/B/C·핵심 설비·기본 보전 담당).
 * 행이 없으면 C·N·담당 없음으로 보이고, 처음 저장할 때 만든다(rowVer 없음 = 새로). P1 mst_equip 은 읽기만.
 */
@Service
@RequiredArgsConstructor
public class EquipProfileService {

    static final String PROG = "cms-equip-profile";
    static final Set<String> CRITS = Set.of("A", "B", "C");
    /** 목록 상한 — 공장·검색어로 좁힌다 */
    static final int MAX_ROWS = 1000;
    /** 이론 사이클 상한 — numeric(10,3) */
    static final java.math.BigDecimal MAX_CT = new java.math.BigDecimal("9999999.999");

    private final CmsMapper mapper;
    private final UserDirectory users;
    private final PolicyService policies;
    private final ExcelExportService excel;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public List<ProfileRow> list(String plantCd, String keyword, String criticality, String keyOnly) {
        checkListArgs(keyword, criticality, keyOnly);
        return mapper.findProfiles(blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), blank(criticality) ? null : criticality, "Y".equals(keyOnly),
                MAX_ROWS);
    }

    private static void checkListArgs(String keyword, String criticality, String keyOnly) {
        if (!blank(criticality) && !CRITS.contains(criticality) || keyword != null && keyword.length() > 100
                || !blank(keyOnly) && !"Y".equals(keyOnly) && !"N".equals(keyOnly)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link ProfileSort}) */
    @Transactional(readOnly = true)
    public ProfilePage grid(String plantCd, String keyword, String criticality, String keyOnly, Integer page, Integer size, List<String> sort,
            Boolean total) {
        checkListArgs(keyword, criticality, keyOnly);
        String pl = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String c = blank(criticality) ? null : criticality;
        boolean key = "Y".equals(keyOnly);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ProfileSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("plantCd,asc", "equipCd,asc"));
        List<ProfileRow> rows = mapper.findProfilePage(pl, k, c, key, pq);
        return new ProfilePage(rows, pq.isWithTotal() ? mapper.countProfiles(pl, k, c, key) : null);
    }

    /** grid 열(화면 설비 표와 같은 칸). 중요도 A·B·C 는 코드 그룹 없이 글자 그대로 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("equipCd", "cms.col.equipCd", 110).withSort(),
            ColumnSpec.text("equipNm", "cms.col.equipNm", 160).withSort(),
            ColumnSpec.text("plantCd", "cms.col.plant", 80).withSort(),
            ColumnSpec.text("classCd", "cms.profile.col.class", 100).withSort(),
            ColumnSpec.text("locationCd", "cms.profile.col.location", 100).withSort(),
            ColumnSpec.text("criticality", "cms.profile.col.criticality", 70).withSort(),
            ColumnSpec.text("keyEquipYn", "cms.profile.col.keyEquip", 80).withSort(),   // 화면이 Y 만 "핵심" 표시(N = 빈 칸)
            ColumnSpec.text("ownerNm", "cms.profile.col.owner", 100).withSort(),
            ColumnSpec.of("openMwoCnt", "cms.profile.col.openMwo", ColumnType.INT, 80));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 열 바꿈 — 핵심 설비는 화면이 Y 만 표시하지만 엑셀은 예/아니오(프로필 없는 설비 = 아니오, 목록 쿼리 coalesce) */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "keyEquipYn", List.of(ColumnSpec.text("keyEquipNm", "cms.profile.col.keyEquip", 80)));

    /** 엑셀(C16) — grid 와 같은 조건·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(ProfileExport req, HttpServletResponse response) throws IOException {
        ProfileExport r = req == null ? new ProfileExport(null, null, null, null, null, null) : req;
        checkListArgs(r.keyword(), r.criticality(), r.keyOnly());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        String pl = blank(r.plantCd()) ? null : r.plantCd().strip().toUpperCase(Locale.ROOT);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        String c = blank(r.criticality()) ? null : r.criticality();
        boolean key = "Y".equals(r.keyOnly());
        PageQuery pq = PageQuery.sortOnly(r.sort(), ProfileSort.values(), List.of("plantCd,asc", "equipCd,asc"));
        Locale locale = LocaleContextHolder.getLocale();
        List<String> keys = new ArrayList<>();
        if (pl != null) {
            keys.add("plantCd");
        }
        if (k != null) {
            keys.add("keyword");
        }
        if (c != null) {
            keys.add("criticality");
        }
        if (key) {
            keys.add("keyOnly");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsEquipProfile", cols, mapper.countProfiles(pl, k, c, key), keys),
                w -> mapper.findProfilePage(pl, k, c, key, pq).forEach(p -> {
                    p.setKeyEquipNm(messages.getMessage("Y".equals(p.getKeyEquipYn()) ? "js.common.yes" : "js.common.no", null, locale));
                    w.accept(p);
                }));
    }

    @Transactional(readOnly = true)
    public ProfileRow get(long equipId) {
        ProfileRow p = mapper.findProfile(equipId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    @Transactional(readOnly = true)
    public List<InstalledAsset> installed(long equipId) {
        get(equipId);
        return mapper.findInstalledAssets(equipId);
    }

    /** 저장 — rowVer 가 없으면 새 행(이미 있으면 409), 있으면 수정(다르면 409) */
    @Transactional
    public void save(long equipId, ProfileForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ProfileRow cur = get(equipId);
        FieldErrors e = FieldErrors.create();
        String crit = blank(in.criticality()) ? "C" : in.criticality().strip();
        if (!CRITS.contains(crit)) {
            e.add("criticality", "CODE");
        }
        String key = yn(in.keyEquipYn(), "N", "keyEquipYn", e);
        Long owner = null;
        if (!blank(in.ownerLoginId())) {
            String login = in.ownerLoginId().strip();
            UserRef u = users.byLogin(login);
            if (u == null) {
                e.add("ownerLoginId", "NO_SUCH_CODE");
            } else {
                owner = u.userId();
                boolean same = owner.equals(cur.getOwnerUserId());
                if (!same && !u.active()) {
                    e.add("ownerLoginId", "INACTIVE");
                }
            }
        }
        java.math.BigDecimal ct = CmsSupport.decimal(in.idealCtSec(), "idealCtSec", 3, e);
        if (ct != null && (ct.signum() <= 0 || ct.compareTo(MAX_CT) > 0)) {
            e.add("idealCtSec", "RANGE", "0.001", "9999999.999");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        try {
            if (in.rowVer() == null) {
                if (cur.getRowVer() != null) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                mapper.insertProfile(equipId, crit, key, owner, ct, remark, me.loginId());
            } else if (mapper.updateProfile(equipId, crit, key, owner, ct, remark, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.CONFLICT);   // 동시에 처음 저장 — 다시 조회
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            if (c.startsWith("fk_cms_equip_profile_owner")) {
                throw CmsSupport.fieldError("ownerLoginId", "NO_SUCH_CODE");
            }
            throw ex;
        }
    }
}
