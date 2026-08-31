import Link from "next/link";

import { getGeneratedArtifacts } from "./content";

export function SiteHeader() {
  const { shop } = getGeneratedArtifacts().content;
  return (
    <header className="site-header">
      <Link className="brand-link" href="/">
        {shop.shopName}
      </Link>
      <nav aria-label="주요 메뉴">
        <Link href="/#services">서비스</Link>
        <Link href="/#gallery">갤러리</Link>
        <Link href="/#notices">공지</Link>
        <Link href="/#location">오시는 길</Link>
      </nav>
    </header>
  );
}
