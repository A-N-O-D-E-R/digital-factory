import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { format } from "date-fns";
import { useState } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/evals")({ component: Evals });

type Run = Schemas["EvalRunView"];
type Result = { fixture: string; type: string; expect: string; workOrderId: string; status?: string; passed?: boolean };

/**
 * Fixture work orders pushed through a workflow and judged: the complete suite by hidden tests (its
 * success per class feeds the autonomy policy), the fast one on classification and analysis. A
 * candidate settings overlay is tried the same way, compared task by task, and promoted if better.
 */
function Evals() {
  const runs = useQuery({ queryKey: ["evals"], queryFn: () => api.get("eval-runs").json<Run[]>(), refetchInterval: 15_000 }).data;
  const workflows = useQuery({ queryKey: ["workflows"], queryFn: () => api.get("workflows").json<Schemas["VersionView"][]>() }).data;
  const [workflow, setWorkflow] = useState("default");
  const [suite, setSuite] = useState("fast");
  const [candidate, setCandidate] = useState("");
  const [compareWith, setCompareWith] = useState<string>();
  const start = useAdminChange(["evals"], (body: unknown) => api.post("eval-runs", { json: body }), "Évaluation lancée");
  const promote = useAdminChange(["evals"], (id: string) => api.post(`eval-runs/${id}/promote`), "Candidat promu dans les paramètres globaux");
  const overlay = (() => { if (!candidate.trim()) return null; try { return JSON.parse(candidate) as unknown; } catch { return undefined; } })();
  const baseline = runs?.find((r) => r.id === compareWith);
  return (
    <section className="flex flex-col gap-4">
      <div className="grid max-w-4xl grid-cols-[auto_auto_1fr_auto] items-start gap-2">
        <select aria-label="Suite" value={suite} onChange={(e) => setSuite(e.target.value)} className="rounded-md border bg-transparent px-2 py-1">
          <option value="fast">Rapide (classification, analyse)</option>
          <option value="complete">Complète (tests cachés)</option>
        </select>
        <select aria-label="Workflow évalué" value={workflow} disabled={suite === "fast"} onChange={(e) => setWorkflow(e.target.value)}
                className="rounded-md border bg-transparent px-2 py-1">
          {(workflows ?? [{ name: "default" }]).map((w) => <option key={w.name}>{w.name}</option>)}
        </select>
        <textarea aria-label="Candidat" value={candidate} onChange={(e) => setCandidate(e.target.value)} spellCheck={false}
                  placeholder='Candidat (facultatif), ex. {"roles":{"classifier":{"model":"sonnet"}}}'
                  className="min-h-9 rounded-md border bg-transparent px-2 py-1 font-mono text-xs" />
        <button disabled={overlay === undefined}
                onClick={() => confirm("Lancer une évaluation ? Ses runs sont facturés et comptent dans les budgets.")
                  && start.mutate({ workflow: suite === "fast" ? undefined : workflow, suite, candidate: overlay })}
                className="rounded-md border px-3 py-1.5 hover:bg-accent disabled:opacity-50">Lancer</button>
      </div>
      {runs?.map((run) => (
        <EvalRun key={run.id} run={run} baseline={baseline?.id === run.id ? undefined : baseline}
                 compareWith={compareWith} setCompareWith={setCompareWith} promote={() => promote.mutate(run.id)} />
      ))}
    </section>
  );
}

function EvalRun({ run, baseline, compareWith, setCompareWith, promote }: {
  run: Run; baseline?: Run; compareWith?: string; setCompareWith: (id: string) => void; promote: () => void;
}) {
  const results = run.results as Result[];
  const before = new Map(((baseline?.results ?? []) as Result[]).map((r) => [r.fixture, r]));
  const byType = Object.entries(Object.groupBy(results, (r) => r.type));
  return (
    <article className="flex flex-col gap-2 rounded-lg border p-3">
      <header className="flex flex-wrap items-center gap-3">
        <span className="font-medium">{run.suite === "fast" ? "Rapide" : "Complète"} · {run.workflow}</span>
        {run.candidate && <code className="rounded bg-muted px-1 font-mono text-xs">{run.candidate}</code>}
        <span className="text-muted-foreground">{format(new Date(run.createdAt), "d MMM HH:mm")} · {run.createdBy}</span>
        <span className="ml-auto">{run.finished ? "Terminée" : "En cours"}</span>
        <button onClick={() => setCompareWith(run.id)} className={`rounded-md border px-2 py-0.5 text-xs hover:bg-accent ${compareWith === run.id ? "bg-accent" : ""}`}>
          Référence
        </button>
        {run.finished && run.candidate && (
          <button onClick={() => confirm("Promouvoir ce candidat dans les paramètres globaux ?") && promote()}
                  className="rounded-md bg-foreground px-2 py-0.5 text-xs text-background">Promouvoir</button>
        )}
      </header>
      {run.finished && (
        <div className="flex flex-wrap gap-4 text-xs">
          {byType.map(([type, rs]) => <span key={type}>{type} : {rs!.filter((r) => r.passed).length}/{rs!.length}</span>)}
        </div>
      )}
      <table className="w-full text-left text-xs">
        <thead className="text-muted-foreground">
          <tr><th className="py-1">Fixture</th><th>Classe</th><th>Attendu</th><th>Statut</th><th>Réussi</th>{baseline && <th>Référence</th>}</tr>
        </thead>
        <tbody>
          {results.map((r) => {
            const was = before.get(r.fixture)?.passed;
            const moved = baseline && was != null && r.passed != null && was !== r.passed;
            return (
              <tr key={r.workOrderId} className="border-t">
                <td className="py-1"><Link to="/work-orders/$id" params={{ id: r.workOrderId }} className="hover:underline">{r.fixture}</Link></td>
                <td>{r.type}</td><td>{r.expect}</td><td>{r.status ?? "…"}</td>
                <td className={r.passed == null ? "" : r.passed ? "text-done" : "text-failed"}>{r.passed == null ? "…" : r.passed ? "oui" : "non"}</td>
                {baseline && (
                  <td className={moved ? (r.passed ? "text-done" : "text-failed") : "text-muted-foreground"}>
                    {was == null ? "—" : was ? "oui" : "non"}{moved && (r.passed ? " ↑" : " ↓")}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </article>
  );
}
