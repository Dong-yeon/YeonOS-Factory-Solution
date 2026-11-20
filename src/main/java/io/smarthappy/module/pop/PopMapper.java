package io.smarthappy.module.pop;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pop.PopDtos.NodeOption;
import io.smarthappy.module.pop.PopDtos.PlantOption;
import io.smarthappy.module.pop.PopDtos.TaskRow;
import io.smarthappy.module.pop.PopDtos.TerminalAuthRow;
import io.smarthappy.module.pop.PopDtos.TerminalForm;
import io.smarthappy.module.pop.PopDtos.TerminalRow;
import io.smarthappy.module.pop.PopDtos.WorkerAuthRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface PopMapper {

    // ---------- 단말 관리 ----------

    List<TerminalRow> findTerminals();

    /** grid(UI 단계 3 ①) — 목록과 같은 대상(전체) + 페이지·정렬(허용 목록) */
    List<TerminalRow> findTerminalPage(@Param("page") PageQuery page);

    long countTerminals();

    TerminalRow findTerminal(@Param("id") long id);

    List<PlantOption> plantOptions();

    List<NodeOption> nodeOptions(@Param("plantCd") String plantCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    long insertTerminal(@Param("f") TerminalForm f, @Param("activationHash") String activationHash,
            @Param("expiresAt") Instant expiresAt, @Param("by") String by);

    int updateTerminal(@Param("id") long id, @Param("f") TerminalForm f, @Param("by") String by);

    /** 새 등록 코드 — 기존 단말 키는 지운다(즉시 무효) */
    int reissue(@Param("id") long id, @Param("activationHash") String activationHash, @Param("expiresAt") Instant expiresAt,
            @Param("rowVer") int rowVer, @Param("by") String by);

    /** 수집 장치 키 발급·다시 발급(폐기 안 된 것만) */
    int setKey(@Param("id") long id, @Param("keyHash") String keyHash, @Param("by") String by);

    int revoke(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 단말 인증 ----------

    /** 등록 코드로 찾기(만료 전·폐기 안 됨) — 회사 범위 안에서 */
    Long findByActivation(@Param("activationHash") String activationHash);

    int activate(@Param("id") long id, @Param("keyHash") String keyHash);

    TerminalAuthRow findAuth(@Param("id") long id);

    int touch(@Param("id") long id);

    // ---------- 작업자 ----------

    WorkerAuthRow findWorkerByBadge(@Param("badgeHash") String badgeHash);

    /**
     * 키오스크 빠져나오기 기록(KS9) — 이 단말(행위자)이 최근 1시간 안에 받은 exitRef 의 POP_KIOSK_EXIT OK 행 detail, 없으면 null.
     * needle = " ref={exitRef}"(detail 끝 토큰). 회사는 RLS, 기간은 접속 로그 시간 인덱스(comp_cd, occurred_at)로 좁힌다
     */
    String findKioskExitDetail(@Param("actor") String actor, @Param("needle") String needle);

    WorkerAuthRow findWorker(@Param("id") long id);

    /** PIN 시도 선점 — 이번까지의 실패 수, 잠겨 있으면 null */
    Integer pinClaim(@Param("id") long id, @Param("lockAt") int lockAt, @Param("now") java.time.OffsetDateTime now,
            @Param("lockEnd") java.time.OffsetDateTime lockEnd);

    /** 맞는 PIN — claimed = 이번 선점이 돌려준 실패 수. 그대로면 0·잠금 해제, 그사이 다른 선점이 있었으면 자기 몫만 − 1(잠금 그대로) */
    int pinOk(@Param("id") long id, @Param("claimed") int claimed);

    /** 감독자 자격(코드)을 그날 유효하게 가졌는지 — 자격 사용 중, 취득 ≤ 그날 ≤ 만료, 취소 안 됨 (docs/25 4.37) */
    boolean approverQualified(@Param("workerId") long workerId, @Param("qualCd") String qualCd, @Param("today") java.time.LocalDate today);

    // ---------- 작업장 홈 ----------

    List<TaskRow> findTasks(@Param("nodeId") Long nodeId, @Param("plantCd") String plantCd, @Param("limit") int limit);
}
