package io.smarthappy.platform.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.BusinessException.Detail;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.module.ModuleCatalog.Broken;
import io.smarthappy.platform.module.ModuleCatalog.Module;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 회사별 사용 모듈 (docs/80). 행이 없으면 켜짐. 판정 자체는 권한 SQL(AuthMapper.xml moduleOn)이 하고,
 * 여기서는 "왜 막혔나"(MODULE_DISABLED)를 가리고, 화면 목록·저장(의존 검사)을 맡는다.
 * 회사 범위 안(트랜잭션)에서만 — 회사는 TenantContext 에서.
 */
@Service
@RequiredArgsConstructor
public class CompModuleService {

    public static final String PROG = "sys-module";
    private static final String CACHE_KEY = CompModuleService.class.getName() + ".off";

    private final JdbcTemplate jdbc;
    private final InboxService inbox;
    private final MessageSource messages;

    /** 화면 한 줄 */
    public record ModuleView(String code, String name, boolean toggleable, boolean on, int progCount,
            List<String> requires, List<String> recommends, List<String> requiredBy) {
    }

    public record SaveForm(List<String> on) {
    }

    /** 이 회사에서 꺼진 모듈 코드(요청 안 캐시) */
    @Transactional(readOnly = true)
    public Set<String> offModules() {
        RequestAttributes req = RequestContextHolder.getRequestAttributes();
        if (req != null && req.getAttribute(CACHE_KEY, RequestAttributes.SCOPE_REQUEST) instanceof Set<?> cached) {
            @SuppressWarnings("unchecked")
            Set<String> hit = (Set<String>) cached;
            return hit;
        }
        Set<String> off = Set.copyOf(jdbc.queryForList(
                "select module_cd from sys_comp_module where use_yn = 'N'", String.class));
        if (req != null) {
            req.setAttribute(CACHE_KEY, off, RequestAttributes.SCOPE_REQUEST);
        }
        return off;
    }

    @Transactional(readOnly = true)
    public boolean isOn(String moduleCd) {
        return moduleCd == null || ModuleCatalog.SYS.equals(moduleCd) || !offModules().contains(moduleCd);
    }

    /** 화면이 속한 모듈이 꺼졌으면 그 코드, 아니면 null (없는 화면도 null — 404·권한 판정은 원래 자리에서) */
    @Transactional(readOnly = true)
    public String disabledModuleOfProg(String progCd) {
        Set<String> off = offModules();
        if (off.isEmpty() || progCd == null) {
            return null;
        }
        List<String> mod = jdbc.queryForList("select module_cd from sys_prog where prog_cd = ?", String.class, progCd);
        return mod.isEmpty() || !off.contains(mod.get(0)) ? null : mod.get(0);
    }

    @Transactional(readOnly = true)
    public List<ModuleView> list(Locale locale) {
        Set<String> off = offModules();
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("select module_cd, count(*) as n from sys_prog where use_yn = 'Y' group by module_cd",
                rs -> {
                    counts.put(rs.getString(1), rs.getInt(2));
                });
        List<ModuleView> out = new ArrayList<>();
        for (Module m : ModuleCatalog.MODULES) {
            out.add(new ModuleView(m.code(), name(m.code(), locale), m.toggleable(), !off.contains(m.code()),
                    counts.getOrDefault(m.code(), 0), sorted(m.requires()), sorted(m.recommends()),
                    sorted(ModuleCatalog.requiredBy(m.code()))));
        }
        return out;
    }

    /**
     * 켤 모듈 목록으로 저장(목록에 없는 끌 수 있는 모듈 = 끔). 필수 짝이 빠지면 400 MODULE_DEPENDENCY(빠진 짝마다 한 줄).
     * 자동으로 함께 켜지 않는다 — 몰래 다른 값이 저장되지 않게.
     */
    @Transactional
    public List<ModuleView> save(SaveForm form, Locale locale) {
        inbox.claimRequestKey("SYS_MODULE_SAVE");
        Set<String> on = validate(form == null ? null : form.on(), locale);
        // 같은 회사 동시 저장은 차례로(트랜잭션 advisory 잠금) — 마지막 저장이 이긴다. 둘 다 검사를 통과한 온전한 집합이라 섞이지 않는다
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))::text", String.class, "sys_comp_module:" + TenantContext.requireCompCd());
        write(jdbc, TenantContext.requireCompCd(), on, TenantContext.currentActor());
        RequestAttributes req = RequestContextHolder.getRequestAttributes();
        if (req != null) {
            req.removeAttribute(CACHE_KEY, RequestAttributes.SCOPE_REQUEST);
        }
        return list(locale);
    }

    /** 목록 밖 코드·SYS 는 400, 필수 짝이 빠지면 400 MODULE_DEPENDENCY. 켤 모듈 집합(SYS 제외)을 돌려준다 */
    public Set<String> validate(List<String> requested, Locale locale) {
        if (requested == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(
                    FieldErrors.create().add("on", "REQUIRED").list());
        }
        FieldErrors e = FieldErrors.create();
        Set<String> on = new LinkedHashSet<>();
        for (String code : requested) {
            Module m = ModuleCatalog.find(code).orElse(null);
            if (m == null) {
                e.add("on", "NOT_FOUND");
            } else if (m.toggleable()) {
                on.add(code);
            }
        }
        e.throwIfAny();
        List<Broken> broken = ModuleCatalog.brokenPairs(on);
        if (!broken.isEmpty()) {
            List<Detail> details = broken.stream()
                    .map(b -> new Detail("module.dep.requires", name(b.module(), locale), name(b.missing(), locale))).toList();
            throw new BusinessException(HbErrorCode.MODULE_DEPENDENCY, details);
        }
        return on;
    }

    /**
     * 회사 모듈 행을 켤 집합대로 적는다(끌 수 있는 모듈 전부 Y/N). 회사 범위 안에서 부른다 — 시드·운영 콘솔·화면 저장이 같이 쓴다.
     * 값이 같으면 행을 건드리지 않는다(감사·row_ver 그대로).
     */
    public static void write(JdbcTemplate jdbc, String compCd, Set<String> on, String actor) {
        for (String code : ModuleCatalog.toggleableCodes()) {
            String yn = on.contains(code) ? "Y" : "N";
            jdbc.update("""
                    insert into sys_comp_module (comp_cd, module_cd, use_yn, created_by, updated_by) values (?, ?, ?, ?, ?)
                    on conflict (comp_cd, module_cd) do update
                       set use_yn = excluded.use_yn, row_ver = sys_comp_module.row_ver + 1,
                           updated_at = now(), updated_by = excluded.updated_by
                     where sys_comp_module.use_yn <> excluded.use_yn""", compCd, code, yn, actor, actor);
        }
    }

    /** 개발·데모 시드: 회사에 모듈 행이 하나도 없을 때만 기본(DEMO_DEFAULT_ON)을 적는다 — 관리자가 바꾼 값은 둔다 */
    public static void seedDefaults(JdbcTemplate jdbc, String compCd, String actor) {
        Integer n = jdbc.queryForObject("select count(*) from sys_comp_module where comp_cd = ?", Integer.class, compCd);
        if (n == null || n == 0) {
            write(jdbc, compCd, ModuleCatalog.DEMO_DEFAULT_ON, actor);
        }
    }

    private String name(String code, Locale locale) {
        String key = ModuleCatalog.find(code).map(Module::nameKey).orElse(code);
        return messages.getMessage(key, null, code, locale);
    }

    private static List<String> sorted(Set<String> codes) {
        return codes.stream().sorted(ModuleCatalog::byMenuOrder).toList();
    }
}
