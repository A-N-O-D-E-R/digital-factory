import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";
import { useCurrentUser } from "../../api/queries";
import { StepPanel } from "../../components/StepPanel";
import { WorkflowCanvas } from "../../components/WorkflowCanvas";
import { addStep, blank, connect, layout, removeStep, updateStep, type WorkflowDefinition } from "../../lib/workflow";

export const Route = createFileRoute("/workflows/$name")({ component: Workflow });

/**
 * A workflow's versions (immutable) and an editor drawn as a graph (or as JSON): validate, dry-run with what each
 * step would learn, publish as a new version with a note. A name without versions starts a new workflow.
 */
function Workflow() {
  const { name } = Route.useParams();
  const history = useQuery({ queryKey: ["workflows", name], queryFn: () => api.get(`workflows/${name}/versions`).json<Schemas["VersionView"][]>() });
  const [selected, setSelected] = useState<number>();
  const version = history.data?.find((v) => v.version === selected) ?? history.data?.[0]; // newest first
  if (!history.data) {
    return <p className="p-6 text-muted-foreground">{history.isError ? "Workflow introuvable." : "Chargement…"}</p>;
  }
  // a fresh editor per version: it starts from that version's definition
  return <Editor key={version?.version ?? 0} name={name} version={version} versions={history.data} select={setSelected} />;
}

function Editor({ name, version, versions, select }: {
  name: string; version?: Schemas["VersionView"]; versions: Schemas["VersionView"][]; select: (version: number) => void;
}) {
  const client = useQueryClient();
  const admin = useCurrentUser().data?.roles.includes("ADMIN") ?? false;
  const published = version ? (version.definition as unknown as WorkflowDefinition) : undefined;
  const [definition, setDefinition] = useState<WorkflowDefinition>(() => published ?? blank());
  // ponytail: positions live only in the page (the schema has no place for them); laid out again on each visit
  const [at, setAt] = useState(() => layout(definition));
  const [step, setStep] = useState<string>();
  const [view, setView] = useState<"graph" | "json">("graph");
  const [text, setText] = useState("");
  const [learnt, setLearnt] = useState('{\n  "verify": [{ "tests.passed": true }]\n}');
  const [note, setNote] = useState(version ? "" : "Création");
  const [result, setResult] = useState<{ errors: string[]; path?: Schemas["Path"] }>();
  const dirty = JSON.stringify(definition) !== JSON.stringify(published);

  const edit = (next: WorkflowDefinition, renamed?: [string, string]) => {
    if (renamed) { setAt(({ [renamed[0]]: p, ...rest }) => ({ ...rest, [renamed[1]]: p })); setStep(renamed[1]); }
    setDefinition(next);
    setResult(undefined);
  };
  const add = (kind: "agent" | "factory" | "gate") => {
    const [next, id] = addStep(definition, kind);
    const near = (step && at[step]) || { x: 0, y: Math.max(0, ...Object.values(at).map((p) => p.y)) };
    setAt({ ...at, [id]: { x: near.x + (step ? 280 : 0), y: near.y + (step ? 0 : 130) } });
    edit(next);
    setStep(id);
  };
  const remove = (id: string) => { edit(removeStep(definition, id)); setStep(undefined); };

  const parse = (value: string) => { try { return JSON.parse(value); } catch { return undefined; } };
  const check = useMutation({
    mutationFn: async (dryRun: boolean) => {
      if (!dryRun) return { errors: (await api.post("workflows/validate", { json: definition }).json<Schemas["ValidationView"]>()).errors };
      const run = await api.post("workflows/dry-run", { json: { definition, facts: {}, learnt: parse(learnt) ?? {} } }).json<Schemas["DryRunView"]>();
      return { errors: run.errors, path: run.result ?? undefined };
    },
    onSuccess: setResult,
    onError: async (e) => toast.error(await errorMessage(e)),
  });
  const publish = useMutation({
    mutationFn: () => api.post(`workflows/${name}/versions`, { json: { definition, note } }).json<Schemas["VersionView"]>(),
    onSuccess: (v) => { toast.success(`Version ${v.version} publiée`); setNote(""); select(v.version);
      void client.invalidateQueries({ queryKey: ["workflows"] }); },
    onError: async (e) => toast.error(await errorMessage(e)),
  });

  // the simulated path, lit on the canvas: each visit's step to where it went
  const walked = new Set(result?.path?.path.flatMap((v) => {
    const i = definition.steps.find((s) => s.id === v.step)?.next.findIndex((t) => t.to === v.next) ?? -1;
    return i < 0 ? [] : [`${v.step}>${i}`];
  }));

  const changes = useQuery({
    queryKey: ["workflows", name, version?.version, "diff"],
    queryFn: () => api.get(`workflows/${name}/versions/${version!.version}`).json<Schemas["VersionView"]>(),
    enabled: (version?.version ?? 0) > 1,
    staleTime: Infinity,
  }).data?.diff?.filter((line) => line.startsWith("+") || line.startsWith("-"));
  const tab = (v: typeof view, label: string) => (
    <button role="tab" aria-selected={view === v} onClick={() => { if (v === "json") setText(JSON.stringify(definition, null, 2)); setView(v); }}
            className={`rounded-md px-2.5 py-1 ${view === v ? "bg-accent font-medium" : "text-muted-foreground"}`}>{label}</button>
  );
  return (
    <div className="flex h-full flex-col gap-3 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold">{name}</h1>
        {version ? (
          <select aria-label="Version" value={version.version} onChange={(e) => select(Number(e.target.value))}
                  className="rounded-md border bg-transparent px-2 py-1">
            {versions.map((v) => <option key={v.version} value={v.version}>v{v.version} — {v.note}</option>)}
          </select>
        ) : <span className="rounded-full bg-muted px-2 py-0.5 text-xs">nouveau, non publié</span>}
        {dirty && version && <span className="text-xs text-waiting">modifié</span>}
        <div role="tablist" className="ml-auto flex gap-1 rounded-lg border p-0.5 text-xs">{tab("graph", "Graphe")}{tab("json", "JSON")}</div>
      </div>
      {changes && changes.length > 0 && (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">Changements depuis la version précédente</summary>
          <pre aria-label="Changements depuis la version précédente" className="mt-1 max-h-48 overflow-auto rounded-md bg-muted p-2 font-mono">
            {changes.map((line, i) => <div key={i} className={line.startsWith("+") ? "text-done" : "text-failed"}>{line}</div>)}
          </pre>
        </details>
      )}
      <div className="grid min-h-[32rem] flex-1 grid-cols-[1fr_20rem] gap-4">
        {view === "graph" ? (
          <section aria-label="Graphe" className="overflow-hidden rounded-lg border">
            <WorkflowCanvas definition={definition} at={at} selected={step} walked={walked} onSelect={setStep}
                            onMove={(id, p) => setAt((a) => ({ ...a, [id]: p }))}
                            onConnect={(from, to) => edit(connect(definition, from, to))} onRemoveStep={remove}
                            onRemoveTransition={(from, i) => edit(updateStep(definition, from, (s) =>
                              (s.next.length > 1 ? { ...s, next: s.next.filter((_, j) => j !== i) } : s)))}
                            onAdd={add} />
          </section>
        ) : (
          <textarea aria-label="Définition JSON" value={text} spellCheck={false} onChange={(e) => {
            setText(e.target.value);
            const parsed = parse(e.target.value);
            if (parsed?.steps) { setDefinition(parsed); setAt((a) => ({ ...layout(parsed), ...a })); }
          }} className={`rounded-md border bg-transparent p-2 font-mono text-xs ${parse(text) ? "" : "border-failed"}`} />
        )}
        <aside aria-label="Étape" className="flex flex-col gap-4 overflow-y-auto">
          <StepPanel definition={definition} id={step} onChange={edit} onRemove={remove} onSelect={setStep} />
          <div className="flex flex-col gap-2 border-t pt-3">
            <label className="flex flex-col gap-1 text-xs">Ce que chaque passage apprend (dry-run)
              <textarea aria-label="Faits appris" value={learnt} onChange={(e) => setLearnt(e.target.value)} spellCheck={false}
                        className="min-h-20 rounded-md border bg-transparent p-2 font-mono" />
            </label>
            <div className="flex gap-2">
              <button onClick={() => check.mutate(false)} className="flex-1 rounded-md border px-3 py-1.5 hover:bg-accent">Valider</button>
              <button onClick={() => check.mutate(true)} className="flex-1 rounded-md border px-3 py-1.5 hover:bg-accent">Simuler</button>
            </div>
            {result && (result.errors.length > 0 ? (
              <ul role="alert" className="list-disc pl-5 text-xs text-failed">{result.errors.map((e) => <li key={e}>{e}</li>)}</ul>
            ) : result.path ? (
              <ol aria-label="Parcours simulé" className="flex flex-col gap-0.5 text-xs">
                {result.path.path.map((v, i) => <li key={i}>{v.step} #{v.visit} → {v.next} ({v.reason})</li>)}
                <li className="font-medium">{result.path.outcome ?? result.path.reason}</li>
              </ol>
            ) : <p className="text-xs text-done">Workflow valide.</p>)}
            {admin && (
              <div className="flex gap-2">
                <input aria-label="Note de version" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note de version"
                       className="min-w-0 flex-1 rounded-md border bg-transparent px-2" />
                <button disabled={!note.trim() || !dirty && !!version || publish.isPending} onClick={() => publish.mutate()}
                        className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Publier</button>
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
