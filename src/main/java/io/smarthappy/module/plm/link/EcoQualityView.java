package io.smarthappy.module.plm.link;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * ECO 품질 요약 계약 (docs/29_P3 11.3 ③, docs/39 16.2) — 인터페이스 = PLM, 구현 = QMS. ECO 상세 "품질" 영역이 부른다.
 * QMS 가 없는 설치본은 {@link #NONE}(영역을 숨긴다). 읽기만, ECO 상세 조회 트랜잭션 안에서 불린다.
 */
public interface EcoQualityView {

    /**
     * 4M 요약 — hasChange = 4M 세부(qms_change) 있음, fourMTypes = 4M 유형 코드(MAN·MACHINE…), charCount = 영향 특성 수.
     * 초품 수(firstOk·firstNg·firstPending)는 QMS Q7 전까지 null
     */
    record Summary(boolean hasChange, List<String> fourMTypes, int charCount, Integer firstOk, Integer firstNg,
            Integer firstPending) {
    }

    /** 요약 없음 — QMS 모듈이 없는 설치본 */
    EcoQualityView NONE = ecoId -> Optional.empty();

    /** 다른 회사·없는 ECO 는 빈 값 */
    Optional<Summary> summary(long ecoId);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static EcoQualityView of(ObjectProvider<EcoQualityView> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
