package io.smarthappy.module.demo;

/**
 * 모듈 데모 시더 순서 (local·demo 프로필). 기준정보·MES 관통은 {@link DemoDataSeeder}(ApplicationRunner)라 모든 ApplicationReadyEvent 보다 먼저 돈다.
 * 모듈 시더는 ApplicationReadyEvent 리스너 메서드에 {@code @Order(DemoSeedOrder.X)} 를 단다 — {@code @Order} 가 없으면 LOWEST_PRECEDENCE 라
 * 뒤에 와야 할 시더(SRM·PLM)보다 늦게 돌 수 있다(같은 순서끼리는 정해지지 않음). {@code DemoSeedOrderGuardTest} 가 빠뜨림을 막는다.
 *
 * <p>순서 이유: QMS·WMS 시더는 "그 회사에 데이터가 없을 때만" 넣는다 — SRM 입고·NCR 이 먼저 생기면 그 시더들이 건너뛴다.
 * SRM 은 입고(WMS 원장)·NCR(QMS) 을, PLM 은 4M(QMS)·설비·공정을 쓰므로 그 뒤.
 * MES 이력(2026-11-03)은 CMS 상태 원장의 가동 시간으로 양을 정하므로 CMS 뒤, QMS 이력(최종검사·부적합)·WMS(출하·반품)가 MES LOT·완제품 재고를
 * 쓰므로 그 앞. QMS 이력 보충은 QMS 공정검사 시더 뒤 — 그 시더는 "검사가 하나라도 있으면" 건너뛰기 때문이다.
 */
public final class DemoSeedOrder {

    public static final int CMS = 100;
    /** MES 지난 이력(지시·실적·완제품 입고) */
    public static final int MES = 150;
    public static final int QMS = 200;
    /** QMS 이력 보충(최종검사·부적합·8D·COPQ) — QMS 공정검사 시더 뒤 */
    public static final int QMS_HISTORY = 210;
    public static final int WMS = 300;
    /** PMS·APQP(같은 세션) */
    public static final int PMS = 400;
    public static final int SRM = 500;
    public static final int PLM = 600;
    /** APS 계획 시나리오(재고·공급원·수주를 읽는다 — 모든 모듈 시더 뒤) */
    public static final int APS = 700;

    private DemoSeedOrder() {
    }
}
