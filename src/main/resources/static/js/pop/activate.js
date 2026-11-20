/* POP 단말 활성화 (/pop/activate) — 회사 코드 + 등록 코드 → 단말 쿠키(서버가 HttpOnly 로 내려준다) → 작업장 홈 */
(function () {
    'use strict';
    var form = document.getElementById('actForm');
    var feedback = document.getElementById('feedback');

    hb.loadMessages().then(function () {
        document.getElementById('compCd').focus();
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            var btn = form.querySelector('button');
            hb.busy(btn, function () {
                return hb.pop.api('/api/pop/activate', {method: 'POST', body: {
                    compCd: document.getElementById('compCd').value,
                    code: document.getElementById('code').value
                }});
            }).then(function (res) {
                if (!res) { return; }
                hb.pop.feedback(feedback, 'ok', hb.t('js.pop.activated'));
                window.location.href = '/pop';
            }, function (err) {
                document.getElementById('code').value = '';
                hb.pop.feedback(feedback, 'error', err.message);
            });
        });
    });
})();
