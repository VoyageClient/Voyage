package com.otaliastudios.autocomplete;

import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;


/**
 * Entry point for adding Autocomplete behavior to a {@link EditText}.
 *
 * You can construct a {@code Autocomplete} using the builder provided by {@link Autocomplete#on(EditText)}.
 * Building is enough, but you can hold a reference to this class to call its public methods.
 *
 * Requires:
 * - {@link EditText}: this is both the anchor for the popup, and the source of text events that we listen to
 * - {@link AutocompletePresenter}: this presents items in the popup window. See class for more info.
 * - {@link AutocompleteCallback}: if specified, this listens to click events and visibility changes
 * - {@link AutocompletePolicy}: if specified, this controls how and when to show the popup based on text events
 *   If not, this defaults to {@link SimplePolicy}: shows the popup when text.length() bigger than 0.
 */
public final class Autocomplete<T> implements TextWatcher, SpanWatcher {

    private final static String TAG = Autocomplete.class.getSimpleName();
    private final static boolean DEBUG = false;

    private static void log(String log) {
        if (DEBUG) Log.e(TAG, log);
    }

    /**
     * Builder for building {@link Autocomplete}.
     * The only mandatory item is a presenter, {@link #with(AutocompletePresenter)}.
     *
     * @param <T> the data model
     */
    public final static class Builder<T> {
        private EditText source;
        private AutocompletePresenter<T> presenter;
        private AutocompletePolicy policy;
        private AutocompleteCallback<T> callback;
        private Drawable backgroundDrawable;
        private float elevationDp = 6;
        private ViewGroup host;

        private Builder(EditText source) {
            this.source = source;
        }

        /**
         * Registers the {@link AutocompletePresenter} to be used, responsible for showing
         * items. See the class for info.
         *
         * @param presenter desired presenter
         * @return this for chaining
         */
        public Builder<T> with(AutocompletePresenter<T> presenter) {
            this.presenter = presenter;
            return this;
        }

        /**
         * Registers the {@link AutocompleteCallback} to be used, responsible for listening to
         * clicks provided by the presenter, and visibility changes.
         *
         * @param callback desired callback
         * @return this for chaining
         */
        public Builder<T> with(AutocompleteCallback<T> callback) {
            this.callback = callback;
            return this;
        }

        /**
         * Registers the {@link AutocompletePolicy} to be used, responsible for showing / dismissing
         * the popup when certain events happen (e.g. certain characters are typed).
         *
         * @param policy desired policy
         * @return this for chaining
         */
        public Builder<T> with(AutocompletePolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Sets a background drawable for the popup.
         *
         * @param backgroundDrawable drawable
         * @return this for chaining
         */
        public Builder<T> with(Drawable backgroundDrawable) {
            this.backgroundDrawable = backgroundDrawable;
            return this;
        }

        /**
         * Sets elevation for the popup. Defaults to 6 dp.
         *
         * @param elevationDp popup elevation, in DP
         * @return this for chaning.
         */
        public Builder<T> with(float elevationDp) {
            this.elevationDp = elevationDp;
            return this;
        }

        /**
         * Renders into a container in your own layout rather than a popup window. See
         * {@link AutocompletePopup#setHostContainer(ViewGroup)}.
         *
         * @param host the container to render into
         * @return this for chaining
         */
        public Builder<T> withHostContainer(ViewGroup host) {
            this.host = host;
            return this;
        }

        /**
         * Builds an Autocomplete instance. This is enough for autocomplete to be set up,
         * but you can hold a reference to the object and call its public methods.
         *
         * @return an Autocomplete instance, if you need it
         *
         * @throws RuntimeException if either EditText or the presenter are null
         */
        public Autocomplete<T> build() {
            if (source == null) throw new RuntimeException("Autocomplete needs a source!");
            if (presenter == null) throw new RuntimeException("Autocomplete needs a presenter!");
            if (policy == null) policy = new SimplePolicy();
            return new Autocomplete<T>(this);
        }

        private void clear() {
            source = null;
            presenter = null;
            callback = null;
            policy = null;
            backgroundDrawable = null;
            elevationDp = 6;
            host = null;
        }
    }

    /**
     * Entry point for building autocomplete on a certain {@link EditText}.
     * @param anchor the anchor for the popup, and the source of text events
     * @param <T> your data model
     * @return a Builder for set up
     */
    public static <T> Builder<T> on(EditText anchor) {
        return new Builder<T>(anchor);
    }

    private AutocompletePolicy policy;
    private AutocompletePopup popup;
    private AutocompletePresenter<T> presenter;
    private AutocompleteCallback<T> callback;
    private EditText source;

    private boolean block;
    private boolean dismissing;
    private boolean pendingShow;
    private boolean disabled;
    private boolean openBefore;
    private String lastQuery = "null";

    private Autocomplete(Builder<T> builder) {
        policy = builder.policy;
        presenter = builder.presenter;
        callback = builder.callback;
        source = builder.source;

        // Set up popup
        popup = new AutocompletePopup(source.getContext());
        presenter.registerDismissRequest(new Runnable() {
            @Override
            public void run() {
                // Posted: this is raised from inside the presenter's own data pipeline, and dismissing
                // re-enters its lifecycle (hideView/onViewHidden) synchronously if run inline.
                source.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissPopup();
                    }
                });
            }
        });
        popup.setHostContainer(builder.host);
        popup.setAnchorView(source);
        popup.setGravity(Gravity.START);
        popup.setModal(false);
        popup.setBackgroundDrawable(builder.backgroundDrawable);
        popup.setElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, builder.elevationDp,
                source.getContext().getResources().getDisplayMetrics()));

        // popup dimensions
        AutocompletePresenter.PopupDimensions dim = this.presenter.getPopupDimensions();
        popup.setWidth(dim.width);
        popup.setHeight(dim.height);
        popup.setMaxWidth(dim.maxWidth);
        popup.setMaxHeight(dim.maxHeight);

        // Fire visibility events
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                dismissing = false;
                pendingShow = false;
                lastQuery = "null";
                if (callback != null) callback.onPopupVisibilityChanged(false);
                boolean saved = block;
                block = true;
                policy.onDismiss(source.getText());
                block = saved;
                presenter.hideView();
            }
        });

        // Set up source
        source.getText().setSpan(this, 0, source.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        source.addTextChangedListener(this);

        // Set up presenter
        presenter.registerClickProvider(new AutocompletePresenter.ClickProvider<T>() {
            @Override
            public void click(@NonNull T item) {
                AutocompleteCallback<T> callback = Autocomplete.this.callback;
                EditText edit = Autocomplete.this.source;
                if (callback == null) return;
                boolean saved = block;
                block = true;
                boolean dismiss = callback.onPopupItemClicked(edit.getText(), item);
                if (dismiss) dismissPopup();
                block = saved;
            }
        });

        builder.clear();
    }

    /**
     * Controls how the popup operates with an input method.
     *
     * If the popup is showing, calling this method will take effect only
     * the next time the popup is shown.
     *
     * @param mode a {@link PopupWindow} input method mode
     */
    public void setInputMethodMode(int mode) {
        popup.setInputMethodMode(mode);
    }

    /**
     * Sets the operating mode for the soft input area.
     *
     * @param mode The desired mode, see {@link WindowManager.LayoutParams#softInputMode}
     */
    public void setSoftInputMode(int mode) {
        popup.setSoftInputMode(mode);
    }

    /**
     * Shows the popup with the given query.
     * There is rarely need to call this externally: it is already triggered by events on the anchor.
     * To control when this is called, provide a good implementation of {@link AutocompletePolicy}.
     *
     * @param query query text.
     */
    public void showPopup(@NonNull CharSequence query) {
        if ((isPopupShowing() || pendingShow) && lastQuery.equals(query.toString())) return;
        lastQuery = query.toString();

        log("showPopup: called with filter "+query);
        if (!isPopupShowing() && !pendingShow) {
            log("showPopup: showing");
            presenter.registerDataSetObserver(new Observer()); // Calling new to avoid leaking... maybe...
            popup.setView(presenter.getView());
            presenter.showView();
            // Deliberately not shown yet: the window would be created and placed for an empty list, with
            // the presenter's full-size view already inside it, drawing across the anchor until the query
            // returns and moves it. Wait for the first data instead, then show at the right size and place.
            pendingShow = true;
        }
        presenter.onQuery(query);
        // The observer only fires when the data actually changes. Re-opening with the same suggestions
        // produces no callback, so pendingShow would stick and block every later trigger.
        if (pendingShow) {
            source.post(new Runnable() {
                @Override
                public void run() {
                    if (pendingShow && presenter.hasContent()) {
                        pendingShow = false;
                        popup.show();
                        if (callback != null) callback.onPopupVisibilityChanged(true);
                    }
                }
            });
        }
    }

    /**
     * Dismisses the popup, if showing.
     * There is rarely need to call this externally: it is already triggered by events on the anchor.
     * To control when this is called, provide a good implementation of {@link AutocompletePolicy}.
     */
    public void dismissPopup() {
        if (pendingShow) {
            // Never made it on screen, so the popup's OnDismissListener will not run. Do its work here:
            // without policy.onDismiss the policy keeps its marker span on the text, and those accumulate
            // until every keystroke copies thousands of spans and the main thread wedges.
            pendingShow = false;
            lastQuery = "null";
            boolean saved = block;
            block = true;
            policy.onDismiss(source.getText());
            block = saved;
            presenter.hideView();
            return;
        }
        if (isPopupShowing()) {
            if (dismissing) return;
            dismissing = true;
            presenter.animateViewOut(new Runnable() {
                @Override
                public void run() {
                    dismissing = false;
                    if (isPopupShowing()) popup.dismiss();
                }
            });
        }
    }

    /**
     * Showing, or shown as soon as the presenter publishes data. The policy adds a marker span every time
     * it is asked whether to show, so a "not showing" answer during the pending window would have it add
     * one per keystroke until copying them stalls the main thread.
     *
     * @return whether the popup is on screen or about to be
     */
    private boolean isPopupActive() {
        return isPopupShowing() || pendingShow;
    }

    /**
     * Returns true if the popup is showing.
     * @return whether the popup is currently showing
     */
    public boolean isPopupShowing() {
        return this.popup.isShowing();
    }

    /**
     * Switch to control the autocomplete behavior. When disabled, no popup is shown.
     * This is useful if you want to do runtime edits to the anchor text, without triggering
     * the popup.
     *
     * @param enabled whether to enable autocompletion
     */
    public void setEnabled(boolean enabled) {
        disabled = !enabled;
    }

    /**
     * Sets the gravity for the popup. Basically only {@link Gravity#START} and {@link Gravity#END}
     * do work.
     *
     * @param gravity gravity for the popup
     */
    public void setGravity(int gravity) {
        popup.setGravity(gravity);
    }

    /**
     * Controls the vertical offset of the popup from the EditText anchor.
     *
     * @param offset offset in pixels.
     */
    public void setOffsetFromAnchor(int offset) { popup.setVerticalOffset(offset); }

    /**
     * Controls whether the popup should listen to clicks outside its boundaries.
     *
     * @param outsideTouchable true to listen to outside clicks
     */
    public void setOutsideTouchable(boolean outsideTouchable) { popup.setOutsideTouchable(outsideTouchable); }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (block || disabled) return;
        openBefore = isPopupShowing();
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (block || disabled) return;
        if (openBefore && !isPopupShowing()) {
            return; // Copied from somewhere.
        }
        if (!(s instanceof Spannable)) {
            source.setText(new SpannableString(s));
            return;
        }
        Spannable sp = (Spannable) s;

        int cursor = source.getSelectionEnd();
        log("onTextChanged: cursor end position is "+cursor);
        if (cursor == -1) { // No cursor present.
            dismissPopup(); return;
        }
        if (cursor != source.getSelectionStart()) {
            // Not sure about this. We should have no problems dealing with multi selections,
            // we just take the end...
            // dismissPopup(); return;
        }

        boolean b = block;
        block = true; // policy might add spans or other stuff.
        if (isPopupActive() && policy.shouldDismissPopup(sp, cursor)) {
            log("onTextChanged: dismissing");
            dismissPopup();
        } else if (isPopupActive() || policy.shouldShowPopup(sp, cursor)) {
            // LOG.now("onTextChanged: updating with filter "+policy.getQuery(sp));
            showPopup(policy.getQuery(sp));
        }
        block = b;
    }

    @Override
    public void afterTextChanged(Editable s) {}

    @Override
    public void onSpanAdded(Spannable text, Object what, int start, int end) {}

    @Override
    public void onSpanRemoved(Spannable text, Object what, int start, int end) {}

    @Override
    public void onSpanChanged(Spannable text, Object what, int ostart, int oend, int nstart, int nend) {
        if (disabled || block) return;
        if (what == Selection.SELECTION_END) {
            // Selection end changed from ostart to nstart. Trigger a check.
            log("onSpanChanged: selection end moved from "+ostart+" to "+nstart);
            log("onSpanChanged: block is "+block);
            boolean b = block;
            block = true;
            if (!isPopupActive() && policy.shouldShowPopup(text, nstart)) {
                showPopup(policy.getQuery(text));
            }
            block = b;
        }
    }

    private class Observer extends DataSetObserver implements Runnable {
        private Handler ui = new Handler(Looper.getMainLooper());

        @Override
        public void onChanged() {
            // Resize in the same frame as the content changed. Posting leaves the window at its old
            // geometry for a frame, which shows as empty space where a row used to be when the list
            // shrinks, and as a jump when it grows. Fall back to posting if we are inside a layout pass,
            // where measuring the popup's view is not allowed.
            ViewGroup content = popup.getView();
            if (!pendingShow && isPopupShowing() && content != null && !content.isInLayout()) {
                popup.show();
            } else {
                ui.post(this);
            }
        }

        @Override
        public void run() {
            if (pendingShow) {
                pendingShow = false;
                popup.show();
                if (callback != null) callback.onPopupVisibilityChanged(true);
            } else if (isPopupShowing()) {
                // Call show again to revisit width and height.
                popup.show();
            }
        }
    }

    /**
     * A very simple {@link AutocompletePolicy} implementation.
     * Popup is shown when text length is bigger than 0, and hidden when text is empty.
     * The query string is the whole text.
     */
    public static class SimplePolicy implements AutocompletePolicy {
        @Override
        public boolean shouldShowPopup(@NonNull Spannable text, int cursorPos) {
            return text.length() > 0;
        }

        @Override
        public boolean shouldDismissPopup(@NonNull Spannable text, int cursorPos) {
            return text.length() == 0;
        }

        @NonNull
        @Override
        public CharSequence getQuery(@NonNull Spannable text) {
            return text;
        }

        @Override
        public void onDismiss(@NonNull Spannable text) {}
    }
}
