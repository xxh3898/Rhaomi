import rawContent from "../generated/content.json";
import rawMediaManifest from "../generated/media-manifest.json";

import {
  parseGeneratedArtifactsV2,
  type GeneratedArtifactsV2,
  type PublicMediaManifestItem,
} from "./contracts.mts";

const DEFAULT_SITE_URL = "https://example.invalid/";
const artifacts = parseGeneratedArtifactsV2(rawContent, rawMediaManifest);

export function getGeneratedArtifacts(): GeneratedArtifactsV2 {
  return artifacts;
}

export function getPublicSiteUrl(): URL {
  const value = process.env.PUBLIC_SITE_URL ?? DEFAULT_SITE_URL;
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("PUBLIC_SITE_URL contract is invalid");
  }
  if (
    parsed.protocol !== "https:" ||
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    parsed.search.length > 0 ||
    parsed.hash.length > 0 ||
    parsed.pathname !== "/"
  ) {
    throw new Error("PUBLIC_SITE_URL contract is invalid");
  }
  return parsed;
}

export function absolutePublicUrl(pathname: string): string {
  if (!pathname.startsWith("/") || pathname.startsWith("//")) {
    throw new Error("Public path contract is invalid");
  }
  return new URL(pathname, getPublicSiteUrl()).toString();
}

export function findMedia(
  mediaId: string | null,
): PublicMediaManifestItem | null {
  if (mediaId === null) return null;
  return (
    artifacts.mediaManifest.items.find((item) => item.mediaId === mediaId) ??
    null
  );
}
