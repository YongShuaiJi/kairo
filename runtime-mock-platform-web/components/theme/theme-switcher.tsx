"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { cn } from "@/lib/utils";
import { type ThemeMode, useTheme } from "@/components/theme/theme-provider";

const modes: Array<{ value: ThemeMode; label: string; icon: typeof Sun }> = [
  { value: "light", label: "白天", icon: Sun },
  { value: "dark", label: "夜晚", icon: Moon },
  { value: "system", label: "跟随系统", icon: Monitor },
];

export function ThemeSwitcher({ compact = false }: { compact?: boolean }) {
  const { mode, setMode } = useTheme();

  return (
    <div className={cn("theme-muted-panel rounded-xl border p-1", compact ? "grid grid-cols-3" : "flex")}>
      {modes.map((item) => {
        const Icon = item.icon;
        const active = mode === item.value;
        return (
          <button
            key={item.value}
            type="button"
            onClick={() => setMode(item.value)}
            className={cn(
              "inline-flex items-center justify-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition",
              active ? "bg-[var(--surface-elevated)] text-[color:var(--primary-strong)] shadow-sm" : "text-[color:var(--muted)] hover:bg-[var(--surface-elevated)] hover:text-[color:var(--foreground)]",
            )}
            aria-pressed={active}
          >
            <Icon className="size-3.5" />
            {compact ? <span className="sr-only">{item.label}</span> : item.label}
          </button>
        );
      })}
    </div>
  );
}
