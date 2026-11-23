package io.archud;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * REMOTE CONTROL: lets the phone drive any app on this box (Home, Back, arrows, OK, scroll, screen off and on)
 * without adb. It does nothing until it is switched on by hand under Settings, Accessibility, Services, and it
 * only ever runs the fixed commands below, which arrive through the PIN-protected /key endpoint of ARC LINK.
 * It reads no screen text and sends nothing anywhere.
 */
public class ArcAccessibility extends AccessibilityService {
    private static final Set<String> COMMANDS = new HashSet<>(Arrays.asList(
            "home", "back", "recents", "navup", "navdown", "navleft", "navright", "ok",
            "scrollup", "scrolldown", "screenoff", "screenon"));
    private static final String DONE = "Done.";

    private static volatile ArcAccessibility instance;

    /** True when k is one of the controls this service runs. */
    static boolean handles(String k) {
        return COMMANDS.contains(k);
    }

    /** True while the service is switched on in the system's Accessibility settings. */
    static boolean running() {
        return instance != null;
    }

    /** Runs a control n times (arrows and scrolling only); null when the service is off. */
    static String run(String k, int n) {
        ArcAccessibility a = instance;
        if (a == null) {
            return null;
        }
        int times = k.startsWith("nav") || k.startsWith("scroll") ? Math.max(1, Math.min(20, n)) : 1;
        String result = DONE;
        for (int i = 0; i < times; i++) {
            result = a.once(k);
            if (!DONE.equals(result)) {
                break;
            }
            if (i < times - 1) {
                try {
                    Thread.sleep(180);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // nothing to react to: this service only acts when the phone asks
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }

    private String once(String k) {
        switch (k) {
            case "home":
                return global(GLOBAL_ACTION_HOME);
            case "back":
                return global(GLOBAL_ACTION_BACK);
            case "recents":
                return global(GLOBAL_ACTION_RECENTS);
            case "navup":
                return move(View.FOCUS_UP);
            case "navdown":
                return move(View.FOCUS_DOWN);
            case "navleft":
                return move(View.FOCUS_LEFT);
            case "navright":
                return move(View.FOCUS_RIGHT);
            case "ok":
                return press();
            case "scrollup":
                return scroll(false);
            case "scrolldown":
                return scroll(true);
            case "screenoff":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return "Screen off needs Android 9 or newer.";
                }
                return global(GLOBAL_ACTION_LOCK_SCREEN);
            case "screenon":
                return wake();
            default:
                return "Unknown control.";
        }
    }

    private String global(int action) {
        return performGlobalAction(action) ? DONE : "Android did not accept that.";
    }

    private AccessibilityNodeInfo focused(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (cur == null) {
            cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
        return cur;
    }

    /** Moves the highlight one step; with nothing highlighted yet it lands on the first item on screen. */
    private String move(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "Nothing on the screen to move around.";
        }
        AccessibilityNodeInfo cur = focused(root);
        AccessibilityNodeInfo next = cur == null ? firstFocusable(root) : cur.focusSearch(direction);
        if (next == null) {
            return "Nothing further that way.";
        }
        boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                || next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return ok ? DONE : "Could not move there.";
    }

    /** Presses the highlighted item (or the nearest clickable parent). */
    private String press() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "Nothing on the screen to press.";
        }
        AccessibilityNodeInfo n = focused(root);
        if (n == null) {
            return "Nothing is highlighted. Use the arrows first.";
        }
        for (int depth = 0; n != null && depth < 12; depth++) {
            if (n.isClickable() && n.isEnabled()) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? DONE : "That item did not respond.";
            }
            n = n.getParent();
        }
        return "The highlighted item cannot be pressed.";
    }

    /** Scrolls the list around the highlight, or the first scrollable area on screen. */
    private String scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "Nothing on the screen to scroll.";
        }
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo n = focused(root);
        for (int depth = 0; n != null && depth < 12 && target == null; depth++) {
            if (n.isScrollable()) {
                target = n;
            }
            n = n.getParent();
        }
        if (target == null) {
            target = firstScrollable(root);
        }
        if (target == null) {
            return "Nothing on this screen scrolls.";
        }
        boolean ok = target.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        return ok ? DONE : "Already at the end.";
    }

    @SuppressWarnings("deprecation")
    private String wake() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return "No power service.";
        }
        PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "archud:wake");
        w.acquire(3000);
        return DONE;
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo n = queue.poll();
            if (n != root && n.isFocusable() && n.isVisibleToUser() && n.isEnabled()) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isScrollable() && n.isVisibleToUser()) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        return null;
    }
}
