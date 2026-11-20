package io.smarthappy.module.srm.link;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** {@link AsnQuery} 의 SRM 구현 (docs/31 4.11) */
@Component
@RequiredArgsConstructor
class SrmAsnQuery implements AsnQuery {

    private final AsnQueryMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<AsnHead> expected(String plantCd, LocalDate fromEta, LocalDate toEta) {
        if (plantCd == null || plantCd.isBlank()) {
            return List.of();
        }
        return mapper.expected(plantCd, fromEta, toEta, MAX_HEADS).stream().map(AsnQueryMapper.Head::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Asn> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.strip();
        if (k.regionMatches(true, 0, SCAN_PREFIX, 0, SCAN_PREFIX.length())) {
            k = k.substring(SCAN_PREFIX.length()).strip();
        }
        if (k.isEmpty() || k.length() > 60) {
            return Optional.empty();
        }
        List<AsnQueryMapper.Head> hit = mapper.findByKey(k);
        if (hit.size() != 1) {
            return Optional.empty();      // 없음 또는 납품서 번호가 여러 협력사에 겹침
        }
        AsnHead h = hit.get(0).view();
        return Optional.of(new Asn(h, mapper.lines(h.asnId()).stream().map(AsnQueryMapper.Line::view).toList()));
    }
}
