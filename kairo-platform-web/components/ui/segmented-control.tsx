"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

type SegmentedControlProps<T extends string> = {
  value: T;
  onValueChange: (value: T) => void;
  items: Array<{
    value: T;
    label: React.ReactNode;
    icon?: React.ComponentType<{ className?: string }>;
    disabled?: boolean;
  }>;
  compact?: boolean;
  className?: string;
  itemClassName?: string;
  "aria-label"?: string;
};

export function SegmentedControl<T extends string>({
  value,
  onValueChange,
  items,
  compact,
  className,
  itemClassName,
  "aria-label": ariaLabel,
}: SegmentedControlProps<T>) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className={cn("theme-muted-panel inline-flex rounded-xl border p-1", compact && "grid grid-cols-3", className)}
    >
      {items.map((item) => {
        const active = item.value === value;
        const Icon = item.icon;
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={active}
            disabled={item.disabled}
            onClick={() => onValueChange(item.value)}
            className={cn(
              "inline-flex min-h-8 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium transition",
              "disabled:pointer-events-none disabled:opacity-50",
              active
                ? "bg-[var(--surface-elevated)] text-[color:var(--primary-strong)] shadow-sm"
                : "text-[color:var(--muted)] hover:bg-[var(--surface-elevated)] hover:text-[color:var(--foreground)]",
              itemClassName,
            )}
          >
            {Icon ? <Icon className="size-3.5" /> : null}
            {item.label}
          </button>
        );
      })}
    </div>
  );
}
