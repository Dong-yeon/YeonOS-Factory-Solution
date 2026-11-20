package io.smarthappy.module.qms;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.DocPort;

/** 계약 {@link DocPort} 구현 — {@link DocService#publishDraft} 그대로(채번·리비전 규칙·첨부 검사는 관리문서 화면과 같은 코드) */
@Component
public class QmsDocPort implements DocPort {

    private final DocService docs;

    public QmsDocPort(DocService docs) {
        this.docs = docs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DocRevRef publish(DocDraft draft) {
        return docs.publishDraft(draft);
    }
}
