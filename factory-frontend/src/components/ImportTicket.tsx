import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, type Schemas } from "../api/client";
import { useCan, useChange } from "../api/queries";

/** A ticket (key or URL) becomes a work order, on its own repository unless one is chosen. */
export function ImportTicket() {
  const can = useCan("work-order:write");
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const importTicket = useChange(
    (ticket: string) => api.post("tickets/import", { json: { ticket } }).json<Schemas["ImportedView"]>(),
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
    importTicket.mutate(String(new FormData(e.currentTarget).get("ticket")), {
      onSuccess: (imported) => { setOpen(false); void navigate({ to: "/work-orders/$id", params: { id: imported.workOrderId } }); },
    });
  };
  return (
    <form onSubmit={submit} className="flex gap-2">
      <input name="ticket" required autoFocus aria-label="Ticket" placeholder="OPS-12, github:acme/api#12 ou URL"
             className="w-80 rounded-md border bg-transparent px-2 py-1.5" />
      <button disabled={importTicket.isPending} className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Importer</button>
      <button type="button" onClick={() => setOpen(false)} className="rounded-md border px-3 py-1.5">Annuler</button>
    </form>
  );
}
