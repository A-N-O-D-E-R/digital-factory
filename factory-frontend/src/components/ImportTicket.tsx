import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, type Schemas } from "../api/client";
import { useCan, useChange } from "../api/queries";

/** A ticket (key or URL) becomes a work order, on its own repository unless one is chosen. */
export function ImportTicket({ initiallyOpen = false }: { initiallyOpen?: boolean }) {
  const can = useCan("work-order:write");
  const [open, setOpen] = useState(initiallyOpen);
  const navigate = useNavigate();
  const repositories = useQuery({
    queryKey: ["repositories"],
    queryFn: () => api.get("repositories").json<Schemas["RepositoryView"][]>(),
    enabled: open,
  });
  const importTicket = useChange(
    (json: { ticket: string; repository?: string }) => api.post("tickets/import", { json }).json<Schemas["ImportedView"]>(),
    "Ticket importé",
  );
  if (!can) {
    return null;
  }
  if (!open) {
    return <button onClick={() => setOpen(true)} className="rounded-md border px-3 py-1.5 hover:bg-accent">Importer un ticket</button>;
  }
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    importTicket.mutate({ ticket: String(form.get("ticket")), repository: String(form.get("repository")) || undefined }, {
      onSuccess: (imported) => { setOpen(false); void navigate({ to: "/work-orders/$id", params: { id: imported.workOrderId } }); },
    });
  };
  return (
    <form onSubmit={submit} className="flex gap-2">
      <input name="ticket" required autoFocus aria-label="Ticket" placeholder="OPS-12, github:acme/api#12 ou URL"
             className="w-80 rounded-md border bg-transparent px-2 py-1.5" />
      <RepositoryChoice repositories={repositories.data} />
      <button disabled={importTicket.isPending} className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Importer</button>
      <button type="button" onClick={() => setOpen(false)} className="rounded-md border px-3 py-1.5">Annuler</button>
    </form>
  );
}

/** Issues know their repository; Jira and Notion tickets need one chosen. */
export function RepositoryChoice({ repositories }: { repositories?: Schemas["RepositoryView"][] }) {
  return (
    <select name="repository" aria-label="Dépôt" className="rounded-md border bg-transparent px-2 py-1.5">
      <option value="">Dépôt du ticket</option>
      {repositories?.filter((r) => r.active).map((r) => <option key={r.key}>{r.key}</option>)}
    </select>
  );
}
