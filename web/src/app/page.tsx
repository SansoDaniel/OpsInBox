"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, CATEGORY_LABELS, type Dashboard } from "@/lib/api";

function MailIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="m3.5 7 7.6 5.4a1.6 1.6 0 0 0 1.8 0L20.5 7" />
    </svg>
  );
}

function InboxIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M4 4h16v12h-4.5l-1.5 3h-4l-1.5-3H4z" />
      <path d="M4 12h4l1.5 2h5L16 12h4" />
    </svg>
  );
}

function AlertIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M10.3 3.9 1.8 18.1a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  );
}

function StatCard({
  label,
  value,
  icon,
  tone,
}: {
  label: string;
  value: number;
  icon: React.ReactNode;
  tone: "indigo" | "zinc" | "red";
}) {
  const iconTones = {
    indigo: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400",
    zinc: "bg-zinc-500/10 text-zinc-600 dark:text-zinc-400",
    red: "bg-red-500/10 text-red-600 dark:text-red-400",
  };
  const active = tone === "red" && value > 0;
  return (
    <div className="card p-5">
      <div className="flex items-center gap-3">
        <span className={`flex h-9 w-9 items-center justify-center rounded-xl ${iconTones[tone]}`}>
          {icon}
        </span>
        <span className="text-sm text-zinc-500">{label}</span>
      </div>
      <div className={`stat-value mt-3 ${active ? "text-red-600 dark:text-red-500" : ""}`}>
        {value}
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(() => {
    api
      .dashboard()
      .then((d) => {
        setData(d);
        setError(false);
      })
      .catch(() => setError(true));
  }, []);

  useEffect(() => {
    load();
    const interval = setInterval(load, 10_000);
    return () => clearInterval(interval);
  }, [load]);

  if (error) {
    return (
      <div className="card border-amber-300/50 p-5 text-sm">
        Backend non raggiungibile su <code>{process.env.NEXT_PUBLIC_API_URL}</code>.
        Avvia Postgres (<code>docker compose up -d</code>) e il backend (
        <code>cd backend && gradlew.bat run</code>).
      </div>
    );
  }

  if (!data) return <div className="text-sm text-zinc-500">Caricamento…</div>;

  const categories = Object.entries(data.byCategory).sort((a, b) => b[1] - a[1]);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">Oggi</h1>
        <p className="text-sm text-zinc-500 mt-1">
          Quello che è arrivato e quello che richiede la tua attenzione.
        </p>
      </div>

      <div className="stagger grid grid-cols-2 sm:grid-cols-3 gap-4">
        <StatCard label="Email ricevute oggi" value={data.emailsToday} icon={<MailIcon />} tone="indigo" />
        <StatCard label="Attività in attesa" value={data.pendingTasks} icon={<InboxIcon />} tone="zinc" />
        <StatCard label="Attività in ritardo" value={data.overdueTasks} icon={<AlertIcon />} tone="red" />
      </div>

      {categories.length > 0 && (
        <div>
          <h2 className="text-sm font-medium text-zinc-500 mb-3">Per categoria (oggi)</h2>
          <div className="flex flex-wrap gap-2">
            {categories.map(([cat, count]) => (
              <span
                key={cat}
                className="card inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 text-sm"
              >
                {CATEGORY_LABELS[cat] ?? cat}
                <span className="font-semibold tabular-nums">{count}</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {data.pendingTasks > 0 && (
        <Link href="/tasks" className="btn btn-primary">
          Rivedi {data.pendingTasks} attività in attesa →
        </Link>
      )}
    </div>
  );
}
