/*
 * 고장 신고 (cms-breakdown) — CMS 1부 C1 (docs/33 5장). 좁은 화면 우선.
 * 설비·금형 찾기(또는 ?equip=코드) → 현상 타일(누르면 선택, 다시 누르면 해제)·글 → 우선순위·정지 → 사진 → 신고.
 * 사진은 신고가 만들어진 뒤 그 번호로 올린다(대상 화면 = cms-breakdown). 완료된 내 신고는 "확인"으로 닫는다.
 */
(function () {
    'use strict';

    var API = '/api/cms/breakdowns';
    var msg = document.getElementById('msg');
    var form = document.getElementById('reportForm');
    var findForm = document.getElementById('findForm');
    var targetsUl = document.getElementById('targets');
    var tiles = document.getElementById('symptomTiles');
    var mineBody = document.querySelector('#mine tbody');
    var target = null, symptomCd = null;
    var names = {status: {}};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function time(t) { return t ? String(t).substring(0, 16).replace('T', ' ') : ''; }

    function radio(name) {
        var r = form.querySelector('input[name="' + name + '"]:checked');
        return r ? r.value : '';
    }

    function choose(t) {
        target = t;
        symptomCd = null;
        targetsUl.textContent = '';
        document.getElementById('reportBox').hidden = false;
        document.getElementById('targetLabel').textContent = hb.t('js.cms.bd.target', t.code, t.name, t.locationCd || t.plantCd || '');
        // 설비 클래스 한정 현상 코드는 그 클래스 설비에만 보인다(금형·지그는 한정 없는 코드만)
        tiles.querySelectorAll('button').forEach(function (b) {
            var cls = b.getAttribute('data-class');
            b.hidden = !!cls && String(t.classId) !== cls;
            b.setAttribute('aria-pressed', 'false');
            b.classList.add('hb-btn-secondary');
        });
        hb.showFieldErrors(form, []);
        notify('');
    }

    function find(keyword) {
        return hb.latest('targets', hb.api(API + '/targets?keyword=' + encodeURIComponent(keyword || ''))).then(function (res) {
            targetsUl.textContent = '';
            if (!res.data.length) {
                targetsUl.appendChild(hb.el('li', 'hb-muted', hb.t('js.cms.bd.noTarget')));
            }
            res.data.forEach(function (t) {
                var li = hb.el('li');
                var b = hb.el('button', 'hb-btn-secondary', (t.targetType === 'ASSET' ? hb.t('js.cms.bd.asset') + ' ' : '')
                    + t.code + ' · ' + t.name + (t.locationCd ? ' (' + t.locationCd + ')' : ''));
                b.type = 'button';
                b.addEventListener('click', function () { choose(t); });
                li.appendChild(b);
                targetsUl.appendChild(li);
            });
            return res.data;
        }, function (err) { notify(err.message, true); });
    }

    function loadMine() {
        var to = hb.dates.todayIn(hb.session().timeZone);
        return hb.latest('mine', hb.api(API + '?from=' + hb.dates.add(to, -29) + '&to=' + to)).then(function (res) {
            mineBody.textContent = '';
            if (!res.data.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.cms.bd.noMine'));
                td.colSpan = 6;
                tr.appendChild(td);
                mineBody.appendChild(tr);
            }
            res.data.forEach(function (m) {
                var tr = hb.el('tr', m.status === 'CANCELLED' ? 'hb-tree-dim' : null);
                cmsUi.cells(tr, [m.mwoNo, m.targetCd + ' · ' + m.targetNm, m.title,
                    cmsUi.withBadge('', cmsUi.badge('mwo', m.status, names.status[m.status] || m.status),
                        m.status === 'CANCELLED' && m.cancelReason ? '— ' + m.cancelReason : null),
                    time(m.reportedAt)]);
                var act = hb.el('td');
                if (m.status === 'DONE' && document.getElementById('reportBtn')) {
                    var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.bd.confirm'));
                    b.type = 'button';
                    b.title = m.workText || '';
                    b.addEventListener('click', function () {
                        cmsUi.ask('js.cms.dlg.bdConfirm', hb.t('js.cms.bd.confirmAsk', m.mwoNo, m.workText || ''), 'js.cms.bd.confirm', false).then(function (yes) {
                            if (!yes) { return; }
                            hb.busy(b, function () {
                                return hb.api(API + '/' + m.mwoId + '/confirm', {method: 'POST', body: {rowVer: m.rowVer}});
                            }).then(function (res) {
                                if (!res) { return; }
                                notify(hb.t('js.common.saved'));
                                return loadMine();
                            }, function (err) { notify(err.message, true); });
                        });
                    });
                    act.appendChild(b);
                }
                tr.appendChild(act);
                mineBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    function resetForm() {
        form.reset();
        document.getElementById('reportBox').hidden = true;   // .hb-form 은 display:grid — 감싼 div 로 숨긴다
        target = null;
        symptomCd = null;
        document.getElementById('downStartLabel').hidden = true;
        hb.showFieldErrors(form, []);
    }

    if (findForm) {
        findForm.addEventListener('submit', function (e) {
            e.preventDefault();
            hb.busy(document.getElementById('findBtn'), function () {
                return find(document.getElementById('findInput').value);
            });
        });
        form.querySelectorAll('input[name="downYn"]').forEach(function (r) {
            r.addEventListener('change', function () {
                document.getElementById('downStartLabel').hidden = radio('downYn') !== 'Y';
            });
        });
        document.getElementById('resetBtn').addEventListener('click', resetForm);

        var reportBtn = document.getElementById('reportBtn');
        reportBtn.addEventListener('click', function () {
            if (!target) { return; }
            var down = radio('downYn');
            var dsInput = form.querySelector('[data-field="downStartAt"]');
            var body = {targetType: target.targetType, targetCd: target.code, symptomCd: symptomCd,
                symptomText: form.querySelector('[data-field="symptomText"]').value, priority: Number(radio('priority')),
                downYn: down, downStartAt: down === 'Y' ? dsInput.value : null};   // 비우면 서버가 지금(대상 공장 시간대)
            var photo = document.getElementById('photo');
            hb.busy(reportBtn, function () {
                return hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                var done = function () {
                    notify(hb.t('js.cms.bd.reported', res.data.mwoNo));
                    resetForm();
                    return loadMine();
                };
                if (!photo.files.length) { return done(); }
                var fd = new FormData();
                fd.append('targetType', 'cms-breakdown');
                fd.append('targetId', String(res.data.mwoId));
                fd.append('file', photo.files[0]);
                return hb.upload('/api/platform/files', fd).then(done, function (err) {
                    // 신고는 들어갔다 — 사진만 실패
                    notify(hb.t('js.cms.bd.photoFailed', res.data.mwoNo) + ' ' + err.message, true);
                    resetForm();
                    return loadMine();
                });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        var reqs = [hb.loadSession(), hb.codeList('CMS_MWO_STATUS')];
        if (findForm) { reqs.push(hb.api(API + '/symptoms')); }
        return Promise.all(reqs);
    }).then(function (r) {
        r[1].forEach(function (c) { names.status[c.code] = c.name; });
        if (r[2]) {
            r[2].data.forEach(function (s) {
                var b = hb.el('button', 'hb-btn-secondary', s.failNm);
                b.type = 'button';
                b.setAttribute('aria-pressed', 'false');
                if (s.equipClassId) { b.setAttribute('data-class', String(s.equipClassId)); }
                b.addEventListener('click', function () {
                    var on = b.getAttribute('aria-pressed') !== 'true';
                    tiles.querySelectorAll('button').forEach(function (x) {
                        x.setAttribute('aria-pressed', 'false');
                        x.classList.add('hb-btn-secondary');
                    });
                    b.setAttribute('aria-pressed', on ? 'true' : 'false');
                    b.classList.toggle('hb-btn-secondary', !on);
                    symptomCd = on ? s.failCd : null;
                });
                tiles.appendChild(b);
            });
            // QR: ?equip=설비코드 로 열리면 바로 그 설비
            var code = new URLSearchParams(window.location.search).get('equip');
            if (code) {
                return find(code).then(function (list) {
                    var hit = (list || []).filter(function (t) { return t.targetType === 'EQUIP' && t.code === code.toUpperCase(); })[0];
                    if (hit) { choose(hit); }
                });
            }
        }
    }).then(loadMine).then(null, function (err) { notify(err && err.message, true); });
})();
