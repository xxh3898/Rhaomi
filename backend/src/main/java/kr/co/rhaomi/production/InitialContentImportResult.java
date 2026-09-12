package kr.co.rhaomi.production;

record InitialContentImportResult(
        String manifestSha256,
        long contentRevision,
        int mediaCount,
        int breedCount,
        int serviceCount,
        int noticeCount,
        int galleryItemCount) {}
