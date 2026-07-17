-- Canali di notifica configurabili per azienda.
-- email: indirizzo del titolare; slack/teams: incoming webhook URL.
-- Se nessun canale è configurato, la notifica finisce solo nel log (canale 'log').

ALTER TABLE companies
    ADD COLUMN notification_email TEXT,
    ADD COLUMN slack_webhook_url  TEXT,
    ADD COLUMN teams_webhook_url  TEXT;
