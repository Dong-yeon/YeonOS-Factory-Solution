package io.smarthappy.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 서버가 사설망으로 <b>먼저</b> 연결하는 기능(라벨 프린터 직접 TCP, DIRECT 장비 어댑터 HTTP)을 켤지 (docs/67 F3).
 * <p>
 * 설계상 이 기능은 설치형 전용이다(docs/65 3장·DIRECT = 설치형, docs/30 D6). SaaS 서버가 회사가 적은 사설 주소로 연결하면
 * 고객 공장이 아니라 <b>호스팅 망 안</b>(다른 내부 서비스·다른 회사 자원)에 닿는다 — 사설 대역 검사는 설치형 LAN 에서만 의미가 있다.
 * <ul>
 * <li>onprem → 켬</li>
 * <li>saas → 끔. 단 {@code happyboot.deploy.lan-direct=true}(또는 .env {@code LAN_DIRECT=true})로 명시하면 켬
 *     — 호스팅 망이 분리된 전용 설치 같은 경우를 위한 탈출구</li>
 * <li>값을 비우면 local·test 프로필은 켬(가짜 프린터·가짜 엣지로 화면 확인·시험). {@code false} 로 명시하면 어디서든 끔</li>
 * </ul>
 * 저장(칸 오류 {@code LAN_DIRECT_OFF})과 송신(아웃박스 실패) 양쪽에서 본다 — 직접 SQL·예전에 저장된 행도 막히게.
 */
@Slf4j
@Component
public class LanDirectGate {

    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public LanDirectGate(HappyBootProperties props, Environment env,
            @Value("${happyboot.deploy.lan-direct:${LAN_DIRECT:}}") String flag) {
        this.enabled = decide(props.deploy().onprem(), flag, env.acceptsProfiles(Profiles.of("local", "test")));
        if (!enabled) {
            log.info("LAN direct connections (label printer TCP, DIRECT equipment adapter) are off in SaaS mode (docs/67 F3)");
        }
    }

    private LanDirectGate(boolean enabled) {
        this.enabled = enabled;
    }

    /** 시험용 — 정해진 값 */
    public static LanDirectGate fixed(boolean enabled) {
        return new LanDirectGate(enabled);
    }

    static boolean decide(boolean onprem, String flag, boolean devProfile) {
        String f = flag == null ? "" : flag.strip();
        if ("false".equalsIgnoreCase(f)) {
            return false;
        }
        if ("true".equalsIgnoreCase(f)) {
            return true;
        }
        return onprem || devProfile;
    }

    /** 서버 → 사설망 직접 연결을 해도 되는가 */
    public boolean enabled() {
        return enabled;
    }
}
