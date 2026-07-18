import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Header } from "@/components/Header";
import { Providers } from "@/lib/auth";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "OpsInbox – AI Operations Inbox",
  description:
    "Ogni email diventa un evento di business strutturato con azioni consigliate.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="it"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <a href="#contenuto" className="skip-link">
          Salta al contenuto
        </a>
        <Providers>
          <Header />
          <main
            id="contenuto"
            className="page-enter mx-auto w-full max-w-5xl px-4 py-8 flex-1 flex flex-col"
          >
            {children}
          </main>
        </Providers>
      </body>
    </html>
  );
}
