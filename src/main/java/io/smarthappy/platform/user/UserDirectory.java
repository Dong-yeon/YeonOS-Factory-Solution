package io.smarthappy.platform.user;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 공통 사용자 고르기 (사용자 결정 2026-10-24, docs/42 15장) — 업무 모듈의 담당자·소유자·결재자 고르기와 아이디 확인은 여기로만.
 * 돌려주는 사람은 **회사 사용자(INTERNAL)뿐**이다 — 협력사 포털 사용자는 담당자·결재자가 될 수 없다(docs/42 2장).
 * 모듈이 sys_user 를 직접 골라 쓰지 못하게 가드 시험(UserPickGuardTest)이 막는다(저장된 id 의 이름 표시 조인은 허용).
 * active = 사용 중(use_yn = 'Y') + 관리자 잠금 아님 — 결재선·위임과 같은 규칙. 같은 회사만(RLS)
 */
@Service
@RequiredArgsConstructor
public class UserDirectory {

    private static final int MAX_LOGIN = 50;

    private final UserDirectoryMapper mapper;

    /** 고르기 목록: 사용 중 회사 사용자, 이름순 */
    @Transactional(readOnly = true)
    public List<UserOption> activeUsers() {
        return mapper.activeUsers();
    }

    /** 아이디(대소문자 무시)로 — 상태 무관. 없거나 협력사 사용자면 null */
    @Transactional(readOnly = true)
    public UserRef byLogin(String loginId) {
        String login = loginId == null ? null : loginId.strip();
        return login == null || login.isEmpty() || login.length() > MAX_LOGIN ? null : mapper.byLogin(login);
    }

    /** 아이디로 — 사용 중인 사람만(아니면 null) */
    @Transactional(readOnly = true)
    public UserOption activeByLogin(String loginId) {
        UserRef r = byLogin(loginId);
        return r == null || !r.active() ? null : new UserOption(r.userId(), r.loginId(), r.userNm());
    }

    /** 사용 중 회사 사용자인가 (없음·협력사 = false) */
    @Transactional(readOnly = true)
    public boolean isActive(long userId) {
        UserRef r = mapper.byId(userId);
        return r != null && r.active();
    }

    /**
     * 그 사람의 부서장 — 결재선 기본값(PMS 착수·종료 등, docs/36 9.7 D-1). 소속 부서에서 위로 올라가며 처음 만나는 장을 돌려준다.
     * <ul>
     *   <li>본인이 장이면 건너뛴다 → 상위 부서의 장(자기 결재 방지)</li>
     *   <li>장이 사용 중지·관리자 잠금이거나 부서가 사용 중지면 건너뛴다 → 상위로</li>
     *   <li>소속이 없거나 끝까지 없으면 null — 부르는 쪽이 사람에게 고르게 한다(기본값일 뿐, 결재선 확정은 상신하는 사람)</li>
     * </ul>
     * 같은 회사만(RLS). 다른 회사·협력사 사용자 id 는 null
     */
    @Transactional(readOnly = true)
    public UserOption deptHeadOf(long userId) {
        return mapper.deptHeadOf(userId);
    }

    /**
     * 권한그룹 행으로 그 화면 액션을 가진 사용 중 회사 사용자 — 결재자 선택지(예: 발주 승인 srm-po APPROVE).
     * 관리자 그룹(행 없이 전부)은 넣지 않는다(지금 SRM 동작 그대로 — 결재자는 명시적으로 준 사람만)
     */
    @Transactional(readOnly = true)
    public List<UserOption> groupActionHolders(String progCd, String actionCd) {
        return mapper.groupActionHolders(progCd, actionCd);
    }
}
