import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, type Approval, type CurrentUser, type Detail, errorMessage, type TimelineEvent, type WorkOrder } from "./client";

/** One hook per endpoint; live events invalidate these keys (see live.ts). */
export const keys = {
  user: ["user"] as const,
  workOrders: ["work-orders"] as const,
  workOrder: (id: string) => ["work-orders", id] as const,
  timeline: (id: string) => ["work-orders", id, "timeline"] as const,
  approvals: ["approvals"] as const,
};

export const useCurrentUser = () =>
  useQuery({ queryKey: keys.user, queryFn: () => api.get("user").json<CurrentUser>(), staleTime: Infinity });

/** Whether the user may (UI only: the API enforces it anyway). */
export function useCan(permission: string): boolean {
  return useCurrentUser().data?.permissions.includes(permission) ?? false;
}

export const useWorkOrders = () =>
  useQuery({ queryKey: keys.workOrders, queryFn: () => api.get("work-orders").json<WorkOrder[]>() });

export const useWorkOrder = (id: string) =>
  useQuery({ queryKey: keys.workOrder(id), queryFn: () => api.get(`work-orders/${id}`).json<Detail>() });

export const useTimeline = (id: string) =>
  useQuery({ queryKey: keys.timeline(id), queryFn: () => api.get(`work-orders/${id}/timeline`).json<TimelineEvent[]>() });

export const usePendingApprovals = () =>
  useQuery({ queryKey: keys.approvals, queryFn: () => api.get("approvals").json<Approval[]>() });

/** A change, its error shown as a toast; the live events refresh what it changed. */
function useChange<T, R = unknown>(run: (input: T) => Promise<R>, done: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: () => {
      toast.success(done);
      void client.invalidateQueries({ queryKey: keys.workOrders });
      void client.invalidateQueries({ queryKey: keys.approvals });
    },
    onError: async (error) => toast.error(await errorMessage(error)),
  });
}

export const useDecide = () =>
  useChange(
    ({ workOrderId, approvalId, decision, comment }: { workOrderId: string; approvalId: string; decision: "APPROVED" | "REJECTED"; comment?: string }) =>
      api.post(`work-orders/${workOrderId}/approvals/${approvalId}`, { json: { decision, comment } }),
    "Décision enregistrée",
  );

export const useCancel = () =>
  useChange((id: string) => api.post(`work-orders/${id}/cancel`), "Work order annulé");

export const useRework = () =>
  useChange(
    ({ id, instructions, step }: { id: string; instructions: string; step?: string }) =>
      api.post(`work-orders/${id}/rework`, { json: { instructions, step } }),
    "Reprise lancée",
  );

export const useCreateWorkOrder = () =>
  useChange(
    (input: { repository: string; title: string; description: string; workflow?: string }) =>
      api.post("work-orders", { json: input }).json<WorkOrder>(),
    "Work order créé",
  );
