package io.archud;

import android.content.Context;
import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Web results. Articles come from Wikipedia's open search API (Tamil Wikipedia when the query is in
 * Tamil); every row opens in the TV browser. A pinned row hands the whole query to DuckDuckGo in the
 * browser, so ARC itself never fetches a search engine's pages.
 */
final class WebProvider implements SearchProvider {
    @Override
    public String heading() {
        return "WEB";
    }

    @Override
    public int firstRows() {
        return 5;
    }

    @Override
    public List<SearchResult> search(Context context, String query) {
        List<SearchResult> out = new ArrayList<>();
        String host = hasTamil(query) ? "ta.wikipedia.org" : "en.wikipedia.org";
        try {
            String json = SafeHttp.getText("https://" + host + "/w/api.php?action=query&list=search&format=json"
                    + "&utf8=1&srlimit=8&srsearch=" + URLEncoder.encode(query, "UTF-8"));
            JSONArray hits = new JSONObject(json).getJSONObject("query").getJSONArray("search");
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                String title = hit.getString("title");
                String snippet = Html.fromHtml(hit.optString("snippet"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                String page = "https://" + host + "/wiki/" + Uri.encode(title.replace(' ', '_'));
                out.add(new SearchResult(title, snippet.isEmpty() ? host : snippet, null,
                        Browsers.view(context, page), false));
            }
        } catch (Exception offlineOrBlocked) {
            // no Wikipedia answer: the browser row below still works
        }
        out.add(new SearchResult("SEARCH THE WEB FOR “" + query + "”", "DuckDuckGo, opens in the browser", null,
                Browsers.view(context, "https://duckduckgo.com/?q=" + Uri.encode(query)), true));
        return out;
    }

    private static boolean hasTamil(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0B80 && c <= 0x0BFF) {
                return true;
            }
        }
        return false;
    }
}
