import type { Metadata } from "next";
import { Toaster } from "sonner";
import { Providers } from "@/components/providers";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "Kairo",
    template: "%s · Kairo",
  },
  description: "Java 运行时 Mock 与故障注入控制台",
  icons: {
    icon: "/icon.svg",
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>
        <script
          dangerouslySetInnerHTML={{
            __html: `(() => {try {const m = localStorage.getItem("kairo-theme") || "system"; const d = m === "system" ? matchMedia("(prefers-color-scheme: dark)").matches : m === "dark"; const r = document.documentElement; r.dataset.themeMode = m; r.dataset.theme = d ? "dark" : "light"; r.classList.toggle("theme-night", d); r.classList.toggle("theme-day", !d); r.style.colorScheme = d ? "dark" : "light";} catch {}})();`,
          }}
        />
        <Providers>{children}</Providers>
        <Toaster richColors position="top-right" closeButton />
      </body>
    </html>
  );
}
