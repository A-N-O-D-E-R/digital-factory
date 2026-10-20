import { createFileRoute, Link, Outlet } from "@tanstack/react-router";
import { useCurrentUser } from "../../api/queries";

export const Route = createFileRoute("/admin")({ component: Admin });

const PAGES = [
  { to: "/admin/integrations", label: "Intégrations" },
  { to: "/admin/workers", label: "Workers" },
  { to: "/admin/autonomy", label: "Autonomie" },
  { to: "/admin/repositories", label: "Dépôts" },
  { to: "/admin/factory", label: "Usine" },
  { to: "/admin/settings", label: "Paramètres" },
  { to: "/admin/roles", label: "Rôles et prompts" },
  { to: "/admin/intake", label: "Tickets" },
] as const;

function Admin() {
  const admin = useCurrentUser().data?.roles.includes("ADMIN");
  if (admin === false) {
    return <p className="p-6 text-muted-foreground">Réservé aux administrateurs.</p>;
  }
  return (
    <div className="flex flex-col gap-4 p-6">
      <nav className="flex gap-1">
        {PAGES.map((page) => (
          <Link key={page.to} to={page.to} className="rounded-md px-3 py-1.5 hover:bg-accent [&.active]:bg-accent">{page.label}</Link>
        ))}
      </nav>
      <Outlet />
    </div>
  );
}
