# OpsInbox — AI Operations Inbox

*(nome di lavoro, facile da cambiare)*

Un "dipendente AI" per PMI (edilizia, manifattura, installatori, studi tecnici,
piccola distribuzione): legge le email in arrivo, capisce i documenti allegati,
estrae i dati utili e crea attività da approvare con un click.
**Ogni comunicazione diventa un evento di business strutturato: niente più
fatture dimenticate o clienti senza risposta.**

## Architettura

```
Email (inoltro) → Webhook → Postgres (coda job) → Pipeline AI → Task → Dashboard
                                                     │
                                          OpenAI Responses API
                                     (classifica + estrae + riassume
                                        + genera task, 1 chiamata)
```

| Componente | Tecnologia |
|---|---|
| `backend/` | Kotlin + Ktor 3, Exposed, Flyway |
| `web/` | Next.js 16 (App Router, Tailwind) |
| Database | PostgreSQL 16 (via Docker) |
| Coda | Tabella `jobs` su Postgres (`FOR UPDATE SKIP LOCKED`) — niente Redis per l'MVP |
| AI | OpenAI Responses API con Structured Outputs; PDF/immagini passati direttamente al modello (niente OCR separato per ora) |
| Storage allegati | Disco locale (`backend/var/storage`), interfaccia pronta per S3 |
| Ingestione email | Webhook formato Postmark Inbound: ogni azienda ha un indirizzo di inoltro dedicato. Niente OAuth Gmail/M365 nell'MVP |

## Avvio in locale

```powershell
# 1. Database
docker compose up -d

# 2. Backend (porta 8080) — prima esecuzione: scarica Gradle e dipendenze
cd backend
.\gradlew.bat run

# 3. Frontend (porta 3000)
cd web
npm run dev

# 4. Simula l'arrivo di un'email con fattura
.\scripts\send-test-email.ps1
```

Apri <http://localhost:3000>: la dashboard mostra l'email classificata come
fattura, il riassunto e l'attività "Approva pagamento" in attesa di approvazione.

### Modalità demo vs AI reale

Senza `OPENAI_API_KEY` il backend usa `MockAiClient` (euristiche su parole
chiave): l'intera pipeline funziona senza costi. Per l'AI reale:

```powershell
$env:OPENAI_API_KEY = "sk-..."   # opzionale: $env:OPENAI_MODEL (default gpt-4o)
.\gradlew.bat run
```

### Variabili d'ambiente (backend)

| Variabile | Default |
|---|---|
| `PORT` | `8080` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/opsinbox` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `opsinbox` / `opsinbox` |
| `OPENAI_API_KEY` | *(vuoto = modalità demo)* |
| `OPENAI_MODEL` | `gpt-4o` |
| `STORAGE_DIR` | `var/storage` |
| `INBOUND_WEBHOOK_TOKEN` | *(vuoto = webhook aperto, solo dev)* |
| `AUTH0_DOMAIN` / `AUTH0_AUDIENCE` | *(vuoti = auth disattivata, modalità dev)* |
| `INBOUND_DOMAIN` | `inbox.local` (dominio degli indirizzi generati in onboarding) |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `1025` (= Mailpit in dev) |
| `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_STARTTLS` | *(vuoti in dev; da impostare col provider reale)* |
| `SMTP_FROM` | `OpsInbox <notifiche@opsinbox.local>` |
| `APP_URL` | `http://localhost:3000` (link nelle notifiche) |

## API principali

| Endpoint | Descrizione |
|---|---|
| `POST /webhooks/inbound-email` | Email in ingresso (formato Postmark Inbound), idempotente per MessageID |
| `GET /api/dashboard/today` | Contatori del giorno |
| `GET /api/emails`, `GET /api/emails/{id}` | Lista/dettaglio email con riassunto, documenti, task |
| `GET /api/attachments/{id}` | Download allegato (verifica che appartenga alla company) |
| `GET /api/tasks?status=` | Attività (default: in attesa di approvazione) |
| `GET /api/search?q=` | Ricerca in linguaggio naturale (LLM → filtro JSON → query SQL; mai SQL generato dall'LLM) |
| `GET/POST /api/settings` | Canali di notifica per azienda (email, Slack, Teams) + indirizzo di inoltro |
| `POST /api/tasks/{id}/approve` · `/dismiss` · `/done` | Approvazione con un click |

Multi-tenant: header `X-Company-Id` (in dev il fallback è la company seed
`00000000-...-000001`, vedi `V2__dev_seed.sql`). L'auth (Auth0) arriverà dopo.

## Collegare un provider email reale (Postmark)

Il webhook accetta il formato [Postmark Inbound](https://postmarkapp.com/developer/webhooks/inbound-webhook)
nativamente. Passaggi:

1. **Account Postmark** → crea un Server → tab **Inbound**: ottieni un indirizzo
   tipo `a1b2c3...@inbound.postmarkapp.com`.
2. **Proteggi il webhook**: imposta `INBOUND_WEBHOOK_TOKEN` sul backend e usa
   come Webhook URL una di queste forme (Postmark non firma con HMAC; raccomanda
   Basic Auth nell'URL):
   - `https://x:IL_TUO_TOKEN@tuodominio.com/webhooks/inbound-email` (Basic Auth)
   - `https://tuodominio.com/webhooks/inbound-email?token=IL_TUO_TOKEN`
3. **Test in locale**: esponi il backend con un tunnel:
   ```powershell
   cloudflared tunnel --url http://localhost:8080   # oppure: ngrok http 8080
   ```
   e usa l'URL del tunnel come Webhook URL in Postmark.
4. **Collega un'azienda**: metti l'indirizzo inbound Postmark nella colonna
   `companies.inbound_address` (la risoluzione usa `OriginalRecipient`).
5. **Onboarding cliente**: il cliente crea una regola di inoltro automatico
   dalla propria casella (Gmail/Outlook/qualsiasi) verso quell'indirizzo.
   Due minuti, nessun OAuth.
6. In produzione: dominio inbound custom (`inbox.tuodominio.com` con MX verso
   `inbound.postmarkapp.com`) per avere indirizzi brandizzati per cliente.

Alternativa equivalente: Mailgun Inbound Routes (firma HMAC nativa). Il payload
differisce: servirebbe un secondo parser nel webhook.

## Attivare Auth0 (login + onboarding azienda)

Senza variabili Auth0 tutto gira in **modalità dev**: nessun login, company seed.
Con Auth0 attivo: login → se l'utente non ha un'azienda viene portato
all'onboarding (nome azienda + P.IVA) → viene generato l'indirizzo di inoltro
dedicato (es. `rossi-impianti-a1b2c3@inbox.local`) → dashboard.

Setup (una tantum, ~10 minuti):

1. Crea un tenant su [auth0.com](https://auth0.com) (region EU).
2. **Applications → Create Application → Single Page Web App.**
   - Allowed Callback URLs / Logout URLs / Web Origins: `http://localhost:3000`
3. **APIs → Create API**: identifier ad es. `https://api.opsinbox.app`
   (è l'*audience*; non deve esistere come URL reale).
4. Backend: `AUTH0_DOMAIN=dev-xxx.eu.auth0.com`, `AUTH0_AUDIENCE=https://api.opsinbox.app`
5. Frontend (`web/.env.local`): `NEXT_PUBLIC_AUTH0_DOMAIN`, `NEXT_PUBLIC_AUTH0_CLIENT_ID`
   (dalla SPA app), `NEXT_PUBLIC_AUTH0_AUDIENCE` (uguale al backend).

Endpoint coinvolti: `GET /api/me` (stato utente/azienda),
`POST /api/onboarding` (crea azienda + utente owner). Tutte le route `/api/*`
richiedono il Bearer token quando l'auth è attiva; il webhook inbound resta
protetto dal suo token dedicato.

## Notifiche

Quando l'AI crea un'attività, parte una notifica per ogni canale configurato in
**Impostazioni** (per azienda): **email** (SMTP), **Slack** e **Teams** (incoming
webhook, payload `{"text": ...}`). Senza canali configurati resta il fallback `log`.
Consegna asincrona via coda job (retry con backoff); se il canale non è più
configurato al momento dell'invio la notifica va in `failed` senza retry.

In dev le email si vedono in **Mailpit**: <http://localhost:8025> (parte con
`docker compose up -d`). In produzione basterà puntare `SMTP_*` a Postmark/SES.

## Scelte di design da conoscere

- **Dedup a due livelli**: per email (`company_id + message_id`) e per documento
  (hash di mittente+numero+importo). La stessa fattura inoltrata due volte non
  genera task doppi.
- **Human-in-the-loop**: nessuna azione automatica; ogni task nasce
  `pending_approval` e il titolare approva o ignora.
- **`confidence` su ogni estrazione**: sotto soglia si potrà forzare la revisione
  manuale (non ancora attivo).
- **Prompt e schema JSON**: `backend/src/main/kotlin/com/opsinbox/pipeline/Prompts.kt`.

## Prossimi passi (roadmap MVP)

1. ~~Scheletro end-to-end~~ ✅
2. ~~Provider email reale (Postmark inbound) + protezione webhook~~ ✅
3. ~~Auth0 + onboarding azienda (indirizzo di inoltro dedicato)~~ ✅
4. ~~Ricerca in linguaggio naturale (LLM → filtro strutturato)~~ ✅
5. ~~Notifiche email/Slack/Teams~~ ✅
6. ~~Dettaglio email nella UI (documenti estratti, allegati, task collegati)~~ ✅
7. Storage S3, deploy, GDPR (DPA, data residency EU)
