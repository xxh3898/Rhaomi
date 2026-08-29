import type { Metadata } from "next";

import { AdminAuthShell } from "./_components/AdminAuthShell";

export const metadata: Metadata = {
  title: "라오미펫 관리자",
  robots: {
    index: false,
    follow: false,
    noarchive: true,
  },
};

export default function AdminPage() {
  return <AdminAuthShell />;
}
