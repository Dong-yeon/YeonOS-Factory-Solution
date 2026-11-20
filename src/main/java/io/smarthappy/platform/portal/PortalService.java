package io.smarthappy.platform.portal;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.MyInfoService;
import lombok.RequiredArgsConstructor;

/**
 * 포털 셸 (docs/42 7장 2) — 머리글의 소속 협력사, 자기 비밀번호 바꾸기.
 * 소속은 PortalUser.current() 로만(요청 값 없음). 회사 범위는 요청 필터가 연 그대로(RLS).
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    private final JdbcTemplate jdbc;
    private final MyInfoService myInfo;

    public record Supplier(String custCd, String custNm) {
    }

    /** 내 소속 협력사 (사용 중지면 요청 필터가 이미 세션을 끊었다) */
    @Transactional(readOnly = true)
    public Supplier supplier() {
        PortalUser me = PortalUser.current();
        List<Supplier> rows = jdbc.query("select cust_cd, cust_nm from mst_cust where cust_id = ?",
                (rs, i) -> new Supplier(rs.getString(1), rs.getString(2)), me.custId());
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 자기 비밀번호 바꾸기 — 회사 사용자 내 정보와 같은 검사(현재 비밀번호·정책·같은 값 금지, 틀리면 실패 횟수).
     * 트랜잭션으로 감싸지 않는다: MyInfoService 가 틀린 비밀번호의 실패 횟수를 남기려 noRollbackFor 를 쓰는데,
     * 바깥 트랜잭션이 있으면 그 예외로 바깥이 롤백돼 실패 횟수가 사라진다(5회 잠금이 걸리지 않는다)
     */
    public void changePassword(String currentPassword, String newPassword) {
        PortalUser.current();
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        myInfo.changePassword(user, currentPassword, newPassword);
    }
}
