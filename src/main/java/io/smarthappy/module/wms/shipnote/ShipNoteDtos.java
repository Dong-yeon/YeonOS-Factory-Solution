package io.smarthappy.module.wms.shipnote;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 출하 특이사항 (docs/56) — 규칙 화면·출하 화면·PDA 피킹이 같이 쓰는 모양 */
public final class ShipNoteDtos {

    private ShipNoteDtos() {
    }

    /** 규칙 — 조건(고객·품목군·품목, 비면 전체)과 글. rowVer = 규칙 버전(확인은 이 버전에 묶인다) */
    @Getter
    @Setter
    public static class Note {
        private long noteId;
        private Long custId;
        private String custCd;
        private String custNm;
        private Long itemClassId;
        private String classCd;
        private String classNm;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private String title;
        private String body;
        private String ackRequiredYn;
        private String useYn;
        private int sortNo;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 규칙 저장 — noteId 없으면 추가. 고객·품목군·품목은 코드(비우면 전체), 품목군과 품목은 둘 중 하나만 */
    public record SaveForm(Long noteId, String custCd, String classCd, String itemCd, String title, String body, String ackRequiredYn,
            String useYn, Integer sortNo, Integer rowVer) {
    }

    /** 품목군 고르기 */
    public record ClassOpt(long classId, String classCd, String classNm) {
    }

    /** 지시에 걸린 규칙 한 줄(SQL) — 줄마다 한 행, 확인은 현재 버전 것 */
    @Getter
    @Setter
    public static class MatchRow {
        private long noteId;
        private String title;
        private String body;
        private String ackRequiredYn;
        private int noteVer;
        private String custCd;
        private String classCd;
        private String itemCd;
        private long shipLineId;
        private OffsetDateTime ackedAt;
        private String ackedBy;
        private String ackWorkerNm;
    }

    /** 출하 지시에 걸린 특이사항 — lineIds = 걸린 출하 줄, acked = 현재 버전 확인 있음 */
    @Getter
    @Setter
    public static class ShipNote {
        private long noteId;
        private String title;
        private String body;
        private String ackRequiredYn;
        private int noteVer;
        private String custCd;
        private String classCd;
        private String itemCd;
        private List<Long> lineIds = new ArrayList<>();
        private boolean acked;
        private OffsetDateTime ackedAt;
        private String ackedBy;
        private String ackWorkerNm;
    }

    /** 그리드 한 쪽 */
    public record Page<T>(List<T> list, Long total) {
    }

    /** 규칙 목록 정렬 허용 열 */
    public enum NoteSort implements io.smarthappy.platform.common.query.SortField {
        SORT_NO("sortNo"), TITLE("title"), CUST_CD("custCd"), UPDATED_AT("updatedAt");

        private final String field;

        NoteSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
