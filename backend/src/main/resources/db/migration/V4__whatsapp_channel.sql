-- Canale WhatsApp (via open-wa EasyAPI): numero del titolare con prefisso
-- internazionale, solo cifre (es. 393331234567).

ALTER TABLE companies
    ADD COLUMN whatsapp_number TEXT;
