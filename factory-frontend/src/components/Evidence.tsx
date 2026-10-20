import { useQuery } from "@tanstack/react-query";
import { api, type Run, type Schemas } from "../api/client";
import { splitDiff, type Side } from "../lib/diff";

type Suite = { total: number; failed: number; errors: number; skipped: number; failures?: string[] };
type TestEvidence = { diff: { files: number; insertions: number; deletions: number }; patch?: string; tests: Suite; acceptance?: Suite };
type Verdict = { verdict: "PASS" | "REVISE"; summary: string; comments: { path?: string; line?: number; body: string }[] };

function useOutput<T>(workOrderId: string, run: Run | undefined) {
  return useQuery({
    queryKey: ["work-orders", workOrderId, "runs", run?.id],
    queryFn: async () => (await api.get(`work-orders/${workOrderId}/runs/${run!.id}`).json<Schemas["RunOutput"]>()).output as T,
    enabled: run != null,
    staleTime: Infinity, // a finished run's output never changes
  });
}

/** What the factory proved, side by side: the diff, tests and acceptance tests, the reviewers' verdicts. */
export function Evidence({ workOrderId, runs }: { workOrderId: string; runs: Run[] }) {
  const succeeded = runs.filter((r) => r.status === "SUCCEEDED");
  const latestTests = succeeded.filter((r) => r.task === "run-tests").at(-1);
  const lastVisit = (task: (r: Run) => boolean) => {
    const matching = succeeded.filter(task);
    const last = matching.at(-1);
    return matching.filter((r) => last && r.stepId === last.stepId && r.visit === last.visit);
  };
  const reviews = lastVisit((r) => r.kind === "AGENT" && (r.task === "reviewer" || r.task.endsWith("reviewer")));
  const evidence = useOutput<TestEvidence>(workOrderId, latestTests);
  if (!latestTests && reviews.length === 0) {
    return null;
  }
  return (
    <section className="flex flex-col gap-3">
      <h2 className="font-medium">Preuves</h2>
      {evidence.data && (
        <div className="flex flex-wrap gap-6 text-xs">
          <span>{evidence.data.diff.files} fichier(s), <span className="text-done">+{evidence.data.diff.insertions}</span>{" "}
            <span className="text-failed">−{evidence.data.diff.deletions}</span></span>
          <SuiteSummary name="Tests" suite={evidence.data.tests} />
          {evidence.data.acceptance && <SuiteSummary name="Acceptation" suite={evidence.data.acceptance} />}
        </div>
      )}
      {reviews.map((run) => <Review key={run.id} workOrderId={workOrderId} run={run} />)}
      {evidence.data?.patch && <SplitDiff patch={evidence.data.patch} />}
    </section>
  );
}

function SuiteSummary({ name, suite }: { name: string; suite: Suite }) {
  const red = suite.failed + suite.errors > 0;
  return (
    <div className="flex flex-col">
      <span className={red ? "text-failed" : "text-done"}>
        {name} : {suite.total - suite.failed - suite.errors - suite.skipped}/{suite.total} ok
        {suite.skipped > 0 && `, ${suite.skipped} ignoré(s)`}
      </span>
      {suite.failures?.map((f) => <span key={f} className="font-mono text-muted-foreground">{f}</span>)}
    </div>
  );
}

function Review({ workOrderId, run }: { workOrderId: string; run: Run }) {
  const review = useOutput<Verdict>(workOrderId, run).data;
  if (!review) {
    return null;
  }
  return (
    <div className="flex flex-col gap-1 rounded-lg border p-3">
      <div className="flex gap-2">
        <span className="font-medium">{run.task}</span>
        <span className={review.verdict === "PASS" ? "text-done" : "text-blocked"}>{review.verdict === "PASS" ? "Validé" : "À revoir"}</span>
      </div>
      <p className="whitespace-pre-wrap text-muted-foreground">{review.summary}</p>
      {review.comments.map((c, i) => (
        <p key={i} className="text-xs"><span className="font-mono">{c.path}{c.line ? `:${c.line}` : ""}</span> {c.body}</p>
      ))}
    </div>
  );
}

const SIDE_COLORS = { removed: "bg-failed/15", added: "bg-done/15", context: "" };

function Cell({ side }: { side: Side | null }) {
  return (
    <>
      <td className="w-10 select-none pr-2 text-right align-top text-muted-foreground">{side?.number}</td>
      <td className={`whitespace-pre-wrap break-all align-top ${side ? SIDE_COLORS[side.kind] : "bg-muted"}`}>{side?.text}</td>
    </>
  );
}

// ponytail: own side-by-side rendering of the unified patch; Monaco's diff editor if syntax colouring is missed
function SplitDiff({ patch }: { patch: string }) {
  return (
    <div className="flex flex-col gap-3">
      {splitDiff(patch).map((file) => (
        <details key={file.path} open className="overflow-hidden rounded-lg border">
          <summary className="cursor-pointer bg-muted px-3 py-1.5 font-mono text-xs">{file.path}</summary>
          <table className="w-full table-fixed font-mono text-xs">
            <tbody>
              {file.rows.map((row, i) => "hunk" in row ? (
                <tr key={i}><td colSpan={4} className="bg-accent px-2 py-0.5 text-muted-foreground">{row.hunk}</td></tr>
              ) : (
                <tr key={i}><Cell side={row.left} /><Cell side={row.right} /></tr>
              ))}
            </tbody>
          </table>
        </details>
      ))}
    </div>
  );
}

type Analysis = { valid: boolean; reproduced: boolean | null; plan: string; acceptanceCriteria: string[] };

/** The analyst's plan and acceptance criteria: what a plan gate approves, what the work was asked to do. */
export function Plan({ workOrderId, runs }: { workOrderId: string; runs: Run[] }) {
  const analysis = useOutput<Analysis>(workOrderId, runs.filter((r) => r.task === "analyst" && r.status === "SUCCEEDED").at(-1)).data;
  if (!analysis) {
    return null;
  }
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-medium">Plan</h2>
      {!analysis.valid && <p className="text-blocked">L'analyste juge la demande invalide.</p>}
      {analysis.reproduced === false && <p className="text-blocked">Le bug n'a pas été reproduit.</p>}
      <p className="whitespace-pre-wrap">{analysis.plan}</p>
      <ul className="list-disc pl-5 text-muted-foreground">
        {analysis.acceptanceCriteria.map((c) => <li key={c}>{c}</li>)}
      </ul>
    </section>
  );
}

