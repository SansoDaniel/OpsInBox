"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { UserArea } from "@/lib/auth";

const NAV = [
  { href: "/", label: "Dashboard" },
  { href: "/tasks", label: "Attività" },
  { href: "/emails", label: "Email" },
  { href: "/search", label: "Ricerca" },
  { href: "/settings", label: "Impostazioni" },
];

export function Header() {
  const pathname = usePathname();

  function isActive(href: string): boolean {
    if (href === "/") return pathname === "/";
    return pathname === href || pathname.startsWith(`${href}/`);
  }

  return (
    <header className="header-material">
      <div className="mx-auto max-w-5xl px-4 h-14 flex items-center gap-6">
        <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <span className="brand-dot" aria-hidden />
          OpsInbox
        </Link>
        <nav className="nav-scroll flex gap-1 text-sm flex-1 overflow-x-auto">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={`nav-link whitespace-nowrap ${isActive(item.href) ? "nav-link-active" : ""}`}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <UserArea />
      </div>
    </header>
  );
}
