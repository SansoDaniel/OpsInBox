"use client"; // Gli error boundary devono essere Client Component

import "./globals.css";

/**
 * Error boundary globale: si attiva solo se lancia il ROOT layout (app/layout.tsx),
 * caso in cui il normale app/error.tsx non è disponibile. Deve definire i propri
 * <html>/<body> e importare gli stili globali, perché sostituisce il root layout.
 *
 * Come app/error.tsx, in 16.2 la prop di recupero è `unstable_retry`; teniamo anche
 * `reset` per robustezza.
 */
export default function GlobalError({
  reset,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  reset?: () => void;
  unstable_retry?: () => void;
}) {
  const retry = unstable_retry ?? reset ?? (() => window.location.reload());

  return (
    <html lang="it">
      <body className="min-h-full flex flex-col">
        <main className="mx-auto w-full max-w-5xl px-4 py-8 flex-1 flex flex-col items-center justify-center text-center">
          <div className="card max-w-md p-8 space-y-4">
            <h1 className="text-xl font-semibold">Errore imprevisto</h1>
            <p className="text-sm text-zinc-500">
              L&apos;applicazione ha riscontrato un problema. Riprova o ricarica
              la pagina.
            </p>
            <div className="pt-2">
              <button onClick={() => retry()} className="btn btn-primary">
                Riprova
              </button>
            </div>
          </div>
        </main>
      </body>
    </html>
  );
}
