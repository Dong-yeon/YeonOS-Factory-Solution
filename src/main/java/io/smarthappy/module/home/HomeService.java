package io.smarthappy.module.home;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeMapper.PrefRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.AuthMapperFacade;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.auth.ProgInfo;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.extern.slf4j.Slf4j;

/**
 * 홈 "내 할 일" 카드 모으기 (docs/37). 홈은 업무 표를 읽지 않는다 — 카드 제공자 빈({@link HomeCardProvider})이 계산한다.
 * <ul>
 * <li>보이는 카드 = 링크 화면 조회 권한이 있는 것 + 개인 카드(R1·R2)</li>
 * <li>카드마다 따로 읽기 전용 트랜잭션 + statement_timeout(R3) — SQL 오류는 PG 트랜잭션을 깨뜨려 다음 카드까지 실패시키므로
 *     한 트랜잭션에 모으지 않는다. 그래서 이 메서드는 <b>트랜잭션 밖에서만</b> 부른다</li>
 * <li>실패 카드는 error=true 로만(R4, 원문 비노출)</li>
 * <li>순서·숨김은 겹쳐 쓴다(3단계, 15장): 사용자 설정 &gt; 역할별 기본 세트(권한그룹) &gt; 코드 기본 순서. 설정은 권한을 주지 않는다</li>
 * </ul>
 */
@Slf4j
@Service
public class HomeService {

    public static final String PROG = "home-main";
    public static final int MAX_FACTS = 3;
    /** 카드 항목 상한 — 제공자는 이만큼만 읽는다(넘치면 서비스가 자른다) */
    public static final int MAX_ITEMS = 5;
    static final int CARD_TIMEOUT_MS = 2000;
    /** 이보다 오래 걸린 카드는 경고 로그(카드 코드·ms) — 시간 제한(2초)에 닿기 전에 어느 카드가 느려지는지 보이게(docs/37 24장) */
    static final int SLOW_CARD_MS = 500;
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");
    /** 링크 조건 이름 = 조회 패널 칸 이름 형식 */
    private static final Pattern LINK_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,29}$");
    static final int MAX_LINK_PARAMS = 6;
    static final int MAX_LINK_VALUE = 200;

    private final List<HomeCardProvider> providers;
    private final PermissionService permissions;
    private final AuthMapperFacade progs;
    private final MessageSource messages;
    private final BizClock clock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final HomeMapper mapper;
    private final TransactionTemplate write;

    /** desc = 카드 기준 설명(문구 키 {titleKey}.desc, 없으면 null), link = 카드 숫자 → 목록 조회 조건(4단계, 없으면 null) */
    public record CardView(String code, String title, String desc, String progCd, String progTitle, Long count,
            HomeCard.Tone tone, List<Fact> facts, List<Item> items, Map<String, String> link, boolean plantScoped, boolean error) {
    }

    /** 카드 하나 다시 읽기 결과(4단계) — 기준 시각과 그 카드 */
    public record CardReload(OffsetDateTime asOf, CardView card) {
    }

    /**
     * hiddenCount = 사용자가 숨긴 카드 수(볼 수 있는 카드 중) — 화면이 "숨긴 카드 N장" 으로 알린다.
     * plant·plantToday = 고른 공장과 그 시간대 오늘(5단계, 전체면 plant null·plantToday = today)
     */
    public record HomeView(OffsetDateTime asOf, String today, List<CardView> cards, int hiddenCount, String plant, String plantToday) {
    }

    /** 카드 설정 한 줄(2단계, docs/37 14.1) — 볼 수 있는 카드만, 사용자 순서대로 */
    public record SettingView(String code, String title, String desc, boolean hidden) {
    }

    /** 카드 설정 저장 — 목록 순서 = 표시 순서 */
    public record SettingForm(String code, Boolean hidden) {
    }

    /** 권한 판정을 지난 카드 */
    private record Visible(HomeCardProvider provider, String progTitle) {
    }

    /** 겹쳐 쓴 카드 설정 — 사용자 행이 있으면 그것, 없으면 그룹 기본 행, 둘 다 없으면 보이기 + 코드 기본 순서 */
    record Layout(Map<String, PrefRow> user, Map<String, PrefRow> group) {

        static final Layout NONE = new Layout(Map.of(), Map.of());

        PrefRow row(String code) {
            PrefRow r = user.get(code);
            return r != null ? r : group.get(code);
        }

        boolean hidden(String code) {
            PrefRow r = row(code);
            return r != null && "Y".equals(r.hiddenYn());
        }
    }

    public HomeService(ObjectProvider<HomeCardProvider> providers, PermissionService permissions, AuthMapperFacade progs,
            MessageSource messages, BizClock clock, JdbcTemplate jdbc, PlatformTransactionManager tm, HomeMapper mapper) {
        this.mapper = mapper;
        this.providers = checked(providers.orderedStream().toList());
        this.permissions = permissions;
        this.progs = progs;
        this.messages = messages;
        this.clock = clock;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(tm);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.write = new TransactionTemplate(tm);
    }

    /** 등록된 카드 전체(코드 기본 순서) — 관리 화면용 */
    List<HomeCardProvider> providers() {
        return providers;
    }

    /** 카드 코드 형식·중복은 기동 시 막는다(R6) — 두 모듈이 같은 코드를 쓰면 한 카드가 조용히 사라진다 */
    static List<HomeCardProvider> checked(List<HomeCardProvider> list) {
        Set<String> seen = new HashSet<>();
        for (HomeCardProvider p : list) {
            String code = p.code();
            if (code == null || !CODE.matcher(code).matches()) {
                throw new IllegalStateException("홈 카드 코드 형식 오류: " + code + " (" + p.getClass().getName() + ")");
            }
            if (!seen.add(code)) {
                throw new IllegalStateException("홈 카드 코드 중복: " + code);
            }
            if (p.titleKey() == null || p.titleKey().isBlank()) {
                throw new IllegalStateException("홈 카드 제목 키 없음: " + code);
            }
        }
        return list.stream().sorted(Comparator.comparingInt(HomeCardProvider::sortNo).thenComparing(HomeCardProvider::code))
                .toList();
    }

    public HomeView cards(Locale locale) {
        return cards(locale, null);
    }

    /** plant = 홈에서 고른 공장(null·빈 값 = 전체). 없거나 사용 중지·다른 회사 공장이면 400(조용히 전체로 바꾸지 않는다) */
    public HomeView cards(Locale locale, String plant) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HomeService.cards 는 트랜잭션 밖에서 부른다 (카드마다 트랜잭션을 연다)");
        }
        LoginUser user = me();
        ZonedDateTime now = clock.now();
        HomeCardContext ctx = context(now, user, locale, plant);
        Layout layout = layout(user.userId());
        List<CardView> result = new ArrayList<>();
        int hidden = 0;
        for (Visible v : ordered(visible(locale), layout, Visible::provider)) {
            if (layout.hidden(v.provider().code())) {
                hidden++;
                continue;
            }
            result.add(load(v.provider(), ctx, v.progTitle()));
        }
        return new HomeView(now.toOffsetDateTime(), now.toLocalDate().toString(), result, hidden, ctx.plantCd(),
                ctx.plantToday().toString());
    }

    /**
     * 카드 하나 다시 읽기(4단계, docs/37 16.1) — 판정은 홈 전체와 같다(R1·R2). 볼 수 없는 카드·없는 코드는 같은 404
     * (권한 없는 카드가 있는지 흘리지 않는다). 숨긴 카드도 읽는다 — 숨김은 보기 설정이지 권한이 아니다.
     * 카드마다 트랜잭션을 여므로 트랜잭션 밖에서만 부른다
     */
    public CardReload card(String code, Locale locale) {
        return card(code, locale, null);
    }

    public CardReload card(String code, Locale locale, String plant) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HomeService.card 는 트랜잭션 밖에서 부른다 (카드 트랜잭션을 연다)");
        }
        LoginUser user = me();
        Visible v = code == null ? null : visible(locale).stream().filter(x -> x.provider().code().equals(code)).findFirst().orElse(null);
        if (v == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ZonedDateTime now = clock.now();
        return new CardReload(now.toOffsetDateTime(), load(v.provider(), context(now, user, locale, plant), v.progTitle()));
    }

    /** 공장 선택지(5단계) — 사용 중 공장, 회사 범위는 RLS */
    public List<HomeMapper.PlantRow> plants() {
        List<HomeMapper.PlantRow> rows = readOnly.execute(s -> mapper.findPlants());
        return rows == null ? List.of() : rows;
    }

    /** 카드 문맥 — 공장을 골랐으면 그 공장 시간대 시각(BizClock.zone(plant): 시간대 없으면 회사). 공장 검증은 RLS 안에서 */
    private HomeCardContext context(ZonedDateTime now, LoginUser user, Locale locale, String plant) {
        if (plant == null || plant.isBlank()) {
            return new HomeCardContext(now, user.userId(), locale, messages);
        }
        String cd = plant.strip();
        ZonedDateTime plantNow = cd.length() > 20 ? null : readOnly.execute(s -> {
            HomeMapper.PlantRow row = mapper.findPlant(cd);
            return row == null ? null : now.withZoneSameInstant(clock.zone(row.plantCd()));
        });
        if (plantNow == null) {
            FieldErrors.create().add("plant", "NO_SUCH_CODE").throwIfAny();
        }
        return new HomeCardContext(now, user.userId(), locale, messages, cd, plantNow);
    }

    /** 카드 설정 목록 — 볼 수 있는 카드만(권한 없는 카드는 설정에도 안 나온다), 지금 홈과 같은 순서(사용자 > 역할별 기본 > 코드) */
    public List<SettingView> settings(Locale locale) {
        Layout layout = layout(me().userId());
        return ordered(visible(locale), layout, Visible::provider).stream().map(v -> {
            HomeCardProvider p = v.provider();
            return new SettingView(p.code(), messages.getMessage(p.titleKey(), null, p.titleKey(), locale),
                    messages.getMessage(p.titleKey() + ".desc", null, null, locale), layout.hidden(p.code()));
        }).toList();
    }

    /**
     * 카드 설정 저장(한 벌) — 목록 순서 = 표시 순서. 볼 수 없는 카드·없는 카드·중복·빈 칸은 행 오류(행 키 = 카드 코드 또는 순번).
     * 사용자 본인 설정이라 낙관적 잠금 없이 나중 저장이 이긴다(두 탭). 지금 볼 수 없는 카드의 옛 행도 지운다 — 다시 권한을 받으면 기본 순서로 끝에
     */
    public void saveSettings(List<SettingForm> in, Locale locale) {
        LoginUser user = me();
        Set<String> allowed = new HashSet<>();
        visible(locale).forEach(v -> allowed.add(v.provider().code()));
        FieldErrors e = FieldErrors.create();
        if (in == null || in.isEmpty()) {
            e.add("cards", "REQUIRED");
            e.throwIfAny();
        }
        Set<String> seen = new HashSet<>();
        List<PrefRow> rows = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            SettingForm f = in.get(i);
            String key = f == null || f.code() == null ? String.valueOf(i + 1) : f.code();
            if (f == null || f.code() == null || f.code().isBlank()) {
                e.row(key).add("code", "REQUIRED");
            } else if (!allowed.contains(f.code())) {
                e.row(key).add("code", "NO_SUCH_CODE");
            } else if (!seen.add(f.code())) {
                e.row(key).add("code", "DUPLICATE");
            } else {
                rows.add(new PrefRow(f.code(), rows.size() + 1, Boolean.TRUE.equals(f.hidden()) ? "Y" : "N"));
            }
        }
        e.throwIfAny();
        write.executeWithoutResult(s -> {
            mapper.deletePrefs(user.userId());
            mapper.insertPrefs(user.userId(), rows, user.loginId());
        });
    }

    /** 내 설정 지우기 — 역할별 기본 세트(없으면 코드 기본 순서·모두 보이기)로 */
    public void resetSettings() {
        long userId = me().userId();
        write.executeWithoutResult(s -> mapper.deletePrefs(userId));
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new IllegalStateException("로그인 사용자 없음"));
    }

    private Layout layout(long userId) {
        Layout l = readOnly.execute(s -> new Layout(byCode(mapper.findPrefs(userId)), byCode(mapper.findGroupDefault(userId))));
        return l == null ? Layout.NONE : l;
    }

    static Map<String, PrefRow> byCode(List<PrefRow> rows) {
        Map<String, PrefRow> m = new HashMap<>();
        if (rows != null) {
            rows.forEach(r -> m.put(r.cardCd(), r));
        }
        return m;
    }

    /** 권한 판정(R1) — 링크 화면 조회 권한이 있는 카드 + 개인 카드, 기본 순서 */
    private List<Visible> visible(Locale locale) {
        List<Visible> list = new ArrayList<>();
        for (HomeCardProvider p : providers) {
            String progCd = p.progCd();
            String progTitle = null;
            if (progCd != null) {
                if (!permissions.actions(progCd).contains(Action.SEARCH)) {
                    continue;
                }
                ProgInfo prog = progs.findProg(progCd);
                if (prog == null) {
                    continue;   // 사용 중지된 화면 — 링크가 갈 곳이 없다
                }
                progTitle = messages.getMessage(prog.getProgNmKey(), null, progCd, locale);
            }
            list.add(new Visible(p, progTitle));
        }
        return list;
    }

    /**
     * 순서 — 사용자 행이 있는 카드(그 순서) → 그룹 기본 행만 있는 카드(그 순서) → 행 없는 카드(새 카드, 코드 기본 순서).
     * 새 카드는 숨겨진 채로 오지 않는다(모듈 세션이 카드를 붙이면 바로 보인다). list 는 코드 기본 순서로 들어온다
     */
    static <T> List<T> ordered(List<T> list, Layout layout, Function<T, HomeCardProvider> provider) {
        List<T> mine = new ArrayList<>();
        List<T> grp = new ArrayList<>();
        List<T> rest = new ArrayList<>();
        for (T v : list) {
            String code = provider.apply(v).code();
            (layout.user().containsKey(code) ? mine : layout.group().containsKey(code) ? grp : rest).add(v);
        }
        mine.sort(Comparator.comparingInt(v -> layout.user().get(provider.apply(v).code()).sortNo()));
        grp.sort(Comparator.comparingInt(v -> layout.group().get(provider.apply(v).code()).sortNo()));
        mine.addAll(grp);
        mine.addAll(rest);
        return mine;
    }

    /** 항목 링크 조건 정리 — 링크 화면이 없거나 이름·값 형식이 틀리면 버린다(조회 칸 이름만, 값 200자) */
    static Map<String, String> safeLink(Map<String, String> link, String progCd) {
        if (link == null || progCd == null || link.isEmpty() || link.size() > MAX_LINK_PARAMS) {
            return null;
        }
        for (Map.Entry<String, String> en : link.entrySet()) {
            if (!LINK_NAME.matcher(en.getKey()).matches() || en.getValue() == null || en.getValue().length() > MAX_LINK_VALUE) {
                return null;
            }
        }
        return link;
    }

    private CardView load(HomeCardProvider p, HomeCardContext ctx, String progTitle) {
        String title = messages.getMessage(p.titleKey(), null, p.titleKey(), ctx.locale());
        String desc = messages.getMessage(p.titleKey() + ".desc", null, null, ctx.locale());
        try {
            long t0 = System.nanoTime();
            HomeCard card = readOnly.execute(s -> {
                jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, String.valueOf(CARD_TIMEOUT_MS));
                return p.load(ctx);
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= SLOW_CARD_MS) {
                log.warn("홈 카드 느림 card={} ms={} plant={}", p.code(), ms, ctx.plantCd());
            }
            if (card == null) {
                throw new IllegalStateException("카드가 null 을 돌려줌");
            }
            return new CardView(p.code(), title, desc, p.progCd(), progTitle, card.count(), card.tone(),
                    card.facts().stream().limit(MAX_FACTS).toList(),
                    card.items().stream().limit(MAX_ITEMS)
                            .map(it -> new Item(it.title(), it.detail(), it.tone(), safeLink(it.link(), p.progCd()))).toList(),
                    safeLink(card.link(), p.progCd()), p.plantScoped(), false);
        } catch (RuntimeException e) {
            log.warn("홈 카드 실패 card={} ({})", p.code(), e.getClass().getSimpleName(), e);
            return new CardView(p.code(), title, desc, p.progCd(), progTitle, null, HomeCard.Tone.NEUTRAL, List.of(), List.of(), null,
                    p.plantScoped(), true);
        }
    }
}
