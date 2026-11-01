package io.archud;

import android.content.Intent;

/** One row in ARC SEARCH. Pinned rows are always shown (after any MORE row), e.g. "open in the app". */
final class SearchResult {
    final String title;
    final String subtitle;
    final String thumbUrl;
    final Intent intent;
    final boolean pinned;

    SearchResult(String title, String subtitle, String thumbUrl, Intent intent, boolean pinned) {
        this.title = title;
        this.subtitle = subtitle;
        this.thumbUrl = thumbUrl;
        this.intent = intent;
        this.pinned = pinned;
    }
}
