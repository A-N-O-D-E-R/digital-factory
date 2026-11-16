import { createFileRoute } from "@tanstack/react-router";
import { LOGIN_PATH } from "../api/client";
import { Logo } from "../components/Logo";

export const Route = createFileRoute("/login")({ component: Login });

function Login() {
  return (
    <div className="flex h-dvh">
      <div className="grid flex-1 place-items-center">
        <div className="flex flex-col items-center gap-4">
          <h1 className="text-lg font-semibold">Digital Factory</h1>
          <a href={LOGIN_PATH} className="rounded-md border px-4 py-2 hover:bg-accent">Se connecter</a>
        </div>
      </div>
      <div className="brand-gradient hidden w-1/2 flex-col items-center justify-center gap-6 md:flex">
        <Logo size="size-16" />
        <p className="text-sm tracking-wider text-white">BY ATS DIGITAL FACTORY</p>
      </div>
    </div>
  );
}
