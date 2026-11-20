package io.smarthappy.platform.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 사용자 고르기 선택지 한 줄 — 회사 사용자(INTERNAL)만 (UserDirectory, docs/42 15장) */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserOption {
    private long userId;
    private String loginId;
    private String userNm;
}
