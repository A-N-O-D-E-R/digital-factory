import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";
import { NativeSelect } from "@/components/ui/native-select";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";

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
    <Table className="w-full max-w-5xl text-left text-xs">
      <TableHeader className="text-muted-foreground">
        <TableRow><TableHead className="py-1">Classe</TableHead><TableHead>Niveau</TableHead><TableHead>Succès mesuré / requis</TableHead><TableHead>Échecs réels / maximum</TableHead><TableHead>Par</TableHead></TableRow>
      </TableHeader>
      <TableBody>
        {policies.data?.map((p) => (
          <TableRow key={p.type} className="border-t">
            <TableCell className="py-1.5 font-mono">{p.type}</TableCell>
            <TableCell>
              <NativeSelect size="sm" aria-label={`Niveau ${p.type}`} value={p.level}
                      onChange={(e) => grant.mutate({ type: p.type, level: e.target.value as Policy["level"],
                        minSuccessRate: p.minSuccessRate ?? 0.9, maxFailureRate: p.maxFailureRate ?? 0.25 })}>
                <option value="DRAFT_PR">PR brouillon</option>
                <option value="MERGE">Fusion</option>
              </NativeSelect>
            </TableCell>
            <TableCell>{percent(p.successRate)} / {percent(p.minSuccessRate)}</TableCell>
            <TableCell>{percent(p.failureRate)} / {percent(p.maxFailureRate)}</TableCell>
            <TableCell className="text-muted-foreground">{p.updatedBy ?? "—"}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
