"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  api,
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  type EmailDetail,
} from "@/lib/api";
import { LoadingState } from "@/components/states";

const STATUS_LABELS: Record<string, string> = {
  received: "Ricevuta",
  processing: "In analisi…",
  processed: "Analizzata",
  failed: "Errore",
};

const DOC_FIELD_LABELS: Record<string, string> = {
  invoice: "Fattura",
  quote: "Preventivo",
  order: "Ordine",
  delivery_note: "DDT",
  contract: "Contratto",
};

function euro(amount: number | null): string {
  return amount != null
    ? `€ ${amount.toLocaleString("it-IT", { minimumFractionDigits: 2 })}`
    : "—";
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function EmailDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<EmailDetail | null>(null);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .emailDetail(id)
      .then((d) => {
        setDetail(d);
        setError(false);
      })
      .catch(() => setError(true));
  }, [id]);

  useEffect(load, [load]);

  async function act(taskId: string, action: "approve" | "dismiss" | "done") {
    setBusy(taskId);
    try {
      if (action === "approve") await api.approveTask(taskId);
      if (action === "dismiss") await api.dismissTask(taskId);
      if (action === "done") await api.doneTask(taskId);
      load();
    } finally {
      setBusy(null);
    }
  }

  async function download(attachmentId: string, filename: string) {
    setBusy(attachmentId);
    try {
      const blob = await api.downloadAttachment(attachmentId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setBusy(null);
    }
  }

  if (error) {
    return (
      <div className="space-y-4">
        <Link href="/emails" className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100">
          ← Email
        </Link>
        <div className="text-sm text-amber-600">Email non trovata o backend non raggiungibile.</div>
      </div>
    );
  }

  if (!detail) return <LoadingState />;

  const { email } = detail;

  return (
    <div className="stagger space-y-8">
      <div className="space-y-3">
        <Link href="/emails" className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100">
          ← Email
        </Link>
        <div className="flex items-center gap-2 flex-wrap text-xs">
          {email.category && (
            <span className="rounded-full bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900 px-2 py-0.5 font-medium">
              {CATEGORY_LABELS[email.category] ?? email.category}
            </span>
          )}
          <span className={email.status === "failed" ? "text-red-600" : "text-zinc-500"}>
            {STATUS_LABELS[email.status] ?? email.status}
          </span>
          <span className="text-zinc-400">
            {new Date(email.receivedAt).toLocaleString("it-IT")}
          </span>
        </div>
        <h1 className="text-2xl font-semibold">{email.subject || "(senza oggetto)"}</h1>
        <div className="text-sm text-zinc-500">
          {email.fromName ? `${email.fromName} · ` : ""}
          {email.fromAddress}
        </div>
        {email.summary && (
          <p className="text-sm border-l-2 border-indigo-400/40 pl-3 text-zinc-600 dark:text-zinc-300">
            {email.summary}
          </p>
        )}
      </div>

      {detail.documents.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-medium text-zinc-500">Documenti estratti</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {detail.documents.map((doc) => (
              <div key={doc.id} className="card p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">
                    {DOC_FIELD_LABELS[doc.docType] ?? doc.docType}
                    {doc.documentNumber ? ` ${doc.documentNumber}` : ""}
                  </span>
                  {doc.confidence != null && (
                    <span
                      className={`text-xs ${doc.confidence < 0.7 ? "text-amber-600" : "text-zinc-400"}`}
                      title="Confidenza dell'estrazione AI"
                    >
                      {Math.round(doc.confidence * 100)}%
                    </span>
                  )}
                </div>
                <dl className="text-sm space-y-1">
                  {(doc.supplierName || doc.customerName) && (
                    <div className="flex justify-between gap-2">
                      <dt className="text-zinc-500">{doc.supplierName ? "Fornitore" : "Cliente"}</dt>
                      <dd className="text-right">{doc.supplierName ?? doc.customerName}</dd>
                    </div>
                  )}
                  <div className="flex justify-between gap-2">
                    <dt className="text-zinc-500">Importo</dt>
                    <dd className="font-medium">{euro(doc.amount)}</dd>
                  </div>
                  {doc.docDate && (
                    <div className="flex justify-between gap-2">
                      <dt className="text-zinc-500">Data</dt>
                      <dd>{doc.docDate}</dd>
                    </div>
                  )}
                  {doc.dueDate && (
                    <div className="flex justify-between gap-2">
                      <dt className="text-zinc-500">Scadenza</dt>
                      <dd>{doc.dueDate}</dd>
                    </div>
                  )}
                </dl>
              </div>
            ))}
          </div>
        </section>
      )}

      {detail.tasks.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-medium text-zinc-500">Attività generate</h2>
          <ul className="space-y-3">
            {detail.tasks.map((task) => (
              <li
                key={task.id}
                className="card p-4 flex flex-col sm:flex-row sm:items-center gap-3"
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap text-xs">
                    <span className="rounded-full bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5">
                      {PRIORITY_LABELS[task.priority] ?? task.priority}
                    </span>
                    {task.dueDate && <span className="text-zinc-500">scadenza {task.dueDate}</span>}
                  </div>
                  <div className="mt-1 font-medium">{task.title}</div>
                  {task.description && (
                    <div className="text-sm text-zinc-500 mt-0.5">{task.description}</div>
                  )}
                </div>
                <div className="flex gap-2 shrink-0">
                  {task.status === "pending_approval" && (
                    <>
                      <button
                        disabled={busy === task.id}
                        onClick={() => act(task.id, "approve")}
                        className="btn btn-approve px-3.5 py-1.5"
                      >
                        ✓ Approva
                      </button>
                      <button
                        disabled={busy === task.id}
                        onClick={() => act(task.id, "dismiss")}
                        className="btn btn-ghost px-3.5 py-1.5"
                      >
                        Ignora
                      </button>
                    </>
                  )}
                  {task.status === "approved" && (
                    <button
                      disabled={busy === task.id}
                      onClick={() => act(task.id, "done")}
                      className="btn btn-ghost px-3.5 py-1.5"
                    >
                      Segna come fatta
                    </button>
                  )}
                  {(task.status === "done" || task.status === "dismissed") && (
                    <span className="text-sm text-zinc-400 px-2 py-1.5">
                      {task.status === "done" ? "Completata" : "Ignorata"}
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {detail.attachments.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-medium text-zinc-500">Allegati</h2>
          <ul className="space-y-2">
            {detail.attachments.map((att) => (
              <li key={att.id} className="card px-4 py-3 flex items-center gap-3">
                <span className="flex-1 min-w-0 truncate text-sm">{att.filename}</span>
                <span className="text-xs text-zinc-400 shrink-0">{formatBytes(att.sizeBytes)}</span>
                <button
                  disabled={busy === att.id}
                  onClick={() => download(att.id, att.filename)}
                  aria-label={`Scarica ${att.filename}`}
                  className="btn btn-ghost px-3.5 py-1.5 shrink-0"
                >
                  Scarica
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {detail.bodyText && (
        <details className="card p-4">
          <summary className="text-sm font-medium cursor-pointer select-none">
            Testo originale dell&apos;email
          </summary>
          <pre className="mt-3 text-sm whitespace-pre-wrap text-zinc-600 dark:text-zinc-300 font-sans">
            {detail.bodyText}
          </pre>
        </details>
      )}
    </div>
  );
}
