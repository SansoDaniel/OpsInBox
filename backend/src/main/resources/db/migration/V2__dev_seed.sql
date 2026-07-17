-- Seed di sviluppo: una company fissa per lavorare senza auth.
-- NB: da rimuovere/condizionare prima del deploy in produzione.

INSERT INTO companies (id, name, vat_number, inbound_address)
VALUES ('00000000-0000-0000-0000-000000000001', 'Dev Company SRL', 'IT00000000000', 'dev@inbox.local');

INSERT INTO users (company_id, email, name, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'daniel.sanso.developer@gmail.com', 'Daniel', 'owner');
