import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { errorMessage } from "./client";

/** An admin change: done toast, error toast, the queries under {@code key} refreshed. */
export function useAdminChange<T, R = unknown>(key: readonly unknown[], run: (input: T) => Promise<R>, done: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: () => { toast.success(done); void client.invalidateQueries({ queryKey: key }); },
    onError: async (error) => toast.error(await errorMessage(error)),
  });
}
