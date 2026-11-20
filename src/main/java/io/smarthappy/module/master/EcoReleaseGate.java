package io.smarthappy.module.master;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * BOM·라우팅 확정 허용 범위 (D4, docs/39 14.2) — 회사 정책 PLM_ECO_REQUIRED=Y 면 확정은 ECO 적용 경로에서만.
 *
 * - 허용 표시 = 트랜잭션 로컬 DB 설정 app.plm_eco_release. 서비스 검사와 DB 트리거(tg_plm_eco_required)가 같은 신호를 본다
 * - 트랜잭션이 끝나면 저절로 사라진다. ECO 적용(PLM)이 {@link #open()} → 확정 → {@link #close()}
 * - master 패키지에 둔다 — BOM·라우팅이 PLM 을 알지 않게(PLM 이 master 에 기대는 방향만)
 */
@Component
@RequiredArgsConstructor
public class EcoReleaseGate {

    static final String POLICY = "PLM_ECO_REQUIRED";

    private final JdbcTemplate jdbc;
    private final PolicyService policies;

    /** 정책 Y 이고 ECO 적용 경로가 아니면 409 PLM_ECO_REQUIRED. 정책 N(기본)이면 아무것도 하지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkRelease() {
        if (policies.bool(POLICY) && !"Y".equals(
                jdbc.queryForObject("select coalesce(current_setting('app.plm_eco_release', true), '')", String.class))) {
            throw new BusinessException(HbErrorCode.PLM_ECO_REQUIRED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void open() {
        jdbc.queryForObject("select set_config('app.plm_eco_release', 'Y', true)", String.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close() {
        jdbc.queryForObject("select set_config('app.plm_eco_release', '', true)", String.class);
    }
}
