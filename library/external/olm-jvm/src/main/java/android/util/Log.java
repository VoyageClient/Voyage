/* Minimal desktop stand-in for android.util.Log, only what the olm bindings use. */
package android.util;

public final class Log {

    private Log() {
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg) {
        System.err.println(tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg) {
        System.err.println(tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.err.println(tag + ": " + msg);
        if (tr != null) {
            tr.printStackTrace(System.err);
        }
        return 0;
    }
}
