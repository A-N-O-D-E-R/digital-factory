import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
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
      const { workOrderId: id } = JSON.parse(event.data) as { workOrderId: string };
      void client.invalidateQueries({ queryKey: workOrderId ? keys.workOrder(id) : keys.workOrders });
      if (!workOrderId && event.type === "approval") {
        void client.invalidateQueries({ queryKey: keys.approvals });
      }
    };
    for (const type of ["work-order", "run", "approval", "pr-event"]) {
      source.addEventListener(type, refresh);
    }
    return () => source.close();
  }, [client, workOrderId]);
}
