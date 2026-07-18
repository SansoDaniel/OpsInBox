import Link from "next/link";

/**
 * Pagina 404. Il root app/not-found.tsx gestisce sia gli URL non mappati sia le
 * chiamate esplicite a notFound(). Renderizzata dentro il root layout (header + main),
 * quindi eredita navigazione e design system.
 */
export default function NotFound() {
  return (
    <div className="page-enter flex flex-1 flex-col items-center justify-center gap-5 py-24 text-center">
      <div className="card max-w-md p-8 space-y-4">
        <span className="brand-dot mx-auto scale-[1.6]" aria-hidden />
        <h1 className="text-xl font-semibold">Pagina non trovata</h1>
        <p className="text-sm text-zinc-500">
          La pagina che cerchi non esiste o è stata spostata.
        </p>
        <div className="pt-2">
          <Link href="/" className="btn btn-primary">
            Torna alla dashboard
          </Link>
        </div>
      </div>
    </div>
  );
}
