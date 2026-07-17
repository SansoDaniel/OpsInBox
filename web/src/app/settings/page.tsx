"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";

export default function SettingsPage() {
  const [inboundAddress, setInboundAddress] = useState<string | null>(null);
  const [notificationEmail, setNotificationEmail] = useState("");
  const [slackWebhookUrl, setSlackWebhookUrl] = useState("");
  const [teamsWebhookUrl, setTeamsWebhookUrl] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    api
      .settings()
      .then((s) => {
        setInboundAddress(s.inboundAddress);
        setNotificationEmail(s.notificationEmail ?? "");
        setSlackWebhookUrl(s.slackWebhookUrl ?? "");
        setTeamsWebhookUrl(s.teamsWebhookUrl ?? "");
        setError(false);
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setSaved(false);
    setError(false);
    try {
      await api.updateSettings({ notificationEmail, slackWebhookUrl, teamsWebhookUrl });
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <div className="text-sm text-zinc-500">Caricamento…</div>;

  const inputClass = "field";

  return (
    <div className="max-w-lg space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">Impostazioni</h1>
        <p className="text-sm text-zinc-500 mt-1">
          Canali di notifica: quando l&apos;AI crea un&apos;attività, ricevi un avviso
          sui canali configurati.
        </p>
      </div>

      {inboundAddress && (
        <div className="card p-4 space-y-1.5">
          <div className="text-sm font-medium">Indirizzo di inoltro dedicato</div>
          <code className="block rounded-lg bg-indigo-500/10 text-indigo-700 dark:text-indigo-300 px-3 py-2 text-sm select-all">
            {inboundAddress}
          </code>
          <p className="text-xs text-zinc-500">
            Inoltra qui le email aziendali (regola di inoltro automatico).
          </p>
        </div>
      )}

      <form onSubmit={save} className="space-y-4">
        <div>
          <label className="block text-sm font-medium mb-1" htmlFor="notificationEmail">
            Email per le notifiche
          </label>
          <input
            id="notificationEmail"
            type="email"
            value={notificationEmail}
            onChange={(e) => setNotificationEmail(e.target.value)}
            placeholder="titolare@tuaazienda.it"
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1" htmlFor="slackWebhookUrl">
            Slack — Incoming Webhook URL
          </label>
          <input
            id="slackWebhookUrl"
            value={slackWebhookUrl}
            onChange={(e) => setSlackWebhookUrl(e.target.value)}
            placeholder="https://hooks.slack.com/services/…"
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1" htmlFor="teamsWebhookUrl">
            Microsoft Teams — Incoming Webhook URL
          </label>
          <input
            id="teamsWebhookUrl"
            value={teamsWebhookUrl}
            onChange={(e) => setTeamsWebhookUrl(e.target.value)}
            placeholder="https://outlook.office.com/webhook/…"
            className={inputClass}
          />
        </div>
        <p className="text-xs text-zinc-500">
          Lascia vuoto un campo per disattivare quel canale. Senza canali
          configurati le notifiche restano solo nel registro interno.
        </p>
        <div className="flex items-center gap-3">
          <button type="submit" disabled={busy} className="btn btn-primary">
            {busy ? "Salvataggio…" : "Salva"}
          </button>
          {saved && <span className="text-sm text-emerald-600">Salvato ✓</span>}
          {error && <span className="text-sm text-red-600">Errore, riprova.</span>}
        </div>
      </form>
    </div>
  );
}
