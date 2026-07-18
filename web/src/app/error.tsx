"use client"; // Gli error boundary devono essere Client Component

import { useEffect } from "react";
import { ApiError } from "@/lib/api";

/**
 * Error boundary a livello di route (Next 16.2). Avvolge le pagine sotto il root
 * layout: se il rendering lancia, mostra questa UI di fallback con recupero.
 *
 * Next 16.2 introduce `unstable_retry` (ri-fetch + ri-render del segmento) al posto
 * di `reset`. Manteniamo entrambe le prop per robustezza: usiamo `unstable_retry`
 * se disponibile, altrimenti `reset`.
 */
export default function Error({
  error,
  reset,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  reset?: () => void;
  unstable_retry?: () => void;
}) {
  useEffect(() => {
    // In un ambiente reale qui si loggerebbe verso un servizio di monitoraggio.
    console.error(error);
  }, [error]);

  const retry = unstable_retry ?? reset ?? (() => window.location.reload());

  // Se è un ApiError con correlationId (500 dal backend), mostralo per il supporto.
  const correlationId = error instanceof ApiError ? error.correlationId : undefined;

  return (
    <div className="page-enter flex flex-1 flex-col items-center justify-center gap-5 py-24 text-center">
      <div className="card max-w-md p-8 space-y-4">
        <h1 className="text-xl font-semibold">Qualcosa è andato storto</h1>
        <p className="text-sm text-zinc-500">
          Si è verificato un errore imprevisto. Puoi riprovare; se il problema
          persiste, ricarica la pagina.
        </p>
        {correlationId && (
          <p className="text-xs text-zinc-400">
            Codice di riferimento per il supporto:{" "}
            <code className="select-all">{correlationId}</code>
          </p>
        )}
        <div className="flex items-center justify-center gap-3 pt-2">
          <button onClick={() => retry()} className="btn btn-primary">
            Riprova
          </button>
          <a href="/" className="btn btn-ghost">
            Torna alla dashboard
          </a>
        </div>
      </div>
    </div>
  );
}
