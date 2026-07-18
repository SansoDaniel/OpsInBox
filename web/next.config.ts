import type { NextConfig } from "next";

/**
 * Hardening ciclo 1 — S7 (header di sicurezza del sito, lato frontend).
 *
 * La CSP è costruita dagli env così vale sia in dev sia in prod senza rompere l'app:
 * - `connect-src` deve includere l'origine dell'API (le pagine chiamano il backend via
 *   fetch) e, quando l'auth è attiva, il dominio Auth0 (login + scambio token).
 * - Next e Tailwind iniettano stili inline → serve `style-src 'unsafe-inline'`.
 * - In sviluppo React usa `eval` per lo stack degli errori → `script-src 'unsafe-eval'`
 *   SOLO in dev; in produzione non è ammesso.
 *
 * Approccio senza nonce (vedi node_modules/next/dist/docs/.../content-security-policy.md,
 * sezione "Without Nonces"): non richiede un proxy/middleware né il rendering dinamico
 * forzato di ogni pagina. Adeguato a una SPA client-side con font auto-hostati.
 */

const isDev = process.env.NODE_ENV !== "production";

// Origine dell'API: stesso default del client (web/src/lib/api.ts).
const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** Estrae l'origine (schema://host:porta) da un URL; null se non parsabile. */
function toOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    return new URL(value).origin;
  } catch {
    // Potrebbe essere solo un host (es. "tenant.eu.auth0.com"): normalizza a https.
    try {
      return new URL(`https://${value}`).origin;
    } catch {
      return null;
    }
  }
}

const apiOrigin = toOrigin(API_URL);
// Dominio Auth0 (login, /authorize, /oauth/token, JWKS): serve in connect-src quando l'auth è attiva.
const auth0Origin = toOrigin(process.env.NEXT_PUBLIC_AUTH0_DOMAIN);

// connect-src: self + API + (eventuale) Auth0. Dedup e filtro dei null.
const connectSrc = Array.from(
  new Set(["'self'", apiOrigin, auth0Origin].filter(Boolean) as string[]),
);

// I font Geist sono auto-hostati da next/font (serviti da 'self'). Manteniamo comunque
// i domini Google per robustezza in caso di fallback CSS/fonts (costo nullo).
const csp = [
  `default-src 'self'`,
  // 'unsafe-inline' per gli script di bootstrap inline di Next; 'unsafe-eval' solo in dev (React).
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""}`,
  // Next/Tailwind iniettano stili inline; fonts.googleapis.com per eventuale CSS dei font.
  `style-src 'self' 'unsafe-inline' https://fonts.googleapis.com`,
  // blob: per i download degli allegati; data: e https: per gli avatar utente (Auth0).
  `img-src 'self' blob: data: https:`,
  `font-src 'self' https://fonts.gstatic.com`,
  `connect-src ${connectSrc.join(" ")}`,
  `frame-src 'self'`,
  `object-src 'none'`,
  `base-uri 'self'`,
  `form-action 'self'`,
  // Equivalente moderno di X-Frame-Options: DENY.
  `frame-ancestors 'none'`,
  // In produzione forza HTTPS sulle sotto-risorse; in dev lo omettiamo (http://localhost).
  ...(isDev ? [] : ["upgrade-insecure-requests"]),
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: csp },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), browsing-topics=()",
  },
  // HSTS solo in produzione (in dev l'app gira su http://localhost).
  ...(isDev
    ? []
    : [
        {
          key: "Strict-Transport-Security",
          value: "max-age=63072000; includeSubDomains; preload",
        },
      ]),
];

const nextConfig: NextConfig = {
  reactCompiler: true,
  // Non esporre la versione di Next nell'header X-Powered-By.
  poweredByHeader: false,
  async headers() {
    return [
      {
        // Applica gli header di sicurezza a ogni risposta del sito.
        source: "/:path*",
        headers: securityHeaders,
      },
    ];
  },
};

export default nextConfig;
