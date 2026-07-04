"use client";

import * as React from "react";
import { DayPicker } from "react-day-picker";
import { cn } from "@/lib/utils";

export type CalendarProps = React.ComponentProps<typeof DayPicker>;

export function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  ...props
}: CalendarProps) {
  return (
    <DayPicker
      showOutsideDays={showOutsideDays}
      className={cn("p-3", className)}
      classNames={{
        root: "w-full",
        months: "flex flex-col gap-4",
        month: "space-y-3",
        month_caption: "flex justify-center pt-1",
        caption_label: "text-sm font-semibold text-[color:var(--foreground)]",
        dropdowns: "flex items-center justify-center gap-2",
        dropdown_root: "relative",
        dropdown:
          "theme-field h-9 rounded-lg border px-3 pr-8 text-sm font-medium outline-none hover:border-[color:var(--border-strong)]",
        nav: "absolute inset-x-3 top-3 flex items-center justify-between",
        button_previous:
          "inline-flex size-8 items-center justify-center rounded-lg text-[color:var(--muted-strong)] hover:bg-[var(--surface-muted)]",
        button_next:
          "inline-flex size-8 items-center justify-center rounded-lg text-[color:var(--muted-strong)] hover:bg-[var(--surface-muted)]",
        month_grid: "w-full border-collapse",
        weekdays: "grid grid-cols-7",
        weekday:
          "flex h-8 items-center justify-center text-xs font-medium text-[color:var(--muted)]",
        weeks: "block",
        week: "grid grid-cols-7",
        day: "flex size-9 items-center justify-center p-0 text-center text-sm",
        day_button:
          "flex size-8 items-center justify-center rounded-lg text-sm transition-colors hover:bg-[var(--surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-border)]",
        selected:
          "[&>button]:bg-[var(--primary)] [&>button]:font-semibold [&>button]:text-white [&>button]:hover:bg-[var(--primary)]",
        today:
          "[&>button]:border [&>button]:border-[color:var(--primary)] [&>button]:font-semibold [&>button]:text-[color:var(--primary-strong)]",
        outside: "text-[color:var(--muted)] opacity-45",
        disabled: "pointer-events-none opacity-35",
        hidden: "invisible",
        ...classNames,
      }}
      {...props}
    />
  );
}
