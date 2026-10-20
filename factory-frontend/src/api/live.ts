import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { toast } from "sonner";
import { keys } from "./queries";

/**
 * Server-sent events refresh what they are about: /api/events for lists and boards, a work order's
 * own stream for its page. EventSource reconnects by itself, sending Last-Event-ID.
 */
export function useLiveEvents(workOrderId?: string) {
  const client = useQueryClient();
  useEffect(() => {
    const source = new EventSource(workOrderId ? `/api/work-orders/${workOrderId}/events` : "/api/events", {
      withCredentials: true,
    });
    const refresh = (event: MessageEvent) => {
      const { workOrderId: id, data } = JSON.parse(event.data) as { workOrderId: string; data: Record<string, unknown> };
      if (!workOrderId && event.type === "approval" && data.decision === "PENDING") {
        // stays until dismissed: a gate waiting on you must not be missed
        toast.warning(`Une barrière « ${String(data.gate)} » vous attend`, {
          id: `gate-${String(data.approval)}`,
          duration: Infinity,
          action: { label: "Voir", onClick: () => window.location.assign(`/work-orders/${id}`) },
        });
      }
      void client.invalidateQueries({ queryKey: workOrderId ? keys.workOrder(id) : keys.workOrders });
      if (!workOrderId && event.type === "approval") {
        void client.invalidateQueries({ queryKey: keys.approvals });
      }
    };
    for (const type of ["work-order", "run", "approval"]) {
      source.addEventListener(type, refresh);
    }
    return () => source.close();
  }, [client, workOrderId]);
}
