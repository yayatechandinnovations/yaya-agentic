-- A removed (REVOKED) sender keeps its row as history, but must not lock its
-- phone number forever: the number has to be re-registrable after a stuck or
-- rejected registration is cancelled. Uniqueness therefore applies only to
-- live senders — a real phone still can't be double-booked across workspaces.
ALTER TABLE whatsapp_senders DROP CONSTRAINT whatsapp_senders_phone_uniq;
CREATE UNIQUE INDEX whatsapp_senders_live_phone_uniq
    ON whatsapp_senders(phone_e164)
    WHERE status <> 'REVOKED';

-- Same reasoning for the BSP SID: when a revoke's best-effort BSP delete
-- didn't take, re-registering the number adopts the still-existing BSP
-- sender, whose SID the revoked row also carries.
DROP INDEX whatsapp_senders_twilio_sid_uniq;
CREATE UNIQUE INDEX whatsapp_senders_twilio_sid_uniq
    ON whatsapp_senders(twilio_sender_sid)
    WHERE twilio_sender_sid IS NOT NULL AND status <> 'REVOKED';
