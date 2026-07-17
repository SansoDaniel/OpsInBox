# OpsInbox

Leggi **STATUS.md** per lo stato del progetto, le decisioni architetturali e cosa
manca. Guida operativa completa (avvio, env, provider, Auth0) nel **README.md**.

Regole del progetto:
- Rispondere e scrivere UI in italiano
- Frontend web = Next.js (`web/`), NON Flutter Web; backend = Kotlin/Ktor (`backend/`)
- Senza `OPENAI_API_KEY` e senza `AUTH0_*` tutto gira in modalità dev (mock AI,
  company seed): mantenere sempre questa doppia modalità
- Script PowerShell solo ASCII (PS 5.1 legge male UTF-8 senza BOM)
- L'LLM non genera mai SQL: la ricerca passa da `SearchFilter` (JSON validato)
