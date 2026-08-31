import type { Metadata } from "next";
import Link from "next/link";

import { ResponsiveMedia } from "../public-site/ResponsiveMedia";
import { SiteHeader } from "../public-site/SiteHeader";
import {
  absolutePublicUrl,
  findMedia,
  getGeneratedArtifacts,
} from "../public-site/content";
import styles from "./page.module.css";

const DEFAULT_DESCRIPTION =
  "반려견을 위한 편안한 1:1 맞춤 미용 공간 라오미펫입니다.";
const WEEKDAY_LABEL = {
  MONDAY: "월요일",
  TUESDAY: "화요일",
  WEDNESDAY: "수요일",
  THURSDAY: "목요일",
  FRIDAY: "금요일",
  SATURDAY: "토요일",
  SUNDAY: "일요일",
} as const;

export function generateMetadata(): Metadata {
  const { shop } = getGeneratedArtifacts().content;
  const description = shop.heroDescription ?? DEFAULT_DESCRIPTION;
  const ogMedia = findMedia(shop.ogImageId);
  const ogVariant = ogMedia?.variants.find(
    (variant) =>
      variant.profile === "PUBLIC_FALLBACK" && variant.format === "jpeg",
  );
  if (shop.ogImageId !== null && ogVariant === undefined) {
    throw new Error("Open Graph media fallback is missing");
  }
  return {
    title: `${shop.shopName} | ${shop.regionLabel} ${shop.businessType}`,
    description,
    alternates: { canonical: absolutePublicUrl("/") },
    openGraph: {
      type: "website",
      locale: "ko_KR",
      title: shop.shopName,
      description,
      url: absolutePublicUrl("/"),
      images:
        ogVariant === undefined
          ? undefined
          : [
              {
                url: absolutePublicUrl(ogVariant.publicPath),
                width: ogVariant.width,
                height: ogVariant.height,
                alt: shop.shopName,
              },
            ],
    },
    robots: { index: true, follow: true },
  };
}

function telephoneHref(phone: string): string {
  return `tel:${phone.replace(/[^0-9+]/gu, "")}`;
}

export default function Home() {
  const { content } = getGeneratedArtifacts();
  const { shop, services, breeds, galleryItems, notices } = content;
  const breedNames = new Map(breeds.map((breed) => [breed.id, breed.name]));
  const serviceNames = new Map(
    services.map((service) => [service.id, service.name]),
  );
  const heroMedia = findMedia(shop.heroImageId);
  const groomerMedia = findMedia(shop.groomerImageId);
  const externalChannels = [
    ["인스타그램", shop.instagramUrl],
    ["네이버 블로그", shop.naverBlogUrl],
    ["네이버 톡톡", shop.naverTalktalkUrl],
    ["카카오 채널", shop.kakaoChannelUrl],
  ].filter((entry): entry is [string, string] => entry[1] !== null);
  const maps = [
    ["네이버 지도", shop.naverMapUrl],
    ["카카오맵", shop.kakaoMapUrl],
  ].filter((entry): entry is [string, string] => entry[1] !== null);
  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "LocalBusiness",
    name: shop.shopName,
    url: absolutePublicUrl("/"),
    telephone: shop.phone,
    address: {
      "@type": "PostalAddress",
      streetAddress: shop.address,
      addressRegion: shop.regionLabel,
      addressCountry: "KR",
    },
    openingHours: `${shop.openingTime}-${shop.closingTime}`,
    sameAs: externalChannels.map(([, url]) => url),
  };

  return (
    <>
      <SiteHeader />
      <main>
      <section className={styles.hero} aria-labelledby="site-title">
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>
            {shop.regionLabel} · {shop.businessType}
          </p>
          <h1 id="site-title">{shop.heroTitle ?? shop.shopName}</h1>
          {shop.heroDescription !== null && <p>{shop.heroDescription}</p>}
          <div className={styles.actions}>
            <a className={styles.primaryAction} href={telephoneHref(shop.phone)}>
              전화 문의 {shop.phone}
            </a>
            <a className={styles.secondaryAction} href="#location">
              오시는 길
            </a>
          </div>
        </div>
        {heroMedia !== null && shop.heroImageAltText !== null && (
          <ResponsiveMedia
            media={heroMedia}
            profile="HERO"
            alt={shop.heroImageAltText}
            className={styles.heroMedia}
            sizes="(max-width: 720px) 100vw, 50vw"
            loading="eager"
          />
        )}
      </section>

      <section
        id="services"
        className={styles.section}
        aria-labelledby="services-title"
      >
        <div className={styles.sectionHeading}>
          <p>Services</p>
          <h2 id="services-title">미용 서비스</h2>
        </div>
        {services.length === 0 ? (
          <p className={styles.empty}>서비스 안내를 준비하고 있습니다.</p>
        ) : (
          <ul className={styles.cardGrid}>
            {services.map((service) => (
              <li key={service.id} className={styles.card}>
                <h3>{service.name}</h3>
                <p>{service.description}</p>
                <strong>{service.priceText}</strong>
              </li>
            ))}
          </ul>
        )}
      </section>

      {(shop.groomerName !== null || shop.groomerIntro !== null) && (
        <section
          className={styles.splitSection}
          aria-labelledby="groomer-title"
        >
          {groomerMedia !== null && shop.groomerImageAltText !== null && (
            <ResponsiveMedia
              media={groomerMedia}
              profile="PUBLIC_FALLBACK"
              alt={shop.groomerImageAltText}
              className={styles.groomerMedia}
              sizes="(max-width: 720px) 100vw, 40vw"
            />
          )}
          <div>
            <p className={styles.eyebrow}>Groomer</p>
            <h2 id="groomer-title">
              {shop.groomerName ?? "미용사 소개"}
            </h2>
            {shop.groomerIntro !== null && <p>{shop.groomerIntro}</p>}
          </div>
        </section>
      )}

      <section
        id="gallery"
        className={styles.section}
        aria-labelledby="gallery-title"
      >
        <div className={styles.sectionHeading}>
          <p>Gallery</p>
          <h2 id="gallery-title">미용 갤러리</h2>
        </div>
        {galleryItems.length === 0 ? (
          <p className={styles.empty}>갤러리를 준비하고 있습니다.</p>
        ) : (
          <ul className={styles.galleryGrid}>
            {galleryItems.map((item) => {
              const media = findMedia(item.coverImageId);
              if (media === null) throw new Error("Gallery media is missing");
              return (
                <li key={item.id} className={styles.galleryCard}>
                  <ResponsiveMedia
                    media={media}
                    profile="GALLERY_CARD"
                    alt={item.altText}
                    sizes="(max-width: 640px) 100vw, 33vw"
                  />
                  <div>
                    <h3>{item.dogName ?? breedNames.get(item.breedId)}</h3>
                    <p>
                      {breedNames.get(item.breedId)} ·{" "}
                      {serviceNames.get(item.primaryServiceId)}
                    </p>
                    {item.summary !== null && <p>{item.summary}</p>}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section
        id="notices"
        className={styles.section}
        aria-labelledby="notices-title"
      >
        <div className={styles.sectionHeading}>
          <p>Notice</p>
          <h2 id="notices-title">공지사항</h2>
        </div>
        {notices.length === 0 ? (
          <p className={styles.empty}>현재 등록된 공지가 없습니다.</p>
        ) : (
          <ul className={styles.noticeList}>
            {notices.map((notice) => (
              <li key={notice.id}>
                <Link href={`/notices/${notice.slug}/`}>
                  <span>{notice.pinned ? "중요" : "공지"}</span>
                  <strong>{notice.title}</strong>
                  {notice.summary !== null && <small>{notice.summary}</small>}
                  <time dateTime={notice.publishedAt}>
                    {notice.publishedAt.slice(0, 10)}
                  </time>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        id="location"
        className={styles.location}
        aria-labelledby="location-title"
      >
        <div>
          <p className={styles.eyebrow}>Location</p>
          <h2 id="location-title">오시는 길</h2>
          <address>{shop.address}</address>
          <p>
            영업시간 {shop.openingTime}–{shop.closingTime}
          </p>
          {shop.closedWeekday !== null && (
            <p>정기 휴무 {WEEKDAY_LABEL[shop.closedWeekday]}</p>
          )}
          <p>
            {shop.parkingAvailable
              ? shop.parkingNote ?? "주차 가능"
              : "주차 안내는 문의해 주세요."}
          </p>
        </div>
        <div className={styles.locationActions}>
          <a href={telephoneHref(shop.phone)}>전화 문의</a>
          {maps.map(([label, url]) => (
            <a key={label} href={url} rel="noopener noreferrer">
              {label}
            </a>
          ))}
          {externalChannels.map(([label, url]) => (
            <a key={label} href={url} rel="noopener noreferrer">
              {label}
            </a>
          ))}
        </div>
      </section>

      {shop.reservationNotice !== null && (
        <aside className={styles.reservation} aria-label="예약 안내">
          <strong>예약 안내</strong>
          <p>{shop.reservationNotice}</p>
        </aside>
      )}

      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(jsonLd).replace(/</gu, "\\u003c"),
        }}
      />
      </main>
    </>
  );
}
