import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { formatDistanceToNow } from "date-fns";
import { fr } from "date-fns/locale";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";

export const Route = createFileRoute("/admin/workers")({ component: Workers });

type Worker = Schemas["WorkerView"];

/** The hosts executing runs: what they report, drain to update one without killing its runs. */
function Workers() {
  const client = useQueryClient();
  const workers = useQuery({ queryKey: ["workers"], queryFn: () => api.get("workers").json<Worker[]>() });
  const [token, setToken] = useState<string>();
  const change = useMutation({
    mutationFn: (request: () => Promise<unknown>) => request(),
    onSuccess: () => client.invalidateQueries({ queryKey: ["workers"] }),
    onError: async (error) => toast.error(await errorMessage(error)),
  });
  const register = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const name = String(new FormData(event.currentTarget).get("name"));
    change.mutate(async () => setToken((await api.post("workers", { json: { name } }).json<{ token: string }>()).token));
  };
  return (
    <section className="flex max-w-4xl flex-col gap-3">
      <form onSubmit={register} className="flex gap-2">
        <input name="name" required pattern="[a-z0-9][a-z0-9.\-]{0,62}" placeholder="nom-du-worker" aria-label="Nom du worker"
               className="rounded-md border bg-transparent px-2 py-1.5" />
        <button className="rounded-md border px-3 py-1.5 hover:bg-accent">Enregistrer</button>
      </form>
      {token && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg border border-waiting p-3">
          <span>Jeton du worker, affiché une seule fois (FACTORY_WORKER_TOKEN) :</span>
          <code className="break-all font-mono text-xs">{token}</code>
        </div>
      )}
      <table className="w-full text-left text-xs">
        <thead className="text-muted-foreground">
          <tr><th className="py-1">Nom</th><th>Version</th><th>Image</th><th>Runs</th><th>Signe de vie</th><th>État</th><th /></tr>
        </thead>
        <tbody>
          {workers.data?.map((w) => (
            <tr key={w.id} className="border-t">
              <td className="py-1.5">{w.name}</td>
              <td>{w.version ?? "—"}</td>
              <td className="font-mono">{w.image ?? "—"}</td>
              <td>{w.slots == null ? "—" : `${w.busy}/${w.slots}`}</td>
              <td>{w.lastHeartbeatAt ? formatDistanceToNow(new Date(w.lastHeartbeatAt), { addSuffix: true, locale: fr }) : "jamais"}</td>
              <td>{!w.active ? "Révoqué" : w.draining ? "En vidange" : "Actif"}</td>
              <td className="flex justify-end gap-1 py-1">
                {w.active && (
                  <button onClick={() => change.mutate(() => api.post(`workers/${w.id}/${w.draining ? "undrain" : "drain"}`))}
                          className="rounded-md border px-2 py-0.5 hover:bg-accent">{w.draining ? "Reprendre" : "Vider"}</button>
                )}
                {w.active && (
                  <button onClick={() => confirm(`Révoquer ${w.name} ? Son jeton cesse de fonctionner.`)
                            && change.mutate(() => api.delete(`workers/${w.id}`))}
                          className="rounded-md border px-2 py-0.5 text-failed hover:bg-accent">Révoquer</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
