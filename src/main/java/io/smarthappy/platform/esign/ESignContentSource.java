package io.smarthappy.platform.esign;

import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * 서명 대상의 "지금 내용 해시" 를 다시 계산해 주는 모듈 계약 (docs/52 Y1 — 기록 무결성 점검 3번).
 * 서명 원장(sys_esign)의 content_hash 는 서명 순간 모듈이 정한 정규 글자의 SHA-256 이다. 점검은 대상 유형마다 이 계약으로
 * 지금 내용을 다시 해시해 대조한다 — 같으면 "서명 그대로", 다르면 "서명 뒤 바뀜"(업무상 정상일 수도 있다: 서명 뒤 처분 추가 등).
 *
 * <ul>
 * <li>회사 범위·읽기 트랜잭션 안에서 부른다(RLS 그대로) — 다른 회사 대상은 "대상 없음" 으로 보인다</li>
 * <li>한 대상에 해시가 여럿일 수 있다(예: 계측기 = 교정 이력 하나하나가 서명 내용) → 그중 하나와 같으면 그대로</li>
 * <li>플랫폼은 모듈을 모른다 — 모듈이 이 계약의 빈을 낸다(ModuleBoundaryGuardTest)</li>
 * </ul>
 */
public interface ESignContentSource {

    /** sys_esign.target_type (예: qms_ncr) */
    String targetType();

    /** 대상의 지금 내용 해시들 — 대상이 없으면(지워졌거나 다른 회사) 빈 Optional */
    Optional<Set<String>> currentHashes(long targetId);

    static ESignContentSource of(String targetType, LongFunction<Optional<Set<String>>> hashes) {
        return new ESignContentSource() {
            @Override
            public String targetType() {
                return targetType;
            }

            @Override
            public Optional<Set<String>> currentHashes(long targetId) {
                return hashes.apply(targetId);
            }
        };
    }
}
