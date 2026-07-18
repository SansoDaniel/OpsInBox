// Origine dell'API. In sviluppo il default a localhost tiene la DX a configurazione zero;
// in produzione un fallback silenzioso a localhost è un bug (l'app chiamerebbe la macchina
// dell'utente). Se manca in prod, lo segnaliamo esplicitamente e usiamo un placeholder
// palesemente rotto così l'errore emerge subito invece di fallire in modo silenzioso.
function resolveApiUrl(): string {
  const configured = process.env.NEXT_PUBLIC_API_URL;
  if (configured) return configured;
  if (process.env.NODE_ENV === "production") {
    // NEXT_PUBLIC_* è inlined a build-time: questo warning compare nei log di build/runtime.
    console.error(
      "[OpsInbox] NEXT_PUBLIC_API_URL non impostato in produzione: le chiamate API falliranno. " +
        "Configura l'URL del backend prima del deploy.",
    );
    return "about:blank";
  }
  return "http://localhost:8080";
}

const API_URL = resolveApiUrl();

// Impostato dal Gate di autenticazione quando Auth0 è attivo; null in modalità dev.
let tokenGetter: (() => Promise<string | null>) | null = null;
export function setApiTokenGetter(fn: typeof tokenGetter) {
  tokenGetter = fn;
}

export type Dashboard = {
  emailsToday: number;
  byCategory: Record<string, number>;
  pendingTasks: number;
  overdueTasks: number;
};

export type Task = {
  id: string;
  title: string;
  description: string | null;
  type: string;
  priority: "low" | "medium" | "high";
  dueDate: string | null;
  status: string;
  createdAt: string;
  emailId: string | null;
  documentId: string | null;
};

export type Email = {
  id: string;
  fromAddress: string;
  fromName: string | null;
  subject: string | null;
  receivedAt: string;
  status: string;
  category: string | null;
  summary: string | null;
};

/**
 * Errore di una chiamata API. Espone lo status HTTP e, quando presente nel body,
 * il messaggio del backend e il `correlationId` (nuovo formato 500 del backend:
 * `{"error":"Errore interno del server","correlationId":"<uuid>"}`, vedi
 * docs/hardening-cycle-1.md). Gli altri errori restano `{"error":"..."}`.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Estrae `{ error, correlationId }` dal body; tollera risposte non-JSON o vuote. */
async function parseError(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { error?: string; correlationId?: string };
    return new ApiError(
      res.status,
      body?.error ?? `Errore API (${res.status})`,
      body?.correlationId,
    );
  } catch {
    return new ApiError(res.status, `Errore API (${res.status})`);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  if (tokenGetter) {
    const token = await tokenGetter();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(`${API_URL}${path}`, { ...init, headers, cache: "no-store" });
  if (!res.ok) throw await parseError(res);
  return res.json() as Promise<T>;
}

export type Me = {
  onboarded: boolean;
  user?: { email: string; name: string | null; role: string };
  company?: {
    id: string;
    name: string;
    vatNumber: string | null;
    inboundAddress: string | null;
  };
};

export type SearchFilter = {
  target?: string | null;
  docType?: string | null;
  counterpartyContains?: string | null;
  amountMin?: number | null;
  amountMax?: number | null;
  dueFrom?: string | null;
  dueTo?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
  openTasksOnly?: boolean | null;
  textContains?: string | null;
};

export type DocumentInfo = {
  id: string;
  docType: string;
  supplierName: string | null;
  customerName: string | null;
  documentNumber: string | null;
  docDate: string | null;
  dueDate: string | null;
  amount: number | null;
  currency: string;
  confidence: number | null;
};

export type SearchDocument = {
  document: DocumentInfo;
  emailId: string | null;
  emailSubject: string | null;
  fromAddress: string | null;
  hasOpenTask: boolean;
};

export type SearchResponse = {
  query: string;
  filter: SearchFilter;
  documents: SearchDocument[];
  emails: Email[];
  tasks: Task[];
};

export type AttachmentInfo = {
  id: string;
  filename: string;
  contentType: string | null;
  sizeBytes: number;
};

export type EmailDetail = {
  email: Email;
  bodyText: string | null;
  attachments: AttachmentInfo[];
  documents: DocumentInfo[];
  tasks: Task[];
};

export type Settings = {
  inboundAddress: string | null;
  notificationEmail: string | null;
  slackWebhookUrl: string | null;
  teamsWebhookUrl: string | null;
  whatsappNumber: string | null;
};

async function requestBlob(path: string): Promise<Blob> {
  const headers: Record<string, string> = {};
  if (tokenGetter) {
    const token = await tokenGetter();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(`${API_URL}${path}`, { headers, cache: "no-store" });
  if (!res.ok) throw await parseError(res);
  return res.blob();
}

export const api = {
  me: () => request<Me>("/api/me"),
  emailDetail: (id: string) => request<EmailDetail>(`/api/emails/${id}`),
  downloadAttachment: (id: string) => requestBlob(`/api/attachments/${id}`),
  settings: () => request<Settings>("/api/settings"),
  updateSettings: (data: {
    notificationEmail?: string;
    slackWebhookUrl?: string;
    teamsWebhookUrl?: string;
    whatsappNumber?: string;
  }) => request<Settings>("/api/settings", { method: "POST", body: JSON.stringify(data) }),
  search: (q: string) => request<SearchResponse>(`/api/search?q=${encodeURIComponent(q)}`),
  onboard: (data: { companyName: string; vatNumber?: string; email?: string; name?: string }) =>
    request<Me>("/api/onboarding", { method: "POST", body: JSON.stringify(data) }),
  dashboard: () => request<Dashboard>("/api/dashboard/today"),
  tasks: (status = "pending_approval") => request<Task[]>(`/api/tasks?status=${status}`),
  emails: () => request<Email[]>("/api/emails"),
  approveTask: (id: string) => request(`/api/tasks/${id}/approve`, { method: "POST" }),
  dismissTask: (id: string) => request(`/api/tasks/${id}/dismiss`, { method: "POST" }),
  doneTask: (id: string) => request(`/api/tasks/${id}/done`, { method: "POST" }),
};

export const CATEGORY_LABELS: Record<string, string> = {
  invoice: "Fattura",
  quote: "Preventivo",
  order: "Ordine",
  complaint: "Reclamo",
  customer_request: "Richiesta cliente",
  appointment: "Appuntamento",
  contract: "Contratto",
  delivery_note: "DDT",
  other: "Altro",
  unclassified: "Da classificare",
};

export const PRIORITY_LABELS: Record<string, string> = {
  low: "Bassa",
  medium: "Media",
  high: "Alta",
};
