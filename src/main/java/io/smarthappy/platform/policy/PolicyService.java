package io.smarthappy.platform.policy;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.net.IpMatcher;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 정책 (01 5.3): 고객 차이를 코드가 아니라 설정으로 흡수한다.
 * 조회 순서: 공장 값 → 회사 값 → 정의의 기본값. 값은 저장할 때 정의(형식·허용값·범위)로 검증한다.
 * 회사 범위 안에서만 동작(RLS) — 다른 회사 정책은 보이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    public static final String COMPANY_WIDE = "*";
    private static final Pattern PLANT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    private static final int MAX_STRING = 2000;

    private final PolicyMapper mapper;

    @Transactional(readOnly = true)
    public String value(String policyKey) {
        return value(policyKey, COMPANY_WIDE);
    }

    @Transactional(readOnly = true)
    public String value(String policyKey, String plantCd) {
        PolicyDef def = def(policyKey);
        String plant = plantCd == null ? COMPANY_WIDE : plantCd;
        String v = mapper.findValue(policyKey, plant);
        return v != null ? v : def.getDefaultValue();
    }

    @Transactional(readOnly = true)
    public boolean bool(String policyKey) {
        return "Y".equals(value(policyKey));
    }

    @Transactional(readOnly = true)
    public int intValue(String policyKey) {
        return Integer.parseInt(value(policyKey));
    }

    @Transactional(readOnly = true)
    public IpMatcher ipList(String policyKey) {
        return IpMatcher.parse(value(policyKey)).orElseGet(() -> IpMatcher.parse("").orElseThrow());
    }

    /** 회사(또는 공장) 정책 값 저장. 정의에 맞지 않으면 400 INVALID_VALUE */
    @Transactional
    public void set(String policyKey, String plantCd, String value, String actor) {
        PolicyDef def = def(policyKey);
        String plant = plantCd == null ? COMPANY_WIDE : plantCd;
        if (!plant.equals(COMPANY_WIDE) && (!"Y".equals(def.getPlantScopeYn()) || !PLANT_CD.matcher(plant).matches())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String normalized = validate(def, value);
        mapper.upsert(TenantContext.requireCompCd(), plant, policyKey, normalized, actor);
    }

    private PolicyDef def(String policyKey) {
        PolicyDef def = policyKey == null ? null : mapper.findDef(policyKey);
        if (def == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return def;
    }

    /** 정의에 맞는지 확인하고 저장할 형태로 정리 (앞뒤 공백 제거 등) */
    static String validate(PolicyDef def, String raw) {
        if (raw == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String v = raw.trim();
        switch (def.getValueType()) {
            case "BOOL" -> {
                if (!v.equals("Y") && !v.equals("N")) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
            }
            case "INT" -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
                if ((def.getMinValue() != null && n < def.getMinValue())
                        || (def.getMaxValue() != null && n > def.getMaxValue())) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
                v = Integer.toString(n);
            }
            case "ENUM" -> {
                if (def.getAllowedValues() == null || !Arrays.asList(def.getAllowedValues()).contains(v)) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
            }
            case "IP_LIST" -> {
                if (IpMatcher.parse(v).isEmpty()) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
            }
            case "STRING" -> {
                if (v.length() > MAX_STRING || v.chars().anyMatch(c -> c < 0x20)) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
            }
            default -> throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return v;
    }
}
