package io.smarthappy.platform.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기본 메뉴 트리 한 벌 (docs/75) — 개발·데모 시드(DevCompanySeed)와 운영 콘솔의 새 회사(OpsConsoleService)가 같이 쓴다.
 *
 * - 폴더 = 이름 키(menu.*), 화면 = progCd. 화면 하나는 본 자리 하나 + 바로가기(link) 여러 곳.
 * - 새 화면을 만들면 여기 넣는다 — 메뉴에 안 넣는 화면(인쇄 팝업 등)은 {@link #NOT_IN_MENU} 에 근거와 함께.
 *   빠뜨리면 DefaultMenuTreeTest 가 실패한다(2026-11-12 이전에는 출하·APQP 등 11개 화면이 메뉴에 없었다).
 * - 깊이는 3단 이하(DB 상한 5, ck_sys_menu_depth).
 */
public final class DefaultMenuTree {

    /** 폴더면 progCd = null·key 있음, 화면이면 key = null. link = 바로가기(본 자리는 다른 곳) */
    public record Node(String key, String progCd, int sortNo, boolean link, List<Node> children) {
        public boolean folder() {
            return progCd == null;
        }
    }

    static Node folder(String key, int sortNo, Node... children) {
        return new Node(key, null, sortNo, false, List.of(children));
    }

    static Node prog(String progCd, int sortNo) {
        return new Node(null, progCd, sortNo, false, List.of());
    }

    static Node link(String progCd, int sortNo) {
        return new Node(null, progCd, sortNo, true, List.of());
    }

    /** 맨 위 순서: 홈(1·2) → 내 업무(50) → 업무 모듈(100~650) → 현장·모바일(800) → 시스템(900) */
    public static final List<Node> ROOTS = List.of(
            prog("home-main", 1),
            prog("home-flow-map", 2),
            folder("menu.my", 50,
                    prog("sys-inbox", 10), prog("sys-approval", 20), link("pms-worklog", 30), prog("sys-my-info", 40)),
            folder("menu.master", 100,
                    folder("menu.master.item", 10,
                            prog("mst-item", 10), prog("mst-bom", 20), prog("mst-class-prop", 60)),
                    folder("menu.master.plant", 20,
                            prog("mst-equip-hier", 20), prog("mst-equip", 30), prog("mst-asset", 40),
                            prog("mst-proc", 50), prog("mst-routing", 60), prog("mst-work-cal", 70)),
                    folder("menu.master.people", 30,
                            prog("mst-shift", 10), prog("mst-qual", 20), prog("mst-worker", 30)),
                    folder("menu.master.quality", 40,
                            prog("mst-char", 10), prog("mst-defect", 20)),
                    folder("menu.master.partner", 50,
                            prog("mst-cust", 10), prog("mst-ext-system", 20))),
            folder("menu.sales", 200,
                    prog("sal-order", 10), prog("sal-forecast", 20)),
            folder("menu.aps", 250,
                    prog("aps-item-param", 10), prog("aps-mps", 20), prog("aps-mrp", 30), prog("aps-plan", 40)),
            folder("menu.production", 300,
                    folder("menu.production.work", 10,
                            prog("prd-wo", 10), prog("prd-result", 20), prog("prd-receipt", 30), prog("prd-erp-export", 40)),
                    folder("menu.production.lot", 20,
                            prog("prd-lot", 10), prog("prd-serial", 30))),
            folder("menu.qms", 350,
                    folder("menu.qms.insp", 10,
                            prog("qms-insp", 10), prog("qms-setup", 20), prog("qms-insp-plan", 30), prog("qms-sampling", 40),
                            prog("qms-spc", 50), prog("qms-preship", 60), prog("qms-preship-tpl", 80)),
                    folder("menu.qms.ncr", 20,
                            prog("qms-ncr", 10), prog("qms-contain", 20), prog("qms-capa", 30), prog("qms-ocap", 40),
                            link("prd-lot", 50)),   // 출하 추적(리콜)은 LOT 추적 화면의 [관련 화면] (docs/79 M2c·Q8)
                    folder("menu.qms.gauge", 30,
                            prog("qms-gauge-calib", 10), prog("qms-msa", 20)),
                    folder("menu.qms.change", 40,
                            prog("qms-change", 10), prog("qms-doc", 20), prog("qms-csr", 30), prog("qms-consistency", 40)),
                    folder("menu.qms.apqp", 50,
                            prog("apqp-fmea", 10), prog("apqp-dfmea", 20), prog("apqp-cp", 30), prog("apqp-ap", 40)),
                    link("mst-char", 80), link("mst-defect", 90)),
            folder("menu.wms", 400,
                    folder("menu.wms.stock", 10,
                            prog("wms-stock", 10), prog("wms-trx", 20), prog("wms-move", 30), prog("wms-count", 40),
                            prog("wms-period", 50), prog("wms-loc-check", 60)),
                    folder("menu.wms.flow", 20,
                            prog("wms-rcv", 10), prog("wms-ship", 20), prog("wms-ship-note", 30), prog("wms-cust-rtn", 40),
                            prog("wms-rtn-issue", 50)),
                    folder("menu.wms.label", 30,
                            prog("wms-loc", 10), prog("wms-label", 20), prog("wms-print-job", 30))),
            folder("menu.srm", 450,
                    folder("menu.srm.po", 10,
                            prog("srm-pr", 10), prog("srm-po", 20), prog("srm-ack", 30), prog("srm-asn", 40),
                            link("sys-outbox", 60)),   // 통지 이력은 연계 현황의 [관련 화면] (docs/79 M8·Q8)
                    folder("menu.srm.supplier", 20,
                            prog("srm-supplier", 10), prog("srm-eval-tmpl", 20), prog("srm-eval", 30), prog("srm-claim", 40),
                            prog("srm-return", 50))),
            folder("menu.pms", 500,
                    prog("pms-home", 10), prog("pms-project", 20), prog("pms-template", 30), prog("pms-gate", 40),
                    prog("pms-deliverable", 50), prog("pms-issue", 60), prog("pms-worklog", 70)),
            folder("menu.cms", 600,
                    folder("menu.cms.maint", 10,
                            prog("cms-breakdown", 10), prog("cms-mwo", 20), prog("cms-chk-run", 30), prog("cms-pm-plan", 40),
                            prog("cms-chk-tmpl", 50), prog("cms-counter", 60), prog("cms-spare", 70)),
                    folder("menu.cms.monitor", 20,
                            prog("cms-dashboard", 10), prog("cms-state", 20), prog("cms-equip-count", 30), prog("cms-alarm", 40)),
                    folder("menu.cms.info", 30,
                            prog("cms-equip-profile", 10), prog("cms-equip-link", 50), link("mst-equip", 80), link("mst-asset", 90)),
                    folder("menu.cms.base", 40,
                            prog("cms-fail-code", 10), prog("cms-state-reason", 20), prog("cms-alarm-def", 30))),
            folder("menu.plm", 650,
                    prog("plm-ecr", 10), prog("plm-eco", 20), link("mst-bom", 80), link("mst-routing", 90)),
            folder("menu.field", 800,
                    prog("sys-mobile-app", 10), prog("sys-terminal", 50)),
            folder("menu.system", 900,
                    folder("menu.system.user", 10,
                            prog("sys-user", 10), prog("sys-auth-group", 20), prog("sys-menu", 30), prog("sys-dept", 40),
                            prog("sys-comp", 50)),
                    folder("menu.system.setting", 20,
                            prog("sys-policy", 10), prog("sys-code", 20), prog("sys-numbering", 30), prog("home-card-mgt", 40),
                            prog("sys-notice", 50), prog("sys-module", 60)),
                    folder("menu.system.log", 30,
                            prog("sys-access-log", 10), prog("sys-audit-log", 20), prog("sys-integrity", 30),
                            prog("sys-outbox", 40), prog("sys-feedback", 50), prog("sys-usage", 60), prog("sys-support", 70))));

    /**
     * 메뉴에 넣지 않는 화면 — 화면·API·권한(sys_prog·@ProgAuth·권한그룹)은 그대로, 메뉴에만 없다. 주소 /page/{progCd} 로는 열린다.
     * 시드가 만든 뒤 손대지 않은 메뉴 행은 {@link DevCompanySeed#ensureMenuTree} 가 지운다(docs/79 Q6).
     */
    public static final Map<String, String> NOT_IN_MENU_WHY = Map.ofEntries(
            Map.entry("srm-po-print", "인쇄 팝업 — 발주 관리에서 [인쇄]"),
            Map.entry("srm-asn-print", "인쇄 팝업 — 사전 납품 통지에서 [인쇄]"),
            // 합치기(docs/79 3.1, 메뉴만 방식 A) — 남는 화면의 [관련 화면] 단추로 연다
            Map.entry("prd-pop", "현장·모바일 진입(sys-mobile-app)의 [관련 화면] — M1"),
            Map.entry("wms-pda", "현장·모바일 진입(sys-mobile-app)의 [관련 화면] — M1"),
            Map.entry("sys-pda-app", "현장·모바일 진입(sys-mobile-app)의 [관련 화면] — M1"),
            Map.entry("prd-lot-report", "LOT 추적(prd-lot)의 [관련 화면] — M2a"),
            Map.entry("prd-serial-trace", "시리얼 구성(prd-serial)의 [관련 화면] — M2b"),
            Map.entry("qms-lot-trace", "LOT 추적(prd-lot)의 [관련 화면], 품질 폴더에 LOT 추적 바로가기 — M2c"),
            Map.entry("cms-kpi", "설비·생산 대시보드(cms-dashboard)의 [관련 화면] — M3a"),
            Map.entry("cms-state-review", "설비 상태 타임라인(cms-state)의 [관련 화면] — M3b"),
            Map.entry("cms-equip-card", "설비 프로필(cms-equip-profile)의 [관련 화면] — M3c"),
            Map.entry("mst-bom-tree", "BOM 관리(mst-bom)의 [관련 화면] — M4"),
            Map.entry("mst-bom-where", "BOM 관리(mst-bom)의 [관련 화면] — M4"),
            Map.entry("mst-bom-replace", "BOM 관리(mst-bom)의 [관련 화면] — M4"),
            Map.entry("mst-plant", "장비 계층·창고(mst-equip-hier)의 [관련 화면] — M5"),
            Map.entry("qms-preship-punch", "출하 전 검수(qms-preship)의 [관련 화면] — M6"),
            Map.entry("srm-po-status", "납기 회신(srm-ack)의 [관련 화면] — M7"),
            Map.entry("srm-notify", "연계 현황(sys-outbox)의 [관련 화면], 구매 폴더에 연계 현황 바로가기 — M8"),
            // 숨김(docs/79 3.2, Q3) — 다른 곳에서 읽지 않거나 장치 없이는 빈 화면
            Map.entry("mst-term", "숨김 H1 — mst_term_alias 를 읽는 곳이 이 화면뿐"),
            Map.entry("qms-copq", "숨김 H2 — qms_copq_cost 를 쓰는 곳이 이 화면뿐"),
            Map.entry("cms-equip-sec", "숨김 H3 — cms_equip_sec 를 쓰는 곳이 이 화면뿐"),
            Map.entry("cms-risk", "숨김 H4 — 산업안전 위험성평가, cms_risk_* 를 쓰는 곳이 이 화면뿐"),
            Map.entry("wms-asrs", "숨김 H5 — 자동창고 장치 연동, 장치 없이는 빈 화면"),
            Map.entry("wms-labeler", "숨김 H6 — 오토라벨러 장치 연동, 장치 없이는 빈 화면"));

    /** 메뉴에 넣지 않는 화면 코드 — 근거는 {@link #NOT_IN_MENU_WHY} */
    public static final Set<String> NOT_IN_MENU = NOT_IN_MENU_WHY.keySet();

    private DefaultMenuTree() {
    }

    /** 본 자리가 있는 화면 전부(바로가기 제외) */
    public static Set<String> progCds() {
        Set<String> out = new LinkedHashSet<>();
        walk(ROOTS, n -> {
            if (!n.folder() && !n.link()) {
                out.add(n.progCd());
            }
        });
        return out;
    }

    /** 폴더 이름 키 전부 */
    public static List<String> folderKeys() {
        List<String> out = new ArrayList<>();
        walk(ROOTS, n -> {
            if (n.folder()) {
                out.add(n.key());
            }
        });
        return out;
    }

    static void walk(List<Node> nodes, java.util.function.Consumer<Node> fn) {
        for (Node n : nodes) {
            fn.accept(n);
            walk(n.children(), fn);
        }
    }
}
