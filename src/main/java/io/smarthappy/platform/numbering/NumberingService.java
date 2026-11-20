package io.smarthappy.platform.numbering;

import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 채번 (01 5.2). 고객이 원하는 번호 체계는 규칙(sys_numbering_rule)만 바꾸면 된다 — 코드 수정 없음.
 *
 * 결번 정책(01 10장 4번 결정): **업무 트랜잭션 안에서만 번호를 받는다(MANDATORY)** → 업무가 롤백되면
 * 번호 증가도 롤백되어 결번이 생기지 않는다. 대신 같은 규칙·기간의 번호를 받는 트랜잭션끼리는
 * 커밋까지 순서대로 기다린다(MES 규모에서는 문제 없음, 업무 트랜잭션을 짧게 유지).
 * 날짜는 회사 시간대(BizClock) 기준.
 */
@Service
@RequiredArgsConstructor
public class NumberingService {

    private static final Pattern RULE_CD = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final Pattern PLANT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");

    private final NumberingMapper mapper;
    private final BizClock bizClock;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String ruleCd) {
        return next(ruleCd, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String ruleCd, String plantCd) {
        if (ruleCd == null || !RULE_CD.matcher(ruleCd).matches()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Map<String, Object> rule = mapper.findRule(ruleCd);
        if (rule == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String resetCycle = (String) rule.get("reset_cycle");
        NumberingPattern pattern = NumberingPattern.parse((String) rule.get("pattern"), resetCycle)
                .orElseThrow(() -> new BusinessException(HbErrorCode.INVALID_VALUE));
        if (pattern.needsPlant() && (plantCd == null || !PLANT_CD.matcher(plantCd).matches())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LocalDate today = bizClock.today();
        long seq = mapper.increment(TenantContext.requireCompCd(), ruleCd, NumberingPattern.periodKey(resetCycle, today));
        if (pattern.overflows(seq)) {
            // 자릿수를 넘기면 번호 형식이 깨지고 정렬·바코드가 어긋난다 → 조용히 늘리지 않고 막는다
            throw new BusinessException(HbErrorCode.NUMBERING_EXHAUSTED);
        }
        return pattern.format(today, plantCd, seq);
    }

    /**
     * 업무 기본 규칙으로 채번 — 회사에 그 규칙이 아직 없으면 기본 패턴으로 만든 뒤 번호를 낸다(P2 2단계 수주부터).
     * 동시에 처음 부르는 두 요청은 insert … on conflict do nothing 으로 한쪽만 만든다. 관리자가 규칙을 끈(use_yn = N) 회사는 404 그대로
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextOrDefault(String ruleCd, String defaultNm, String defaultPattern, String defaultReset) {
        if (mapper.findRule(ruleCd) == null && NumberingPattern.parse(defaultPattern, defaultReset).isPresent()) {
            mapper.insertRuleIfAbsent(TenantContext.requireCompCd(), ruleCd, defaultNm, defaultPattern, defaultReset, "SYSTEM");
        }
        return next(ruleCd);
    }

    /** 규칙 저장 (관리 화면·운영자 콘솔·초기화). 패턴·초기화 주기가 맞지 않으면 400 */
    @Transactional
    public void saveRule(String ruleCd, String ruleNm, String pattern, String resetCycle, String actor) {
        if (ruleCd == null || !RULE_CD.matcher(ruleCd).matches() || ruleNm == null || ruleNm.isBlank()
                || ruleNm.length() > 100 || NumberingPattern.parse(pattern, resetCycle).isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        mapper.upsertRule(TenantContext.requireCompCd(), ruleCd, ruleNm.trim(), pattern, resetCycle, actor);
    }
}
