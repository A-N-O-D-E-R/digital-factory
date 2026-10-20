import { useNavigate } from "@tanstack/react-router";
import { Command } from "cmdk";
import { useEffect, useState } from "react";
import { useCurrentUser, useWorkOrders } from "../api/queries";

/** Mod+K: jump to a work order or a page without the mouse. */
export function CommandPalette() {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const workOrders = useWorkOrders().data ?? [];
  const admin = useCurrentUser().data?.roles.includes("ADMIN");
  useEffect(() => {
    const toggle = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((o) => !o);
      }
    };
    document.addEventListener("keydown", toggle);
    return () => document.removeEventListener("keydown", toggle);
  }, []);
  const go = (to: string, params?: Record<string, string>) => {
    setOpen(false);
    void navigate({ to, params } as never);
  };
  return (
    <Command.Dialog open={open} onOpenChange={setOpen} label="Palette de commandes"
                    className="fixed inset-x-0 top-24 z-20 mx-auto w-[36rem] overflow-hidden rounded-lg border bg-background shadow-xl">
      <Command.Input placeholder="Aller à…" className="w-full border-b bg-transparent px-3 py-2.5 outline-none" />
      <Command.List className="max-h-80 overflow-y-auto p-1">
        <Command.Empty className="p-3 text-muted-foreground">Rien trouvé.</Command.Empty>
        <Command.Group heading="Pages" className="text-xs text-muted-foreground [&_[cmdk-item]]:text-sm [&_[cmdk-item]]:text-foreground">
          <Item onSelect={() => go("/")}>Tableau</Item>
          <Item onSelect={() => go("/approvals")}>Approbations</Item>
          {admin && <Item onSelect={() => go("/admin/integrations")}>Intégrations</Item>}
          {admin && <Item onSelect={() => go("/admin/workers")}>Workers</Item>}
          {admin && <Item onSelect={() => go("/admin/autonomy")}>Autonomie</Item>}
        </Command.Group>
        <Command.Group heading="Work orders" className="text-xs text-muted-foreground [&_[cmdk-item]]:text-sm [&_[cmdk-item]]:text-foreground">
          {workOrders.map((w) => (
            <Item key={w.id} value={`${w.title} ${w.repository} ${w.id}`} onSelect={() => go("/work-orders/$id", { id: w.id })}>
              {w.title} <span className="text-muted-foreground">· {w.repository}</span>
            </Item>
          ))}
        </Command.Group>
      </Command.List>
    </Command.Dialog>
  );
}

function Item(props: React.ComponentProps<typeof Command.Item>) {
  return <Command.Item {...props} className="cursor-pointer rounded-md px-2 py-1.5 data-[selected=true]:bg-accent" />;
}
