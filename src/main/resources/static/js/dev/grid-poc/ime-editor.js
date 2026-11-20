/*
 * 한글 IME 보정 셀 editor (docs/10 4장 "Tabulator 를 쓸 때의 규칙", 8장 2일차).
 * Tabulator 의 기본 input editor 는 Enter 처리에서 keyCode 229(조합 중)를 보지 않는다 → 조합 중 Enter·Tab·화살표가
 * 그리드 키 바인딩으로 새어 나가 마지막 음절이 빠지거나 Enter 가 두 번 처리될 수 있다.
 *
 * 규칙
 *  - 조합 중(compositionstart~end, e.isComposing, keyCode 229) 키는 전부 IME 에 맡기고 그리드로 전파하지 않는다(stopPropagation)
 *  - 확정은 조합이 끝난 뒤의 Enter·Tab·Shift+Tab·↑↓, 다른 곳 mousedown(캡처 단계), blur — 어느 쪽이든 **한 번만**(done 플래그)
 *    · Tab·↑↓ 는 그리드로 보내지 않고 여기서 확정 후 직접 이동한다: Tabulator 6.6.1 + selectableRange 의 이동은 열린 editor 를
 *      cancelEdit() 으로 지워 입력값을 버린다(기본 input editor 도 같음 — PoC 에서 확인)
 *    · Windows·macOS Chrome 은 조합 중 Enter 에 keydown(229, isComposing=true) → compositionend → keydown(Enter, 13) 이
 *      연달아 올 수 있다 → 앞의 것은 무시, 뒤의 것으로 한 번 확정
 *    · Safari 는 compositionend 뒤 keydown(229, isComposing=false) → 229 이므로 무시(Enter 를 한 번 더 눌러 확정)
 *  - Esc 는 취소. 값은 문자열 원문으로 넘기고, 타입 변환·오류 표시는 grid-poc.js 의 cellEdited 가 파서(paste-parser)로 한 번에
 *
 * hbImeEditor.create({inputMode, label}) → Tabulator editor 함수
 */
(function (global) {
    'use strict';

    var NAV = {Tab: 'next', ArrowUp: 'up', ArrowDown: 'down'};

    function editable(c) {
        return !!(c && c.getColumn().isVisible() && c.getColumn().getDefinition().editor);
    }

    /**
     * 확정 뒤 이동해서 그 칸 editor 를 연다. Tab = 같은 행 다음 편집 칸(끝이면 다음 행 첫 편집 칸), Shift+Tab 반대, ↑↓ 같은 열.
     * Tabulator 의 cell.navigateNext() 는 열린 editor 가 없으면 다음 "행"으로 가 버려서 직접 찾는다.
     */
    function moveFrom(cell, nav) {
        var row = cell.getRow();
        var target = null;
        if (nav === 'up' || nav === 'down') {
            var r = nav === 'up' ? row.getPrevRow() : row.getNextRow();
            target = r ? r.getCell(cell.getField()) : null;
        } else {
            var step = nav === 'next' ? 1 : -1;
            var cells = row.getCells();
            var i = cells.indexOf(cells.filter(function (c) { return c.getField() === cell.getField(); })[0]) + step;
            while (!target && row) {
                for (; i >= 0 && i < cells.length; i += step) {
                    if (editable(cells[i])) {
                        target = cells[i];
                        break;
                    }
                }
                if (!target) {
                    row = step > 0 ? row.getNextRow() : row.getPrevRow();
                    cells = row ? row.getCells() : [];
                    i = step > 0 ? 0 : cells.length - 1;
                }
            }
        }
        if (target) {
            setTimeout(function () { target.edit(); }, 0);
        }
    }

    function create(options) {
        options = options || {};
        return function (cell, onRendered, success, cancel) {
            var field = cell.getField();
            var input = document.createElement('input');
            input.type = 'text';
            input.className = 'poc-cell-editor';
            input.setAttribute('autocomplete', 'off');
            if (options.inputMode) {
                input.inputMode = options.inputMode;
            }
            var v = cell.getValue();
            input.value = v === null || v === undefined ? '' : String(v);

            var composing = false;
            var done = false;
            var log = function () {
                var args = ['ime:' + field].concat(Array.prototype.slice.call(arguments));
                global.hbPoc.log.apply(null, args);
            };

            function commit(reason) {
                if (done) {
                    log('commit 무시(이미 확정)', reason);
                    return;
                }
                done = true;
                log('commit', reason, input.value);
                if (success(input.value) === false) {
                    done = false; // 검증 실패로 editor 가 남아 있으면 다시 확정할 수 있게
                }
            }

            input.addEventListener('compositionstart', function (e) {
                composing = true;
                log(global.hbPoc.describeKey(e));
            });
            input.addEventListener('compositionupdate', function (e) {
                log(global.hbPoc.describeKey(e));
            });
            input.addEventListener('compositionend', function (e) {
                composing = false;
                log(global.hbPoc.describeKey(e), 'value=' + JSON.stringify(input.value));
            });
            input.addEventListener('keydown', function (e) {
                var ime = composing || e.isComposing || e.keyCode === 229;
                log(global.hbPoc.describeKey(e), ime ? '→ IME 처리 중, 그리드로 보내지 않음' : '');
                if (ime) {
                    e.stopPropagation();
                    return;
                }
                if (e.key === 'Enter') {
                    e.preventDefault();
                    e.stopPropagation(); // 그리드의 "Enter = 편집 시작" 으로 다시 열리지 않게
                    commit('enter');
                } else if (e.key === 'Escape') {
                    e.preventDefault();
                    e.stopPropagation();
                    if (!done) {
                        done = true;
                        log('cancel', 'esc');
                        cancel();
                    }
                } else if (NAV[e.key]) {
                    // Tabulator 6.6.1 + selectableRange: Tab·↑↓ 이동은 열린 editor 를 cancelEdit() 으로 먼저 지운다(blur 없음)
                    // → 입력값이 사라진다(기본 input editor 도 같음, PoC 에서 확인). 그래서 여기서 먼저 확정하고 직접 이동한다.
                    e.preventDefault();
                    e.stopPropagation();
                    var nav = NAV[e.key] === 'next' && e.shiftKey ? 'prev' : NAV[e.key];
                    commit('nav:' + nav);
                    if (done) {
                        moveFrom(cell, nav);
                    }
                }
            });
            input.addEventListener('blur', function () {
                commit('blur');
            });
            // 다른 셀 클릭: 그리드의 mousedown 처리(범위 선택)보다 먼저 확정한다 (조합 중이면 조합 중 글자까지 input.value 에 있음)
            var outside = function (e) {
                if (e.target !== input) {
                    commit('mousedown');
                }
            };
            document.addEventListener('mousedown', outside, true);
            var detach = new MutationObserver(function () {
                if (!input.isConnected) {
                    document.removeEventListener('mousedown', outside, true);
                    detach.disconnect();
                }
            });

            onRendered(function () {
                detach.observe(cell.getTable().element, {childList: true, subtree: true});
                input.focus({preventScroll: true});
                input.setSelectionRange(input.value.length, input.value.length);
                log('editor 열림', input.value);
            });
            return input;
        };
    }

    global.hbImeEditor = {create: create};
})(window);
