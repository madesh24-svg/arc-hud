package io.archud;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/** Opens web pages in the TV's browser of choice: TV Bro first, then BrowseHere, then whatever answers. */
final class Browsers {
    private static final String[] PREFERRED = {"com.phlox.tvwebbrowser", "com.tcl.browser"};

    private Browsers() {
    }

    static Intent view(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PackageManager pm = context.getPackageManager();
        for (String pkg : PREFERRED) {
            try {
                pm.getApplicationInfo(pkg, 0);
                intent.setPackage(pkg);
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed, try the next one
            }
        }
        return intent;
    }
}
