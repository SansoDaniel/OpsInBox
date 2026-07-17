# OpsInbox — Stato del progetto

> Resoconto per riprendere il lavoro in una nuova sessione.
> Ultimo aggiornamento: **17 luglio 2026** (redesign UI: design system in globals.css)

## Cos'è

**OpsInbox** (nome di lavoro): "AI Operations Inbox" per PMI (edilizia, manifattura,
installatori, studi tecnici, distribuzione). Legge le email inoltrate dall'azienda,
classifica, estrae i dati dai documenti allegati, riassume e crea attività che il
titolare approva con un click. Posizionamento: *ogni comunicazione diventa un evento
di business strutturato — niente più fatture dimenticate o clienti senza risposta*.

## Stack e struttura

| Cartella | Tecnologia |
|---|---|
| `backend/` | Kotlin + Ktor 3.1, Exposed 0.56, Flyway, PostgreSQL 16 (Docker), Gradle 8.13 (wrapper incluso) |
| `web/` | Next.js 16.2 (App Router, Tailwind 4, TypeScript), UI in italiano |
| `scripts/` | `send-test-email.ps1` — simula una fattura in arrivo (param `-Token`) |
| root | `docker-compose.yml` (solo Postgres), `README.md` (guida completa) |

**Nota**: il frontend web è Next.js **per scelta esplicita di Daniel** (non Flutter Web;
Flutter resta per il futuro mobile). Package Kotlin: `com.opsinbox`.

## Decisioni architetturali chiave (e perché)

1. **Ingestione email via inoltro, non OAuth**: ogni azienda ha un indirizzo dedicato
   (es. `rossi-a1b2c3@inbox.local`); il cliente crea una regola di inoltro. Evita la
   verifica CASA di Google (settimane + costi) per l'MVP. Webhook formato **Postmark
   Inbound** su `POST /webhooks/inbound-email`.
2. **Niente OCR separato**: PDF/immagini vanno direttamente al modello multimodale
   (OpenAI Responses API + Structured Outputs, 1 sola chiamata per classificazione +
   estrazione + riassunto + task). Document AI solo se servirà più accuratezza.
3. **Coda su Postgres** (tabella `jobs`, `FOR UPDATE SKIP LOCKED`), niente Redis.
4. **Doppia modalità AI**: senza `OPENAI_API_KEY` gira `MockAiClient` (euristiche
   keyword IT/EN) — tutta la pipeline è provabile gratis. `OpenAiClient` è pronto ma
   **mai testato con chiave vera** (serve la chiave di Daniel).
5. **Ricerca naturale = LLM → filtro JSON validato → query Exposed**. L'LLM non genera
   mai SQL. La risposta include l'interpretazione (mostrata come chip nella UI).
6. **Dedup a due livelli**: email (`company_id+message_id`) e documento (hash
   mittente+numero+importo). Verificato: re-invio stessa fattura → nessun task doppio.
7. **Human-in-the-loop**: ogni task nasce `pending_approval`; nessuna azione automatica.
8. **Auth opzionale**: senza `AUTH0_DOMAIN`/`AUTH0_AUDIENCE` (backend) e
   `NEXT_PUBLIC_AUTH0_*` (frontend) tutto gira in modalità dev con company seed
   (`00000000-...-0001`, utente daniel.sanso.developer@gmail.com, `V2__dev_seed.sql`).

## Fatto e verificato end-to-end

- ✅ Pipeline completa: webhook → storage allegati (disco, interfaccia pronta per S3) →
  job → classificazione (9 categorie) → estrazione documento → riassunto → task →
  notifica (canale "log") → approvazione con un click nella UI
- ✅ Dashboard "Oggi" (contatori live), pagine Attività, Email, Ricerca
- ✅ Protezione webhook: `INBOUND_WEBHOOK_TOKEN` via Basic Auth (stile Postmark),
  header `X-Webhook-Token` o `?token=` — testati 401/200
- ✅ Auth0: JWT RS256 validato via JWKS, tutte le `/api/*` protette quando attivo
  (testato 401 con dominio fittizio); webhook escluso (ha il suo token)
- ✅ Onboarding: `POST /api/onboarding` crea azienda + owner + indirizzo di inoltro
  generato; frontend con login → redirect automatico → form → mostra indirizzo;
  `GET /api/me` per lo stato. **Flusso completo con Auth0 vero mai provato** (manca il tenant)
- ✅ Ricerca naturale: `GET /api/search?q=` — testate "fatture non pagate", "fatture
  sopra 1000 euro", "richieste clienti", "fatture da ABC" (mock); UI con chip
  interpretazione e tabella risultati
- ✅ Notifiche reali: `NotificationService` (email SMTP via angus-mail, Slack/Teams
  via incoming webhook `{"text":...}`, fallback log); canali per azienda in colonne
  `companies.notification_email/slack_webhook_url/teams_webhook_url` (V3), API
  `GET/POST /api/settings`, pagina UI Impostazioni. Verificato end-to-end: email
  catturata da **Mailpit** (in docker-compose, UI :8025, SMTP :1025) e webhook Slack
  catturato da server locale. Config mancante al momento dell'invio → `failed`
  senza retry; invio fallito → retry del job con backoff
- ✅ Dettaglio email nella UI: pagina `/emails/[id]` (card documenti con confidenza,
  attività con approvazione inline, allegati con download, testo originale
  ripiegabile); card della lista email cliccabili; "Vedi email di origine" dai task.
  Nuovo endpoint `GET /api/attachments/{id}` (download con controllo company).
  Verificato: API, download (headers + contenuto), click-through nella UI
- ✅ Redesign UI (filosofie Apple design + Emil Kowalski): design system in
  `web/src/app/globals.css` dentro `@layer components` (così le utility Tailwind
  possono sovrascrivere) — token superfici/ombre/easing custom, `.card`,
  `.btn-primary/-approve/-ghost` (gradiente + `:active scale(0.97)`), `.field`,
  header sticky traslucido (`backdrop-filter`), pill di navigazione attiva
  (usePathname), sfondo con bagliori su `body::before` fisso (NON usare
  `background-attachment: fixed`: uccide il compositor), stagger d'ingresso,
  numeri tabulari, accento indigo, `prefers-reduced-motion/-transparency`.
  Hover gated dietro `@media (hover: hover)`

## Bug risolti da ricordare

- **Exposed + `UPDATE … RETURNING`**: serve `explicitStatementType = StatementType.SELECT`
  nella `exec()` (vedi `JobQueue.claimNext`), altrimenti "risultato inatteso".
- **PowerShell 5.1 + UTF-8 senza BOM**: gli script `.ps1` devono restare **solo ASCII**
  (niente `€` o accentate) o il simbolo si corrompe. Il mock ora estrae importi sia
  "€ 1.285,90" sia "1.285,90 EUR".
- **Preview Claude**: `.claude/launch.json` sta in `...\Developer\Flutter` (cartella
  padre, dove parte la sessione) e punta a `ProjectContainer/web` con `--prefix`.
- Il fill programmatico degli input React non attiva `onChange` (test UI: usare i
  bottoni suggerimento).

## Come si avvia

```powershell
docker compose up -d                          # Postgres (healthcheck incluso)
cd backend; .\gradlew.bat run                 # API su :8080 (Flyway migra da solo)
cd web; npm run dev                           # UI su :3000
.\scripts\send-test-email.ps1                 # simula fattura in arrivo
```

Variabili d'ambiente: tabella completa nel README (`DATABASE_URL`, `OPENAI_API_KEY`,
`OPENAI_MODEL`, `INBOUND_WEBHOOK_TOKEN`, `AUTH0_*`, `INBOUND_DOMAIN`, `STORAGE_DIR`).

## Cosa manca (in ordine consigliato)

1. **Prova con AI reale**: `OPENAI_API_KEY` + 2-3 PDF di fatture vere — la validazione
   più importante, mai fatta
2. **Deploy/beta**: S3 per gli allegati (interfaccia `StorageService` già astratta),
   hosting, GDPR (DPA, data residency EU — argomento di vendita)
3. Post-MVP: connettori OAuth Gmail/M365, parser Mailgun, SDI/FatturaPA (in Italia le
   fatture B2B viaggiano via SDI: il vero valore email sono preventivi/ordini/DDT/reclami),
   notifiche Teams con Adaptive Card (oggi testo semplice, ok per i connector legacy)

**L'MVP software è funzionalmente completo** (tutte le feature dello spec originale
implementate e verificate in modalità dev). Manca la validazione con AI reale e la
messa in produzione.

### Dipendono da Daniel (account/chiavi)
- Chiave OpenAI (per la prova reale)
- Tenant Auth0 (guida nel README, ~10 min)
- Account Postmark + tunnel `cloudflared` per test con email vere (guida nel README)

## Note varie

- **Git**: repo inizializzato in `ProjectContainer/` ma **nessun commit ancora fatto**
- Valutazione Ollama (fatta, decisione presa): per l'estrazione documenti restare su
  API top (costo ~€0,01-0,03/email, meglio di un server GPU); Ollama utile in futuro
  per dev locale o tier enterprise "privacy". L'interfaccia `AiClient` rende il cambio
  banale (~30 righe)
- Prompt e schemi JSON (analisi email + ricerca): `backend/.../pipeline/Prompts.kt`
- Pricing previsto: Starter €49 / Professional €149 / Business €399 / Enterprise custom
