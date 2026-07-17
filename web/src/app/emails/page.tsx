"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, CATEGORY_LABELS, type Email } from "@/lib/api";

const STATUS_LABELS: Record<string, string> = {
  received: "Ricevuta",
  processing: "In analisi…",
  processed: "Analizzata",
  failed: "Errore",
};

export default function EmailsPage() {
  const [emails, setEmails] = useState<Email[] | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(() => {
    api
      .emails()
      .then((e) => {
        setEmails(e);
        setError(false);
      })
      .catch(() => setError(true));
  }, []);

  useEffect(() => {
    load();
    const interval = setInterval(load, 10_000);
    return () => clearInterval(interval);
  }, [load]);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Email</h1>

      {error && <div className="text-sm text-amber-600">Backend non raggiungibile.</div>}
      {emails && emails.length === 0 && (
        <div className="card rounded-2xl p-12 text-center text-sm text-zinc-500">
          Nessuna email ancora. Prova lo script <code>scripts/send-test-email.ps1</code>.
        </div>
      )}

      <ul className="stagger space-y-3">
        {emails?.map((email) => (
          <li key={email.id}>
            <Link
              href={`/emails/${email.id}`}
              className="card card-interactive block p-4"
            >
              <div className="flex items-center gap-2 flex-wrap text-xs">
                {email.category && (
                  <span className="rounded-full bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900 px-2.5 py-0.5 font-medium">
                    {CATEGORY_LABELS[email.category] ?? email.category}
                  </span>
                )}
                <span
                  className={
                    email.status === "failed" ? "text-red-600" : "text-zinc-500"
                  }
                >
                  {STATUS_LABELS[email.status] ?? email.status}
                </span>
                <span className="text-zinc-400 tabular-nums">
                  {new Date(email.receivedAt).toLocaleString("it-IT")}
                </span>
              </div>
              <div className="mt-1.5 font-medium">
                {email.subject || "(senza oggetto)"}
              </div>
              <div className="text-sm text-zinc-500">
                {email.fromName ? `${email.fromName} · ` : ""}
                {email.fromAddress}
              </div>
              {email.summary && (
                <p className="mt-2 text-sm border-l-2 border-indigo-400/40 pl-3 text-zinc-600 dark:text-zinc-300">
                  {email.summary}
                </p>
              )}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
