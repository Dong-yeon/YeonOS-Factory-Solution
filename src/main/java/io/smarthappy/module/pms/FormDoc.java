package io.smarthappy.module.pms;

import java.util.List;

import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocLookup.DocRevView;
import io.smarthappy.platform.common.web.FieldErrors;

/**
 * 산출물 빈 서식 = QMS 관리문서 번호(docs/32 4.17, P-8·D6). PMS 는 파일을 갖지 않고 번호만 저장한다.
 * 입력 때 QMS 계약 DocLookup.revsByDocNo(대소문자 무시, 사용 중 문서)로 있는지 보고, QMS 의 표기(대문자)로 저장한다
 */
final class FormDoc {

    private FormDoc() {
    }

    /** 빈 값 = 서식 없음(null). 없는 문서면 칸 오류 PMS_DOC_NOT_FOUND */
    static String check(DocLookup docs, String raw, String field, FieldErrors e) {
        String no = PmsInputs.visible(raw, field, 30, false, e);
        if (no == null) {
            return null;
        }
        List<DocRevView> revs = docs.revsByDocNo(no);
        if (revs.isEmpty()) {
            e.add(field, "PMS_DOC_NOT_FOUND");
            return null;
        }
        return revs.get(0).docNo();
    }
}
