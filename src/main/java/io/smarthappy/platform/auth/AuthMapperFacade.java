package io.smarthappy.platform.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 컨트롤러가 매퍼를 직접 부르지 않도록 하는 얇은 조회 계층 (트랜잭션 = RLS 범위) */
@Component
@RequiredArgsConstructor
public class AuthMapperFacade {

    private final AuthMapper mapper;

    @Transactional(readOnly = true)
    public ProgInfo findProg(String progCd) {
        return mapper.findProg(progCd);
    }
}
