"use client";

import { Auth0Provider, useAuth0 } from "@auth0/auth0-react";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, setApiTokenGetter } from "@/lib/api";

const AUTH0_DOMAIN = process.env.NEXT_PUBLIC_AUTH0_DOMAIN;
const AUTH0_CLIENT_ID = process.env.NEXT_PUBLIC_AUTH0_CLIENT_ID;
const AUTH0_AUDIENCE = process.env.NEXT_PUBLIC_AUTH0_AUDIENCE;

/** Senza variabili Auth0 l'app gira in modalità dev, senza login (come il backend). */
export const authEnabled = Boolean(AUTH0_DOMAIN && AUTH0_CLIENT_ID);

function Splash({ text }: { text: string }) {
  return (
    <div className="flex flex-1 items-center justify-center py-24 text-sm text-zinc-500">
      {text}
    </div>
  );
}

export function Providers({ children }: { children: React.ReactNode }) {
  if (!authEnabled) return <>{children}</>;
  return (
    <Auth0Provider
      domain={AUTH0_DOMAIN!}
      clientId={AUTH0_CLIENT_ID!}
      cacheLocation="localstorage"
      authorizationParams={{
        redirect_uri: typeof window !== "undefined" ? window.location.origin : "",
        audience: AUTH0_AUDIENCE,
      }}
    >
      <Gate>{children}</Gate>
    </Auth0Provider>
  );
}

/** Blocca l'app finché il token non è disponibile; mostra il login se serve. */
function Gate({ children }: { children: React.ReactNode }) {
  const { isLoading, isAuthenticated, loginWithRedirect, getAccessTokenSilently } = useAuth0();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (isLoading) return;
    setApiTokenGetter(
      isAuthenticated ? () => getAccessTokenSilently().catch(() => null) : null,
    );
    setReady(true);
  }, [isLoading, isAuthenticated, getAccessTokenSilently]);

  if (isLoading || !ready) return <Splash text="Caricamento…" />;

  if (!isAuthenticated) {
    return (
      <div className="page-enter flex flex-1 flex-col items-center justify-center gap-5 py-24">
        <span className="brand-dot scale-[2.2]" aria-hidden />
        <h1 className="text-3xl font-semibold">OpsInbox</h1>
        <p className="text-sm text-zinc-500 max-w-sm text-center">
          Ogni email diventa un evento di business con azioni consigliate. Accedi
          per vedere la tua inbox operativa.
        </p>
        <button onClick={() => loginWithRedirect()} className="btn btn-primary px-6">
          Accedi
        </button>
      </div>
    );
  }

  return <OnboardingCheck>{children}</OnboardingCheck>;
}

/** Se l'utente autenticato non ha ancora un'azienda, lo porta all'onboarding. */
function OnboardingCheck({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [status, setStatus] = useState<"loading" | "ok" | "needs">("loading");

  useEffect(() => {
    api
      .me()
      .then((me) => setStatus(me.onboarded ? "ok" : "needs"))
      // backend giù: lascia passare, le pagine mostrano il proprio stato di errore
      .catch(() => setStatus("ok"));
  }, []);

  useEffect(() => {
    if (status === "needs" && pathname !== "/onboarding") {
      router.replace("/onboarding");
    }
  }, [status, pathname, router]);

  if (status === "loading") return <Splash text="Caricamento…" />;
  if (status === "needs" && pathname !== "/onboarding") return <Splash text="Reindirizzamento…" />;
  return <>{children}</>;
}

/** Email utente + logout nell'header; null in modalità dev. */
export function UserArea() {
  if (!authEnabled) {
    return <span className="text-xs text-zinc-400">modalità dev</span>;
  }
  return <UserAreaInner />;
}

function UserAreaInner() {
  const { user, isAuthenticated, logout } = useAuth0();
  if (!isAuthenticated) return null;
  return (
    <div className="flex items-center gap-3 text-sm">
      <span className="text-zinc-500 hidden sm:inline">{user?.email}</span>
      <button
        onClick={() =>
          logout({ logoutParams: { returnTo: window.location.origin } })
        }
        className="text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-100"
      >
        Esci
      </button>
    </div>
  );
}
