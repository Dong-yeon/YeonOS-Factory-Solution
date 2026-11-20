package io.smarthappy.module.qms;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.link.ChangeLookup;

/**
 * 계약 {@link ChangeLookup} 구현 — 매퍼만, 트랜잭션을 따로 열지 않는다(부르는 쪽 트랜잭션에서 읽기, 예외 없음).
 * 회사 범위는 RLS 라 다른 회사 4M 세부는 보이지 않는다(빠짐).
 */
@Component
public class QmsChangeLookup implements ChangeLookup {

    private final QmsLinkMapper linkMapper;

    public QmsChangeLookup(QmsLinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    @Override
    public List<ChangeDetail> details(Collection<Long> ecoIds) {
        if (ecoIds == null || ecoIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = ecoIds.stream().filter(Objects::nonNull).filter(id -> id > 0).distinct().sorted().limit(MAX_IDS).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return linkMapper.changeDetails(ids).stream().map(r -> new ChangeDetail(r.getEcoId(), r.getEcoNo(), words(r.getM4Text()),
                r.getBeforeDesc(), r.getAfterDesc(), r.getReasonDesc(), r.getImpactDesc(), r.getRevertCond(),
                words(r.getCharIdText()).stream().map(Long::valueOf).toList(),
                words(r.getInspPlanIdText()).stream().map(Long::valueOf).toList())).toList();
    }

    private static List<String> words(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(","));
    }
}
