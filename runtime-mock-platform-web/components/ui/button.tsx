import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "bg-[var(--primary)] text-white shadow-sm hover:bg-[var(--primary-strong)]",
        secondary: "border border-[color:var(--border)] bg-[var(--surface-elevated)] text-[color:var(--foreground)] shadow-sm hover:bg-[var(--surface-muted)]",
        ghost: "text-[color:var(--muted-strong)] hover:bg-[var(--surface-muted)] hover:text-[color:var(--foreground)]",
        destructive: "bg-red-600 text-white hover:bg-red-700",
        outline: "border border-[color:var(--border-strong)] bg-transparent text-[color:var(--muted-strong)] hover:bg-[var(--surface-elevated)] hover:text-[color:var(--foreground)]",
      },
      size: {
        default: "h-10 px-4 py-2",
        sm: "h-8 rounded-md px-3 text-xs",
        lg: "h-11 px-5",
        icon: "size-9",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({ className, variant, size, asChild, ...props }: ButtonProps) {
  const Comp = asChild ? Slot : "button";
  return <Comp className={cn(buttonVariants({ variant, size, className }))} {...props} />;
}

export { buttonVariants };
