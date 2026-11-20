/** MES 화면 공용 표시 mesUi (src/main/resources/static/js/views/mes/mes-ui.js) — MES·영업·MES 몫 기준정보 화면만 싣는다 */
declare var mesUi: {
    /** 저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다 */
    leaveOk(): Promise<boolean>;
    ask(titleKey: string, message: string | null, okKey: string, danger?: boolean): Promise<boolean>;
    /** 사유 입력(500자, required 기본 true). 취소 = null */
    reason(titleKey: string, labelKey: string, okKey: string, required?: boolean): Promise<string | null>;
    /** 상태 배지 — kind: wo · proc · order · line · hold · qual · use */
    badge(kind: string, code: string, text?: string, title?: string | null): HTMLSpanElement;
    TONES: Record<string, Record<string, HB.StatusTone>>;
};
