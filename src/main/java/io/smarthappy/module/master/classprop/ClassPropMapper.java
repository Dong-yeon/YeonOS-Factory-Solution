package io.smarthappy.module.master.classprop;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.master.classprop.ClassPropDtos.ClassForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassRow;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropRow;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ClassPropMapper {

    // ---------- 클래스 ----------

    List<ClassRow> findItemClasses(@Param("useYn") String useYn);

    ClassRow findItemClass(@Param("id") long id);

    long insertItemClass(@Param("f") ClassForm f, @Param("by") String by);

    int updateItemClass(@Param("id") long id, @Param("f") ClassForm f, @Param("by") String by);

    /** 설비 클래스는 4b 화면이 관리 — 여기서는 읽기만 */
    List<ClassRow> findEquipClasses();

    /** 그리드(UI 단계 3 ①) — classType = ITEM | EQUIP(검사는 서비스), useYn 은 품목 클래스만 */
    List<ClassRow> findClassPage(@Param("classType") String classType, @Param("useYn") String useYn,
            @Param("page") PageQuery page);

    long countClasses(@Param("classType") String classType, @Param("useYn") String useYn);

    ClassRow findEquipClass(@Param("id") long id);

    // ---------- 속성 정의 ----------

    List<PropRow> findProps(@Param("classType") String classType, @Param("classId") long classId,
            @Param("activeOnly") boolean activeOnly);

    PropRow findProp(@Param("id") long id);

    long insertProp(@Param("f") PropValue f, @Param("by") String by);

    int updateProp(@Param("id") long id, @Param("f") PropValue f, @Param("by") String by);

    // ---------- 대상 값 ----------

    /** 품목·설비의 지금 클래스·attrs(JSON 글자)·row_ver — 없으면 null */
    TargetRow findTarget(@Param("classType") String classType, @Param("id") long id);

    int updateTarget(@Param("classType") String classType, @Param("id") long id, @Param("classId") Long classId,
            @Param("attrs") String attrs, @Param("rowVer") int rowVer, @Param("by") String by);

    @lombok.Getter
    @lombok.Setter
    class TargetRow {
        private Long classId;
        private String attrs;
        private int rowVer;
    }
}
