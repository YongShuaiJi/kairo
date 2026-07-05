"use client";

import * as React from "react";
import { DayPicker } from "react-day-picker";
import { zhCN } from "date-fns/locale";
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
      locale={zhCN}
      className={cn("w-[320px] p-3", className)}
      classNames={{
        root:
          "relative w-[320px] [--rdp-day-width:40px] [--rdp-day-height:40px] [--rdp-day_button-width:36px] [--rdp-day_button-height:36px]",
        months: "flex w-full flex-col",
        month: "w-full space-y-3",
        month_caption: "flex h-10 items-center justify-center px-10",
        caption_label: "text-sm font-semibold text-[color:var(--foreground)]",
        nav: "absolute left-3 right-3 top-3 flex h-10 items-center justify-between",
        chevron: "size-4 fill-current",
        button_previous:
          "inline-flex size-8 items-center justify-center rounded-lg text-[color:var(--muted-strong)] transition-colors hover:bg-[var(--surface-muted)] disabled:pointer-events-none disabled:opacity-40",
        button_next:
          "inline-flex size-8 items-center justify-center rounded-lg text-[color:var(--muted-strong)] transition-colors hover:bg-[var(--surface-muted)] disabled:pointer-events-none disabled:opacity-40",
        month_grid: "w-full table-fixed border-collapse",
        weekdays: "",
        weekday:
          "h-8 w-10 text-center text-xs font-medium text-[color:var(--muted)]",
        weeks: "",
        week: "",
        day: "h-10 w-10 p-0 text-center align-middle text-sm",
        day_button:
          "mx-auto flex size-9 items-center justify-center rounded-lg text-sm transition-colors hover:bg-[var(--surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-border)]",
        selected: "runtime-calendar-selected",
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
