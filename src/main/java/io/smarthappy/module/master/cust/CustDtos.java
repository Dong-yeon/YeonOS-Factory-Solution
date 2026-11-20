package io.smarthappy.module.master.cust;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 거래처관리 DTO 모음 (화면 표준 참조본) */
public final class CustDtos {

    private CustDtos() {
    }

    /** 목록·엑셀 행 — 목록과 엑셀이 같은 SQL·같은 DTO (docs/09 6.1) */
    @Getter
    @Setter
    public static class CustRow {
        private long custId;
        private String custCd;
        private String custNm;
        private String custType;
        private String bizNo;
        private String ceoNm;
        private String tel;
        private String email;
        private String addr;
        private String remark;
        private String useYn;
        private int rowVer;
        private int contactCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class ContactRow {
        private long contactId;
        private long custId;
        private String contactNm;
        private String deptNm;
        private String tel;
        private String email;
        private String mainYn;
        private int rowVer;
    }

    /** 검색 조건 (MyBatis 가 getter 로 읽는다). like 값은 이스케이프된 것 */
    @Getter
    public static class CustSearch {
        private final String custCdLike;
        private final String custNmLike;
        private final String custType;
        private final String useYn;
        private final PageQuery page;

        public CustSearch(String custCdLike, String custNmLike, String custType, String useYn, PageQuery page) {
            this.custCdLike = custCdLike;
            this.custNmLike = custNmLike;
            this.custType = custType;
            this.useYn = useYn;
            this.page = page;
        }
    }

    /** 저장 요청 (작성자·회사는 서버가 채운다 — 필드 자체를 두지 않음) */
    public record CustForm(String custCd, String custNm, String custType, String bizNo, String ceoNm, String tel,
            String email, String addr, String remark, String useYn, Integer rowVer) {
    }

    public record IdVer(Long id, Integer rowVer) {
    }

    public record DeleteRequest(List<IdVer> items) {
    }

    public record ContactItem(String rowKey, Long contactId, Integer rowVer, String contactNm, String deptNm, String tel,
            String email, String mainYn) {
    }

    /** 일괄 저장 규약 (docs/09 5.1) */
    public record ContactBatch(List<ContactItem> created, List<ContactItem> updated, List<ContactItem> deleted) {
    }

    public record CreatedKey(String rowKey, long id) {
    }

    public record BatchResult(List<CreatedKey> created, int updated, int deleted) {
    }

    public record CustDetail(CustRow cust, List<ContactRow> contacts) {
    }

    /** 엑셀 내보내기 요청 (목록과 같은 조건 + 보이는 열) */
    public record ExportRequest(String custCd, String custNm, String custType, String useYn, List<String> sort,
            List<String> columns) {
    }

    /** 정렬 화이트리스트 — 매퍼 <choose> 가 enum 이름으로 열을 고른다 */
    public enum CustSort implements SortField {
        CUST_CD("custCd"), CUST_NM("custNm"), CUST_TYPE("custType"), USE_YN("useYn"), UPDATED_AT("updatedAt");

        private final String field;

        CustSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
