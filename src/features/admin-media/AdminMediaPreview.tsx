"use client";

import { useEffect, useRef, useState } from "react";

import { isAdminApiError } from "@/features/admin-auth/api";

import { AdminMediaApi } from "./api";
import type { MediaItem } from "./types";
import styles from "./AdminMediaPreview.module.css";

type AdminMediaPreviewProps = Readonly<{
  api: AdminMediaApi;
  item: MediaItem;
  alt: string;
  onSessionExpired: () => void;
}>;

export function AdminMediaPreview({
  api,
  item,
  alt,
  onSessionExpired,
}: AdminMediaPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [shouldLoad, setShouldLoad] = useState(false);
  const [preview, setPreview] = useState<
    | Readonly<{ kind: "waiting" | "error" }>
    | Readonly<{ kind: "ready"; url: string }>
  >({ kind: "waiting" });

  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      setShouldLoad(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setShouldLoad(true);
          observer.disconnect();
        }
      },
      { rootMargin: "160px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!shouldLoad) {
      return;
    }

    let active = true;
    let objectUrl: string | null = null;

    void api.content(item.id).then(
      (blob) => {
        if (!active) {
          return;
        }
        try {
          objectUrl = URL.createObjectURL(blob);
          setPreview({ kind: "ready", url: objectUrl });
        } catch {
          setPreview({ kind: "error" });
        }
      },
      (error: unknown) => {
        if (!active) {
          return;
        }
        if (isAdminApiError(error) && error.kind === "session-expired") {
          onSessionExpired();
          return;
        }
        setPreview({ kind: "error" });
      },
    );

    return () => {
      active = false;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [api, item.id, onSessionExpired, shouldLoad]);

  const previewLoading = shouldLoad && preview.kind === "waiting";

  return (
    <div className={styles.preview} ref={containerRef}>
      {preview.kind === "ready" ? (
        // private authenticated Blob URL은 Next image optimizer를 거치지 않는다.
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={preview.url}
          alt={alt}
          width={item.width}
          height={item.height}
          onError={() => setPreview({ kind: "error" })}
        />
      ) : (
        <p role={previewLoading ? "status" : undefined}>
          {preview.kind === "waiting" && !previewLoading
            ? "미리보기 대기 중"
            : previewLoading
              ? "미리보기 불러오는 중"
              : "미리보기를 불러오지 못했습니다."}
        </p>
      )}
    </div>
  );
}
