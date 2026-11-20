// 링크 조건(hb.applyLinkQuery → SearchPanel.link) — 실행: node --test src/test/js/*.test.js (docs/37 14.2·22.2 홈 감사 ③)
// 이미 열린 탭에 홈 카드 링크가 오면 조회 칸을 기본값으로 되돌린 뒤 링크 값을 채운다 — 링크에 없는 칸(검색어 등)에 이전 값이 남아
// 목록 건수가 카드 숫자보다 적게 보이던 것. 그리고 화면 훅 onLink 가 조건 전체(조회 칸 밖의 키 포함)를 받는다
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const numbers = require('../../main/resources/static/js/common/hb.numbers.js');

const SRC = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/common/hb.input.js'), 'utf8');

/** 브라우저 흉내 — instanceof 판정에 쓰는 클래스만 */
class HTMLInputElement {
    constructor(name, value, type) {
        this.name = name;
        this.type = type || 'text';
        this.value = value;
        this.defaultValue = value;   // HTML 의 value 속성 = 기본값(reset 이 되돌리는 값)
        this.checked = false;
        this.defaultChecked = false;
        this.disabled = false;
        this.required = false;
        this.tagName = 'INPUT';
        this.attrs = {};
    }
    getAttribute(n) { return n in this.attrs ? this.attrs[n] : null; }
    setAttribute(n, v) { this.attrs[n] = String(v); }
    removeAttribute(n) { delete this.attrs[n]; }
}
class HTMLSelectElement {}
class HTMLTextAreaElement {}
class RadioNodeList {}

/** 조회 패널 폼 — 일반 칸만(컴포넌트 없음) */
function fakeForm(controls, ranges) {
    const elements = controls.slice();
    elements.namedItem = (n) => controls.find((c) => c.name === n) || null;
    return {
        elements,
        classList: {add() {}},
        setAttribute() {},
        getAttribute: () => null,
        querySelectorAll: (sel) => (sel === '[data-hb]' ? (ranges || []) : []),
        querySelector: () => null,
        addEventListener() {}
    };
}

function load(form, search) {
    const doc = {activeElement: null, hidden: false, body: {}, getElementById: () => null,
        querySelector: (sel) => (sel === 'form[data-hb="searchPanel"]' ? form : null)};
    const win = {hb: {numbers, t: (k) => k, el: () => ({setAttribute() {}, hidden: false, textContent: ''})},
        location: {search: search || ''}};
    vm.runInNewContext(SRC, {window: win, document: doc, URLSearchParams,
        HTMLInputElement, HTMLSelectElement, HTMLTextAreaElement, RadioNodeList, Promise});
    return win.hb;
}

/** 조회 패널 + 부른 기록 */
async function panel(options) {
    const keyword = new HTMLInputElement('keyword', '');
    const status = new HTMLInputElement('status', 'FAIL');   // 기본값이 있는 칸
    const chgReq = new HTMLInputElement('chgReq', '', 'hidden');
    const form = fakeForm([keyword, status, chgReq]);
    const hb = load(form);
    const calls = {search: [], link: []};
    const sp = hb.searchPanel(form, Object.assign({
        onSearch: (v) => { calls.search.push(Object.assign({}, v)); }
    }, options ? options(calls) : {}));
    await sp.ready;
    return {hb, sp, keyword, status, chgReq, calls};
}

const tick = () => new Promise((r) => setTimeout(r, 0));

test('열린 탭에 링크가 오면 링크에 없는 칸은 기본값으로 — 이전 검색어가 남지 않는다', async () => {
    const p = await panel();
    // 사람이 검색어를 치고 상태를 바꿔 둔 화면
    p.keyword.value = 'OLD-LOT';
    p.status.value = 'SENT';
    // 홈 카드 링크 = 상태만
    assert.equal(p.hb.applyLinkQuery({status: 'FAIL'}), true);
    await tick(); await tick();
    assert.equal(p.calls.search.length, 1);
    assert.equal(p.calls.search[0].keyword, '', '링크에 없는 검색어는 기본값(빈 칸)으로');
    assert.equal(p.calls.search[0].status, 'FAIL');
});

test('링크에 있는 칸은 링크 값, 기본값이 있는 칸도 링크 값이 이긴다', async () => {
    const p = await panel();
    p.keyword.value = 'OLD';
    p.hb.applyLinkQuery({keyword: 'LOT-1', status: 'CLOSED_MANUAL'});
    await tick(); await tick();
    assert.deepEqual([p.calls.search[0].keyword, p.calls.search[0].status], ['LOT-1', 'CLOSED_MANUAL']);
});

test('onLink 는 조건 전체(조회 칸 밖의 키 포함)를 받고, true 면 조회하지 않는다', async () => {
    const p = await panel((calls) => ({
        onLink: (q) => { calls.link.push(Object.assign({}, q)); return q.chgReq ? true : undefined; }
    }));
    p.hb.applyLinkQuery({view: 'events', status: 'FAIL'});   // view = 조회 칸에 없는 키(QMS SPC)
    await tick(); await tick();
    assert.deepEqual(p.calls.link[0], {view: 'events', status: 'FAIL'});
    assert.equal(p.calls.search.length, 1, 'undefined 면 그대로 조회');

    p.hb.applyLinkQuery({chgReq: '42'});                       // 화면이 직접 연다(PMS 변경 요청)
    await tick(); await tick();
    assert.deepEqual(p.calls.link[1], {chgReq: '42'});
    assert.equal(p.calls.search.length, 1, 'true 면 조회 안 함');
    assert.equal(p.chgReq.value, '42', '훅이 부르기 전에 링크 값은 이미 칸에 채워져 있다');
});

test('틀린 링크(이름 형식·개수)는 통째로 버리고 화면은 그대로', async () => {
    const p = await panel();
    p.keyword.value = 'KEEP';
    assert.equal(p.hb.applyLinkQuery({'bad-name': 'x'}), false);
    assert.equal(p.hb.applyLinkQuery({a: '1', b: '2', c: '3', d: '4', e: '5', f: '6', g: '7'}), false);
    assert.equal(p.hb.applyLinkQuery({keyword: 'x'.repeat(201)}), false);
    await tick();
    assert.equal(p.keyword.value, 'KEEP', '버린 링크는 기본값으로 되돌리지도 않는다');
    assert.equal(p.calls.search.length, 0);
});

// 화면 JS 가 계산한 기본값(기본 기간 등) — reset 이 HTML 빈 값이 아니라 처음 연 탭과 같은 값으로 돌아가는지(상위 확인 요청 2026-11-02)
test('패널을 만들기 전에 JS 가 넣은 값(기본 기간)은 기본값 — 링크 되돌림·초기화 뒤에도 그 값', async () => {
    const from = new HTMLInputElement('from', '');      // HTML 기본값은 빈 칸
    from.value = '2026-10-27';                           // 화면 JS: 오늘-6 (CMS 알람 이력·상태 검토·KPI·위험 화면)
    const keyword = new HTMLInputElement('keyword', '');
    const form = fakeForm([from, keyword]);
    const hb = load(form);
    const searches = [];
    const sp = hb.searchPanel(form, {onSearch: (v) => { searches.push(Object.assign({}, v)); }});
    await sp.ready;
    from.value = '2026-01-01';                           // 사람이 바꿈
    keyword.value = 'OLD';
    hb.applyLinkQuery({keyword: 'LOT-9'});
    await tick(); await tick();
    assert.deepEqual([searches[0].from, searches[0].keyword], ['2026-10-27', 'LOT-9']);
    from.value = '2026-02-02';
    sp.reset();
    assert.equal(from.value, '2026-10-27', '[초기화]도 같은 기본값');
});

test('setDefault: 패널을 만든 뒤 정한 기본값(이번 달 등)도 되돌림의 대상', async () => {
    const month = new HTMLInputElement('toMonth', '');
    const form = fakeForm([month]);
    const hb = load(form);
    const searches = [];
    const sp = hb.searchPanel(form, {onSearch: (v) => { searches.push(Object.assign({}, v)); }});
    await sp.ready;
    sp.setDefault('toMonth', '2026-11');                 // COPQ 처럼 ready 뒤에 계산
    assert.equal(month.value, '2026-11');
    month.value = '2025-01';
    hb.applyLinkQuery({other: 'x'});
    await tick(); await tick();
    assert.equal(searches[0].toMonth, '2026-11');
});

test('첫 로드 주소 링크(?q.) 값은 기본값에 섞이지 않는다 — 다음 링크에서 그 칸은 원래 기본값', async () => {
    const keyword = new HTMLInputElement('keyword', '');
    const status = new HTMLInputElement('status', 'FAIL');
    const form = fakeForm([keyword, status]);
    const hb = load(form, '?q.keyword=FIRST-LINK');
    const searches = [];
    const sp = hb.searchPanel(form, {onSearch: (v) => { searches.push(Object.assign({}, v)); }});
    await sp.ready;
    assert.equal(keyword.value, 'FIRST-LINK', '첫 링크는 채워진다');
    hb.applyLinkQuery({status: 'SENT'});
    await tick(); await tick();
    assert.deepEqual([searches[0].keyword, searches[0].status], ['', 'SENT']);
});

/** 기간 컴포넌트 흉내 — data-default 프리셋이면 {preset}, 날짜면 {from, to}. toQuery 는 프리셋을 고정 날짜로(오늘 = 2026-11-02) */
function fakeRange(preset) {
    const attrs = {'data-hb': 'dateRange', 'data-from-name': 'from', 'data-to-name': 'to'};
    if (preset) { attrs['data-default'] = preset; }
    let v = preset ? {from: null, to: null, preset} : {from: null, to: null, preset: null};
    const api = {
        value: () => Object.assign({days: null}, v),
        setValue: (x) => { v = typeof x === 'string' ? {from: null, to: null, preset: x} : {from: x.from || null, to: x.to || null, preset: null}; },
        toQuery: () => (v.preset === 'last7' ? {from: '2026-10-27', to: '2026-11-02'} : {from: v.from, to: v.to}),
        validate: () => ({ok: true})
    };
    return {
        getAttribute: (n) => (n in attrs ? attrs[n] : null),
        setAttribute: (n, x) => { attrs[n] = String(x); },
        contains: () => false,
        hbDateRange: api
    };
}

// 프리셋이 아닌 기본 기간(MES #780 제안 — 작업지시 오늘-7 ~ 오늘+30)
test('setDefault(기간 이름, {from,to}): 링크 되돌림·[초기화]가 프리셋 대신 그 날짜로', async () => {
    const range = fakeRange('last7');
    const keyword = new HTMLInputElement('keyword', '');
    const form = fakeForm([keyword], [range]);
    const hb = load(form);
    const searches = [];
    const sp = hb.searchPanel(form, {onSearch: (v) => { searches.push(Object.assign({}, v)); }});
    sp.setDefault('from', {from: '2026-10-26', to: '2026-12-02'});   // ready 전(첫 링크가 이기게)
    await sp.ready;
    range.hbDateRange.setValue({from: '2026-01-01', to: '2026-01-31'});   // 사람이 바꿈
    hb.applyLinkQuery({keyword: 'WO-1'});
    await tick(); await tick();
    assert.deepEqual([searches[0].from, searches[0].to, searches[0].keyword], ['2026-10-26', '2026-12-02', 'WO-1']);
    range.hbDateRange.setValue('last7');
    sp.reset();
    assert.deepEqual(sp.values().from + '~' + sp.values().to, '2026-10-26~2026-12-02', '[초기화]도 같은 날짜(프리셋 last7 아님)');
});

test('setDefault 기간 한쪽만: 다른 쪽은 앞서 정한 기본값 또는 지금 값을 지킨다', async () => {
    const range = fakeRange(null);
    const form = fakeForm([], [range]);
    const hb = load(form);
    const sp = hb.searchPanel(form, {onSearch: () => {}});
    await sp.ready;
    sp.setDefault('to', '2026-12-02');
    sp.setDefault('from', '2026-10-26');
    range.hbDateRange.setValue({from: null, to: null});
    sp.reset();
    assert.deepEqual([sp.values().from, sp.values().to], ['2026-10-26', '2026-12-02']);
});

test('setDefault 를 부르지 않은 기간은 지금처럼 data-default 프리셋으로 되돌린다', async () => {
    const range = fakeRange('last7');
    const form = fakeForm([], [range]);
    const hb = load(form);
    const sp = hb.searchPanel(form, {onSearch: () => {}});
    await sp.ready;
    range.hbDateRange.setValue({from: '2026-01-01', to: '2026-01-02'});
    sp.reset();
    assert.equal(range.hbDateRange.value().preset, 'last7');
    assert.deepEqual([sp.values().from, sp.values().to], ['2026-10-27', '2026-11-02']);
});

