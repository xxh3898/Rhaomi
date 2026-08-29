"use client";

import { useState } from "react";

import type { AdminApiTransport } from "@/features/admin-auth/types";
import { AdminMediaManager } from "@/features/admin-media/AdminMediaManager";

import styles from "./AdminDashboard.module.css";

const MANAGEMENT_AREAS = [
  { name: "매장정보", enabled: false },
  { name: "갤러리", enabled: false },
  { name: "미디어", enabled: true },
  { name: "공지", enabled: false },
  { name: "견종", enabled: false },
  { name: "서비스", enabled: false },
] as const;

type AdminDashboardProps = Readonly<{
  transport: AdminApiTransport;
  onSessionExpired: () => void;
}>;

export function AdminDashboard({
  transport,
  onSessionExpired,
}: AdminDashboardProps) {
  const [view, setView] = useState<"home" | "media">("home");

  if (view === "media") {
    return (
      <AdminMediaManager
        transport={transport}
        onBack={() => setView("home")}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  return (
    <section className={styles.home} aria-labelledby="management-title">
      <div className={styles.heading}>
        <div>
          <p>Content workspace</p>
          <h2 id="management-title">관리 영역</h2>
        </div>
        <span>Phase 1C-8a</span>
      </div>

      <ul className={styles.areaList}>
        {MANAGEMENT_AREAS.map((area) => (
          <li key={area.name}>
            <button
              className={area.enabled ? styles.enabledArea : styles.disabledArea}
              type="button"
              disabled={!area.enabled}
              aria-label={
                area.enabled
                  ? `${area.name} 관리 열기`
                  : `${area.name}, 준비 중`
              }
              onClick={area.enabled ? () => setView("media") : undefined}
            >
              <span>{area.name}</span>
              <small>{area.enabled ? "사용 가능" : "준비 중"}</small>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
