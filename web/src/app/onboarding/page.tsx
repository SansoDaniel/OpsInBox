"use client";

import { useAuth0 } from "@auth0/auth0-react";
import { useState } from "react";
import { api, type Me } from "@/lib/api";
import { authEnabled } from "@/lib/auth";

export default function OnboardingPage() {
  if (!authEnabled) {
    return (
      <div className="rounded-xl border border-amber-300 bg-amber-50 dark:bg-amber-950 dark:border-amber-800 p-5 text-sm max-w-lg mx-auto mt-12">
        L&apos;onboarding richiede Auth0 attivo. In modalità dev l&apos;azienda seed è
        già configurata: torna alla dashboard.
      </div>
    );
  }
  return <OnboardingForm />;
}

function OnboardingForm() {
  const { user } = useAuth0();
  const [companyName, setCompanyName] = useState("");
  const [vatNumber, setVatNumber] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Me | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!companyName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const me = await api.onboard({
        companyName: companyName.trim(),
        vatNumber: vatNumber.trim() || undefined,
        email: user?.email,
        name: user?.name,
      });
      setDone(me);
    } catch {
      setError("Errore durante la creazione dell'azienda. Riprova.");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="max-w-lg mx-auto mt-12 space-y-6">
        <div>
          <h1 className="text-xl font-semibold">Benvenuto in OpsInbox 🎉</h1>
          <p className="text-sm text-zinc-500 mt-1">
            {done.company?.name} è pronta. Un ultimo passo:
          </p>
        </div>
        <div className="card p-5 space-y-2">
          <div className="text-sm font-medium">
            Inoltra le email a questo indirizzo dedicato:
          </div>
          <code className="block rounded-lg bg-indigo-500/10 text-indigo-700 dark:text-indigo-300 px-3 py-2 text-sm select-all">
            {done.company?.inboundAddress}
          </code>
          <p className="text-xs text-zinc-500">
            Crea una regola di inoltro automatico dalla casella aziendale
            (Gmail, Outlook o qualsiasi provider) verso questo indirizzo: da quel
            momento OpsInbox leggerà, classificherà e trasformerà le email in
            attività da approvare.
          </p>
        </div>
        <button onClick={() => (window.location.href = "/")} className="btn btn-primary">
          Vai alla dashboard →
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-lg mx-auto mt-12 space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Crea la tua azienda</h1>
        <p className="text-sm text-zinc-500 mt-1">
          Un minuto e la tua inbox operativa è pronta.
        </p>
      </div>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="block text-sm font-medium mb-1" htmlFor="companyName">
            Nome azienda *
          </label>
          <input
            id="companyName"
            value={companyName}
            onChange={(e) => setCompanyName(e.target.value)}
            required
            placeholder="Rossi Impianti SRL"
            className="field"
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1" htmlFor="vatNumber">
            Partita IVA
          </label>
          <input
            id="vatNumber"
            value={vatNumber}
            onChange={(e) => setVatNumber(e.target.value)}
            placeholder="IT01234567890"
            className="field"
          />
        </div>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <button type="submit" disabled={busy || !companyName.trim()} className="btn btn-primary">
          {busy ? "Creazione…" : "Crea azienda"}
        </button>
      </form>
    </div>
  );
}
