import { Link } from "@tanstack/react-router";
import { Bell, X } from "lucide-react";
import { useState } from "react";
import { usePendingApprovals, useWorkOrders } from "../api/queries";
import { GATE_LABELS } from "../lib/status";

/**
 * The gates waiting for a decision, stacked until decided: from the server (they survive a reload),
 * kept fresh by the live events. Hiding one only hides it here, for this page's life.
 */
export function GateNotifications() {
  const approvals = usePendingApprovals().data ?? [];
  const titles = new Map((useWorkOrders().data ?? []).map((w) => [w.id, w.title]));
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const shown = approvals.filter((a) => !hidden.has(a.id));
  if (shown.length === 0) {
    return null;
  }
  return (
    <aside aria-label="Barrières en attente" className="fixed bottom-4 left-4 z-10 flex w-80 flex-col gap-2">
      {shown.slice(0, 4).map((a) => (
        <div key={a.id} className="flex items-start gap-2 rounded-lg border border-waiting bg-background p-3 shadow-lg">
          <Bell className="mt-0.5 size-4 shrink-0 text-waiting" />
          <Link to="/work-orders/$id" params={{ id: a.workOrderId }} className="flex-1 hover:underline">
            <span className="block font-medium">Barrière « {GATE_LABELS[a.gate] ?? a.gate} »</span>
            <span className="text-muted-foreground">{titles.get(a.workOrderId) ?? a.workOrderId}</span>
          </Link>
          <button aria-label="Masquer" onClick={() => setHidden(new Set(hidden).add(a.id))} className="rounded p-0.5 hover:bg-accent">
            <X className="size-3.5" />
          </button>
        </div>
      ))}
      {shown.length > 4 && <Link to="/approvals" className="text-xs text-muted-foreground hover:underline">et {shown.length - 4} autre(s)…</Link>}
    </aside>
  );
}
