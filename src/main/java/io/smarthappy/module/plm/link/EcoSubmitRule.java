package io.smarthappy.module.plm.link;

import java.util.List;

import io.smarthappy.platform.common.web.FieldErrors.FieldError;

/**
 * ECO 상신 확장점 (docs/29_P3 11.3 ④, docs/39 16장) — 다른 모듈이 빈으로 등록한다(FileLockRule 과 같은 모양, 여러 빈 가능).
 * 예: QMS 가 PROCESS_4M·TEMP ECO 에 4M 세부가 없으면 상신 거부(정책 QMS_4M_REQUIRED).
 *
 * - 부르는 곳: EcoService.submit — ECO 행 잠금(FOR UPDATE)·rowVer 확인 뒤, 상태 변경 전. 같은 트랜잭션, 결과 칸 오류는 ECO 의 다른 검사와 합쳐 400
 * - 읽기만 하고 예외 대신 칸 오류를 돌려준다(없으면 빈 목록). rowKey·field·code 는 규칙이 정한다(문구 키 valid.{code})
 * - 잠금 순서: plm_eco → 규칙이 읽는 표. 규칙 쪽 저장 경로도 plm_eco 를 먼저(FOR SHARE) 잡아야 상신과 직렬화된다
 * - 빈이 없으면 지금 동작 그대로
 */
public interface EcoSubmitRule {

    List<FieldError> check(long ecoId);
}
