package io.smarthappy.module.home;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.auth.AuthMapper;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 업무 흐름 지도 (home-flow-map, docs/46 12장) — 지도 상자에 쓸 화면 이름과 "열 수 있는지".
 * - 이름은 사용 중 화면 전부(권한 없는 화면도 회색 상자로 이름이 보여야 한다). 화면 카탈로그는 전역이라 회사 정보가 없다
 * - 열 수 있는지 = 조회(SEARCH) 권한 — 메뉴와 같은 판정(AuthMapper.findSearchableProgs, 회사 범위는 RLS)
 * - 흐름 단계·화살표는 정적 파일(flow-map.json·help-flow.json)이라 여기서 다루지 않는다
 */
@Service
@RequiredArgsConstructor
public class FlowMapService {

    public static final String PROG = "home-flow-map";

    private final FlowMapMapper mapper;
    private final AuthMapper auth;
    private final MessageSource messages;

    public record Screen(String progCd, String name, boolean canOpen) {
    }

    @Transactional(readOnly = true)
    public List<Screen> screens(Locale locale) {
        LoginUser user = CurrentUser.get().orElse(null);
        Set<String> searchable = user == null ? Set.of() : new HashSet<>(auth.findSearchableProgs(user.userId()));
        return mapper.findProgs().stream()
                .map(p -> new Screen(p.progCd(), messages.getMessage(p.progNmKey(), null, p.progCd(), locale),
                        searchable.contains(p.progCd())))
                .toList();
    }
}
