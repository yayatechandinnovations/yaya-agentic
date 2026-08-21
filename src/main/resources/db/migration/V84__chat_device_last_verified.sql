-- Periodic OTP step-up (chat-experience-design.md §5.4).
--
-- PIN unlock lets an enrolled device re-auth without an SMS, but a device may
-- not ride the PIN forever: every N days it must re-prove phone control with a
-- fresh OTP. last_verified_at records the last OTP enrollment for this device;
-- pin-unlock forces a fresh OTP once it ages past yaya.agentic.chat.pin-reverify-days.
--
-- Existing devices default to now(): the clock starts at deploy, so nobody is
-- force-verified in a thundering herd the moment this ships.
ALTER TABLE chat_account_devices
    ADD COLUMN last_verified_at TIMESTAMPTZ NOT NULL DEFAULT now();
