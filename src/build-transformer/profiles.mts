export const DERIVATIVE_FORMATS = ["avif", "webp", "jpeg"] as const;

export type DerivativeFormat = (typeof DERIVATIVE_FORMATS)[number];

export const DERIVATIVE_PROFILE_ORDER = [
  "GALLERY_CARD",
  "GALLERY_LARGE",
  "HERO",
  "PUBLIC_FALLBACK",
] as const;

export type DerivativeProfile = (typeof DERIVATIVE_PROFILE_ORDER)[number];

export type DerivativeProfileDefinition = Readonly<{
  widths: readonly number[];
  formats: readonly DerivativeFormat[];
}>;

export const DERIVATIVE_PROFILES: Readonly<
  Record<DerivativeProfile, DerivativeProfileDefinition>
> = {
  GALLERY_CARD: {
    widths: [360, 640, 960],
    formats: DERIVATIVE_FORMATS,
  },
  GALLERY_LARGE: {
    widths: [768, 1200, 1600],
    formats: DERIVATIVE_FORMATS,
  },
  HERO: {
    widths: [768, 1280, 1920],
    formats: DERIVATIVE_FORMATS,
  },
  PUBLIC_FALLBACK: {
    widths: [1200],
    formats: ["jpeg"],
  },
};
