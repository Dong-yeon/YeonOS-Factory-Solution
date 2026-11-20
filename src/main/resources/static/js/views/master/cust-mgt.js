// @ts-check
/*
 * 거래처관리 (mst-cust) — P0 8단계 표준 참조 화면. 새 기준정보 화면은 이 파일을 본보기로 한다.
 *   마스터 = hb.grid(조회) + 오른쪽 상세 폼 / 디테일 = 담당자 폼 목록(일괄 저장 규약) / 첨부 / 엑셀 / 변경 이력
 * 회사·작성자·일시는 서버가 채운다(보내지 않는다). 서버 데이터는 textContent·value 로만 넣는다.
 * 타입 검사(// @ts-check) 본보기: 템플릿에 항상 있는 요소는 JSDoc 형 변환, th:if 로 빠질 수 있는 요소는 null 확인.
 */
/**
 * @typedef {{custId: number, custCd: string, custNm: string, rowVer: number, [field: string]: any}} Cust
 * @typedef {{cust: Cust, contacts: Contact[]}} CustDetail
 * @typedef {'contactNm' | 'deptNm' | 'tel' | 'email'} ContactField
 * @typedef {{rowKey: string, contactId?: number, rowVer?: number, contactNm?: string, deptNm?: string, tel?: string,
 *   email?: string, mainYn?: string, state: 'new' | 'old' | 'deleted', dirty: boolean}} Contact
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var API = '/api/master/customers';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('custForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var contactBox = /** @type {HTMLElement} */ (document.getElementById('contacts'));
    /** 선택된 거래처 @type {CustDetail | null} */
    var current = null;
    /** @type {Contact[]} */
    var contacts = [];
    var keySeq = 0;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;

    function params() {
        return sp.values();
    }

    // ---------- 상세 폼 ----------
    /** 상세 폼 칸 @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    /** @param {Cust | null} cust */
    function setForm(cust) {
        hb.showFieldErrors(form, []);
        ['custCd', 'custNm', 'custType', 'bizNo', 'ceoNm', 'tel', 'email', 'addr', 'remark', 'useYn'].forEach(function (n) {
            var v = cust ? cust[n] : '';
            field(n).value = n === 'bizNo' ? hb.fmt.bizNo(v) : (v === null || v === undefined ? '' : v);
        });
        if (!cust) {
            field('useYn').value = 'Y';
            /** @type {HTMLSelectElement} */ (field('custType')).selectedIndex = 0;
        }
        hb.dirty.clear();
    }

    function formBody() {
        /** @type {Record<string, unknown>} */
        var b = {};
        ['custCd', 'custNm', 'custType', 'bizNo', 'ceoNm', 'tel', 'email', 'addr', 'remark', 'useYn'].forEach(function (n) {
            b[n] = field(n).value;
        });
        if (current) { b.rowVer = current.cust.rowVer; }
        return b;
    }

    /** @param {CustDetail | null} detail */
    function showDetail(detail) {
        current = detail;
        setForm(detail ? detail.cust : null);
        formMode.textContent = detail ? hb.t('js.mstCust.editing', detail.cust.custCd) : hb.t('js.mstCust.creating');
        /** @type {HTMLElement} */ (document.getElementById('contactSection')).hidden = !detail;
        /** @type {HTMLElement} */ (document.getElementById('fileSection')).hidden = !detail;
        contacts = detail ? detail.contacts.map(function (c) {
            return Object.assign({rowKey: 'k' + (++keySeq), state: 'old', dirty: false}, c);
        }) : [];
        renderContacts();
        if (detail) { loadFiles(); }
    }

    /** @param {number} custId @param {boolean} [keepMsg] */
    function loadDetail(custId, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + custId)).then(function (res) { showDetail(res.data); },
            function (err) { notify(err.message, true); });
    }

    function hasUnsaved() {
        return hb.dirty.isDirty() || contacts.some(function (c) { return c.dirty || c.state !== 'old'; });
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hasUnsaved()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** 목록 행·페이지 이동 전 확인 — 화면 안 대화상자(docs/40 V-7 예시). 조회·신규 버튼은 아직 confirmLeave */
    function confirmMove() {
        if (!hasUnsaved()) { return true; }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'),
            ok: hb.t('js.common.leaveOk')});
    }

    // ---------- 담당자 (일괄 저장 규약) ----------
    function renderContacts() {
        contactBox.textContent = '';
        var canSave = ACTIONS.indexOf('SAVE') >= 0;
        var visible = contacts.filter(function (c) { return c.state !== 'deleted'; });
        if (!visible.length) {
            contactBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstCust.noContacts')));
        }
        visible.forEach(function (c) {
            var row = hb.el('div', 'hb-row');
            row.setAttribute('data-row-key', c.rowKey);
            /** @type {[ContactField, string, number][]} */ ([['contactNm', hb.t('js.mstCust.col.contactNm'), 100], ['deptNm', hb.t('js.mstCust.col.deptNm'), 100],
                ['tel', hb.t('js.mstCust.col.tel'), 30], ['email', hb.t('js.mstCust.col.email'), 200]]).forEach(function (f) {
                var input = hb.el('input');
                input.maxLength = f[2];
                input.value = c[f[0]] || '';
                input.placeholder = f[1];
                input.setAttribute('aria-label', f[1]);
                input.setAttribute('data-field', f[0]);
                input.readOnly = !canSave;
                input.addEventListener('input', function () { c[f[0]] = input.value; c.dirty = true; hb.dirty.mark(); });
                row.appendChild(input);
            });
            var mainLabel = hb.el('label', 'hb-check');
            var main = hb.el('input');
            main.type = 'radio';
            main.name = 'mainContact';
            main.checked = c.mainYn === 'Y';
            main.disabled = !canSave;
            main.setAttribute('data-field', 'mainYn');
            main.addEventListener('change', function () {
                contacts.forEach(function (o) {
                    var was = o.mainYn;
                    o.mainYn = o === c ? 'Y' : 'N';
                    if (was !== o.mainYn) { o.dirty = true; }
                });
            });
            mainLabel.appendChild(main);
            mainLabel.appendChild(hb.el('span', null, hb.t('js.mstCust.col.mainYn')));
            row.appendChild(mainLabel);
            if (canSave) {
                var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    if (c.state === 'new') {
                        contacts.splice(contacts.indexOf(c), 1);
                    } else {
                        c.state = 'deleted';
                    }
                    renderContacts();
                });
                row.appendChild(del);
            }
            contactBox.appendChild(row);
        });
    }

    function addContact() {
        contacts.push({rowKey: 'n' + (++keySeq), state: 'new', dirty: true, mainYn: 'N', contactNm: ''});
        renderContacts();
        var last = /** @type {HTMLInputElement | null} */ (contactBox.querySelector('.hb-row:last-child input'));
        if (last) { last.focus(); }
    }

    /** 서버로 보낼 담당자(화면 상태 state·dirty 제외) @param {Contact} c */
    function contactItem(c) {
        return {rowKey: c.rowKey, contactId: c.contactId, rowVer: c.rowVer, contactNm: c.contactNm, deptNm: c.deptNm,
            tel: c.tel, email: c.email, mainYn: c.mainYn};
    }

    /** 행 키로 담당자 칸 오류 표시 @param {HB.FieldError[] | undefined} errors */
    function markRowErrors(errors) {
        contactBox.querySelectorAll('.hb-field-error').forEach(function (e) { e.remove(); });
        contactBox.querySelectorAll('[aria-invalid]').forEach(function (e) { e.removeAttribute('aria-invalid'); });
        (errors || []).forEach(function (er) {
            var row = contactBox.querySelector('[data-row-key="' + er.rowKey + '"]');
            if (!row) { return; }
            var input = er.field ? row.querySelector('[data-field="' + er.field + '"]') : null;
            (input || row).setAttribute('aria-invalid', 'true');
            row.appendChild(hb.el('span', 'hb-field-error', er.message));
        });
    }

    /** 목록 다시 읽기 — 그리드가 아직 준비 전이면(첫 조회 전 저장) 건너뛴다 @param {unknown} [keepId] */
    function reloadGrid(keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }

    /** @param {HTMLButtonElement} btn */
    function saveContacts(btn) {
        if (!current) { return; }
        var cust = current.cust;   // 저장 중에 다른 거래처를 골라도 이 거래처로 마무리
        var batch = {
            created: contacts.filter(function (c) { return c.state === 'new'; }).map(contactItem),
            updated: contacts.filter(function (c) { return c.state === 'old' && c.dirty; }).map(contactItem),
            deleted: contacts.filter(function (c) { return c.state === 'deleted'; }).map(contactItem)
        };
        if (!batch.created.length && !batch.updated.length && !batch.deleted.length) {
            notify(hb.t('js.common.noChanges'));
            return;
        }
        hb.busy(btn, function () {
            return hb.api(API + '/' + cust.custId + '/contacts/batch?rowVer=' + cust.rowVer,
                    {method: 'POST', body: batch});
        }).then(function (res) {
            if (!res) { return; }
            notify(hb.t('js.common.saved'));
            var id = cust.custId;
            reloadGrid(id);
            loadDetail(id, true);
        }, function (err) {
            notify(err.message, true);
            markRowErrors(err.errors);
        });
    }

    // ---------- 첨부 ----------
    /** 첨부 목록 — 거래처를 빨리 바꾸면 늦게 온 이전 거래처 목록은 hb.latest 가 버린다 */
    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!current) { return; }
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + current.cust.custId)).then(function (res) {
            (res.list || []).forEach(function (f) {
                var li = hb.el('li');
                var a = hb.el('a', null, f.name);
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (ACTIONS.indexOf('SAVE') >= 0) {
                    var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        hb.dialog.confirm({title: hb.t('js.mstCust.dlg.fileDelete'), message: hb.t('js.mstCust.confirmFileDelete', f.name),
                            ok: hb.t('js.mstCust.dlg.fileDeleteOk'), danger: true}).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                .then(loadFiles, function (err) { notify(err.message, true); });
                        });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
        }, function (err) { notify(err.message, true); });
    }

    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));   // SAVE 권한 없으면 없음
    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            if (!file || !current) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(current.cust.custId));
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                loadFiles();
            }, function (err) {
                upInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    // ---------- 버튼 ----------
    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function newCust() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            showDetail(null);
            hb.page.openDetail();
            field('custCd').focus();
        });
    }
    bind('newBtn', newCust);
    bind('newBtnList', newCust);
    bind('importBtn', function () {
        // 반영되면 목록을 다시 읽는다(검증만 하고 닫으면 그대로)
        hb.importDialog({templateCd: 'MST_CUST', onDone: function () { if (grid) { grid.reload(true); } }});
    });

    bind('saveBtn', function (btn) {
        var body = formBody();
        var editId = current ? current.cust.custId : null;   // 저장 중에 다른 행을 골라도 이 거래처로 마무리
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            var id = editId !== null ? editId : res.data;
            hb.dirty.clear();
            // 그리드가 같은 행을 다시 고르면 onSelect 가 상세를 다시 읽는다(그때 문구를 지움) → 다 읽은 뒤 알린다
            return reloadGrid(id).then(function () {
                if (!grid || !grid.selected()) { return loadDetail(id, true); }   // 조회 조건 밖이면 상세만 다시
            }).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    bind('disableBtn', function (btn) {
        if (!current) { return; }
        var cust = current.cust;
        hb.dialog.confirm({title: hb.t('js.mstCust.dlg.disable', cust.custNm), message: hb.t('js.mstCust.confirmDisable', cust.custNm),
            ok: hb.t('js.mstCust.dlg.disableOk'), danger: true}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/delete', {method: 'POST',
                    body: {items: [{id: cust.custId, rowVer: cust.rowVer}]}});
            }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.mstCust.disabled'));
                showDetail(null);
                reloadGrid();
            }, function (err) { notify(err.message, true); });
        });
    });

    bind('addContactBtn', addContact);
    bind('saveContactsBtn', saveContacts);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready, hb.select(/** @type {HTMLSelectElement} */ (field('custType'))).ready]);
    }).then(function () {
        grid = hb.grid('#custGrid', {
            progCd: PROG, id: 'main', key: 'custId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_cust',
            actions: ACTIONS, params: params,
            filters: {custType: 'custType', useYn: 'useYn'},
            format: {bizNo: hb.fmt.bizNo},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmMove,
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { loadDetail(row.custId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
