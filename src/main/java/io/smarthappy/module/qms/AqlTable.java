package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * AQL 표 한 벌(표준 하나) — 순수 데이터 (docs/29 Q1, D3). 값은 회사가 엑셀로 올린 것만 쓴다(시드 없음).
 * 표 1: 검사 수준 × 로트 크기 구간 → 시료 문자 / 표 2: 엄격도 × 시료 문자 × AQL → n · Ac · Re (화살표는 풀어서 들어 있다).
 */
public record AqlTable(String stdCd, List<Letter> letters, List<PlanRow> rows) {

    /** 로트 구간 [lotFrom, lotTo] (lotTo null = 끝 없음) */
    public record Letter(String inspLevel, long lotFrom, Long lotTo, String codeLetter) {
    }

    public record PlanRow(String severity, String codeLetter, BigDecimal aql, int sampleSize, int ac, int re) {
    }

    public AqlTable {
        letters = letters == null ? List.of() : List.copyOf(letters);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public Optional<String> letter(String inspLevel, long lotQty) {
        return letters.stream()
                .filter(l -> l.inspLevel().equals(inspLevel) && l.lotFrom() <= lotQty && (l.lotTo() == null || lotQty <= l.lotTo()))
                .map(Letter::codeLetter).findFirst();
    }

    /** AQL 은 수치로 비교한다(0.65 = 0.650) */
    public Optional<PlanRow> row(String severity, String codeLetter, BigDecimal aql) {
        return rows.stream()
                .filter(r -> r.severity().equals(severity) && r.codeLetter().equals(codeLetter) && r.aql().compareTo(aql) == 0)
                .findFirst();
    }
}
