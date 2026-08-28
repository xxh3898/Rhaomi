import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.main}>
      <section className={styles.panel} aria-labelledby="site-title">
        <p className={styles.phase}>Phase 1A · Static Export</p>
        <h1 id="site-title" className={styles.title}>
          라오미펫
        </h1>
        <p className={styles.description}>
          정적 사이트 기반을 준비하고 있습니다.
        </p>
      </section>
    </main>
  );
}
