/* Minimal desktop stand-in for android.text.TextUtils, only what the olm bindings use. */
package android.text;

public final class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}
