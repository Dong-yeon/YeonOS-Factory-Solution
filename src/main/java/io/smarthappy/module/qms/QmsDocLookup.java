package io.smarthappy.module.qms;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.DocDtos.RevRow;
import io.smarthappy.module.qms.link.DocLookup;

/**
 * 계약 {@link DocLookup} 구현 — 매퍼만, 트랜잭션을 따로 열지 않는다(부르는 쪽 트랜잭션에서 읽기, 예외 없음).
 * 회사 범위는 RLS 라 다른 회사 리비전·문서는 보이지 않는다(빈 값).
 */
@Component
public class QmsDocLookup implements DocLookup {

    private final DocMapper docMapper;
    private final QmsLinkMapper linkMapper;

    public QmsDocLookup(DocMapper docMapper, QmsLinkMapper linkMapper) {
        this.docMapper = docMapper;
        this.linkMapper = linkMapper;
    }

    @Override
    public Optional<DocRevView> rev(long docRevId) {
        return Optional.ofNullable(docMapper.findRev(docRevId)).map(QmsDocLookup::view);
    }

    @Override
    public List<DocRevView> revsByDocNo(String docNo) {
        if (docNo == null || docNo.isBlank()) {
            return List.of();
        }
        Long docId = linkMapper.findDocIdByNo(docNo.strip());
        return docId == null ? List.of() : docMapper.findRevs(docId).stream().map(QmsDocLookup::view).toList();
    }

    private static DocRevView view(RevRow r) {
        return new DocRevView(r.getDocRevId(), r.getDocId(), r.getDocNo(), r.getDocNm(), r.getRev(), r.getStatus());
    }
}
