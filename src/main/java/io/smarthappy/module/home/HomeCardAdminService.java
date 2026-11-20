package io.smarthappy.module.home;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.home.HomeMapper.GroupRow;
import io.smarthappy.module.home.HomeMapper.PrefRow;
import io.smarthappy.module.home.HomeService.Layout;
import io.smarthappy.module.home.HomeService.SettingForm;
import io.smarthappy.platform.auth.AuthMapperFacade;
import io.smarthappy.platform.auth.ProgInfo;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 역할별 기본 카드 세트 관리 (home-card-mgt, docs/37 15장).
 * <ul>
 * <li>세트 = 권한그룹 × 카드 × 순서·숨김. 사용자 설정이 없을 때 그 그룹 사용자의 홈 순서가 된다(HomeService.Layout)</li>
 * <li>세트는 <b>권한을 주지 않는다</b> — 카드가 보이는지는 여전히 화면 조회 권한(R1). 그래서 관리 화면은 그룹이 지금 못 보는 카드도
 *     세트에 넣을 수 있고(권한을 받으면 그 자리), 화면에 "이 그룹은 화면 권한이 없음" 으로 알린다</li>
 * <li>한 벌 저장(지우고 다시 넣기) + 판(版) 지문으로 동시 편집 409. 같은 그룹 저장은 권고 잠금으로 줄 세운다</li>
 * <li>카드 숫자는 계산하지 않는다(제목·설명만) — 관리자가 남의 업무 건수를 이 화면으로 보지 않게</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class HomeCardAdminService {

    public static final String PROG = "home-card-mgt";
    /** 저장 목록 상한 — 정상 화면은 등록 카드 수만큼만 보낸다 */
    static final int MAX_ROWS = 200;

    private final HomeService home;
    private final HomeMapper mapper;
    private final AuthMapperFacade progs;
    private final MessageSource messages;

    /** visible = 이 그룹 구성원에게 보이는가(화면 조회 권한 / 관리자 그룹 / 개인 카드), inSet = 세트에 행이 있는가 */
    public record GroupCardView(String code, String title, String desc, String progCd, String progTitle,
            boolean visible, boolean hidden, boolean inSet) {
    }

    public record GroupCards(GroupRow group, String ver, List<GroupCardView> cards) {
    }

    @Transactional(readOnly = true)
    public List<GroupRow> groups() {
        return mapper.findGroups();
    }

    /** 등록된 카드 전체 — 세트 순서(행 있는 카드) → 나머지 코드 기본 순서 */
    @Transactional(readOnly = true)
    public GroupCards detail(String authGrpCd, Locale locale) {
        GroupRow g = requireGroup(authGrpCd);
        Map<String, PrefRow> set = HomeService.byCode(mapper.findGroupCards(authGrpCd));
        Set<String> searchable = new HashSet<>(mapper.groupSearchProgs(authGrpCd));
        boolean admin = "Y".equals(g.adminYn());
        List<GroupCardView> cards = new ArrayList<>();
        for (HomeCardProvider p : HomeService.ordered(home.providers(), new Layout(Map.of(), set), x -> x)) {
            String progCd = p.progCd();
            ProgInfo prog = progCd == null ? null : progs.findProg(progCd);
            boolean visible = progCd == null || prog != null && (admin || searchable.contains(progCd));
            PrefRow row = set.get(p.code());
            cards.add(new GroupCardView(p.code(), messages.getMessage(p.titleKey(), null, p.titleKey(), locale),
                    messages.getMessage(p.titleKey() + ".desc", null, null, locale), progCd,
                    prog == null ? null : messages.getMessage(prog.getProgNmKey(), null, progCd, locale),
                    visible, row != null && "Y".equals(row.hiddenYn()), row != null));
        }
        return new GroupCards(g, mapper.groupVer(authGrpCd), cards);
    }

    /**
     * 세트 한 벌 저장 — 목록 순서 = 표시 순서. 등록되지 않은 카드·중복·빈 칸은 행 오류(행 키 = 카드 코드 또는 순번).
     * 반환: 새 판 지문
     */
    @Transactional
    public String save(String authGrpCd, String ver, List<SettingForm> in) {
        LoginUser me = me();
        requireGroup(authGrpCd);
        if (ver == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<String> registered = new HashSet<>();
        home.providers().forEach(p -> registered.add(p.code()));
        FieldErrors e = FieldErrors.create();
        if (in == null || in.isEmpty()) {
            e.add("cards", "REQUIRED");
            e.throwIfAny();
        }
        if (in.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<String> seen = new HashSet<>();
        List<PrefRow> rows = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            SettingForm f = in.get(i);
            String key = f == null || f.code() == null ? String.valueOf(i + 1) : f.code();
            if (f == null || f.code() == null || f.code().isBlank()) {
                e.row(key).add("code", "REQUIRED");
            } else if (!registered.contains(f.code())) {
                e.row(key).add("code", "NO_SUCH_CODE");
            } else if (!seen.add(f.code())) {
                e.row(key).add("code", "DUPLICATE");
            } else {
                rows.add(new PrefRow(f.code(), rows.size() + 1, Boolean.TRUE.equals(f.hidden()) ? "Y" : "N"));
            }
        }
        e.throwIfAny();
        lockAndCheck(authGrpCd, ver);
        mapper.deleteGroupCards(authGrpCd);
        mapper.insertGroupCards(authGrpCd, rows, me.loginId());
        return mapper.groupVer(authGrpCd);
    }

    /** 세트 지우기 — 그 그룹 사용자는 코드 기본 순서(또는 다음 그룹의 세트)로. 반환: 새 판 지문 */
    @Transactional
    public String reset(String authGrpCd, String ver) {
        me();
        requireGroup(authGrpCd);
        if (ver == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        lockAndCheck(authGrpCd, ver);
        mapper.deleteGroupCards(authGrpCd);
        return mapper.groupVer(authGrpCd);
    }

    private void lockAndCheck(String authGrpCd, String ver) {
        mapper.lockGroup(authGrpCd);
        if (!Objects.equals(ver, mapper.groupVer(authGrpCd))) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private GroupRow requireGroup(String authGrpCd) {
        GroupRow g = authGrpCd == null || authGrpCd.length() > 30 ? null : mapper.findGroup(authGrpCd);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return g;
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new IllegalStateException("로그인 사용자 없음"));
    }
}
