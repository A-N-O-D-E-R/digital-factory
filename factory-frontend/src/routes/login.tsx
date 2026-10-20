import { createFileRoute } from "@tanstack/react-router";
import { LOGIN_PATH } from "../api/client";

export const Route = createFileRoute("/login")({ component: Login });

function Login() {
  return (
    <div className="grid h-dvh place-items-center">
      <div className="flex flex-col items-center gap-4">
        <h1 className="text-lg font-semibold">Digital Factory</h1>
        <a href={LOGIN_PATH} className="rounded-md border px-4 py-2 hover:bg-accent">Se connecter</a>
      </div>
    </div>
  );
}
