import type {
  DerivativeFormat,
  DerivativeProfile,
} from "../build-transformer/profiles.mts";
import type { PublicMediaManifestItem } from "./contracts.mts";

type ResponsiveMediaProps = Readonly<{
  media: PublicMediaManifestItem;
  profile: DerivativeProfile;
  alt: string;
  className?: string;
  sizes: string;
  loading?: "eager" | "lazy";
}>;

const FORMAT_MIME: Readonly<Record<DerivativeFormat, string>> = {
  avif: "image/avif",
  webp: "image/webp",
  jpeg: "image/jpeg",
};

function variantsFor(
  media: PublicMediaManifestItem,
  profile: DerivativeProfile,
  format: DerivativeFormat,
) {
  return media.variants
    .filter(
      (variant) => variant.profile === profile && variant.format === format,
    )
    .sort((left, right) => left.width - right.width);
}

function sourceSet(
  media: PublicMediaManifestItem,
  profile: DerivativeProfile,
  format: DerivativeFormat,
): string | null {
  const variants = variantsFor(media, profile, format);
  if (variants.length === 0) return null;
  return variants
    .map((variant) => `${variant.publicPath} ${variant.width}w`)
    .join(", ");
}

export function ResponsiveMedia({
  media,
  profile,
  alt,
  className,
  sizes,
  loading = "lazy",
}: ResponsiveMediaProps) {
  const jpeg = variantsFor(media, profile, "jpeg");
  const fallback = jpeg.at(-1);
  if (fallback === undefined) {
    throw new Error("Responsive media fallback is missing");
  }

  return (
    <picture className={className}>
      {(["avif", "webp"] as const).map((format) => {
        const srcSet = sourceSet(media, profile, format);
        return srcSet === null ? null : (
          <source
            key={format}
            type={FORMAT_MIME[format]}
            srcSet={srcSet}
            sizes={sizes}
          />
        );
      })}
      <img
        src={fallback.publicPath}
        srcSet={sourceSet(media, profile, "jpeg") ?? undefined}
        sizes={sizes}
        width={fallback.width}
        height={fallback.height}
        alt={alt}
        loading={loading}
        decoding="async"
      />
    </picture>
  );
}
