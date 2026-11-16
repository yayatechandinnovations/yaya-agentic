-- Senders Twilio reported OFFLINE mid-registration were mapped to SUSPENDED,
-- which offers "Resume" for a number that never went live and stops the
-- poller from visiting it. Put them back in review. A sender that was ever
-- live has an approved display name, or was last seen ONLINE before an
-- operator paused it, so neither matches.
UPDATE whatsapp_senders
   SET status = 'PENDING_REVIEW',
       updated_at = NOW()
 WHERE status = 'SUSPENDED'
   AND approved_display_name IS NULL
   AND twilio_sender_sid IS NOT NULL
   AND onboarding_state_json ->> 'lastTwilioStatus' = 'OFFLINE';
