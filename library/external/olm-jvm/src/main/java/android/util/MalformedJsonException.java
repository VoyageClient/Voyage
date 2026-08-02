/* Minimal desktop stand-in for android.util.MalformedJsonException (a java.io.IOException on device). */
package android.util;

import java.io.IOException;

public class MalformedJsonException extends IOException {

    public MalformedJsonException(String msg) {
        super(msg);
    }

    public MalformedJsonException(String msg, Throwable throwable) {
        super(msg, throwable);
    }

    public MalformedJsonException(Throwable throwable) {
        super(throwable);
    }
}
