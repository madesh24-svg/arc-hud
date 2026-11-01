package io.archud;

import android.content.Context;

import java.util.List;

/**
 * A source of search results. ARC SEARCH runs every provider in {@link SearchProviders} in parallel and
 * shows one section per provider, so a new source (installed apps, TV inputs, settings, devices ...)
 * is one new class plus one line in SearchProviders.
 */
interface SearchProvider {
    /** Section title shown above the results. */
    String heading();

    /** How many results to show before the MORE row (the rest appear when MORE is chosen). */
    int firstRows();

    /** Runs on a background thread. Throw to show the section as unavailable. */
    List<SearchResult> search(Context context, String query) throws Exception;
}
