import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { format } from "date-fns";
import { useState } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/evals")({ component: Evals });

type Result = { fixture: string; type: string; expect: string; workOrderId: string; status?: string; passed?: boolean };

/**
 * Fixture work orders pushed through a workflow, judged by their hidden tests: the success rate per
 * class feeds the autonomy policy. They cost real runs.
 */
function Evals() {
  const runs = useQuery({ queryKey: ["evals"], queryFn: () => api.get("eval-runs").json<Schemas["EvalRunView"][]>(), refetchInterval: 15_000 }).data;
  const workflows = useQuery({ queryKey: ["workflows"], queryFn: () => api.get("workflows").json<Schemas["VersionView"][]>() }).data;
  const [workflow, setWorkflow] = useState("default");
  const start = useAdminChange(["evals"], (name: string) => api.post("eval-runs", { json: { workflow: name } }), "Évaluation lancée");
  return (
    <section className="flex flex-col gap-4">
      <div className="flex gap-2">
        <select aria-label="Workflow évalué" value={workflow} onChange={(e) => setWorkflow(e.target.value)} className="rounded-md border bg-transparent px-2 py-1">
          {(workflows ?? [{ name: "default" }]).map((w) => <option key={w.name}>{w.name}</option>)}
        </select>
        <button onClick={() => confirm("Lancer une évaluation ? Ses runs sont facturés et comptent dans les budgets.") && start.mutate(workflow)}
                className="rounded-md border px-3 py-1.5 hover:bg-accent">Lancer une évaluation</button>
      </div>
      {runs?.map((run) => {
        const results = run.results as Result[];
        const byType = Object.entries(Object.groupBy(results, (r) => r.type));
        return (
          <article key={run.id} className="flex flex-col gap-2 rounded-lg border p-3">
            <header className="flex gap-3">
              <span className="font-medium">{run.workflow}</span>
              <span className="text-muted-foreground">{format(new Date(run.createdAt), "d MMM HH:mm")} · {run.createdBy}</span>
              <span className="ml-auto">{run.finished ? "Terminée" : "En cours"}</span>
            </header>
            {run.finished && (
              <div className="flex flex-wrap gap-4 text-xs">
                {byType.map(([type, rs]) => (
                  <span key={type}>{type} : {rs!.filter((r) => r.passed).length}/{rs!.length}</span>
                ))}
              </div>
            )}
            <table className="w-full text-left text-xs">
              <thead className="text-muted-foreground"><tr><th className="py-1">Fixture</th><th>Classe</th><th>Attendu</th><th>Statut</th><th>Réussi</th></tr></thead>
              <tbody>
                {results.map((r) => (
                  <tr key={r.workOrderId} className="border-t">
                    <td className="py-1"><Link to="/work-orders/$id" params={{ id: r.workOrderId }} className="hover:underline">{r.fixture}</Link></td>
                    <td>{r.type}</td><td>{r.expect}</td><td>{r.status ?? "…"}</td>
                    <td className={r.passed == null ? "" : r.passed ? "text-done" : "text-failed"}>{r.passed == null ? "…" : r.passed ? "oui" : "non"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </article>
        );
      })}
    </section>
  );
}
