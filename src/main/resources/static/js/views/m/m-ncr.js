// @ts-check
/*
 * 모바일 부적합 사진 등록 (/m/ncr, docs/74 화면 4·M8) — 기존 API 만. 권한(qms-ncr SAVE)·검증·LOT 보류는 서버.
 * 두 걸음:
 *   ① POST /api/qms/ncrs  — 누른 순간 만든 X-Idempotency-Key(같은 누름의 재전송 → 409). 성공하면 폼을 잠근다(두 번 만들지 않게)
 *   ② 사진을 한 장씩 POST /api/platform/files (targetType = qms-ncr, targetId = 부적합 id) — 실패한 사진만 다시 올릴 수 있다
 * 오프라인이면 등록 단추를 끈다(대기열 없음). 스캔(hb.scan)은 LOT 번호 칸에 넣는다. 서버 글자는 textContent 로만.
 */
/**
 * @typedef {{defectId: number, defectCd: string, defectNm: string}} MNcrDefect
 * @typedef {{file: File, li: HTMLLIElement, done: boolean}} MNcrPhoto
 */
(function () {
    'use strict';

    var PROG = 'qms-ncr';
    var form = /** @type {HTMLFormElement} */ (document.getElementById('ncrForm'));
    var lotNo = /** @type {HTMLInputElement} */ (document.getElementById('lotNo'));
    var source = /** @type {HTMLSelectElement} */ (document.getElementById('source'));
    var defectId = /** @type {HTMLSelectElement} */ (document.getElementById('defectId'));
    var ncQty = /** @type {HTMLInputElement} */ (document.getElementById('ncQty'));
    var description = /** @type {HTMLTextAreaElement} */ (document.getElementById('description'));
    var photos = /** @type {HTMLInputElement} */ (document.getElementById('photos'));
    var photoList = /** @type {HTMLElement} */ (document.getElementById('photoList'));
    var saveBtn = /** @type {HTMLButtonElement} */ (document.getElementById('saveBtn'));
    var msg = document.getElementById('msg');
    var doneBox = /** @type {HTMLElement} */ (document.getElementById('doneBox'));
    var doneInfo = /** @type {HTMLElement} */ (document.getElementById('doneInfo'));
    var uploadList = /** @type {HTMLElement} */ (document.getElementById('uploadList'));
    var retryBtn = /** @type {HTMLButtonElement} */ (document.getElementById('retryBtn'));
    var newBtn = /** @type {HTMLButtonElement} */ (document.getElementById('newBtn'));
    /** 등록이 끝난 부적합 id — 있으면 다시 만들지 않는다 @type {number | null} */
    var createdId = null;
    /** @type {MNcrPhoto[]} */
    var queue = [];

    function showPicked() {
        hbM.clear(photoList);
        Array.prototype.forEach.call(photos.files || [], function (/** @type {File} */ f) {
            photoList.appendChild(hbM.card({title: f.name, meta: [Math.max(1, Math.round(f.size / 1024)) + ' KB']}));
        });
    }

    /** 화면에서 먼저 보는 것 — 빈 칸·수량 형식. 서버가 같은 것을 다시 본다 @return {{body: Record<string, unknown>} | null} */
    function collect() {
        /** @type {HB.FieldError[]} */
        var errors = [];
        var req = hb.t('js.common.required');
        if (hb.text ? hb.text.isBlank(lotNo.value) : !lotNo.value.trim()) { errors.push({field: 'lotNo', message: req}); }
        if (!source.value) { errors.push({field: 'source', message: req}); }
        if (!defectId.value) { errors.push({field: 'defectId', message: req}); }
        var q = hb.numbers.parse(ncQty.value, {scale: 3, allowNegative: false});
        if (!q.ok) {
            errors.push({field: 'ncQty', message: hb.t('js.m.ncr.qtyBad')});
        } else if (q.value === null || Number(q.value) <= 0) {
            errors.push({field: 'ncQty', message: q.value === null ? req : hb.t('js.m.ncr.qtyBad')});
        }
        if (hb.text ? hb.text.isBlank(description.value) : !description.value.trim()) { errors.push({field: 'description', message: req}); }
        hb.showFieldErrors(form, errors);
        if (errors.length || !q.ok) { return null; }
        return {body: {lotNo: lotNo.value.trim(), source: source.value, defectId: Number(defectId.value), ncQty: Number(q.value),
            description: description.value}};
    }

    /** @param {boolean} locked */
    function lock(locked) {
        [lotNo, source, defectId, ncQty, description, photos].forEach(function (e) { e.disabled = locked; });
        form.hidden = locked;
        doneBox.hidden = !locked;
    }

    /** 사진을 한 장씩(순서대로) — 실패해도 다음 장으로. 끝나면 실패가 남았는지 알린다 */
    function uploadAll() {
        var id = createdId;
        if (id === null) { return Promise.resolve(); }
        var target = String(id);
        var todo = queue.filter(function (p) { return !p.done; });
        /** @param {number} i @return {Promise<void>} */
        function next(i) {
            if (i >= todo.length) { return Promise.resolve(); }
            var p = todo[i];
            var meta = /** @type {HTMLElement | null} */ (p.li.querySelector('.m-card-meta'));
            if (meta) { meta.textContent = hb.t('js.m.ncr.uploading'); }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', target);
            fd.append('file', p.file);
            return hb.upload('/api/platform/files', fd).then(function () {
                p.done = true;
                if (meta) { meta.textContent = hb.t('js.m.ncr.uploaded'); }
                if (p.li.firstElementChild) { p.li.firstElementChild.removeAttribute('data-tone'); }
            }, function (err) {
                if (meta) { meta.textContent = hb.t('js.m.ncr.uploadFail', err.message); }
                if (p.li.firstElementChild) { p.li.firstElementChild.setAttribute('data-tone', 'alert'); }
            }).then(function () { return next(i + 1); });
        }
        return next(0).then(function () {
            var failed = queue.filter(function (p) { return !p.done; }).length;
            retryBtn.hidden = failed === 0;
            hbM.say(msg, failed ? hb.t('js.m.ncr.photoFailed', failed) : '', failed > 0);
        });
    }

    /** 등록 결과 — 번호·LOT·보류 여부는 서버가 준 값으로 @param {number} id */
    function showDone(id) {
        hbM.clear(doneInfo);
        return hb.api('/api/qms/ncrs/' + encodeURIComponent(String(id))).then(function (res) {
            var n = /** @type {{ncrNo: string, lotNo: string, itemCd: string, itemNm: string, holdYn: string}} */ (res.data.ncr);
            doneInfo.appendChild(hbM.kv([
                [hb.t('js.m.ncr.no'), n.ncrNo],
                [hb.t('js.m.ncr.lot'), n.lotNo + ' · ' + n.itemCd + ' ' + n.itemNm],
                [hb.t('js.m.ncr.hold'), n.holdYn === 'Y' ? hb.status('danger', hb.t('js.m.ncr.held')) : hb.t('js.m.ncr.notHeld')]
            ]));
        }, function () {
            // 조회 권한이 없거나 끊겼어도 등록은 끝났다 — 번호 대신 안내만
            doneInfo.appendChild(hb.el('p', 'm-muted', hb.t('js.m.ncr.doneNoView')));
        });
    }

    function save() {
        if (createdId !== null) { return Promise.resolve(); }
        hbM.say(msg, '');
        if (!hbM.online()) { hbM.say(msg, hb.t('js.m.offlineSave'), true); return Promise.resolve(); }
        var c = collect();
        if (!c) { return Promise.resolve(); }
        var key = hbM.key('m-ncr');
        return hb.api('/api/qms/ncrs', {method: 'POST', body: c.body, headers: {'X-Idempotency-Key': key}}).then(function (res) {
            createdId = Number(res.data);
            hbM.clear(uploadList);
            queue = [];
            Array.prototype.forEach.call(photos.files || [], function (/** @type {File} */ f) {
                var li = hbM.card({title: f.name, meta: [hb.t('js.m.ncr.waiting')]});
                uploadList.appendChild(li);
                queue.push({file: f, li: li, done: false});
            });
            lock(true);
            window.scrollTo(0, 0);
            return showDone(createdId).then(uploadAll);
        }, function (err) {
            if (err.errors && err.errors.length) { hb.showFieldErrors(form, err.errors); }
            hbM.say(msg, err.message, true);
        });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        hb.busy(saveBtn, save).then(function () { saveBtn.disabled = !hbM.online(); });
    });
    photos.addEventListener('change', showPicked);
    retryBtn.addEventListener('click', function () { hb.busy(retryBtn, uploadAll); });
    newBtn.addEventListener('click', function () {
        createdId = null;
        queue = [];
        form.reset();
        hbM.clear(photoList);
        hbM.clear(uploadList);
        hb.showFieldErrors(form, []);
        hbM.say(msg, '');
        retryBtn.hidden = true;
        lock(false);
        lotNo.focus();
    });

    hbM.ready().then(function () {
        hbM.onNet(function (on) { saveBtn.disabled = !on; });
        Promise.all([hb.codeList('QMS_NCR_SOURCE'), hb.api('/api/qms/ncrs/defects')]).then(function (r) {
            hbM.options(source, r[0].map(function (c) { return {value: c.code, text: c.name}; }), hb.t('js.m.pick'));
            hbM.options(defectId, /** @type {MNcrDefect[]} */ (r[1].data || []).map(function (d) {
                return {value: String(d.defectId), text: d.defectCd + ' ' + d.defectNm};
            }), hb.t('js.m.pick'));
        }, function (err) { hbM.say(msg, hbM.online() ? err.message : hb.t('js.m.offlineRead'), true); });
        // 스캔 값은 LOT 번호 칸으로(내부 라벨 접두어 LOT: 는 뗀다) — 등록이 끝난 뒤에는 받지 않는다
        if (hb.scan) {
            hb.scan.listen(function (r) {
                if (createdId !== null) { return; }
                lotNo.value = r.value.replace(/^LOT:/i, '');
            });
        }
    });
})();
