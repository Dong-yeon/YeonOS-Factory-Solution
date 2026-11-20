package io.smarthappy.module.wms.inv;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 적치 검사(W-12, docs/30 12.19) — 위치의 실효 허용 품목군(상속)·품목의 품목군. 회사 조건은 RLS */
@Mapper
public interface PutawayMapper {

    /** 노드의 실효 허용 품목군 — 자기 또는 가장 가까운 상위 노드 중 사용 중 행이 있는 첫 노드의 목록. 빈 목록 = 전부 허용 */
    List<Allow> allowedFor(@Param("nodeId") long nodeId);

    /** 품목 코드·품목군(없으면 class 칸 null) */
    ItemCls itemClass(@Param("itemId") long itemId);

    /** 노드 코드(문구용) */
    String nodeCd(@Param("nodeId") long nodeId);

    /** 노드에 직접 둔 사용 중 허용 품목군 id(위치 화면) */
    List<Long> ownClassIds(@Param("nodeId") long nodeId);

    /** 노드의 허용 행 전부(사용 중지 포함, 잠금) — 위치 화면 저장 */
    List<AllowRow> lockRows(@Param("nodeId") long nodeId);

    /** 품목군 선택지(사용 중) — 위치 화면 */
    List<Allow> classOptions();

    int insert(@Param("nodeId") long nodeId, @Param("classId") long classId, @Param("by") String by);

    int setUse(@Param("allowId") long allowId, @Param("useYn") String useYn, @Param("by") String by);

    @Getter
    @Setter
    class Allow {
        private long classId;
        private String classCd;
        private String classNm;
        private long ownerNodeId;
    }

    @Getter
    @Setter
    class ItemCls {
        private String itemCd;
        private Long classId;
        private String classCd;
    }

    @Getter
    @Setter
    class AllowRow {
        private long allowId;
        private long classId;
        private String useYn;
    }
}
