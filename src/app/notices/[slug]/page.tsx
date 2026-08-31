import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import {
  absolutePublicUrl,
  getGeneratedArtifacts,
} from "../../../public-site/content";
import {
  noticeDescription,
  renderNoticeMarkdown,
} from "../../../public-site/markdown";
import { SiteHeader } from "../../../public-site/SiteHeader";
import styles from "./notice.module.css";

type NoticePageProps = Readonly<{
  params: Promise<{ slug: string }>;
}>;

export const dynamicParams = false;

export function generateStaticParams() {
  return getGeneratedArtifacts().content.notices.map((notice) => ({
    slug: notice.slug,
  }));
}

function noticeBySlug(slug: string) {
  return getGeneratedArtifacts().content.notices.find(
    (notice) => notice.slug === slug,
  );
}

export async function generateMetadata({
  params,
}: NoticePageProps): Promise<Metadata> {
  const { slug } = await params;
  const notice = noticeBySlug(slug);
  if (notice === undefined) return {};
  const shop = getGeneratedArtifacts().content.shop;
  const description =
    notice.summary ?? noticeDescription(notice.bodyMarkdown) ?? shop.heroDescription;
  const canonical = absolutePublicUrl(`/notices/${notice.slug}/`);
  return {
    title: `${notice.title} | ${shop.shopName}`,
    description: description || undefined,
    alternates: { canonical },
    openGraph: {
      type: "article",
      locale: "ko_KR",
      title: notice.title,
      description: description || undefined,
      url: canonical,
      publishedTime: notice.publishedAt,
    },
    robots: { index: true, follow: true },
  };
}

export default async function NoticePage({ params }: NoticePageProps) {
  const { slug } = await params;
  const notice = noticeBySlug(slug);
  if (notice === undefined) notFound();

  return (
    <>
      <SiteHeader />
      <main className={styles.main}>
        <article>
        <p className={styles.eyebrow}>
          {notice.pinned ? "중요 공지" : "공지사항"}
        </p>
        <h1>{notice.title}</h1>
        <time dateTime={notice.publishedAt}>
          {notice.publishedAt.slice(0, 10)}
        </time>
        {notice.summary !== null && (
          <p className={styles.summary}>{notice.summary}</p>
        )}
        <div
          className={styles.markdown}
          dangerouslySetInnerHTML={{
            __html: renderNoticeMarkdown(notice.bodyMarkdown),
          }}
        />
        <Link className={styles.back} href="/#notices">
          공지 목록으로 돌아가기
        </Link>
        </article>
      </main>
    </>
  );
}
