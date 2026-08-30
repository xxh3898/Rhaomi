"use client";

import { useState } from "react";

import type { AdminApiTransport } from "@/features/admin-auth/types";
import { AdminBreedManager } from "@/features/admin-breed/AdminBreedManager";
import { AdminGalleryManager } from "@/features/admin-gallery/AdminGalleryManager";
import { AdminMediaManager } from "@/features/admin-media/AdminMediaManager";
import { AdminServiceManager } from "@/features/admin-service/AdminServiceManager";
import { AdminShopSettingsManager } from "@/features/admin-shop-settings/AdminShopSettingsManager";

import styles from "./AdminDashboard.module.css";

const MANAGEMENT_AREAS = [
  { name: "매장정보", view: "shop-settings" },
  { name: "갤러리", view: "gallery" },
  { name: "미디어", view: "media" },
  { name: "공지", view: null },
  { name: "견종", view: "breeds" },
  { name: "서비스", view: "services" },
] as const;

type AdminDashboardProps = Readonly<{
  transport: AdminApiTransport;
  onSessionExpired: () => void;
}>;

export function AdminDashboard({
  transport,
  onSessionExpired,
}: AdminDashboardProps) {
  const [view, setView] = useState<
    "home" | "shop-settings" | "gallery" | "media" | "breeds" | "services"
  >("home");

  if (view === "shop-settings") {
    return (
      <AdminShopSettingsManager
        transport={transport}
        onBack={() => setView("home")}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  if (view === "media") {
    return (
      <AdminMediaManager
        transport={transport}
        onBack={() => setView("home")}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  if (view === "gallery") {
    return (
      <AdminGalleryManager
        transport={transport}
        onBack={() => setView("home")}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  if (view === "breeds") {
    return (
      <AdminBreedManager
        transport={transport}
        onBack={() => setView("home")}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  if (view === "services") {
    return (
      <AdminServiceManager
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
        <span>Phase 1C-8d</span>
      </div>

      <ul className={styles.areaList}>
        {MANAGEMENT_AREAS.map((area) => (
          <li key={area.name}>
            <button
              className={area.view ? styles.enabledArea : styles.disabledArea}
              type="button"
              disabled={!area.view}
              aria-label={
                area.view
                  ? `${area.name} 관리 열기`
                  : `${area.name}, 준비 중`
              }
              onClick={area.view ? () => setView(area.view) : undefined}
            >
              <span>{area.name}</span>
              <small>{area.view ? "사용 가능" : "준비 중"}</small>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
