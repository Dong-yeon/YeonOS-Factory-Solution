/*
 * 껍데기 시작 화면 (docs/50 V2·S5). 다리 YeonShell(네이티브)에 묻고 그 답대로 보인다.
 *   start()           → {state: 'SETUP' | 'OPEN' | 'UPDATE' | 'OFFLINE', current?, min?}
 *                       OPEN 이면 네이티브가 이미 {서버}/pop 으로 옮긴다
 *   setServer({url})  → 첫 실행 한 번만. 네이티브가 https·형식·/pda/version.json 응답을 확인하고 저장한 뒤 다시 시작한다
 * 서버 주소 검사는 네이티브가 다시 한다 — 여기 검사는 안내용이다.
 */
(function () {
    'use strict';

    var shell = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.YeonShell;
    var msg = document.getElementById('msg');

    function show(id) {
        ['setup', 'update', 'offline'].forEach(function (s) { document.getElementById(s).hidden = s !== id; });
    }

    function say(text, error) {
        msg.textContent = text;
        msg.classList.toggle('error', !!error);
    }

    function start() {
        if (!shell) {
            say('앱 안에서만 쓰는 화면입니다.', true);
            return;
        }
        say('서버 확인 중…');
        shell.start().then(function (r) {
            if (r.state === 'SETUP') { say('처음 한 번 서버 주소를 넣습니다.'); show('setup'); }
            else if (r.state === 'UPDATE') {
                document.getElementById('cur').textContent = r.current || '-';
                document.getElementById('min').textContent = r.min || '-';
                say('업데이트 필요', true); show('update');
            } else if (r.state === 'OFFLINE') { say('연결 안 됨', true); show('offline'); }
            else { say('여는 중…'); show(null); }
        }, function (e) { say(e && e.message ? e.message : '시작하지 못했습니다.', true); });
    }

    document.getElementById('setup').addEventListener('submit', function (e) {
        e.preventDefault();
        var url = document.getElementById('url').value.trim();
        if (!/^https:\/\/[^/?#@\s]+\/?$/.test(url)) {
            say('https:// 로 시작하는 서버 주소만 넣습니다(경로 없이).', true);
            return;
        }
        say('서버에 연결해 보는 중…');
        shell.setServer({url: url}).catch(function (err) { say(err && err.message ? err.message : '저장하지 못했습니다.', true); });
    });
    document.getElementById('retry').addEventListener('click', start);
    start();
})();
