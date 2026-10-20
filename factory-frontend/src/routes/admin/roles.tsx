import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { Field, formValues } from "../../components/Field";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/roles")({ component: Roles });

type Version = { role: string; version: number; text: string; note: string; createdBy: string; createdAt: string; diff: string[] | null };

/** The agent roles and their prompts: immutable versions, each with its line diff to the previous one. */
function Roles() {
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.get("agent-roles").json<Schemas["RoleView"][]>() }).data;
  const [role, setRole] = useState<string>();
  const current = role ?? roles?.[0]?.name;
  const create = useAdminChange(["roles"], (body: Record<string, string>) => api.post("agent-roles", { json: body }), "Rôle créé");
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const body = formValues(e.currentTarget);
    create.mutate(body, { onSuccess: () => { setRole(body.name); (e.target as HTMLFormElement).reset(); } });
  };
  return (
    <section className="grid grid-cols-[14rem_1fr] gap-4">
      <ul className="flex flex-col gap-0.5">
        {roles?.map((r) => (
          <li key={r.name}>
            <button onClick={() => setRole(r.name)} className={`w-full rounded-md px-2 py-1.5 text-left hover:bg-accent ${r.name === current ? "bg-accent" : ""}`}>
              {r.name} <span className="text-muted-foreground">v{r.latestVersion}</span>
            </button>
          </li>
        ))}
        <li>
          <form onSubmit={submit} aria-label="Nouveau rôle de revue" className="mt-3 flex flex-col gap-2 border-t pt-3">
            <Field label="Nouveau rôle de revue" name="name" required pattern="[a-z][a-z0-9\-]{0,62}" placeholder="perf-reviewer" />
            <Field label="Description" name="description" required />
            <Field label="Prompt"><textarea name="prompt" required className="min-h-24 rounded-md border bg-transparent p-2 text-xs" /></Field>
            <button className="w-fit rounded-md border px-2 py-1 hover:bg-accent">Créer</button>
          </form>
        </li>
      </ul>
      {current && <Prompts key={current} role={current} />}
    </section>
  );
}

function Prompts({ role }: { role: string }) {
  const key = ["roles", role] as const;
  const history = useQuery({ queryKey: key, queryFn: () => api.get(`agent-roles/${role}/prompts`).json<Version[]>() }).data;
  const latest = history?.at(-1);
  const [shown, setShown] = useState<number>();
  const version = history?.find((v) => v.version === shown) ?? latest;
  const detail = useQuery({
    queryKey: [...key, version?.version],
    queryFn: () => api.get(`agent-roles/${role}/prompts/${version!.version}`).json<Version>(),
    enabled: version != null,
  }).data;
  const publish = useAdminChange(["roles"], (body: { text: string; note: string }) => api.post(`agent-roles/${role}/prompts`, { json: body }), "Prompt publié");
  if (!latest) {
    return null;
  }
  return (
    <div className="flex flex-col gap-3">
      <select aria-label="Version du prompt" value={version?.version} onChange={(e) => setShown(Number(e.target.value))}
              className="w-fit rounded-md border bg-transparent px-2 py-1">
        {history?.map((v) => <option key={v.version} value={v.version}>v{v.version} — {v.note}</option>)}
      </select>
      {detail?.diff && detail.diff.length > 0 && (
        <pre aria-label="Différences" className="overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
          {detail.diff.map((line, i) => (
            <div key={i} className={line.startsWith("+") ? "text-done" : line.startsWith("-") ? "text-failed" : ""}>{line}</div>
          ))}
        </pre>
      )}
      <Editor key={latest.version} text={latest.text} publish={(text, note) => publish.mutate({ text, note })} />
    </div>
  );
}

function Editor({ text: initial, publish }: { text: string; publish: (text: string, note: string) => void }) {
  const [text, setText] = useState(initial);
  const [note, setNote] = useState("");
  return (
    <div className="flex flex-col gap-2">
      <textarea aria-label="Prompt" value={text} onChange={(e) => setText(e.target.value)}
                className="min-h-80 rounded-md border bg-transparent p-2 font-mono text-xs" />
      <div className="flex gap-2">
        <input aria-label="Note de version" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Ce qui change"
               className="flex-1 rounded-md border bg-transparent px-2" />
        <button disabled={!note.trim() || text === initial} onClick={() => publish(text, note)}
                className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Publier une version</button>
      </div>
    </div>
  );
}
