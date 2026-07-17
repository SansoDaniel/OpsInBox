const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

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
  if (!res.ok) throw new Error(`API ${res.status}`);
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
  if (!res.ok) throw new Error(`API ${res.status}`);
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
