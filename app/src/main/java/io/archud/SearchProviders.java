package io.archud;

import java.util.ArrayList;
import java.util.List;

/** The registry of search sources, in the order their sections appear. */
final class SearchProviders {
    private SearchProviders() {
    }

    static List<SearchProvider> all() {
        List<SearchProvider> providers = new ArrayList<>();
        providers.add(new SmartTubeProvider());
        providers.add(new WebProvider());
        return providers;
    }
}
