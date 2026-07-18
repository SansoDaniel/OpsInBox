/**
 * Componenti condivisi per gli stati di caricamento / errore / vuoto.
 * Uniformano l'aspetto tra dashboard, attività, email, ricerca e impostazioni,
 * riusando il design system in globals.css (.card ecc.). Non introducono nuovi stili.
 */

/** Stato di caricamento coerente (testo neutro con aria-live per gli screen reader). */
export function LoadingState({ label = "Caricamento…" }: { label?: string }) {
  return (
    <div className="text-sm text-zinc-500" role="status" aria-live="polite">
      {label}
    </div>
  );
}

/** Stato di errore "backend non raggiungibile" coerente su tutte le pagine di lista. */
export function ErrorState({
  message = "Backend non raggiungibile.",
}: {
  message?: string;
}) {
  return (
    <div className="text-sm text-amber-600" role="alert">
      {message}
    </div>
  );
}

/** Stato vuoto coerente (card tratteggiata con messaggio contestuale). */
export function EmptyState({ children }: { children: React.ReactNode }) {
  return (
    <div className="card rounded-2xl p-12 text-center text-sm text-zinc-500">
      {children}
    </div>
  );
}
