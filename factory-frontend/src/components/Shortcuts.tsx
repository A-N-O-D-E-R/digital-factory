import { useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";

const GO: Record<string, { to: string; label: string }> = {
  t: { to: "/", label: "Tableau" },
  a: { to: "/approvals", label: "Approbations" },
  w: { to: "/workflows", label: "Workflows" },
  i: { to: "/admin/integrations", label: "Administration" },
};

function typing(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));
}

/** Keyboard first: g then a letter to go somewhere, n for a new work order, ? for this list. */
export function Shortcuts() {
  const navigate = useNavigate();
  const [help, setHelp] = useState(false);
  useEffect(() => {
    let pending = false;
    const onKey = (e: KeyboardEvent) => {
      if (typing(e.target) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (pending && GO[e.key]) {
        void navigate({ to: GO[e.key].to } as never);
      } else if (e.key === "n") {
        void navigate({ to: "/", search: { action: "new-work-order" } } as never);
      } else if (e.key === "?") {
        setHelp((h) => !h);
      } else if (e.key === "Escape") {
        setHelp(false);
      }
      pending = e.key === "g";
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [navigate]);
  if (!help) return null;
  return (
    <div role="dialog" aria-label="Raccourcis clavier" className="fixed inset-x-0 top-24 z-20 mx-auto w-80 rounded-lg border bg-background p-4 shadow-xl">
      <h2 className="mb-2 font-medium">Raccourcis</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        <dt><kbd>Ctrl</kbd>+<kbd>K</kbd></dt><dd>Palette de commandes</dd>
        {Object.entries(GO).map(([key, { label }]) => <div key={key} className="contents"><dt><kbd>g</kbd> <kbd>{key}</kbd></dt><dd>{label}</dd></div>)}
        <dt><kbd>n</kbd></dt><dd>Nouveau work order</dd>
        <dt><kbd>?</kbd></dt><dd>Cette aide</dd>
      </dl>
    </div>
  );
}
