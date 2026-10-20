import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet, useRouterState } from "@tanstack/react-router";
import { Toaster } from "sonner";
import { useLiveEvents } from "../api/live";
import { CommandPalette } from "../components/CommandPalette";
import { GateNotifications } from "../components/GateNotifications";
import { Sidebar } from "../components/Sidebar";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({ component: Root });

function Root() {
  const login = useRouterState({ select: (s) => s.location.pathname === "/login" });
  return (
    <>
      {login ? <Outlet /> : <Signedin />}
      <Toaster richColors position="bottom-right" />
    </>
  );
}

function Signedin() {
  useLiveEvents();
  return (
    <div className="flex h-dvh">
      <Sidebar />
      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
      <CommandPalette />
      <GateNotifications />
    </div>
  );
}
