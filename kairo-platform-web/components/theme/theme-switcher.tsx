"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { cn } from "@/lib/utils";
import { type ThemeMode, useTheme } from "@/components/theme/theme-provider";
import { SegmentedControl } from "@/components/ui/segmented-control";

const modes: Array<{ value: ThemeMode; label: string; icon: typeof Sun }> = [
  { value: "light", label: "白天", icon: Sun },
  { value: "dark", label: "夜晚", icon: Moon },
  { value: "system", label: "跟随系统", icon: Monitor },
];

export function ThemeSwitcher({ compact = false }: { compact?: boolean }) {
  const { mode, setMode } = useTheme();

  return (
    <SegmentedControl
      value={mode}
      onValueChange={setMode}
      items={modes.map((item) => ({
        value: item.value,
        label: compact ? <span className="sr-only">{item.label}</span> : item.label,
        icon: item.icon,
      }))}
      compact={compact}
      className={cn(compact ? "w-full" : "flex")}
      itemClassName="px-2.5 py-1.5 text-xs"
      aria-label="主题模式"
    />
  );
}
