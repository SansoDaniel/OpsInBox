"use client";

import { useState } from "react";
import {
  api,
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  type SearchFilter,
  type SearchResponse,
} from "@/lib/api";
import { EmptyState, ErrorState } from "@/components/states";

const SUGGESTIONS = [
  "Fatture non pagate",
  "Fatture sopra 1000 euro",
  "Fatture da ABC",
  "Richieste clienti",
  "Contratti in scadenza questo mese",
];

function filterChips(filter: SearchFilter): string[] {
  const chips: string[] = [];
  if (filter.target === "documents") chips.push("Cerco: documenti");
  if (filter.target === "emails") chips.push("Cerco: email");
  if (filter.target === "tasks") chips.push("Cerco: attività");
  if (filter.docType) chips.push(`Tipo: ${CATEGORY_LABELS[filter.docType] ?? filter.docType}`);
  if (filter.counterpartyContains) chips.push(`Controparte: ${filter.counterpartyContains}`);
  if (filter.amountMin != null) chips.push(`Importo ≥ €${filter.amountMin}`);
  if (filter.amountMax != null) chips.push(`Importo ≤ €${filter.amountMax}`);
  if (filter.dueFrom || filter.dueTo)
    chips.push(`Scadenza: ${filter.dueFrom ?? "…"} → ${filter.dueTo ?? "…"}`);
  if (filter.dateFrom || filter.dateTo)
    chips.push(`Data: ${filter.dateFrom ?? "…"} → ${filter.dateTo ?? "…"}`);
  if (filter.openTasksOnly) chips.push("Solo non gestite");
  if (filter.textContains) chips.push(`Testo: "${filter.textContains}"`);
  return chips;
}

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const [result, setResult] = useState<SearchResponse | null>(null);

  async function run(q: string) {
    if (!q.trim()) return;
    setQuery(q);
    setBusy(true);
    setError(false);
    try {
      setResult(await api.search(q.trim()));
    } catch {
      setError(true);
      setResult(null);
    } finally {
      setBusy(false);
    }
  }

  const total = result
    ? result.documents.length + result.emails.length + result.tasks.length
    : 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Ricerca</h1>
        <p className="text-sm text-zinc-500 mt-1">
          Chiedi in linguaggio naturale: l&apos;AI traduce la domanda in filtri sui
          tuoi dati.
        </p>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          run(query);
        }}
        className="flex gap-2"
      >
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Ricerca in linguaggio naturale"
          placeholder='Es. "fatture non pagate", "preventivi sopra 5000"'
          className="field flex-1"
        />
        <button type="submit" disabled={busy || !query.trim()} className="btn btn-primary">
          {busy ? "Cerco…" : "Cerca"}
        </button>
      </form>

      <div className="flex flex-wrap gap-2">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            onClick={() => run(s)}
            className="btn btn-ghost rounded-full px-3.5 py-1 text-xs text-zinc-600 dark:text-zinc-400"
          >
            {s}
          </button>
        ))}
      </div>

      {error && <ErrorState message="Errore nella ricerca. Backend raggiungibile?" />}

      {result && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="text-zinc-500">Ho capito:</span>
            {filterChips(result.filter).map((chip) => (
              <span
                key={chip}
                className="rounded-full bg-indigo-500/10 px-2.5 py-1 text-indigo-700 dark:text-indigo-300"
              >
                {chip}
              </span>
            ))}
            <span className="text-zinc-400 ml-auto">{total} risultati</span>
          </div>

          {total === 0 && <EmptyState>Nessun risultato per questa ricerca.</EmptyState>}

          {result.documents.length > 0 && (
            <div className="card overflow-hidden">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-zinc-500 border-b border-zinc-200 dark:border-zinc-800">
                  <tr>
                    <th className="px-4 py-2 font-medium">Tipo</th>
                    <th className="px-4 py-2 font-medium">Controparte</th>
                    <th className="px-4 py-2 font-medium">Numero</th>
                    <th className="px-4 py-2 font-medium text-right">Importo</th>
                    <th className="px-4 py-2 font-medium">Scadenza</th>
                    <th className="px-4 py-2 font-medium">Stato</th>
                  </tr>
                </thead>
                <tbody>
                  {result.documents.map((d) => (
                    <tr
                      key={d.document.id}
                      className="border-b border-zinc-100 dark:border-zinc-800 last:border-0"
                    >
                      <td className="px-4 py-2">
                        {CATEGORY_LABELS[d.document.docType] ?? d.document.docType}
                      </td>
                      <td className="px-4 py-2">
                        {d.document.supplierName ?? d.document.customerName ?? d.fromAddress ?? "—"}
                      </td>
                      <td className="px-4 py-2">{d.document.documentNumber ?? "—"}</td>
                      <td className="px-4 py-2 text-right">
                        {d.document.amount != null
                          ? `€ ${d.document.amount.toLocaleString("it-IT", { minimumFractionDigits: 2 })}`
                          : "—"}
                      </td>
                      <td className="px-4 py-2">{d.document.dueDate ?? "—"}</td>
                      <td className="px-4 py-2">
                        {d.hasOpenTask ? (
                          <span className="rounded-full bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-400 px-2 py-0.5 text-xs">
                            da gestire
                          </span>
                        ) : (
                          <span className="text-xs text-zinc-400">gestita</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {result.emails.length > 0 && (
            <ul className="stagger space-y-3">
              {result.emails.map((email) => (
                <li key={email.id} className="card p-4">
                  <div className="flex items-center gap-2 text-xs">
                    {email.category && (
                      <span className="rounded-full bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900 px-2 py-0.5 font-medium">
                        {CATEGORY_LABELS[email.category] ?? email.category}
                      </span>
                    )}
                    <span className="text-zinc-400">
                      {new Date(email.receivedAt).toLocaleString("it-IT")}
                    </span>
                  </div>
                  <div className="mt-1 font-medium">{email.subject || "(senza oggetto)"}</div>
                  <div className="text-sm text-zinc-500">
                    {email.fromName ? `${email.fromName} · ` : ""}
                    {email.fromAddress}
                  </div>
                  {email.summary && (
                    <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-300">{email.summary}</p>
                  )}
                </li>
              ))}
            </ul>
          )}

          {result.tasks.length > 0 && (
            <ul className="stagger space-y-3">
              {result.tasks.map((task) => (
                <li key={task.id} className="card p-4">
                  <div className="flex items-center gap-2 text-xs">
                    <span className="rounded-full bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5">
                      {PRIORITY_LABELS[task.priority] ?? task.priority}
                    </span>
                    {task.dueDate && <span className="text-zinc-500">scadenza {task.dueDate}</span>}
                  </div>
                  <div className="mt-1 font-medium">{task.title}</div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
