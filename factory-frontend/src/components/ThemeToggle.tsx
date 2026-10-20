import { Monitor, Moon, Sun } from "lucide-react";
import { useEffect, useState } from "react";

type Theme = "system" | "light" | "dark";
const NEXT: Record<Theme, Theme> = { system: "light", light: "dark", dark: "system" };
const LABELS: Record<Theme, string> = { system: "Thème du système", light: "Thème clair", dark: "Thème sombre" };

export function applyTheme(theme: Theme) {
  const dark = theme === "dark" || (theme === "system" && window.matchMedia?.("(prefers-color-scheme: dark)").matches === true);
  document.documentElement.classList.toggle("dark", dark);
}

export function storedTheme(): Theme {
  return (localStorage.getItem("theme") as Theme | null) ?? "system";
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(storedTheme);
  useEffect(() => {
    localStorage.setItem("theme", theme);
    applyTheme(theme);
  }, [theme]);
  const Icon = theme === "dark" ? Moon : theme === "light" ? Sun : Monitor;
  return (
    <button onClick={() => setTheme(NEXT[theme])} aria-label={LABELS[theme]} title={LABELS[theme]}
            className="rounded-md p-1.5 hover:bg-accent">
      <Icon className="size-4" />
    </button>
  );
}
