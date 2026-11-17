import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, type Schemas } from "../api/client";
import { useCan, useCreateWorkOrder } from "../api/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";

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
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>Nouveau work order</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Nouveau work order</DialogTitle></DialogHeader>
          <form onSubmit={submit} aria-label="Nouveau work order" className="flex flex-col gap-3">
            <Label className="flex-col items-start">Dépôt
              <NativeSelect name="repository" required className="w-full">
                {repositories.data?.filter((r) => r.active).map((r) => <option key={r.key}>{r.key}</option>)}
              </NativeSelect>
            </Label>
            <Label className="flex-col items-start">Titre
              <Input name="title" required maxLength={300} />
            </Label>
            <Label className="flex-col items-start">Description
              <Textarea name="description" required className="min-h-32" />
            </Label>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>Annuler</Button>
              <Button type="submit" disabled={create.isPending}>Créer</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
