package io.smarthappy.module.admin;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.admin.NoticeDtos.NoticeForm;
import io.smarthappy.module.admin.NoticeDtos.NoticeItem;
import io.smarthappy.module.admin.NoticeDtos.NoticeRow;
import io.smarthappy.module.admin.NoticeDtos.NoticeSearch;

/** 공지·자료 (docs/36 11.2). 회사별 표(RLS) — 회사 범위 트랜잭션 안에서 */
@Mapper
public interface NoticeMapper {

    List<NoticeRow> findPage(@Param("q") NoticeSearch q);

    long count(@Param("q") NoticeSearch q);

    NoticeRow find(@Param("noticeId") long noticeId, @Param("today") LocalDate today);

    long insert(@Param("f") NoticeForm f, @Param("by") String by);

    int update(@Param("noticeId") long noticeId, @Param("f") NoticeForm f, @Param("by") String by);

    // ---------- 읽는 사람 (게시 중만) ----------

    List<NoticeItem> findPosted(@Param("userId") long userId, @Param("today") LocalDate today,
            @Param("catCd") String catCd, @Param("limit") int limit);

    /** 게시 중이면 그 행, 아니면 null */
    NoticeRow findPostedOne(@Param("noticeId") long noticeId, @Param("today") LocalDate today);

    int markRead(@Param("noticeId") long noticeId, @Param("userId") long userId);

    long countUnread(@Param("userId") long userId, @Param("today") LocalDate today, @Param("since") LocalDate since);
}
