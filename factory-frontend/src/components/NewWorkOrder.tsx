import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, type Schemas } from "../api/client";
import { useCan, useCreateWorkOrder } from "../api/queries";

// ponytail: plain controlled form; TanStack Form + Zod once forms have more than three fields
export function NewWorkOrder({ initiallyOpen = false }: { initiallyOpen?: boolean }) {
  const can = useCan("work-order:write");
  const [open, setOpen] = useState(initiallyOpen);
  const repositories = useQuery({
    queryKey: ["repositories"],
    queryFn: () => api.get("repositories").json<Schemas["RepositoryView"][]>(),
    enabled: open,
  });
  const create = useCreateWorkOrder();
  const navigate = useNavigate();
  if (!can) {
    return null;
  }
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    create.mutate(
      { repository: String(form.get("repository")), title: String(form.get("title")), description: String(form.get("description")) },
      { onSuccess: (w) => { setOpen(false); void navigate({ to: "/work-orders/$id", params: { id: w.id } }); } },
    );
  };
  if (!open) {
    return <button onClick={() => setOpen(true)} className="rounded-md border px-3 py-1.5 hover:bg-accent">Nouveau work order</button>;
  }
  return (
    <form onSubmit={submit} aria-label="Nouveau work order"
          className="fixed inset-x-0 top-16 z-10 mx-auto flex w-[32rem] flex-col gap-3 rounded-lg border bg-background p-4 shadow-lg">
      <label className="flex flex-col gap-1">Dépôt
        <select name="repository" required className="rounded-md border bg-transparent p-2">
          {repositories.data?.filter((r) => r.active).map((r) => <option key={r.key}>{r.key}</option>)}
        </select>
      </label>
      <label className="flex flex-col gap-1">Titre
        <input name="title" required maxLength={300} className="rounded-md border bg-transparent p-2" />
      </label>
      <label className="flex flex-col gap-1">Description
        <textarea name="description" required className="min-h-32 rounded-md border bg-transparent p-2" />
      </label>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={() => setOpen(false)} className="rounded-md border px-3 py-1.5">Annuler</button>
        <button type="submit" disabled={create.isPending} className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Créer</button>
      </div>
    </form>
  );
}
