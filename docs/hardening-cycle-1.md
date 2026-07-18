# Ciclo 1 — Hardening & Sicurezza (deploy-ready)

> Documento di coordinamento del team. **Ogni agente legge le sezioni degli altri
> prima di procedere.** Il PM (Claude) orchestra gli handoff in sequenza B → A → C.

## Principio non negoziabile

**La modalità dev deve continuare a funzionare a configurazione zero.** Tutto il
progetto è costruito su questo: senza `OPENAI_API_KEY` → MockAiClient; senza
`AUTH0_*` → nessun login + company seed. L'hardening deve essere **gated per
ambiente**: si introduce `APP_ENV` (`development` default | `production`). In
`production` il sistema *fail-closed* e pretende i segreti; in `development` resta
identico a oggi. Nessuna regressione della DX.

## Findings confermati dal PM (per severità)

| # | Sev | File | Problema |
|---|-----|------|----------|
| S1 | Alta | `backend/.../jobs/JobQueue.kt:46` | `locked_by = '$workerId'` interpola una stringa in SQL. `workerId` è interno (non user-input) ma è un pattern da eliminare: usare binding parametrico. |
| S2 | Alta | `backend/.../db/migration/V2__dev_seed.sql` | Il seed dev (company + utente fissi `00000000-…-0001`) gira in **ogni** ambiente, produzione inclusa → backdoor multi-tenant. Va escluso in produzione. **NB Flyway**: V1–V4 sono già applicate; non modificare i checksum dei file esistenti — usare `APP_ENV` + `flyway.locations`/placeholder o seeding a livello app. |
| S3 | Alta | `backend/.../Application.kt` (auth/webhook) | *Fail-open*: senza `AUTH0_*` l'auth è disattivata, senza `INBOUND_WEBHOOK_TOKEN` il webhook è aperto. In `production` il boot deve **fallire** se mancano token webhook e config Auth0. |
| S4 | Media | `backend/.../Application.kt:103` (StatusPages) | Rimanda `cause.message` al client → info disclosure. In produzione: messaggio generico + id di correlazione nei log. |
| S5 | Media | `backend/.../Application.kt` | Nessun **rate limiting**, in particolare sull'endpoint pubblico `POST /webhooks/inbound-email`. |
| S6 | Media | `docker-compose.yml:8` | Password Postgres hardcoded (`opsinbox`). Ok per dev; parametrizzare via env e documentare che la produzione non usa questo compose. |
| S7 | Media | `web/next.config.ts` + backend | Header di sicurezza assenti (CSP, X-Content-Type-Options, Referrer-Policy, HSTS, X-Frame-Options). |
| S8 | Bassa | `backend/.../routes/*` | Verificare isolamento multi-tenant su **tutte** le route (già ok su `/api/attachments/{id}`); ignorare `X-Company-Id` quando l'auth è attiva (difesa in profondità). |
| S9 | Bassa | backend | Limiti dimensione richiesta oltre al check 10MB sugli allegati (body JSON del webhook). |

## Assegnazioni

- **Agente B (Backend)** → S1, S2, S3, S4, S5, S6, S8, S9. Definisce il contratto:
  introduce `APP_ENV`, i guard di boot, il comportamento fail-closed. **Va per primo**
  perché fissa il contratto di sicurezza che il web deve rispettare.
- **Agente A (Web)** → S7 (lato frontend) + error boundary, stati di
  caricamento/errore coerenti, gestione `NEXT_PUBLIC_API_URL` in produzione, pass
  di accessibilità. Legge il report di B prima di partire (eventuali nuovi env/comportamenti).
- **Agente C (Revisore)** → verifica entrambi: build, isolamento multi-tenant,
  assenza di fail-open in produzione, nessuna fuga di segreti/eccezioni, **dev mode
  ancora funzionante**. Usa gli skill `/security-review` e `/code-review`. Se trova
  problemi bloccanti, indica quale agente deve correggere.

## Ambiente (LEGGERE — evita di ri-scoprire i vincoli)

- OS **Windows**, shell **PowerShell** (primaria); Bash disponibile. Script `.ps1`
  solo ASCII (PS 5.1 corrompe UTF-8 senza BOM).
- Backend: `cd backend; .\gradlew.bat build` — Java 21, Kotlin 2.1, Ktor 3.1,
  Exposed 0.56, Flyway, package `com.opsinbox`. Prima build lenta (dipendenze).
- Infra dev: `docker compose up -d` (Postgres :5432 + Mailpit :8025). Docker Desktop
  potrebbe dover essere avviato.
- Frontend: `cd web; npm run build` (veloce) per il typecheck.
- Migrazioni Flyway V1–V4 **già applicate** sul DB in esecuzione: una nuova
  migrazione deve essere **V5+**; non toccare i file esistenti (checksum).
- Repo su GitHub, branch `main`, ultimo commit `7f3ad12`. Le modifiche del ciclo
  restano nel working tree per la review di C (non committare finché C non approva).

## Handoff log

### B (Backend) → COMPLETATO

**Contratto introdotto: `APP_ENV`** (`development` default | `production`). In `development`
il comportamento è **identico a prima** (config-zero: senza `OPENAI_API_KEY` → MockAiClient,
senza `AUTH0_*` → nessun login + company seed). In `production` il sistema è **fail-closed**.
File centrale: `backend/.../config/AppConfig.kt` (`enum AppEnv`, `AppConfig.appEnv/isProduction`,
`validateForBoot()`).

**S1 — SQL injection pattern in `JobQueue.claimNext`** ✅
- `backend/.../jobs/JobQueue.kt`: `locked_by = '$workerId'` → placeholder `?` con binding
  parametrico tipizzato `args = listOf(TextColumnType() to workerId)`. Mantenuto
  `explicitStatementType = StatementType.SELECT` (UPDATE … RETURNING).
- Verificato: build OK + email di test processata end-to-end (job reclamato via la query nuova).

**S2 — seed dev che girava anche in produzione (backdoor multi-tenant)** ✅
- Spostato `V2__dev_seed.sql` da `db/migration/` a **`db/migration-dev/`** (file **non modificato**,
  checksum invariato → nessuna violazione sul DB reale già migrato).
- `backend/.../db/DatabaseFactory.kt`: in `development` Flyway carica `db/migration` **+**
  `db/migration-dev`; in `production` **solo** `db/migration` (+ `ignoreMigrationPatterns("*:missing")`
  perché su un DB nato in dev la V2 risulterebbe applicata ma non più presente nel classpath).
- Verificato su DB puliti: **dev** → V1-V4 + seed (`companies`=1); **prod** → V1,V3,V4 senza V2
  (`companies`=0). Sul DB reale esistente: "Successfully validated 4 migrations", nessun errore checksum.

**S3 — fail-open su auth/webhook** ✅
- `backend/.../Application.kt`: guard di boot `config.validateForBoot()` **prima** di init DB/server.
  In `production`, se mancano `AUTH0_DOMAIN`+`AUTH0_AUDIENCE` o `INBOUND_WEBHOOK_TOKEN`, logga
  l'elenco dei problemi e `exitProcess(1)`.
- Verificato: `APP_ENV=production` senza segreti → boot interrotto, **exit code 1**, messaggio chiaro.

**S4 — info disclosure in StatusPages** ✅
- `backend/.../Application.kt`: l'handler `exception<Throwable>` non rimanda più `cause.message`.
  Genera un **`correlationId`** (UUID), lo logga insieme allo stacktrace completo lato server, e
  risponde al client con `{"error":"Errore interno del server","correlationId":"<uuid>"}`.
  **NB per A**: il formato dell'errore 500 è cambiato (vedi sotto).

**S5 — rate limiting** ✅
- Nuova dipendenza `io.ktor:ktor-server-rate-limit`. `Application.kt` installa `RateLimit` con un
  limiter `WEBHOOK_RATE_LIMIT` (**60 richieste/minuto per IP di origine**). `WebhookRoutes.kt`
  avvolge `POST /webhooks/inbound-email` in `rateLimit(RateLimitName(WEBHOOK_RATE_LIMIT))`.
- Verificato: oltre soglia → **HTTP 429** con header `Retry-After`.

**S6 — password Postgres hardcoded** ✅
- `docker-compose.yml`: `POSTGRES_DB/USER/PASSWORD` ora via env con default dev
  (`${POSTGRES_PASSWORD:-opsinbox}`). Commento esplicito: **questo compose è solo per dev**, la
  produzione usa un Postgres gestito con credenziali da secret manager. DX invariata.

**S8 — isolamento multi-tenant / difesa in profondità** ✅
- `backend/.../auth/AuthSupport.kt` (`resolveCompanyId`): quando **l'auth è attiva** la company
  deriva **solo** dall'identità JWT; l'header `X-Company-Id` è **ignorato** (prima era già inefficace
  ma ora è esplicito). In dev (no JWT) `X-Company-Id`/seed restano.
- `backend/.../routes/WebhookRoutes.kt`: in **produzione niente fallback sulla company seed** — una
  email a un indirizzo di inoltro sconosciuto ora risponde **404** invece di finire nel tenant seed.
  In dev il fallback resta (necessario per `send-test-email.ps1`).
- Rivisto l'isolamento su tutte le route (`ApiRoutes`, `SearchRoutes`, `SettingsRoutes`,
  `OnboardingRoutes`): tutte già filtrano per `companyId` derivato dal server. Nessuna regressione.

**S9 — limiti dimensione richiesta** ✅
- `backend/.../routes/WebhookRoutes.kt`: controllo `Content-Length` sul webhook, limite
  `MAX_WEBHOOK_BODY_BYTES = 50MB` → **HTTP 413** se superato (in aggiunta al check 10MB per allegato).
- Verificato: `Content-Length` fittizio da 60MB → 413.

**Bonus (fix necessario per il deploy) — Flyway nel fat-jar** ✅
- Trovato un bug **preesistente**: eseguendo lo **shadowJar** (come farà la produzione), Flyway 10
  scartava le migrazioni ("did not follow the filename convention" → schema **non creato**). Causa:
  i file `META-INF/services` di `flyway-core` e `flyway-database-postgresql` si sovrascrivevano nel
  fat-jar. Fix in `build.gradle.kts`: `tasks.withType<ShadowJar> { mergeServiceFiles() }`.
  Dopo il fix, il jar migra correttamente sia in dev sia in prod. (Con `gradlew run` non si notava.)

**Header di sicurezza lato backend (parte di S7 lato API)** ✅
- `Application.kt` installa `DefaultHeaders`: `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY` su ogni risposta; `Strict-Transport-Security`
  **solo in produzione**. La CSP completa del sito resta compito di A (frontend/reverse-proxy).

**Esito build/test**: `.\gradlew.bat build` **BUILD SUCCESSFUL**. Testati a runtime dal jar:
fail-closed prod (exit 1), dev config-zero (health 200 + email processata end-to-end),
Flyway dev/prod su DB puliti, rate limit (429), body limit (413), header di sicurezza.

---

#### ⚠️ Per l'Agente A (Web) — nuovi env / comportamenti da conoscere

1. **`APP_ENV`** (nuovo): in dev non serve fare nulla (default `development`). In produzione il
   backend pretende `AUTH0_DOMAIN`, `AUTH0_AUDIENCE`, `INBOUND_WEBHOOK_TOKEN` o non parte.
2. **Formato errore 500 cambiato (S4)**: il body 500 ora è
   `{"error":"Errore interno del server","correlationId":"<uuid>"}` — non contiene più il dettaglio
   dell'eccezione. Se la UI mostra `err.error`, va bene; eventualmente mostrare/loggare il
   `correlationId` per il supporto. Gli altri errori (400/401/403/404/409) sono **invariati**
   (`{"error":"..."}`).
3. **CORS parametrico (nuovo env `CORS_ALLOWED_ORIGINS`)**: in dev restano ammessi `localhost:3000`
   e `127.0.0.1:3000`. In **produzione** il backend **non ammette alcuna origine di default**: va
   impostato `CORS_ALLOWED_ORIGINS` (lista separata da virgole, host o URL; in prod è forzato lo
   schema `https`). Il dominio del frontend di produzione va aggiunto qui.
4. **Header di sicurezza sulle risposte API**: aggiunti `X-Content-Type-Options`, `Referrer-Policy`,
   `X-Frame-Options` (+ HSTS in prod). Nessuna azione richiesta; A gestisce comunque la **CSP** del
   sito lato Next.js/reverse-proxy (S7).
5. **`X-Company-Id`**: con auth attiva è **ignorato** (difesa in profondità). Il frontend in
   produzione non deve fare affidamento su questo header per scegliere il tenant.
6. Nessun nuovo header **richiesto** al frontend per le chiamate `/api/*` (resta il Bearer JWT).

**Finding non risolti**: nessuno tra quelli assegnati (S1-S6, S8, S9 tutti completati). S7 (header
lato frontend/CSP) e error boundary restano ad A come da piano.

### A (Web) → COMPLETATO

Contratto di B rispettato: formato errore 500 con `correlationId` gestito lato client
(vedi sotto), coerenza env documentata, nessuna modifica al backend.

**S7 — Header di sicurezza del sito (CSP + affini)** ✅
- File: `web/next.config.ts`. Aggiunto `async headers()` che applica a `/:path*`:
  `Content-Security-Policy`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`,
  `Permissions-Policy` (camera/microphone/geolocation/browsing-topics tutti disabilitati),
  e `Strict-Transport-Security` **solo in produzione**. Aggiunto `poweredByHeader: false`.
- **CSP costruita dagli env** (vale in dev e in prod):
  - `connect-src`: `'self'` + origine API (da `NEXT_PUBLIC_API_URL`, default
    `http://localhost:8080`) + dominio Auth0 (da `NEXT_PUBLIC_AUTH0_DOMAIN`, per
    login/`/oauth/token`/JWKS). Origini estratte con `new URL().origin` e deduplicate;
    un dominio Auth0 passato come solo host viene normalizzato a `https://`.
  - `script-src 'self' 'unsafe-inline'` + `'unsafe-eval'` **solo in dev** (React usa `eval`
    per lo stack degli errori; in prod assente).
  - `style-src 'self' 'unsafe-inline' https://fonts.googleapis.com` (Next/Tailwind iniettano
    stili inline; i font Geist sono comunque auto-hostati da `next/font`, serviti da `'self'`).
  - `img-src 'self' blob: data: https:` (blob per il download allegati, data:/https: per avatar Auth0).
  - `font-src 'self' https://fonts.gstatic.com`.
  - `frame-ancestors 'none'` (equivalente moderno di X-Frame-Options DENY), `object-src 'none'`,
    `base-uri 'self'`, `form-action 'self'`; `upgrade-insecure-requests` **solo in prod**.
- **Scelta architetturale**: approccio *senza nonce* (doc Next
  `content-security-policy.md` → "Without Nonces"). L'approccio a nonce richiederebbe un
  `proxy.ts` e il rendering dinamico forzato di ogni pagina (costi di perf) senza reale
  beneficio per una SPA client-side. Documentato nel file.
- **Verificato**: `npm run build` OK. Runtime dev (`next dev`): header presenti (CSP con
  `connect-src ... http://localhost:8080`, HSTS assente, X-Powered-By assente), app renderizza
  con stili+font, **nessuna violazione CSP in console**, HMR funzionante. Simulata la CSP prod:
  `unsafe-eval` assente, `upgrade-insecure-requests`+HSTS presenti, `connect-src` con API e
  Auth0 in `https`.

**Error boundary e stati** ✅
- Nuovi file: `web/src/app/error.tsx` (client, con recupero), `web/src/app/not-found.tsx`,
  `web/src/app/global-error.tsx` (con proprio `<html>/<body>` + import di `globals.css`).
- **Nota su Next 16.2**: la prop di recupero degli error boundary è ora `unstable_retry`
  (aggiunta in 16.2.0, vedi doc `error.md`); `reset` è deprecata. Gli error boundary
  accettano **entrambe** le prop e usano `unstable_retry ?? reset ?? location.reload` per
  robustezza. **Da far controllare a C**: se preferite `reset` puro come da testo del piano,
  è una riga da cambiare — ho scelto la API nuova della versione installata.
- `error.tsx` mostra il `correlationId` quando l'errore è un `ApiError` di tipo 500 (utile per
  il supporto, coerente col nuovo formato di B).
- Stati coerenti tra le pagine: nuovo `web/src/components/states.tsx`
  (`LoadingState`/`ErrorState`/`EmptyState`, con `role="status"/"alert"` e `aria-live`),
  applicato a dashboard, tasks, emails, emails/[id], search, settings. Aggiunto lo stato di
  caricamento mancante su tasks/emails e l'avviso di errore di caricamento su settings
  (stato `loadError` separato da quello di salvataggio, per non duplicare i messaggi).
- Verificato a runtime: la route inesistente renderizza `not-found.tsx` dentro il layout.

**`NEXT_PUBLIC_API_URL` in produzione** ✅
- File: `web/src/lib/api.ts` (`resolveApiUrl()`). In dev resta il default `http://localhost:8080`
  (DX config-zero). In **produzione**, se la variabile manca, logga un `console.error` esplicito
  e usa `about:blank` come base: le chiamate falliscono in modo **rumoroso e diagnosticabile**
  invece del bug silenzioso (l'app che chiama il localhost dell'utente). `NEXT_PUBLIC_*` è
  inlined a build-time, quindi il warning emerge in build/runtime.
- Corretto anche il messaggio d'errore della dashboard che stampava `undefined` quando la
  variabile non è impostata (ora con fallback esplicito nel testo).

**Gestione errori client (contratto S4 di B)** ✅
- `web/src/lib/api.ts`: nuova classe esportata `ApiError` (status + message + `correlationId`).
  `request()`/`requestBlob()` ora fanno il parse del body `{"error":...,"correlationId":...}`
  invece di lanciare `new Error("API 500")` generico. Le pagine che fanno `.catch(...)` reggono
  invariate; l'error boundary può mostrare il `correlationId`.

**Pass di accessibilità** ✅
- `web/src/app/globals.css`: aggiunto `:focus-visible` visibile su `.btn` e `.nav-link`
  (anello indigo a 2px), classe `.skip-link` ("Salta al contenuto") nascosta fuori schermo e
  rivelata al focus da tastiera; entrambe rispettano `prefers-reduced-motion`.
- `web/src/app/layout.tsx`: skip-link + `id="contenuto"` sul `<main>`.
- `web/src/components/Header.tsx`: `<nav aria-label="Navigazione principale">` +
  `aria-current="page"` sul link attivo.
- `web/src/app/tasks/page.tsx`: gruppo filtri con `role="group"`/`aria-label` e `aria-pressed`.
- `web/src/app/search/page.tsx`: `aria-label` sull'input di ricerca (aveva solo placeholder).
- `web/src/app/emails/[id]/page.tsx`: `aria-label` descrittivo su ogni bottone "Scarica".
- I bottoni con icona esistenti (dashboard StatCard) hanno già label testuale e icone
  `aria-hidden`; nessun bottone solo-icona senza nome accessibile trovato.
- Verificato a runtime: `.skip-link` risulta `position:fixed; z-index:60` e translato fuori
  schermo di default (comparsa solo al focus). **Nota per C**: durante i test il chunk CSS del
  **dev server Turbopack** non aveva ripreso `.skip-link` dopo l'edit a `globals.css` (cache HMR
  stale); dopo aver ripulito `.next/dev` e riavviato è corretto, e la **build di produzione lo
  include regolarmente**. Se in dev qualcosa non si aggiorna dopo un edit CSS, riavviare il dev
  server.

**Esito `npm run build`**: **Compiled successfully** + TypeScript OK, tutte le route generate
(incluso `/_not-found`). Unico warning **preesistente** (non mio): "multiple lockfiles / inferred
workspace root" causato da `C:\Users\Daniel\package-lock.json` che confonde Turbopack sulla root.
Si può silenziare con `turbopack.root` in config o rimuovendo il lockfile spurio — lasciato a
valle della review perché tocca l'ambiente, non il codice del ciclo.

**Punti da far verificare con attenzione all'Agente C (Revisore)**:
1. **Direttive CSP**: `script-src`/`style-src` usano `'unsafe-inline'` (necessario con
   l'approccio senza nonce per Next/Tailwind). È il trade-off standard documentato: non è la CSP
   più stretta possibile ma è quella che non rompe l'app senza infrastruttura a nonce. Valutare
   se per il deploy si vuole alzare l'asticella (nonce via proxy) — è una scelta di prodotto.
   `connect-src` include Auth0 solo se `NEXT_PUBLIC_AUTH0_DOMAIN` è settato: verificare che in
   prod con auth attiva il dominio Auth0 finisca davvero in `connect-src` (login/token).
2. **`unstable_retry` vs `reset`** negli error boundary (vedi sopra): confermare la scelta.
3. **`about:blank` come fallback API in prod**: verificare che l'approccio "fail-rumoroso"
   sia preferito a un throw a build-time (avrei potuto lanciare, ma bloccare la build su una
   env di runtime mancante è più fragile per il tooling; ho scelto il warning + placeholder rotto).
4. Doppia modalità: verificata a livello di codice — auth off (default) testata a runtime;
   auth on segue lo stesso percorso (Auth0 aggiunto a `connect-src` dagli env, gate invariato).

**Finding non risolti**: nessuno bloccante sul mio ambito. Segnalo solo il warning lockfile
preesistente (sopra), che è ambientale.

### C (Revisore) → COMPLETATO

**Verdetto sintetico: APPROVATO CON RISERVE.** Nessun problema **bloccante**. Tutte le
aree di sicurezza del ciclo (S1–S9 + CSP) sono verificate sul codice e a runtime. Restano
solo rilievi **minori**/informativi, nessuno dei quali blocca il commit del ciclo 1.

#### Esito build
- **Backend** (`.\gradlew.bat build`): **BUILD SUCCESSFUL** (10 task, up-to-date; il codice
  compila). Nota: `test NO-SOURCE` — nessun test automatizzato nel progetto (preesistente,
  fuori scope del ciclo; vedi rilievo M4).
- **Frontend** (`npm run build`): **Compiled successfully** + TypeScript OK, tutte le 9 route
  generate (incluso `/_not-found`). Unico warning: lockfile multipli (`C:\Users\Daniel\package-lock.json`),
  **ambientale e preesistente**, già segnalato da A.

#### Verifiche a runtime (dal fat-jar, come farà la produzione)
- **Fail-closed prod** (`APP_ENV=production` senza segreti): `java -jar ...-all.jar` → **exit code 1**
  con elenco chiaro dei segreti mancanti (AUTH0_* + INBOUND_WEBHOOK_TOKEN). ✅
  (NB: `gradlew run` ritorna 0 perché il task Gradle non propaga `exitProcess(1)`; il jar sì —
  ed è ciò che conta per il deploy.)
- **Dev config-zero**: boot OK → Ambiente DEVELOPMENT, MockAiClient, auth off, Flyway migra,
  `/health` → **200** `{"status":"ok"}`, `/api/dashboard/today` → **200** (seed). Nessuna regressione DX. ✅
- **Header di sicurezza backend**: presenti `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`;
  HSTS **assente in dev** (corretto). ✅
- **S9 body limit**: `Content-Length` 60MB → **413**. ✅  Webhook dev con body valido → 200 (queued). ✅
- Dati di test inseriti durante la review **ripuliti** dal DB dev.

#### Controlli obbligatori — esito per area
1. **S1 (SQL injection JobQueue)** ✅ — `JobQueue.kt:44-63`: `locked_by = ?` con
   `args = listOf(TextColumnType() to workerId)`. Unico `exec(` raw SQL del backend; nessun'altra
   interpolazione di stringa in SQL. `workerId` è server-side (`JobWorker.kt:20`), comunque parametrizzato.
2. **S2/S3 fail-closed** ✅ — `AppConfig.validateForBoot()` (righe 94-106) ritorna gli errori solo in
   prod; `Application.kt:59-65` fa `exitProcess(1)`. `DatabaseFactory.kt:28-40`: prod carica solo
   `db/migration` (no seed), dev carica anche `db/migration-dev`. Seed spostato senza modifica (checksum
   invariato). Verificato a runtime (exit 1). Vedi rilievo **M1** (nota informativa Flyway).
3. **S8 multi-tenant** ✅ — Ispezionate **tutte** le route (`ApiRoutes`, `SearchRoutes`, `SettingsRoutes`,
   `OnboardingRoutes`, `WebhookRoutes`). Ogni endpoint dati filtra per `companyId` derivato dal server
   (`requireCompanyId`/JWT). `AuthSupport.kt:24-36`: con auth attiva la company deriva **solo** dal JWT sub;
   `X-Company-Id` usato **solo** nel ramo dev (auth off). L'endpoint allegati verifica la company proprietaria
   (`ApiRoutes.kt:148`). Nessun endpoint permette di leggere/scrivere dati di un altro tenant. Vedi **M2**.
4. **S4 info disclosure** ✅ — `Application.kt:145-160`: handler `exception<Throwable>` non rimanda più
   `cause.message`; genera `correlationId` (UUID), logga stacktrace server-side, risponde solo
   `{"error":"Errore interno del server","correlationId":"<uuid>"}`. Nessuna fuga di stacktrace al client.
5. **S5/S9** ✅ — Rate limit `WEBHOOK_RATE_LIMIT` 60/min per IP di origine (`Application.kt:118-123`),
   applicato solo al webhook (`WebhookRoutes.kt:109`). Body limit 50MB → 413 (`WebhookRoutes.kt:45,116-122`).
   Limiti sensati per un inbound Postmark (~35MB allegati → ~48MB base64).
6. **CSP (S7)** ✅ con riserva — `next.config.ts`: CSP costruita dagli env, `connect-src` include
   `'self'` + API + Auth0 (solo se `NEXT_PUBLIC_AUTH0_DOMAIN` settato, normalizzato a https). Logica
   verificata riga per riga: con auth attiva il dominio Auth0 **entra** in `connect-src` (righe 40-45,58).
   `'unsafe-inline'` su script/style-src è il trade-off documentato dell'approccio senza nonce: non è la
   CSP più stretta ma non rompe l'app e non è lasca (default-src 'self', object-src/frame-ancestors 'none',
   base-uri/form-action 'self'). `'unsafe-eval'` solo in dev. Accettabile per l'MVP. Vedi **M3** (scelta di prodotto).
7. **Nessun segreto** ✅ — Scan sul diff tracciato e sui file nuovi: nessuna chiave/token/password reale
   introdotta. Tutti i segreti letti da `System.getenv`. Unico literal è il default dev `opsinbox`
   (Postgres), documentato come solo-dev e ora sovrascrivibile via env. `web/.env.local` contiene solo
   `NEXT_PUBLIC_API_URL=http://localhost:8080` (+ commenti/esempi) ed è gitignored.
8. **Regressione dev mode** ✅ — Config-zero verificato a runtime (sopra): identico a prima.

#### Verifica dei punti lasciati da A
- **`unstable_retry` vs `reset`**: confermato corretto. La doc installata
  `node_modules/next/dist/docs/.../error.md` (Next 16.2.10) indica `unstable_retry` come API
  corrente/raccomandata (aggiunta in v16.2.0) e `reset` come fallback ("In most cases, you should use
  unstable_retry() instead"). Gli error boundary usano `unstable_retry ?? reset ?? location.reload`: robusto. ✅
- **`about:blank` fallback API in prod**: scelta ragionevole (fail rumoroso + `console.error`) rispetto a
  un throw a build-time. Approvata. ✅
- **Auth0 in `connect-src`**: verificato (vedi punto 6). ✅

#### Rilievi (nessuno bloccante)

- **M1 — minore/informativo (Backend, Agente B)** — `DatabaseFactory.kt:28-40`. Il fail-closed è corretto
  per un **DB di produzione nuovo/pulito** (parte da V1, salta V2 → `companies=0`, verificato da B). Ma
  `ignoreMigrationPatterns("*:missing")` copre lo scenario "DB nato in dev poi promosso a prod": in quel caso
  la riga di seed (company `…0001` + utente `daniel.sanso.developer`) **resta fisicamente nel DB** perché
  Flyway ignora la migrazione mancante ma non fa rollback dei dati. Non è una regressione (promuovere un DB
  dev a prod è una prassi sconsigliata e fuori dal flusso previsto), ma il `validateForBoot` **non ripulisce**
  un seed già inserito. Consiglio: una nota in `docs`/README ("in produzione partire sempre da un DB nuovo")
  o, in futuro, un check di boot che rifiuti la presenza della company seed in prod. **Assegnato a B** (solo doc).
- **M2 — minore (Backend, Agente B)** — `Application.kt:111`. `allowHeader("X-Company-Id")` resta nel CORS
  anche in produzione. È **innocuo** (l'header è ignorato lato server con auth attiva, `AuthSupport.kt:26-31`),
  ma è un residuo che può confondere. Opzionale: rimuoverlo dall'allowHeader in prod. **Assegnato a B** (cosmetico).
- **M3 — minore/scelta di prodotto (Web, Agente A)** — `next.config.ts:52,54`. `'unsafe-inline'` su
  `script-src`/`style-src` (approccio senza nonce). Corretto e documentato per l'MVP; se in futuro si vuole
  alzare l'asticella serve infrastruttura a nonce via proxy. **Nessuna azione richiesta ora**; decisione da
  prendere prima del deploy pubblico. **Assegnato a A** (solo se si decide di irrigidire).
- **M4 — minore (progetto, non del ciclo)** — Nessun test automatizzato nel backend (`test NO-SOURCE`).
  Preesistente e fuori scope del ciclo 1; segnalato solo perché l'hardening di sicurezza trarrebbe beneficio
  da test di regressione (fail-closed, isolamento tenant, body/rate limit). **Non assegnato** (backlog).

#### Conclusione
Il ciclo 1 è **pronto per il commit**. I quattro rilievi sono minori/informativi e possono essere
gestiti come follow-up (doc o cosmetici) senza bloccare. Build backend + frontend verdi; comportamenti
di sicurezza verificati a runtime.

---

## Chiusura del ciclo (PM)

**M1 e M2 chiusi dal PM.** L'Agente B non ha potuto riprendere il lavoro (limite di sessione
raggiunto durante la ripresa, zero modifiche applicate): trattandosi di due interventi minimi e
già specificati dalla review, li ha applicati direttamente il PM.

- **M1 — chiuso** (`db/DatabaseFactory.kt`): aggiunta nota di deploy esplicita nel ramo produzione.
  Documenta che escludere la location del seed impedisce di *inserire* la company demo ma non
  *rimuove* un seed già presente: in produzione si parte **sempre** da un database nuovo e vuoto,
  mai promuovendo un DB di sviluppo. Modifica **inerte** (solo commento, nessun cambio di
  comportamento) — non altera ciò che l'Agente C ha revisionato.
- **M2 — chiuso** (`Application.kt`, blocco CORS): `allowHeader("X-Company-Id")` è ora gated su
  `if (!config.isProduction)`. In produzione l'header non è più esposto; in dev resta (senza JWT
  è l'unico modo di scegliere il tenant). `Content-Type`, `Authorization` e `X-Webhook-Token`
  invariati.
- **Verifica**: `.\gradlew.bat build` → **BUILD SUCCESSFUL**.

**M3 e M4 restano follow-up aperti** come da verdetto di C: M3 è una decisione di prodotto da
prendere prima del deploy pubblico (irrigidire la CSP con i nonce); M4 è backlog (test di
regressione backend). Nessuno dei due appartiene a questo ciclo.

**Guard di boot non implementato (deliberato)**: C suggeriva, in alternativa alla nota M1, un
check runtime che rilevi la company seed in produzione. Non è stato aggiunto per non introdurre
logica non revisionata dopo l'approvazione di C. Resta come possibile follow-up.
