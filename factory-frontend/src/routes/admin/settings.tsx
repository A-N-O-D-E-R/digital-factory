import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { format } from "date-fns";
import { useState } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/settings")({ component: Settings });

/**
 * The global layer (or a repository's), a JSON Merge Patch over the defaults: edited with a reason,
 * every change recorded and restorable. Work orders keep the settings they were created with.
 */
function Settings() {
  const [repository, setRepository] = useState("");
  const searchParams = repository ? { repository } : {};
  const key = ["settings", repository] as const;
  const layer = useQuery({ queryKey: key, queryFn: () => api.get("settings", { searchParams }).json<Schemas["Layer"]>() }).data;
  const changes = useQuery({ queryKey: [...key, "changes"], queryFn: () => api.get("settings/changes", { searchParams }).json<Schemas["ChangeView"][]>() }).data;
  const repositories = useQuery({ queryKey: ["repositories"], queryFn: () => api.get("repositories").json<Schemas["RepositoryView"][]>() }).data;
  const save = useAdminChange(key, (body: { document: unknown; reason: string }) => api.put("settings", { searchParams, json: body }), "Paramètres enregistrés");
  const restore = useAdminChange(key, (id: string) => api.post(`settings/changes/${id}/restore`, { json: { reason: "restauration" } }), "Version restaurée");
  return (
    <section className="flex flex-col gap-4">
      <select aria-label="Portée" value={repository} onChange={(e) => setRepository(e.target.value)} className="w-fit rounded-md border bg-transparent px-2 py-1">
        <option value="">Global</option>
        {repositories?.map((r) => <option key={r.key} value={r.key}>{r.key}</option>)}
      </select>
      {layer && <LayerEditor key={`${repository}-${layer.updatedAt}`} layer={layer} save={(document, reason) => save.mutate({ document, reason })} />}
      <h2 className="font-medium">Historique</h2>
      <ul className="flex flex-col divide-y rounded-lg border text-xs">
        {changes?.map((c) => (
          <li key={c.id} className="flex items-center gap-3 p-2">
            <time className="text-muted-foreground">{format(new Date(c.changedAt), "d MMM HH:mm")}</time>
            <span>{c.changedBy}</span><span className="text-muted-foreground">{c.reason}</span>
            <button onClick={() => restore.mutate(c.id)} className="ml-auto rounded-md border px-2 py-0.5 hover:bg-accent">Restaurer l'état d'avant</button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function LayerEditor({ layer, save }: { layer: Schemas["Layer"]; save: (document: unknown, reason: string) => void }) {
  const [text, setText] = useState(() => JSON.stringify(layer.document ?? {}, null, 2));
  const [reason, setReason] = useState("");
  const parsed = (() => { try { return JSON.parse(text) as unknown; } catch { return undefined; } })();
  return (
    <div className="grid grid-cols-2 gap-4">
      <div className="flex flex-col gap-2">
        <textarea aria-label="Couche" value={text} onChange={(e) => setText(e.target.value)} spellCheck={false}
                  className="min-h-80 rounded-md border bg-transparent p-2 font-mono text-xs" />
        <div className="flex gap-2">
          <input aria-label="Raison" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Raison du changement"
                 className="flex-1 rounded-md border bg-transparent px-2" />
          <button disabled={parsed === undefined || !reason.trim()} onClick={() => save(parsed, reason)}
                  className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Enregistrer</button>
        </div>
        {parsed === undefined && <p className="text-failed">JSON invalide</p>}
      </div>
      <pre aria-label="Paramètres effectifs" className="overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
        {JSON.stringify(layer.effective, null, 2)}
      </pre>
    </div>
  );
}
