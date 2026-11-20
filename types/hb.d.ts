/*
 * hb.* 화면 공통 API 계약 (docs/13 6.3·11장 L4). **선언만** — 런타임 배포물(static/)에 들어가지 않는다.
 * 구현은 src/main/resources/static/js/common/hb*.js 이고, 각 파일이 `// @ts-check` 로 이 계약에 맞는지 검사받는다
 * (구현이 `global.hb.grid = grid` 처럼 붙일 때 타입이 다르면 tsc 오류).
 *
 * - hb.js 가 먼저 hb 를 만들고, 나머지 모듈(hb.grid·hb.page …)은 **화면 템플릿이 그 스크립트를 넣었을 때만** 붙는다.
 *   여기서는 항상 있는 것으로 선언한다(빠뜨리면 브라우저에서 바로 드러나고, 매번 존재 확인을 쓰게 하면 화면 JS 가 지저분해진다).
 * - 서버 응답 행은 화면마다 모양이 달라 `HB.Row`(키 → any)로 둔다. 화면 JS 에서 좁히고 싶으면 JSDoc @typedef 로.
 * 검사: npm run typecheck (= tsc -p jsconfig.json) · Gradle `check` 의 typecheckJs
 */

declare namespace HB {
    // ------------------------------------------------------------------ hb.js
    /** 서버 행(목록·상세). 키는 camelCase 필드명 */
    type Row = Record<string, any>;

    /** 칸 오류: 폼은 field, 디테일 행은 rowKey + field, 엑셀 올리기 행은 title */
    interface FieldError {
        rowKey?: string;
        field?: string;
        code?: string;
        message: string;
        title?: string;
    }

    /** hb.api·hb.upload·hb.download 가 reject 하는 값. 끊김·타임아웃은 status 0 (code NETWORK·TIMEOUT), hb.latest 가 버린 앞 요청은 code STALE */
    interface ApiError {
        status: number;
        code: string;
        message: string;
        details?: string[];
        errors?: FieldError[];
        /** hb.latest 가 버린 앞 요청(문구 빈 칸) */
        stale?: boolean;
    }

    /** 성공 응답 `{result, data}` 또는 목록 `{list, total}` (ApiResponse.java) */
    interface ApiResponse<T = any> {
        result?: string;
        data?: T;
        list?: T[];
        total?: number;
        message?: string;
    }

    interface ApiOptions {
        method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'get' | 'post' | 'put' | 'delete' | 'patch';
        /** JSON.stringify 해서 보낸다 */
        body?: unknown;
        timeoutMs?: number;
        /** 추가 헤더(X-Idempotency-Key 등). Accept·CSRF·Content-Type 은 hb.api 값이 우선 */
        headers?: Record<string, string>;
    }

    /** /api/platform/session (SessionApiController.SessionInfo). 받기 전 기본값은 timeZone·localeCd 만 */
    interface Session {
        timeZone: string;
        localeCd: string;
        compCd?: string;
        loginId?: string;
        userNm?: string;
        pwdChangeRequired?: boolean;
    }

    /** 공통코드 (CodeService.CodeItem) */
    interface Code {
        code: string;
        name: string;
        system?: boolean;
        attrs?: Record<string, unknown> | null;
    }

    interface Dirty {
        /** root 를 주면 그 폼만, 없으면 화면 전체 */
        mark(root?: Document | Element | null): void;
        /** root 를 주면 그 폼만 지운다, 없으면 전부 */
        clear(root?: Document | Element | null): void;
        isDirty(): boolean;
        /** 이 요소(없으면 document) 안 input 이벤트로 자동 표시 */
        watch(root?: Document | Element | null): void;
    }

    interface Fmt {
        /** scale 자리까지 반올림, minScale(기본 = scale) 자리까지 끝 0 을 채운다. 숫자가 아니면 글자 그대로 */
        num(v: unknown, scale?: number, minScale?: number | null): string;
        /** 'YYYY-MM-DD…' → 앞 10자리 */
        date(v: unknown): string;
        /** 오프셋 포함 ISO → tz(없으면 회사 시간대) 'YYYY-MM-DD HH:mm' */
        datetime(v: unknown, tz?: string | null): string;
        /** 10자리 → 000-00-00000 */
        bizNo(v: string | null | undefined): string;
        /** LABEL 칸 — 표로 값 → 글자, 쉼표 값은 나눠 ", " 로 잇고 표에 없는 값은 그대로 */
        label(labels: Record<string, string> | undefined, v: unknown): string;
    }

    // ------------------------------------------------------------------ hb.dates
    type IsoDate = string;
    type DatePreset = 'today' | 'yesterday' | 'thisWeek' | 'last7' | 'last30' | 'thisMonth' | 'lastMonth';
    type DateParse = { ok: true; value: IsoDate } | { ok: false; code: 'EMPTY' | 'INVALID_DATE' };
    type DateRangeParse = { ok: true; from: IsoDate; to: IsoDate; swapped: boolean } | { ok: false; code: 'INVALID_DATE' };

    interface PresetOptions {
        /** 기준 오늘(테스트용). 없으면 회사 시간대 오늘 */
        today?: IsoDate;
        max?: IsoDate | null;
        min?: IsoDate | null;
        maxDays?: number | null;
        /** 1=월(기본), 0=일 */
        weekStart?: number;
    }

    interface Dates {
        PRESETS: string[];
        today(): IsoDate;
        todayIn(timeZone: string, now?: Date): IsoDate;
        add(iso: IsoDate, days: number): IsoDate;
        /** 종료일 포함 일수 */
        diffDays(from: IsoDate, to: IsoDate): number;
        /** 0=일 … 6=토 */
        weekday(iso: IsoDate): number;
        daysInMonth(y: number, m: number): number;
        compare(a: string, b: string): number;
        parse(text: unknown, opts?: { year?: number }): DateParse;
        /** 날짜가 하나면 null */
        parseRange(text: unknown): DateRangeParse | null;
        preset(key: string, opts?: PresetOptions): { from: IsoDate; to: IsoDate; days: number } | null;
        /** 'today' | 'yesterday' | 'YYYY-MM-DD' → 날짜, 그 밖은 null */
        resolve(spec: string | null | undefined, today?: IsoDate): IsoDate | null;
        isIso(s: unknown): boolean;
    }

    // ------------------------------------------------------------------ hb.numbers
    type NumberErrorCode = 'NUMBER' | 'NEGATIVE' | 'SCALE' | 'MIN' | 'MAX';
    /** value = 정규화된 10진 문자열('1234.5'), 빈 칸은 null */
    type NumberParse = { ok: true; value: string | null } | { ok: false; code: NumberErrorCode };

    interface NumberParseOptions {
        /** 소수 자릿수 상한(0 = 정수만). 넘으면 반올림하지 않고 SCALE */
        scale?: number | null;
        min?: string | number | null;
        max?: string | number | null;
        /** 기본 true */
        allowNegative?: boolean;
    }

    interface Numbers {
        parse(raw: unknown, opts?: NumberParseOptions): NumberParse;
        /** 소수 자릿수 고정 문자열. 빈 값은 null, 숫자 형식이 아니면 그대로 문자열 */
        fixed(value: string | number | null | undefined, scale?: number | null): string | null;
        /** 천 단위 쉼표 + 자릿수 고정. 빈 값은 '' */
        format(value: string | number | null | undefined, scale?: number | null): string;
        /** 정규화된 10진 문자열 비교. 숫자 형식이 아니면 TypeError */
        compare(a: string | number, b: string | number): number;
    }

    // ------------------------------------------------------------------ hb.calendar
    type CalendarCellState = 'start' | 'end' | 'single' | 'in' | 'preview';

    interface CalendarOptions {
        months?: number;
        weekStart?: number;
        focus?: IsoDate;
        state?(iso: IsoDate): CalendarCellState | null;
        disabled?(iso: IsoDate): boolean;
        onPick(iso: IsoDate): void;
        onHover?(iso: IsoDate | null): void;
        labels: { prev: string; next: string };
    }

    interface Calendar {
        el: HTMLElement;
        refresh(takeFocus?: boolean): void;
        paint(): void;
        focus(iso?: IsoDate | null): void;
        setMonths(n: number): void;
    }

    // ------------------------------------------------------------------ hb.dateRange
    interface DateRangeOptions {
        fromName?: string;
        toName?: string;
        label?: string;
        presets?: string[];
        defaultValue?: string | null;
        /** 'today' | 'yesterday' | 'YYYY-MM-DD' */
        max?: string | null;
        maxDays?: number | null;
        required?: boolean;
        weekStart?: number;
    }

    interface DateRangeValue {
        from: IsoDate | null;
        to: IsoDate | null;
        preset: string | null;
        days: number | null;
    }

    type DateRangeErrorCode = 'REQUIRED' | 'INVALID_DATE' | 'INVALID_RANGE' | 'RANGE_TOO_LONG' | 'OUT_OF_BOUNDS';

    interface DateRangeCheck {
        ok: boolean;
        code?: DateRangeErrorCode;
        field?: 'from' | 'to';
        a?: string | number | null;
        b?: string | number | null;
    }

    interface DateRange {
        value(): DateRangeValue;
        /** 프리셋 이름 또는 {from, to} */
        setValue(v: string | { from?: IsoDate | null; to?: IsoDate | null } | null): void;
        validate(): DateRangeCheck;
        /** 서버 요청용 {from, to} — 검사에 실패하면 null. 프리셋이면 이 순간 다시 계산 */
        toQuery(): { from: IsoDate | null; to: IsoDate | null } | null;
        on(name: 'hb:change', fn: (v: DateRangeValue) => void): void;
        setDisabled(b: boolean): void;
        showServerError(field: string, message: string): void;
        open(): void;
        close(returnFocus?: boolean): void;
    }

    // ------------------------------------------------------------------ hb.input
    interface SelectOptions {
        /** 공통코드 그룹(data-code) */
        code?: string | null;
        /** 첫 항목: all = 전체, select = 선택하세요(data-blank) */
        blank?: 'all' | 'select' | string | null;
    }

    interface SelectControl {
        ready: Promise<void>;
        reload(opts?: { code?: string }): Promise<void>;
        value(): string;
        setValue(v: unknown): void;
        reset(): void;
    }

    interface NumberOptions {
        scale?: number | null;
        min?: string | null;
        max?: string | null;
        allowNegative?: boolean;
        /** false = 자릿수는 상한으로만, 끝 0 을 채우지 않음 */
        pad?: boolean;
    }

    type NumberCheck = NumberParse | { ok: false; code: 'REQUIRED' };

    interface NumberControl {
        /** 서버로 보낼 자릿수 고정 문자열 | null (오류면 null — 먼저 validate) */
        value(): string | null;
        numberValue(): number | null;
        setValue(v: unknown): void;
        validate(): NumberCheck;
        reset(): void;
    }

    /** 조회 조건 값: 칸 이름 → 글자 / 배열(같은 이름 체크박스·multiple) / null(빈 기간) */
    type SearchValues = Record<string, any>;

    interface SearchPanelOptions {
        /** promise 를 돌려주면 끝날 때까지 다시 조회하지 않는다 */
        onSearch?: ((values: SearchValues) => unknown) | null;
        onReset?: (() => void) | null;
        /**
         * 이미 열린 화면에 링크 조건이 왔을 때(셸 → hb.applyLinkQuery, docs/37 22.2) — 조회 칸을 기본값으로 되돌리고 링크 값을 채운 **뒤**
         * 조건 전체(조회 칸 밖의 키 포함, 예 QMS SPC `view`·PMS `chgReq`)를 받는다. true 를 돌려주면 화면이 처리한 것이라 조회하지 않는다.
         * 처음 여는 화면(주소 ?q.)에서는 부르지 않는다 — 그때는 ready 뒤 values()·주소를 화면이 읽는다(지금과 같음)
         */
        onLink?: ((query: Record<string, string>) => boolean | void) | null;
    }

    interface SearchPanel {
        values(): SearchValues;
        setValue(name: string, value: unknown): void;
        setValues(obj: Record<string, unknown> | null | undefined): void;
        /**
         * 화면이 계산한 기본값을 넣으면서 기본값으로도 기록 — reset(링크 되돌림·[초기화])이 그 값으로 돌아간다.
         * 패널을 만들기 **전에** 칸에 넣은 값은 자동으로 기본값이 된다(이 함수는 만든 **뒤에** 정하는 기본값용).
         * 기간 컴포넌트: name = 시작·종료 칸 이름(data-from-name·data-to-name), value = {from, to}(둘 다) 또는 날짜 하나(그쪽만) —
         * reset 이 data-default 프리셋 대신 그 날짜로 돌아간다(프리셋이 아닌 기본 기간, 예: 오늘-7 ~ 오늘+30). 기본 기간이 프리셋이면 data-default 그대로
         */
        setDefault(name: string, value: unknown): void;
        /** 첫 오류 칸(없으면 null) — 오류 문구 표시 + 포커스 */
        validate(): HTMLElement | null;
        reset(): void;
        search(): Promise<unknown>;
        /** 링크 조건 적용 — 기본값으로 되돌림 → 채움 → onLink → (true 가 아니면) 조회. hb.applyLinkQuery 가 부른다 */
        link(query: Record<string, string>): Promise<unknown>;
        ready: Promise<unknown>;
    }

    // ------------------------------------------------------------------ hb.grid
    type ColumnType = 'TEXT' | 'INT' | 'DEC' | 'DATE' | 'DATETIME' | 'CODE' | 'YN' | 'LABEL';

    /** 서버 ColumnSpec.toView — 제목은 번역된 글자 */
    interface Column {
        field: string;
        title: string;
        type: ColumnType;
        scale?: number;
        minScale?: number;
        codeGrp?: string;
        /** LABEL 칸의 값 → 글자 표(서버가 보는 사람 언어로, docs/36 18.9 X2) */
        labels?: Record<string, string>;
        width: number;
        sortable?: boolean;
        exportable?: boolean;
        /** 1 = 숨기지 않음, 2·3 = "열 맞춤" 때 3 → 2 순서로 숨김 */
        priority?: number;
        minWidth?: number;
        flex?: boolean;
    }

    /** 그리드 안 열 상태(서버 열 + 화면 상태) */
    interface GridColumn extends Column {
        hidden: boolean;
        priority: number;
        minWidth: number;
        flex: boolean;
        /** "열 맞춤" 으로 숨김(저장 안 함) */
        fitHidden?: boolean;
        /** 사용자가 폭을 바꿈 — 채우기로 다시 늘리지 않음 */
        noFill?: boolean;
    }

    interface GridOptions {
        progCd: string;
        /** 한 화면에 그리드가 여럿이면 구분(설정 저장 키). 기본 main */
        id?: string;
        /** 행 id 필드 */
        key: string;
        url: string;
        columnsUrl: string;
        /** 조회 조건 */
        params?: () => Record<string, unknown>;
        /** "이 값으로 필터" 가능한 필드 → 조회 파라미터 이름 */
        filters?: Record<string, string>;
        exportUrl?: string;
        auditTable?: string;
        /** 페이지 모델의 권한 액션(메뉴 노출용, 판정은 서버) */
        actions?: string[];
        pageSize?: number;
        /**
         * TEXT 열 표시 함수 — (값, 행 전체). 행으로 여러 필드를 합친 글자를 만든다(한 인자 함수도 그대로).
         * 빈 값(null·undefined)은 부르지 않고 빈 글자. 결과는 textContent 로만, 엑셀은 서버 열 그대로
         */
        format?: Record<string, (v: any, row: Row) => unknown>;
        columnOverrides?: Record<string, Partial<Column>>;
        onSelect?: (row: Row) => void;
        /**
         * 다른 행·페이지로 옮기기 전 확인(미저장). false 면 머문다. Promise(hb.dialog.confirm)도 되고, 답을 기다리는 동안
         * 다른 이동은 무시·그 사이 다시 조회됐으면 옮기지 않는다. next = 옮겨 갈 행(페이지 넘김이면 null)
         */
        beforeSelect?: (next: Row | null) => SelectGateResult;
        /** 상태 칸: 필드 → (값 → 톤 표 | 함수). 칸 글자(코드 이름 등)로 hb-status 배지를 그린다 — 복사·엑셀은 글자 그대로 */
        status?: Record<string, GridStatusSpec>;
        /** 행 의미 클래스 — 'hb-row-'·'hb-tree-' 로 시작하는 이름만 붙는다(예: hb-tree-dim = 취소·미사용 흐리게, hb-row-warn = 주의) */
        rowClass?: (row: Row) => string | string[] | null | undefined;
        /**
         * 행 체크박스(여러 행 고르기, 예: LOT 병합) — 맨 앞 고정 칸. 고르기(선택 행·상세)와 따로이고,
         * 쪽을 넘겨도 key 로 남으며 새 조회(reload(true))면 비운다. Space = 고른 행 체크
         */
        checkable?: boolean;
        /**
         * 체크할 수 있는 행(checkable 일 때) — false 면 체크칸 비활성, 머리 칸 "모두"·Space 도 건너뛴다(없으면 모든 행).
         * 다시 읽은 뒤 못 고르는 행이 된 체크는 빠지고 onCheck 가 불린다. 화면 표시일 뿐, 막는 것은 서버
         */
        checkableRow?: (row: Row) => boolean;
        /**
         * 빈 칸에 보일 글자(필드 → 글자). 칸 글자를 먼저 만든 뒤(null → 빈 글자, INT·DEC·DATE·DATETIME → hb.fmt,
         * TEXT → format(값, 행) — null 이면 format 을 부르지 않음) 그 결과가 빈 글자일 때만 쓴다. 모든 칸 유형에 같다.
         * 옵션·필드가 없으면 지금처럼 빈 칸. 화면 표시만 — 칸 복사·행 복사·엑셀(서버)에는 들어가지 않는다
         */
        emptyText?: Record<string, string>;
        /** 체크가 바뀔 때마다(체크한 행 전체, 넣은 순서) */
        onCheck?: (rows: Row[]) => void;
        onFilter?: (param: string, value: unknown) => void;
        notify?: (text: string, error?: boolean) => void;
    }

    /** beforeSelect 답 — false 만 멈춤(없음 = 진행), Promise 면 기다린다 */
    type SelectGateResult = boolean | void | PromiseLike<boolean | void>;

    /** 상태 칸 톤: 값 → 톤 표, 또는 (값, 행) → 톤. 없거나 null 이면 배지 없이 글자만 */
    type GridStatusSpec = Record<string, StatusTone> | ((value: any, row: Row) => StatusTone | null | undefined);

    interface Grid {
        ready: Promise<void>;
        /** 체크한 행(checkable) — 넣은 순서, 다른 쪽 행 포함 */
        getChecked(): Row[];
        clearChecked(): void;
        reload(resetPage?: boolean, keepId?: unknown): Promise<void>;
        selected(): Row | null;
        selectById(id: unknown): void;
        exportExcel(): Promise<void>;
        destroy(): void;
    }

    interface GridFactory {
        (container: string | HTMLElement, opt: GridOptions): Grid;
        /** 열 정의(필드 순서) 지문 — 저장된 설정이 옛 정의인지 판단 */
        hashFields(cols: { field: string }[] | null): string;
    }

    // ------------------------------------------------------------------ hb.tree
    interface TreeOptions {
        columns?: Column[] | null;
        columnsUrl?: string | null;
        /** 들여쓰기·펼침 표시가 붙는 열 */
        treeField?: string | null;
        levelField?: string;
        /** 참이면 행 강조 + flagText 배지 */
        flagField?: string | null;
        flagText?: string | null;
        emptyText?: string | null;
        onSelect?: ((row: Row) => void) | null;
        /** 참이면 행을 흐리게 */
        dimField?: string | null;
        /** 다른 행으로 옮기기 전 확인 — GridOptions.beforeSelect 와 같은 규칙(hb.gate.js 필요). selectWhere 는 묻지 않는다 */
        beforeSelect?: ((next: Row) => SelectGateResult) | null;
        /** 우클릭 메뉴의 "엑셀로 내보내기" — 화면의 엑셀 버튼과 같은 동작(EXCEL 권한이 있을 때만 보인다). 없으면 항목 없음 */
        onExcel?: (() => unknown) | null;
    }

    // ------------------------------------------------------------------ hb.menu (hb.menu.js)
    interface MenuItem {
        label: string;
        run: () => unknown;
        disabled?: boolean;
    }

    interface Menu {
        /** '-' 는 구분선. 한 번에 하나만 열린다 */
        open(items: (MenuItem | '-')[], x: number, y: number): void;
        close(): void;
        copyText(text: string): Promise<void>;
    }

    interface TableMenuOptions {
        /** 메뉴 "엑셀" — onExcel 이 있으면 그것, 없으면 exportUrl 로 hb.download(params()) */
        exportUrl?: string | null;
        params?: (() => Record<string, unknown>) | null;
        onExcel?: (() => unknown) | null;
        /** 기본 = body[data-actions] */
        actions?: string[];
    }

    interface Tree {
        ready: Promise<Tree>;
        setRows(data: Row[] | null | undefined): void;
        expandAll(): void;
        collapseTo(level: number): void;
        visibleCount(): number;
        selectWhere(pred: (row: Row) => boolean): boolean;
        selected(): Row | null;
        columns(): Column[];
    }

    // ------------------------------------------------------------------ hb.page
    interface Page {
        /** 도움말·의견 보내기·상세 서랍·관련 화면 단추([data-prog-links], docs/79) */
        init(): void;
        /** 행 선택·신규 때 — 좁은 화면에서 상세 서랍을 연다 */
        openDetail(): void;
        closeDetail(): void;
    }

    // ------------------------------------------------------------------ hb.help (docs/46 화면 도움말)
    /** 화면 도움말 대화상자 내용 — 문구가 없으면 빈 배열. 문단은 문장(또는 줄)마다 하나 */
    interface HelpModel {
        /** help.{progCd} — 무엇을 하는 화면 */
        what: string[];
        /** help.{progCd}.helpWhy */
        why: string[];
        /** help.{progCd}.helpWhen */
        when: string[];
        /** help.{progCd}.helpDo 를 줄바꿈으로 나눈 주요 동작 */
        does: string[];
        /** help.{progCd}.helpNote(선택) — 주의 */
        note: string[];
        /** 앞·뒤 화면 코드(help-flow.json — 형식이 맞는 것만·자기 제외·최대 5) */
        prev: string[];
        next: string[];
    }

    interface Help {
        /** [도움말] 대화상자. focusKey = 직전 포커스 칸의 data-help-key, onFieldHelp = [칸 도움말 켜기](예전 패널) */
        open(o: { prog: string; title: string; focusKey?: string | null; onFieldHelp?: (() => void) | null }): Promise<HTMLDialogElement>;
    }

    // ------------------------------------------------------------------ 업무 흐름 지도 (home-flow-map, docs/46 12장)
    /** next = 다음 단계, skip = 두 단계 이상 앞, down = 같은 단계 위 → 아래, back = 앞 단계로·같은 단계 아래 → 위 */
    type FlowMapEdgeKind = 'next' | 'skip' | 'down' | 'back';

    interface FlowMapEdge {
        from: string;
        to: string;
        kind: FlowMapEdgeKind;
    }

    interface FlowMapModel {
        /** 단계(열)마다 화면 코드 — 위에서 아래 순서 */
        cols: string[][];
        pos: Record<string, { col: number; row: number }>;
        edges: FlowMapEdge[];
        /** 화면마다 나가는 화살표의 도착 화면(단계 순서) */
        next: Record<string, string[]>;
        /** 화면마다 들어오는 화살표의 출발 화면 */
        prev: Record<string, string[]>;
    }

    interface FlowMapPoint {
        x: number;
        y: number;
    }

    interface FlowMapRect extends FlowMapPoint {
        w: number;
        h: number;
    }

    /** 상자 폭·높이, 열 사이(gx), 같은 단계 쌓기 사이(gy), 줄 사이(gr), 가장자리(pad) */
    interface FlowMapGrid {
        w: number;
        h: number;
        gx: number;
        gy: number;
        gr: number;
        pad: number;
    }

    interface FlowMapLayout {
        points: Record<string, FlowMapPoint>;
        /** 내용 크기(배율 1) */
        w: number;
        h: number;
        /** 한 줄 단계 수 */
        perRow: number;
        /** 화면에 맞는 배율(최대 1) */
        scale: number;
    }

    /** window.hbFlowMap (views/home/flow-map.layout.js) — DOM 없는 순수 함수 */
    interface FlowMapLib {
        model(flow: unknown, links: unknown): FlowMapModel;
        /** 기본 자리 — 뱀(ㄹ자) 배치, 한 줄 단계 수는 avail 에 가장 크게 들어가는 값 */
        layout(m: FlowMapModel, g: FlowMapGrid, avail: { w: number; h: number }, maxScale?: number): FlowMapLayout;
        /** 내용이 avail 안에 들어가는 가장 큰 배율([min, max]) */
        fitScale(size: { w: number; h: number }, avail: { w: number; h: number }, min: number, max: number): number;
        /** 저장한 자리로 덮기(이 흐름 화면·범위 안 숫자만) */
        restore(base: Record<string, FlowMapPoint>, saved: unknown): Record<string, FlowMapPoint>;
        move(p: FlowMapPoint, dx: number, dy: number): FlowMapPoint;
        /** 보일 화살표 = 켠 종류 + 고른 상자의 화살표(hot) */
        shown(m: FlowMapModel, kinds: Partial<Record<FlowMapEdgeKind, boolean>>, selected: string | null): (FlowMapEdge & { hot: boolean })[];
        /** 두 상자 사이 직각 연결 SVG path d */
        route(a: FlowMapRect, b: FlowMapRect): string;
        firstSentence(s: string | null | undefined): string;
    }

    // ------------------------------------------------------------------ hb.dialog · hb.status (docs/40 단계 1)
    interface DialogOptions {
        /** 무엇을 하는지(동사) */
        title: string;
        /** 결과·되돌릴 수 있는지 */
        message?: string | null;
        /** 확정 버튼 글자(동사, 기본 "확인") */
        ok?: string;
        cancel?: string;
        /** 위험 확인 — 확정 버튼 위험 톤, 기본 포커스 = 취소 */
        danger?: boolean;
    }
    interface PromptOptions extends DialogOptions {
        label: string;
        value?: string | number | null;
        required?: boolean;
        maxLength?: number;
        /** 숫자 입력(hb.numbers.js 필요) — 돌려주는 값은 자릿수 고정 10진 문자열 */
        number?: NumberParseOptions | null;
    }
    /** 사유 코드 + 메모 대화상자(docs/68 Q1) */
    interface ReasonCodeOptions extends DialogOptions {
        /** 공통코드 그룹(선택 목록 = 사용 중, 회사 숨김 제외) */
        code: string;
        codeLabel: string;
        /** 메모 칸 이름 */
        label: string;
        memoRequired?: boolean;
        maxLength?: number;
    }
    interface Dialogs {
        confirm(o: DialogOptions): Promise<boolean>;
        /** 취소 = null, 선택 입력을 비우고 확정 = '' */
        prompt(o: PromptOptions): Promise<string | null>;
        /** 취소 = null, 확인 = {code(필수), memo(비우면 '')} */
        reasonCode(o: ReasonCodeOptions): Promise<{ code: string; memo: string } | null>;
        check(raw: string, opts: { required?: boolean; maxLength?: number; number?: NumberParseOptions | null },
            numbers: Numbers | null | undefined): { ok: true; value: string | null } | { ok: false; code: string };
        /** prompt 확정 결과 — check 와 같고 통과한 빈 선택 입력은 '' (null 은 취소만) */
        submitValue(raw: string, opts: { required?: boolean; maxLength?: number; number?: NumberParseOptions | null },
            numbers: Numbers | null | undefined): { ok: true; value: string } | { ok: false; code: string };
    }
    /** 상태 의미 사전의 톤 — 모양: neutral ○ · normal ● · info ● · success ✓ · warning ▲ · danger ■ · error ✕ · alarm-1 ◆ · alarm-2 ▲ · alarm-3 ■ · alarm-4 ● */
    type StatusTone = 'neutral' | 'normal' | 'info' | 'success' | 'warning' | 'danger' | 'error'
        | 'alarm-1' | 'alarm-2' | 'alarm-3' | 'alarm-4';

    // ------------------------------------------------------------------ hb.importDialog
    interface ImportJob {
        jobId: number | string;
        rowVer: number;
        fileName: string;
        mode: 'INSERT_ONLY' | 'UPSERT';
        total: number;
        newCount: number;
        changeCount: number;
        sameCount: number;
        errorCount: number;
        ignoredColumns?: string[];
        result?: {
            inserted?: number;
            updated?: number;
            same?: number;
            errorsExcluded?: number;
            before?: number;
            after?: number;
            matched?: boolean;
        } | null;
    }

    interface ImportOptions {
        templateCd: string;
        /** 실행이 끝나면(반영됨) */
        onDone?: (job: ImportJob) => void;
    }

    // ------------------------------------------------------------------ hb.attrs
    interface ClassProp {
        propCd: string;
        propNm: string;
        dataType: 'TEXT' | 'NUM' | 'DATE' | 'BOOL' | 'LIST';
        scale?: number | null;
        options?: string[] | null;
        unitNm?: string | null;
        requiredYn?: string;
        useYn: string;
    }

    interface Attrs {
        render(props: ClassProp[] | null | undefined, values?: Record<string, unknown> | null): void;
        /** 속성 코드 → 값(빈 칸 null) */
        values(): Record<string, string | boolean | null>;
        /** 숫자 칸 검사 */
        validate(): boolean;
    }

    // ------------------------------------------------------------------ hb.pwa (docs/45 M-1)
    interface Pwa {
        /** 이 앱이 만든 캐시 이름(규칙이 바뀌면 v 를 올린다) */
        CACHE: string;
        PREFIX: string;
        /** 서비스 워커가 다룰 요청인가 — 같은 출처 GET 의 정적 경로(/css/·/js/·/vendor/·/images/·manifest)만 */
        isCacheable(url: string, method: string, origin: string): boolean;
        /** 캐시에 넣어도 되는 응답인가 — 200·basic·리다이렉트 아님·HTML 아님 */
        isStorable(res: { status: number; type: string; redirected: boolean; headers: { get(name: string): string | null } }): boolean;
        /** 푸시 알림을 눌렀을 때 열 경로 — 같은 출처 "/…" 만, 아니면 /main */
        safeOpenPath(u: unknown): string;
        /** yeonos-* 캐시를 지운다 → 지운 수 */
        clearCaches(): Promise<number>;
        /** 서비스 워커 등록(지원·보안 출처일 때만) → 등록했는가 */
        register(): Promise<boolean>;
    }

    // ------------------------------------------------------------------ hb.scan (docs/50 7장 C1~C4)
    /** 스캔 한 번 — value 는 정리된 값(제어 문자 제거·GS 유지·200자), source 는 어디서 왔는지 */
    interface ScanResult {
        value: string;
        source: 'wedge' | 'native';
    }
    interface ScanOptions {
        /** 이 칸에서 Enter 로 끝난 값을 받는다(포커스 칸 + Enter). 없으면 문서 전체(입력 칸 밖 포커스일 때만) */
        input?: HTMLInputElement;
        /** 문서 전체 모드: 스캔으로 보는 글자 간격 상한(ms, 기본 50) */
        gapMs?: number;
        /** 문서 전체 모드: 스캔으로 보는 최소 길이(기본 3) */
        minLength?: number;
    }
    interface Scan {
        /** 한 화면에 듣는 곳은 하나 — 두 번째 listen 은 앞 것을 해제한다. 반환값 = 해제 함수 */
        listen(handler: (result: ScanResult) => void, options?: ScanOptions): () => void;
        /** 값 정리(C3 — 제어 문자 제거·앞뒤 공백·200자) — 스캔 칸이 막힌 곳(키오스크 대화상자, docs/50 KS10)이 직접 받은 값에. 쓸 수 없으면 '' */
        clean(value: unknown): string;
    }

    // ------------------------------------------------------------------ hb.kiosk (docs/50 K3~K6·Q7, MES 계약 docs/25 4.52)
    /** POST /api/pop/kiosk/exit 성공 응답 — 감독자, 다시 잠글 때까지(초). 서버는 잠금 상태를 갖지 않는다 */
    interface KioskExitReply {
        approverCd: string;
        approverNm: string;
        relockAfterSec: number;
        /**
         * 이 빠져나오기 기록을 가리키는 값(KS9 — MES 서버가 넣으면). 서버는 성공으로 적었는데 화면이 풀지 못하면
         * POST /api/pop/kiosk/unlock-failed {exitRef, reason: 'stale' | 'refused'} 로 한 번 알린다. 없으면 알리지 않는다
         */
        exitRef?: string;
    }
    /** attach() 반환 — 다리가 없으면 아무것도 하지 않는 같은 모양 */
    interface KioskHome {
        /** 감독자 대화상자가 열려 있다 — 화면 스캐너는 이때 받은 값을 badge(v) 로 넘긴다(스캔 듣는 곳 하나) */
        isOpen(): boolean;
        badge(value: string): void;
        /** 지금 작업자 알림 — 앞과 다르면 다시 잠근다(승인 없음) */
        worker(code: string | null | undefined): void;
        open(): void;
        close(): void;
    }
    interface Kiosk {
        /** APK 다리(YeonShell) 있음 */
        available(): boolean;
        /** 고정 켬 + 다시 잠금 타이머 정리 → 켰는가(다리 없음·OS 거절이면 false) */
        lock(): Promise<boolean>;
        /** 서버 확인 성공 뒤에만 고정을 풀고 relockAfterSec 뒤 다시 잠근다. 실패는 reject(고정 유지). 다리 없으면 null(서버 안 부름) */
        exit(badge: string, pin: string): Promise<KioskExitReply | null>;
        /** POP 홈 한 줄: lock() + 구석 5초 길게 누르기 → 감독자 대화상자(배지 → PIN) */
        attach(): KioskHome;
    }

    // ------------------------------------------------------------------ hb.text (QA R4-a — 서버 VisibleText 와 같은 정의)
    /** 보이지 않는 글자 = 공백·\p{Z}(NBSP·U+3000)·\p{Cf}(U+200B·U+FEFF·U+00AD …)·\p{Cc} + 한글 채움 문자·점자 빈칸 */
    interface Text {
        /** 보이는 글자가 하나도 없음(null·'' 포함) — 필수 사유 칸 확인에 `!s.trim()` 대신 */
        isBlank(s: unknown): boolean;
        /** 보이는 글자 수(코드 포인트) — "2자 이상" 규칙은 이것으로 */
        visibleLength(s: unknown): number;
        /** 앞뒤의 보이지 않는 글자를 걷어낸 값(가운데는 그대로), 보이는 글자가 없으면 '' */
        strip(s: unknown): string;
    }

    // ------------------------------------------------------------------ hb.tip (버튼 툴팁 기본, docs/36 20장)
    /** 툴팁 문구를 고를 때 보는 버튼 정보(DOM 에서 읽은 값) */
    interface TipInfo {
        /** data-tip-key — i18n 키 */
        tipKey?: string | null;
        /** data-tip — 이미 번역한 글 */
        tip?: string | null;
        /** body[data-prog] — js.tip.{prog}.{id} */
        prog?: string | null;
        id?: string | null;
        /** 버튼에 원래 있던 title(꺼낸 값) */
        title?: string | null;
        /** 관례 역할 — 'search'(.hb-btn-search)·'detailClose'([data-hb-detail-close]) → js.tip.common.{role} */
        role?: string | null;
        ariaLabel?: string | null;
        /** 보이는 글자(textContent) */
        text?: string | null;
        /** input 버튼의 value */
        value?: string | null;
    }
    interface TipText {
        /** 보일 문구('' = 띄우지 않음) */
        text: string;
        /** 버튼 이름(aria-label → 보이는 글자) */
        name: string;
        /** 문구 출처 */
        source: 'key' | 'tip' | 'prog' | 'title' | 'common' | 'name';
        /** 이름과 다른 설명 — 보이는 동안 aria-describedby 로 잇는다 */
        extra: boolean;
    }
    interface Tip {
        /** 문구 고르기(DOM 없음) — ① data-tip-key ② data-tip ③ js.tip.{prog}.{id} ④ title ⑤ js.tip.common.* ⑥ 이름 */
        resolve(info: TipInfo, t: (key: string) => string): TipText;
        /** 뷰포트 안 자리 — 아래 가운데, 모자라면 위 */
        place(r: { left: number; top: number; width: number; height: number }, tip: { width: number; height: number },
              view: { width: number; height: number }): { left: number; top: number; place: 'below' | 'above' };
        /** 위임 리스너 붙이기(스크립트를 실으면 저절로 — 두 번 불러도 한 번) */
        init(): void;
    }

    // ------------------------------------------------------------------ hb
    interface Hb {
        /** 버튼 툴팁 기본 — /js/common/hb.tip.js 를 싣는 화면(공통 page 조각·셸·포털·운영 콘솔) */
        tip?: Tip;
        /** 보이는 글자 판정 — /js/common/hb.text.js 를 넣은 화면에만 있다(공통 page 조각·POP·셸은 넣는다) */
        text?: Text;
        /** 화면 공통 스캔 입력 — /js/common/hb.scan.js 를 넣은 화면에만 있다 */
        scan?: Scan;
        /** 키오스크 다리 연결 — /js/common/hb.kiosk.js 를 넣은 화면(POP 작업장 홈)에만 있다 */
        kiosk?: Kiosk;
        /** PWA 셸 — /js/common/hb.pwa.js 를 넣은 화면(로그인·셸)에만 있다 (docs/45 M-1) */
        pwa?: Pwa;
        /** JSON API 호출. reject 값은 ApiError. 401 이면 로그인 화면으로 */
        api(url: string, options?: ApiOptions): Promise<ApiResponse>;
        /**
         * 같은 key 로 나중에 보낸 요청이 있으면 앞 결과는 ApiError(code STALE, stale true)로 거부한다 —
         * 행을 빨리 바꿔도 늦게 온 앞 행 상세가 그려지지 않는다(docs/24 2.1)
         */
        latest<T>(key: string, promise: T | PromiseLike<T>): Promise<T>;
        /** 화면 문구(js.* 키). 없으면 키 그대로. {0} {1} … 치환 */
        t(key: string, ...args: unknown[]): string;
        loadMessages(): Promise<Record<string, string>>;
        /** 버튼 연타 방지 — 처리 중이면 fn 을 부르지 않고 null 로 끝난다 */
        busy<T>(button: HTMLButtonElement, fn: () => T | PromiseLike<T>): Promise<T | null>;
        dirty: Dirty;
        cookie(name: string): string | null;
        /** POST 후 파일 저장 → 파일명 */
        download(url: string, body?: unknown, timeoutMs?: number): Promise<string>;
        /** extraHeaders — 더할 헤더(hb.pop.upload 가 X-HB-Client: POP). CSRF·Accept 는 늘 붙는다 */
        upload(url: string, formData: FormData, extraHeaders?: Record<string, string>): Promise<ApiResponse>;
        loadSession(): Promise<Session>;
        codes(grp: string): Promise<Record<string, string>>;
        /** 표시용 이름표 — 사용 중지된 코드도(docs/36 18.9 X4) */
        codeNames(grp: string): Promise<Record<string, string>>;
        codeList(grp: string): Promise<Code[]>;
        fmt: Fmt;
        /** 요소 만들기 — 글자는 textContent 로만 */
        el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string | null, text?: unknown): HTMLElementTagNameMap[K];
        el(tag: string, className?: string | null, text?: unknown): HTMLElement;
        /** errors 의 field → [data-field] 칸 옆에 문구 */
        showFieldErrors(root: ParentNode, errors: FieldError[] | null | undefined): void;
        session(): Session;

        dates: Dates;
        numbers: Numbers;
        calendar(host: HTMLElement, opts: CalendarOptions): Calendar;
        dateRange(host: HTMLElement, options?: DateRangeOptions): DateRange;
        select(el: HTMLSelectElement, options?: SelectOptions): SelectControl;
        number(el: HTMLInputElement, options?: NumberOptions): NumberControl;
        searchPanel(form: HTMLFormElement, options?: SearchPanelOptions): SearchPanel;
        /**
         * 링크 조건(조회 칸 이름 → 값)을 이 화면 조회 패널에 적용 — 셸이 이미 열린 탭에 쓴다(docs/37 14.2·22.2).
         * 조회 칸을 기본값으로 되돌린 뒤 채우고(링크에 없는 칸에 이전 값이 남지 않게) onLink → 조회(SearchPanel.link).
         * 처음 여는 화면은 주소 ?q.이름=값 을 패널이 ready 전에 채운다. 조건이 틀리거나 패널이 없으면 false
         */
        applyLinkQuery(params: Record<string, unknown> | null | undefined): boolean;
        grid: GridFactory;
        tree(target: string | HTMLElement, options?: TreeOptions): Tree;
        /** 우클릭 메뉴 공통(hb.menu.js) */
        menu: Menu;
        /** 일반 표에 우클릭 메뉴(칸·행·표 복사 + 엑셀) — 호스트에 위임 리스너 하나라 다시 그려도 그대로 */
        tableMenu(target: string | HTMLElement, options?: TableMenuOptions): void;
        page: Page;
        /** 화면 도움말 대화상자 — hb.help.js(fragments/page 공통 스크립트)를 싣는 화면만 */
        help?: Help;
        importDialog(opt: ImportOptions): { dialog: HTMLDialogElement };
        /** 화면 안 확인·입력 대화상자 — confirm/prompt 대체(docs/40 V-7). hb.dialog.js 를 싣는 화면만 */
        dialog: Dialogs;
        /** 상태 배지 hb-status(톤 × 모양 기호 × 글자, docs/40 부록 A) */
        status(tone: StatusTone, text: string, title?: string | null): HTMLSpanElement;
        /** 일반 표의 빈 목록 한 줄(hb.grid "자료 없음"과 같은 문구·톤, docs/40 10.2 N1) — 칸 수는 머리줄에서. text 없으면 js.grid.empty */
        emptyRow(tbody: HTMLTableSectionElement, text?: string | null): HTMLTableRowElement;
        /** beforeSelect 답으로 진행(hb.gate.js) — 동기면 진행했는지, Promise 면 Promise<진행했는지> */
        gate(result: SelectGateResult | null, proceed: () => void): boolean | Promise<boolean>;
        /** 전자서명 재인증(docs/36 16장) — 비밀번호(+인증 코드) → 5분·한 번 쓰는 토큰, 취소하면 null. hb.esign.js */
        esign(opt: { meaning: string; targetType: string; targetId: number; title?: string }): Promise<string | null>;
        attrs(box: HTMLElement, options?: { editable?: boolean }): Attrs;
        /** 차트(hb.chart.js, docs/61 11장) — 화면은 이것만 부른다(Highcharts 전역 직접 사용 금지, ChartLibraryGuardTest) */
        chart: ChartApi;
    }
}

declare namespace HB {
    // ------------------------------------------------------------------ 차트(hb.chart.js, docs/61 11장)
    type ChartKind = 'pareto' | 'bar' | 'gauge' | 'control';

    /** 네 종류 공통 */
    interface ChartCommon {
        /** 차트 위 제목(표 보기 caption 에도) */
        title?: string;
        /** 그림 높이 px — 폭은 칸에 맞춘다. 기본 300(게이지 220) */
        height?: number;
        /** 표·라벨 소수 자리, 기본 0 */
        decimals?: number;
        /** 값 단위(축 제목·게이지 라벨) */
        unit?: string;
        /** 화면 낭독기용 요약 — 없으면 종류별 기본 문장 */
        summary?: string;
        /** 빈 데이터 문구 — 없으면 js.chart.empty */
        emptyText?: string;
    }

    interface ChartParetoOptions extends ChartCommon {
        categories: string[];
        values: (number | null | undefined)[];
        /** 막대 계열 이름(범례·표), 기본 "값" */
        valueName?: string;
        /** 기준선 %, 기본 80 */
        threshold?: number;
        /** 상위 N개 뒤는 "기타" 한 막대로 */
        top?: number;
        /** 막대 클릭 → 드릴다운. index = 원래 categories 의 자리(기타는 -1) */
        onClick?: (category: string, index: number) => void;
    }

    interface ChartBarSeries {
        name: string;
        data: (number | null)[];
        /** 막대(기본) 또는 선 */
        kind?: 'bar' | 'line';
        /** 오른쪽 축(예: 달성률 %) */
        axis?: 'left' | 'right';
        /** muted = 계획·기준처럼 옅게 */
        tone?: 'muted';
    }

    interface ChartBarOptions extends ChartCommon {
        categories: string[];
        series: ChartBarSeries[];
        /** 왼쪽 축 목표선 */
        target?: number | null;
        stacked?: boolean;
        /** 가로 막대(항목 이름이 길 때·좁은 폭) */
        horizontal?: boolean;
        /** 오른쪽 축 단위 */
        rightUnit?: string;
        onClick?: (category: string, index: number) => void;
    }

    interface ChartGaugeOptions extends ChartCommon {
        value: number | null | undefined;
        min?: number;
        max?: number;
        target?: number | null;
        /** false 면 작을수록 좋음(불량률 등). 기본 true */
        higherIsBetter?: boolean;
        /** 목표에서 이 % 안쪽은 warn, 기본 10 */
        warnPct?: number;
        /** 계열 이름(낭독기) */
        name?: string;
    }

    interface ChartControlOptions extends ChartCommon {
        /** 점 이름(부분군 번호·LOT 등) — 길이가 점 개수 */
        labels: string[];
        values: (number | null | undefined)[];
        /** 하나 또는 점마다(한계가 바뀌면 계단선) */
        ucl: number | null | undefined | (number | null | undefined)[];
        cl: number | null | undefined | (number | null | undefined)[];
        lcl: number | null | undefined | (number | null | undefined)[];
        /** 규칙 위반 점(서버 판정). 없으면 한계 밖 점만 위반 */
        alarms?: boolean[];
        /** 위반 이벤트가 기록된 점(테두리 표시) */
        marked?: boolean[];
        /** 점 툴팁 글자 */
        tips?: string[];
        valueName?: string;
    }

    interface ChartParetoData {
        categories: string[];
        values: number[];
        /** 누적 %(소수 1자리, 마지막 100) */
        cumPct: number[];
        total: number;
        /** 누적이 threshold 에 처음 닿는 항목까지 개수 */
        cutCount: number;
        threshold: number;
    }

    interface ChartControlData {
        labels: string[];
        values: (number | null)[];
        ucl: (number | null)[];
        cl: (number | null)[];
        lcl: (number | null)[];
        alarms: boolean[];
        /** 한계 밖 점 수 */
        outCount: number;
        /** 위반 표시 점 수 */
        alarmCount: number;
    }

    /** 화면 문구(순수 함수에 넘기는 번역본) */
    interface ChartLabels {
        category: string; value: string; cumPct: string; target: string; status: string; no: string; alarm: string; others: string;
        statusText: Record<'ok' | 'warn' | 'danger' | 'none', string>;
        thresholdLine(v: number): string;
        targetLine(v: string): string;
        sumPareto(n: number, cut: number, threshold: number): string;
        sumBar(n: number, names: string): string;
        sumGauge(value: string, target: string, status: string): string;
        sumControl(n: number, alarms: number): string;
    }

    interface ChartHandle {
        /** 그림이 그려졌는지 — 라이브러리가 없거나 빈 데이터면 false(안내·표만) */
        readonly drawn: boolean;
        destroy(): void;
    }

    interface ChartApi {
        /** Highcharts 를 싣는다(처음 한 번). 없으면 false */
        ready(): Promise<boolean>;
        pareto(el: HTMLElement, options: ChartParetoOptions): Promise<ChartHandle>;
        bar(el: HTMLElement, options: ChartBarOptions): Promise<ChartHandle>;
        gauge(el: HTMLElement, options: ChartGaugeOptions): Promise<ChartHandle>;
        control(el: HTMLElement, options: ChartControlOptions): Promise<ChartHandle>;
        /** 파레토 계산만(화면 요약 문장 "상위 N개가 80%" 등) */
        paretoData(categories: string[], values: (number | null | undefined)[],
            options?: { threshold?: number; top?: number; othersLabel?: string }): ChartParetoData;
        gaugeStatus(value: number | null | undefined, target: number | null | undefined, higherIsBetter?: boolean, warnPct?: number):
            'ok' | 'warn' | 'danger' | 'none';
    }
}

declare namespace HB {
    // ------------------------------------------------------------------ 셸(shell.js, main.html)
    interface ShellTab {
        progCd: string;
        title: string;
        tabEl: HTMLElement;
        frameEl: HTMLIFrameElement;
    }

    /** 셸 창의 window.hbShell — 화면은 window.top 으로 닿는다 */
    interface Shell {
        /**
         * 화면 탭 열기(이미 있으면 그 탭으로). progCd 형식이 아니면 무시.
         * params = 조회 조건(링크) — 새 탭은 주소 ?q.이름=값, 열린 탭은 hb.applyLinkQuery(입력 중이면 먼저 묻는다)
         */
        open(progCd: string, title: string, params?: Record<string, string> | null): void;
        /** 열린 탭 사본 */
        tabs(): ShellTab[];
        /** 종 버튼의 안 읽은 수를 다시 센다(알림·공지를 읽은 화면이 부른다, docs/36 11.4) */
        refreshBell(): void;
    }
}

interface Window {
    hb: HB.Hb;
    /** 셸 창 표시 — 준비 전에는 true, 준비되면 HB.Shell (화면 단독 beforeunload 를 셸에서 끄는 데도 쓴다) */
    hbShell?: HB.Shell | true;
    /** 업무 흐름 지도 계산(views/home/flow-map.layout.js, docs/46 12장) */
    hbFlowMap?: HB.FlowMapLib;
    /** 셸 메뉴 묶음 접기 계산(shell.menufold.js, docs/15 4.4) */
    hbMenuFold?: {
        parse(raw: string | null | undefined, known: Set<string>): string[];
        serialize(ids: Iterable<string>): string;
        pathTo(nodes: { menuId?: number; progCd?: string; children?: any[] }[], progCd: string): string[];
        folderIds(nodes: { menuId?: number; progCd?: string; children?: any[] }[]): Set<string>;
        search(nodes: { menuId?: number; name?: string; progCd?: string; children?: any[] }[], query: string | null | undefined):
            { progCds: string[]; folders: Set<string> } | null;
        highlight(name: string, query: string): [number, number] | null;
    };
    /** 셸: 탭 중 입력 중인 화면이 있는지(화면 hb.js 가 세션 만료 때 묻는다) */
    hbAnyDirty?: () => boolean;
    /** 세션 만료 확인에서 "로그인으로" 를 골랐다 — 셸 이탈 경고를 다시 띄우지 않는다 */
    hbLeaving?: boolean;
}

declare var hb: HB.Hb;

// 컴포넌트 인스턴스를 요소에 붙여 두 번 만들지 않는다(hb.select·hb.number·hb.searchPanel·조회 패널 안 기간)
interface HTMLSelectElement {
    hbSelect?: HB.SelectControl;
}
interface HTMLInputElement {
    hbNumber?: HB.NumberControl;
}
interface HTMLElement {
    hbDateRange?: HB.DateRange;
}
interface HTMLFormElement {
    hbSearchPanel?: HB.SearchPanel;
}

// hb.dates·hb.numbers 는 node 단위 테스트(module.exports)에서도 쓴다
declare var module: { exports: unknown } | undefined;
