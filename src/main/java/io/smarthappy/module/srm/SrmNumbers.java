package io.smarthappy.module.srm;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.numbering.NumberingService;
import lombok.RequiredArgsConstructor;

/**
 * SRM 문서 번호 (docs/31 3장 채번). 회사에 규칙 행이 아직 없으면 기본 규칙을 같은 트랜잭션에서 만든 뒤 번호를 받는다 —
 * 첫 구매요청이 "채번 규칙 없음" 으로 막히지 않게. 규칙을 바꾸려면 채번 관리에서 같은 코드로 저장한다(사용 중지면 그대로 404).
 * NumberingService.next 의 예외를 잡아서 다시 시도하지 않는 이유: 트랜잭션 프록시를 지난 예외는 바깥 트랜잭션을 rollback-only 로 만든다.
 */
@Component
@RequiredArgsConstructor
public class SrmNumbers {

    public static final String PR = "SRM_PR";
    public static final String PO = "SRM_PO";
    public static final String EVAL = "SRM_EVAL";
    public static final String ASN = "SRM_ASN";
    public static final String RETURN = "SRM_RTN";
    public static final String CLAIM = "SRM_CLM";

    /** 규칙 코드 → {이름, 패턴, 초기화 주기} */
    private static final Map<String, String[]> DEFAULTS = Map.of(
            PR, new String[] {"구매요청 번호", "PR{yyMM}{seq:4}", "MONTH"},
            PO, new String[] {"발주 번호", "PO{yyMM}{seq:4}", "MONTH"},
            EVAL, new String[] {"협력사 평가 번호", "EV{yyyy}{seq:4}", "YEAR"},
            ASN, new String[] {"사전 납품 통지 번호", "ASN{yyMM}{seq:4}", "MONTH"},
            RETURN, new String[] {"반품 지시 번호", "RT{yyMM}{seq:4}", "MONTH"},
            CLAIM, new String[] {"협력사 클레임 번호", "CL{yyMM}{seq:4}", "MONTH"});

    private final NumberingService numbering;
    private final SrmRuleMapper rules;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String ruleCd, String actor) {
        String[] d = DEFAULTS.get(ruleCd);
        if (d != null && !rules.exists(ruleCd)) {
            numbering.saveRule(ruleCd, d[0], d[1], d[2], actor);
        }
        return numbering.next(ruleCd);
    }

}
