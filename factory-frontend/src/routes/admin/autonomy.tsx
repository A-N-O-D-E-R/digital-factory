import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";

export const Route = createFileRoute("/admin/autonomy")({ component: Autonomy });

type Policy = Schemas["PolicyView"];

const percent = (rate: number | null) => (rate == null ? "—" : `${Math.round(rate * 100)} %`);

/** Where the factory stops per class: merging is earned (evals) and taken back (closed PRs). */
function Autonomy() {
  const client = useQueryClient();
  const policies = useQuery({ queryKey: ["autonomy"], queryFn: () => api.get("autonomy-policy").json<Policy[]>() });
  const grant = useMutation({
    mutationFn: ({ type, ...body }: Pick<Policy, "type" | "level" | "minSuccessRate" | "maxFailureRate">) =>
      api.put(`autonomy-policy/${type}`, { json: body }).json<Policy>(),
    onSuccess: () => { toast.success("Politique enregistrée"); void client.invalidateQueries({ queryKey: ["autonomy"] }); },
    onError: async (error) => toast.error(await errorMessage(error)),
  });
  return (
    <table className="w-full max-w-5xl text-left text-xs">
      <thead className="text-muted-foreground">
        <tr><th className="py-1">Classe</th><th>Niveau</th><th>Succès mesuré / requis</th><th>Échecs réels / maximum</th><th>Par</th></tr>
      </thead>
      <tbody>
        {policies.data?.map((p) => (
          <tr key={p.type} className="border-t">
            <td className="py-1.5 font-mono">{p.type}</td>
            <td>
              <select aria-label={`Niveau ${p.type}`} value={p.level} className="rounded-md border bg-transparent px-1 py-0.5"
                      onChange={(e) => grant.mutate({ type: p.type, level: e.target.value as Policy["level"],
                        minSuccessRate: p.minSuccessRate ?? 0.9, maxFailureRate: p.maxFailureRate ?? 0.25 })}>
                <option value="DRAFT_PR">PR brouillon</option>
                <option value="MERGE">Fusion</option>
              </select>
            </td>
            <td>{percent(p.successRate)} / {percent(p.minSuccessRate)}</td>
            <td>{percent(p.failureRate)} / {percent(p.maxFailureRate)}</td>
            <td className="text-muted-foreground">{p.updatedBy ?? "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
