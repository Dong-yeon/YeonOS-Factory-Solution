package io.smarthappy.module.pop;

import java.io.Serializable;

import io.smarthappy.platform.log.ClientTyped;

/**
 * 인증된 POP 단말 (ROLE_TERMINAL). 사람 로그인(LoginUser)이 아니다 — 요청마다 단말 쿠키로 다시 만든다(세션에 저장하지 않음).
 * 행위자(감사·recorded_by) = {@code t{단말 id}:{단말 코드}}
 */
public record TerminalPrincipal(String compCd, long terminalId, String terminalCd, String terminalNm, String plantCd,
        Long nodeId, String nodeCd, String nodeNm, String terminalType) implements Serializable, ClientTyped {

    public String actor() {
        return "t" + terminalId + ":" + terminalCd;
    }

    /** 접속 로그 단말 종류 = 등록 유형(POP/PDA) — 화면 헤더가 아니라 서버 값 (docs/50 Q9) */
    @Override
    public String clientType() {
        return terminalType;
    }
}
