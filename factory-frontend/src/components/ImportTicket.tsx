import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, type Schemas } from "../api/client";
import { useCan, useChange } from "../api/queries";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";

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
    return <Button variant="outline" onClick={() => setOpen(true)}>Importer un ticket</Button>;
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
      <Input name="ticket" required autoFocus aria-label="Ticket" placeholder="OPS-12, github:acme/api#12 ou URL"
             className="w-80" />
      <RepositoryChoice repositories={repositories.data} />
      <Button disabled={importTicket.isPending}>Importer</Button>
      <Button variant="outline" type="button" onClick={() => setOpen(false)}>Annuler</Button>
    </form>
  );
}

/** Issues know their repository; Jira and Notion tickets need one chosen. */
export function RepositoryChoice({ repositories }: { repositories?: Schemas["RepositoryView"][] }) {
  return (
    <NativeSelect size="sm" name="repository" aria-label="Dépôt">
      <option value="">Dépôt du ticket</option>
      {repositories?.filter((r) => r.active).map((r) => <option key={r.key}>{r.key}</option>)}
    </NativeSelect>
  );
}
