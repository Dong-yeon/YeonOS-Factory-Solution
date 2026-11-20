package io.smarthappy.module.srm.notify;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.notify.NotifyDtos.NoticeCrit;
import io.smarthappy.module.srm.notify.NotifyDtos.NoticeRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface NotifyMapper {

    List<NoticeRow> findNotices(@Param("c") NoticeCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<NoticeRow> findNoticePage(@Param("c") NoticeCrit c, @Param("page") PageQuery page);

    long countNotices(@Param("c") NoticeCrit c);

    NoticeRow findNotice(@Param("id") long id);
}
