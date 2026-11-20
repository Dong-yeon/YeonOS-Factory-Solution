// 시스템관리·플랫폼 화면 전용 도우미 sysUi(static/js/views/admin/sys-ui.js) 타입 — 공통 계약(hb.d.ts) 아님
interface SysUi {
    /** 저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다(없으면 바로 true) */
    leaveOk(): Promise<boolean>;
    /** hb.grid·hb.tree beforeSelect 용 — 입력이 없으면 바로 true(동기), 있으면 leaveOk() */
    beforeSelect(): true | Promise<boolean>;
    /** 확인 대화상자 — 제목·버튼 = 동사 문구 키 */
    ask(titleKey: string, message: string | null, okKey: string, danger?: boolean): Promise<boolean>;
    /** 사유 입력(필수, 500자) — 취소 = null */
    reason(titleKey: string, labelKey: string, okKey: string): Promise<string | null>;
    /** 상태 배지 — TONES[kind][code] 톤, 모르는 코드는 neutral */
    badge(kind: string, code: string, text?: string, title?: string | null): HTMLSpanElement;
    /** 종류 → (코드 → 톤) — hb.grid status 옵션에 그대로 */
    TONES: Record<string, Record<string, HB.StatusTone>>;
    /** 빠른 찾기 1칸(표) — 입력마다 행을 거르고, 표를 다시 그려도 유지 */
    tableFind(formId: string, tbody: HTMLElement): void;
    /** 빠른 찾기 1칸(트리) — Enter = 다음 맞는 행으로(미저장이면 먼저 묻는다) */
    treeFind(formId: string, tree: HB.Tree, fields: string[]): void;
}
declare var sysUi: SysUi;
