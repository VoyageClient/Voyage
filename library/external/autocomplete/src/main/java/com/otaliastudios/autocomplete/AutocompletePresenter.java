package com.otaliastudios.autocomplete;

import android.content.Context;
import android.database.DataSetObserver;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Base class for presenting items inside a popup. This is abstract and must be implemented.
 *
 * Most important methods are {@link #getView()} and {@link #onQuery(CharSequence)}.
 */
public abstract class AutocompletePresenter<T> {

    private Context context;
    private boolean isShowing;
    private Runnable dismissRequest;

    @SuppressWarnings("WeakerAccess")
    public AutocompletePresenter(@NonNull Context context) {
        this.context = context;
    }

    /**
     * At this point the presenter is passed the {@link ClickProvider}.
     * The contract is that {@link ClickProvider#click(Object)} must be called when a list item
     * is clicked. This ensure that the autocomplete callback will receive the event.
     *
     * @param provider a click provider for this presenter.
     */
    protected void registerClickProvider(ClickProvider<T> provider) {

    }

    /**
     * Useful if you wish to change width/height based on content height.
     * The contract is to call {@link DataSetObserver#onChanged()} when your view has
     * changes.
     *
     * This is called after {@link #getView()}.
     *
     * @param observer the observer.
     */
    protected void registerDataSetObserver(@NonNull DataSetObserver observer) {}

    /**
     * Called each time the popup is shown. You are meant to inflate the view here.
     * You can get a LayoutInflater using {@link #getContext()}.
     *
     * @return a ViewGroup for the popup
     */
    @NonNull
    protected abstract ViewGroup getView();

    /**
     * Provide the {@link PopupDimensions} for this popup. Called just once.
     * You can use fixed dimensions or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} and
     * {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}.
     *
     * @return a PopupDimensions object
     */
    // Called at first to understand which dimensions to use for the popup.
    @NonNull
    protected PopupDimensions getPopupDimensions() {
        return new PopupDimensions();
    }

    /**
     * Perform firther initialization here. Called after {@link #getView()},
     * each time the popup is shown.
     */
    protected abstract void onViewShown();

    /**
     * Called to update the view to filter results with the query.
     * It is called any time the popup is shown, and any time the text changes and query is updated.
     *
     * @param query query from the edit text, to filter our results
     */
    protected abstract void onQuery(@Nullable CharSequence query);

    /**
     * Called when the popup is hidden, to release resources.
     */
    protected abstract void onViewHidden();

    /**
     * @return whether this presenter currently has something to display. Used to show a popup whose data
     * did not change since last time, where no data-set callback is raised to trigger it.
     */
    public boolean hasContent() {
        return true;
    }

    /**
     * Asks for the popup to be dismissed, going through the same animated path as any other dismissal.
     * Call this instead of publishing an empty result set: emptying the list first collapses the popup to
     * a sliver, and what animates away is then a bare strip rather than the items the user was looking at.
     */
    protected final void requestDismiss() {
        if (dismissRequest != null) dismissRequest.run();
    }

    final void registerDismissRequest(@NonNull Runnable request) {
        dismissRequest = request;
    }

    /**
     * Called just before the popup is dismissed, so the presenter can animate its content out.
     * Implementations must invoke {@code onEnd} when done. The default dismisses immediately.
     *
     * @param onEnd run this to let the dismissal proceed
     */
    protected void animateViewOut(@NonNull Runnable onEnd) {
        onEnd.run();
    }

    /**
     * @return this presenter context
     */
    @NonNull
    protected final Context getContext() {
        return context;
    }

    /**
     * @return whether we are showing currently
     */
    @SuppressWarnings("unused")
    protected final boolean isShowing() {
        return isShowing;
    }

    final void showView() {
        isShowing = true;
        onViewShown();
    }

    final void hideView() {
        isShowing = false;
        onViewHidden();
    }

    public interface ClickProvider<T> {
        void click(@NonNull T item);
    }

    /**
     * Provides width, height, maxWidth and maxHeight for the popup.
     * @see #getPopupDimensions()
     */
    @SuppressWarnings("WeakerAccess")
    public static class PopupDimensions {
        public int width = ViewGroup.LayoutParams.WRAP_CONTENT;
        public int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        public int maxWidth = Integer.MAX_VALUE;
        public int maxHeight = Integer.MAX_VALUE;
    }
}
