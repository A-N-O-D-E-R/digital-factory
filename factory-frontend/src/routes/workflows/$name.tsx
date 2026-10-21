import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";
import { useCurrentUser } from "../../api/queries";
import { describe, what, type WorkflowDefinition } from "../../lib/workflow";

export const Route = createFileRoute("/workflows/$name")({ component: Workflow });

/**
 * A workflow's versions (immutable), its graph, and a JSON editor: validate, dry-run with what each
 * step would learn, publish as a new version with a note.
 */
// ponytail: steps and transitions as a list, edited as JSON; a drawn graph editor when workflows grow past a screen
function Workflow() {
  const { name } = Route.useParams();
  const history = useQuery({ queryKey: ["workflows", name], queryFn: () => api.get(`workflows/${name}/versions`).json<Schemas["VersionView"][]>() });
  const [selected, setSelected] = useState<number>();
  const version = history.data?.find((v) => v.version === selected) ?? history.data?.[0]; // newest first
  if (!version) {
    return <p className="p-6 text-muted-foreground">{history.isError ? "Workflow introuvable." : "Chargement…"}</p>;
  }
  // a fresh editor per version: it starts from that version's definition
  return <Editor key={version.version} name={name} version={version} versions={history.data ?? []} select={setSelected} />;
}

function Editor({ name, version, versions, select }: {
  name: string; version: Schemas["VersionView"]; versions: Schemas["VersionView"][]; select: (version: number) => void;
}) {
  const client = useQueryClient();
  const admin = useCurrentUser().data?.roles.includes("ADMIN") ?? false;
  const [text, setText] = useState(() => JSON.stringify(version.definition, null, 2));
  const [learnt, setLearnt] = useState('{\n  "verify": [{ "tests.passed": true }]\n}');
  const [note, setNote] = useState("");
  const [result, setResult] = useState<{ errors: string[]; path?: Schemas["Path"] }>();

  const parse = (value: string) => { try { return JSON.parse(value); } catch { return undefined; } };
  const check = useMutation({
    mutationFn: async (dryRun: boolean) => {
      const definition = parse(text);
      if (!definition) return { errors: ["Le JSON du workflow est invalide"] };
      if (!dryRun) return { errors: (await api.post("workflows/validate", { json: definition }).json<Schemas["ValidationView"]>()).errors };
      const run = await api.post("workflows/dry-run", { json: { definition, facts: {}, learnt: parse(learnt) ?? {} } }).json<Schemas["DryRunView"]>();
      return { errors: run.errors, path: run.result ?? undefined };
    },
    onSuccess: setResult,
    onError: async (e) => toast.error(await errorMessage(e)),
  });
  const publish = useMutation({
    mutationFn: () => api.post(`workflows/${name}/versions`, { json: { definition: parse(text), note } }).json<Schemas["VersionView"]>(),
    onSuccess: (v) => { toast.success(`Version ${v.version} publiée`); setNote(""); select(v.version);
      void client.invalidateQueries({ queryKey: ["workflows"] }); },
    onError: async (e) => toast.error(await errorMessage(e)),
  });

  const definition = version.definition as unknown as WorkflowDefinition;
  const changes = useQuery({
    queryKey: ["workflows", name, version.version, "diff"],
    queryFn: () => api.get(`workflows/${name}/versions/${version.version}`).json<Schemas["VersionView"]>(),
    enabled: version.version > 1,
    staleTime: Infinity,
  }).data?.diff?.filter((line) => line.startsWith("+") || line.startsWith("-"));
  return (
    <div className="flex flex-col gap-4 p-6">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">{name}</h1>
        <select aria-label="Version" value={version.version} onChange={(e) => select(Number(e.target.value))}
                className="rounded-md border bg-transparent px-2 py-1">
          {versions.map((v) => <option key={v.version} value={v.version}>v{v.version} — {v.note}</option>)}
        </select>
      </div>
      {changes && changes.length > 0 && (
        <pre aria-label="Changements depuis la version précédente" className="overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
          {changes.map((line, i) => <div key={i} className={line.startsWith("+") ? "text-done" : "text-failed"}>{line}</div>)}
        </pre>
      )}
      <div className="grid grid-cols-2 gap-6">
        <section aria-label="Graphe" className="flex flex-col gap-2">
          {definition.steps.map((step) => (
            <div key={step.id} className={`rounded-lg border p-2 ${step.id === definition.start ? "border-working" : ""}`}>
              <div className="font-medium">{step.id} <span className="font-normal text-muted-foreground">· {what(step)}</span></div>
              <ul className="text-xs text-muted-foreground">
                {step.next.map((t, i) => (
                  <li key={i}>→ <span className="text-foreground">{t.to}</span> si {describe(t.when)}
                    {t.maxVisits != null && ` (au plus ${t.maxVisits}×, puis ${t.onExhausted ?? "@blocked"})`}</li>
                ))}
              </ul>
            </div>
          ))}
        </section>
        <section aria-label="JSON" className="flex flex-col gap-2">
          <textarea aria-label="Définition JSON" value={text} onChange={(e) => setText(e.target.value)} spellCheck={false}
                    className="min-h-96 rounded-md border bg-transparent p-2 font-mono text-xs" />
          <label className="flex flex-col gap-1 text-xs">Ce que chaque passage apprend (dry-run)
            <textarea aria-label="Faits appris" value={learnt} onChange={(e) => setLearnt(e.target.value)} spellCheck={false}
                      className="min-h-24 rounded-md border bg-transparent p-2 font-mono" />
          </label>
          <div className="flex flex-wrap gap-2">
            <button onClick={() => check.mutate(false)} className="rounded-md border px-3 py-1.5 hover:bg-accent">Valider</button>
            <button onClick={() => check.mutate(true)} className="rounded-md border px-3 py-1.5 hover:bg-accent">Simuler</button>
            {admin && (
              <>
                <input aria-label="Note de version" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note de version"
                       className="flex-1 rounded-md border bg-transparent px-2" />
                <button disabled={!note.trim() || publish.isPending} onClick={() => publish.mutate()}
                        className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Publier</button>
              </>
            )}
          </div>
          {result && (result.errors.length > 0 ? (
            <ul role="alert" className="list-disc pl-5 text-failed">{result.errors.map((e) => <li key={e}>{e}</li>)}</ul>
          ) : result.path ? (
            <ol aria-label="Parcours simulé" className="flex flex-col gap-0.5 text-xs">
              {result.path.path.map((v, i) => <li key={i}>{v.step} #{v.visit} → {v.next} ({v.reason})</li>)}
              <li className="font-medium">{result.path.outcome ?? result.path.reason}</li>
            </ol>
          ) : <p className="text-done">Workflow valide.</p>)}
        </section>
      </div>
    </div>
  );
}
