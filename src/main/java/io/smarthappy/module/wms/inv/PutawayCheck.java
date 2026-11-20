package io.smarthappy.module.wms.inv;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.PutawayMapper.Allow;
import io.smarthappy.module.wms.inv.PutawayMapper.ItemCls;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 적치 검사 (W-12, 2026-11-02 사용자 결정, docs/30 12.19) — 입고·이동·PDA·고객 반품 받는 칸이 모두 이 함수 하나를 부른다
 * (경로마다 규칙이 다르면 다른 경로로 우회된다).
 *
 * <ul>
 * <li>실효 허용 품목군 = 그 노드에 사용 중 행이 있으면 그것, 없으면 가장 가까운 상위 노드의 목록(상속), 어디에도 없으면 전부 허용</li>
 * <li>품목군이 없는 품목은 목록이 있는 위치에서 위반(목록 = "이 품목군만")</li>
 * <li>정책 WMS_PUTAWAY_CHECK(공장): OFF = 보지 않음, WARN(기본) = 저장하고 경고 문구를 돌려줌, BLOCK = 409 WMS_PUTAWAY_DENIED</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PutawayCheck {

    static final String POLICY = "WMS_PUTAWAY_CHECK";

    private final PutawayMapper mapper;
    private final PolicyService policies;
    private final MessageSource messages;

    /**
     * 판정 — 업무 트랜잭션 안에서(같은 회사 범위).
     *
     * @return null = 문제 없음(또는 OFF), 문구 = WARN 경고
     * @throws BusinessException 409 WMS_PUTAWAY_DENIED — BLOCK 이고 위반
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String check(String plantCd, long nodeId, long itemId) {
        String mode = policies.value(POLICY, plantCd);
        if (mode == null || "OFF".equals(mode)) {
            return null;
        }
        List<Allow> allow = mapper.allowedFor(nodeId);
        if (allow.isEmpty()) {
            return null;                                                    // 목록 없는 위치 = 전부 허용
        }
        ItemCls it = mapper.itemClass(itemId);
        if (it == null || it.getClassId() != null && allow.stream().anyMatch(a -> a.getClassId() == it.getClassId())) {
            return null;
        }
        String node = Objects.requireNonNullElse(mapper.nodeCd(nodeId), String.valueOf(nodeId));
        String cls = it.getClassCd() == null ? "-" : it.getClassCd();
        String allowed = allow.stream().map(Allow::getClassCd).collect(Collectors.joining(", "));
        if ("BLOCK".equals(mode)) {
            throw new BusinessException(HbErrorCode.WMS_PUTAWAY_DENIED, node, it.getItemCd(), cls, allowed);
        }
        return messages.getMessage("wms.putaway.warn", new Object[] {node, it.getItemCd(), cls, allowed}, LocaleContextHolder.getLocale());
    }

    /** 여러 칸 판정을 모아 경고 목록으로(같은 문구는 한 번) */
    static List<String> warnings(String... w) {
        return java.util.Arrays.stream(w).filter(Objects::nonNull).distinct().toList();
    }
}
