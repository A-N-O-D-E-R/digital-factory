import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { formatDistanceToNow } from "date-fns";
import { fr } from "date-fns/locale";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { api, errorMessage, type Schemas } from "../../api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";

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
        <Input name="name" required pattern="[a-z0-9][a-z0-9.\-]{0,62}" placeholder="nom-du-worker" aria-label="Nom du worker"
                />
        <Button variant="outline">Enregistrer</Button>
      </form>
      {token && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg border border-waiting p-3">
          <span>Jeton du worker, affiché une seule fois (FACTORY_WORKER_TOKEN) :</span>
          <code className="break-all font-mono text-xs">{token}</code>
        </div>
      )}
      <Table className="w-full text-left text-xs">
        <TableHeader className="text-muted-foreground">
          <TableRow><TableHead className="py-1">Nom</TableHead><TableHead>Version</TableHead><TableHead>Image</TableHead><TableHead>Runs</TableHead><TableHead>Signe de vie</TableHead><TableHead>État</TableHead><TableHead /></TableRow>
        </TableHeader>
        <TableBody>
          {workers.data?.map((w) => (
            <TableRow key={w.id} className="border-t">
              <TableCell className="py-1.5">{w.name}</TableCell>
              <TableCell>{w.version ?? "—"}</TableCell>
              <TableCell className="font-mono">{w.image ?? "—"}</TableCell>
              <TableCell>{w.slots == null ? "—" : `${w.busy}/${w.slots}`}</TableCell>
              <TableCell>{w.lastHeartbeatAt ? formatDistanceToNow(new Date(w.lastHeartbeatAt), { addSuffix: true, locale: fr }) : "jamais"}</TableCell>
              <TableCell>{!w.active ? "Révoqué" : w.draining ? "En vidange" : "Actif"}</TableCell>
              <TableCell className="flex justify-end gap-1 py-1">
                {w.active && (
                  <Button variant="outline" size="sm" onClick={() => change.mutate(() => api.post(`workers/${w.id}/${w.draining ? "undrain" : "drain"}`))}
>{w.draining ? "Reprendre" : "Vider"}</Button>
                )}
                {w.active && (
                  <Button variant="outline" size="sm" onClick={() => confirm(`Révoquer ${w.name} ? Son jeton cesse de fonctionner.`)
                            && change.mutate(() => api.delete(`workers/${w.id}`))}
                          className="text-failed">Révoquer</Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
  );
}
