package io.smarthappy.module.production.link;

import java.util.List;

import io.smarthappy.module.production.link.CloseNotice.CloseContext;

/**
 * 공정 완료판정 "막는 이유" 확장점 (docs/72 2.3) — {@link CloseNotice} 의 막는 쪽 짝.
 * MES 완료판정 미리보기·판정이 모든 빈을 부르고 돌려준 코드를 막는 이유(problems)에 더한다 — 코드 하나라도 있으면 판정 API 는 400(칸 status, 문구 valid.{코드}).
 *
 * - 구현은 읽기만, 잠금 없음(CloseNotice 와 같다). 끝난(DONE) 공정에는 부르지 않는다
 * - 코드는 짧은 영문 상수(예 PART_MISSING) — 화면 문구는 구현 모듈이 ko/en 에 둔다
 * - 미리보기와 판정이 같은 검사를 쓰므로 문맥의 preview 는 늘 true 다(막는 이유는 둘이 같아야 한다)
 * 빈이 없으면 막는 이유 없음.
 */
public interface CloseBlock {

    List<String> problems(CloseContext ctx);
}
