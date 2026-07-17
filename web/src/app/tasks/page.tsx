"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, PRIORITY_LABELS, type Task } from "@/lib/api";

const FILTERS = [
  { value: "pending_approval", label: "In attesa" },
  { value: "approved", label: "Approvate" },
  { value: "done", label: "Completate" },
  { value: "all", label: "Tutte" },
] as const;

const PRIORITY_STYLES: Record<string, string> = {
  high: "bg-red-500/10 text-red-600 dark:text-red-400",
  medium: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  low: "bg-zinc-500/10 text-zinc-600 dark:text-zinc-400",
};

const PRIORITY_DOTS: Record<string, string> = {
  high: "bg-red-500",
  medium: "bg-amber-500",
  low: "bg-zinc-400",
};

export default function TasksPage() {
  const [filter, setFilter] = useState<string>("pending_approval");
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .tasks(filter)
      .then((t) => {
        setTasks(t);
        setError(false);
      })
      .catch(() => setError(true));
  }, [filter]);

  useEffect(load, [load]);

  async function act(id: string, action: "approve" | "dismiss" | "done") {
    setBusy(id);
    try {
      if (action === "approve") await api.approveTask(id);
      if (action === "dismiss") await api.dismissTask(id);
      if (action === "done") await api.doneTask(id);
      load();
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <h1 className="text-2xl font-semibold">Attività</h1>
        <div className="card flex gap-1 rounded-xl p-1">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => setFilter(f.value)}
              className={`btn rounded-lg px-3 py-1 text-sm ${
                filter === f.value
                  ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900 shadow-sm"
                  : "text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-100"
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {error && <div className="text-sm text-amber-600">Backend non raggiungibile.</div>}
      {tasks && tasks.length === 0 && (
        <div className="card rounded-2xl border-2 border-dashed border-transparent p-12 text-center text-sm text-zinc-500">
          Nessuna attività qui. Quando arriva un&apos;email, l&apos;AI proporrà le azioni da
          approvare con un click.
        </div>
      )}

      <ul key={filter} className="stagger space-y-3">
        {tasks?.map((task) => (
          <li
            key={task.id}
            className="card p-4 flex flex-col sm:flex-row sm:items-center gap-3"
          >
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${PRIORITY_STYLES[task.priority]}`}
                >
                  <span className={`h-1.5 w-1.5 rounded-full ${PRIORITY_DOTS[task.priority]}`} />
                  {PRIORITY_LABELS[task.priority] ?? task.priority}
                </span>
                {task.dueDate && (
                  <span className="text-xs text-zinc-500 tabular-nums">scadenza {task.dueDate}</span>
                )}
              </div>
              <div className="mt-1.5 font-medium">{task.title}</div>
              {task.description && (
                <div className="text-sm text-zinc-500 mt-0.5">{task.description}</div>
              )}
              {task.emailId && (
                <Link
                  href={`/emails/${task.emailId}`}
                  className="mt-1 inline-block text-xs text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100 underline underline-offset-2"
                >
                  Vedi email di origine →
                </Link>
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
    </div>
  );
}
