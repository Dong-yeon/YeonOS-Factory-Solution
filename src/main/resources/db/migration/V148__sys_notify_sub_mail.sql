-- 메일 알림 구독 칸 (docs/36 18.3, Q12) — 임시 번호 V9709(병합 때 상위가 번호를 정한다)
-- 메일은 알림함에 들어온 알림을 메일로도 받는 것(알림함을 끄면 메일도 안 감). 기본 N(사용자가 켠다)
ALTER TABLE sys_notify_sub ADD COLUMN mail_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE sys_notify_sub ADD CONSTRAINT ck_sys_notify_sub_mail CHECK (mail_yn IN ('Y', 'N'));
COMMENT ON COLUMN sys_notify_sub.mail_yn IS '메일로도 받기 — 알림함에 들어온 것만(inbox_yn = Y), 기본 N (docs/36 18.3)';
