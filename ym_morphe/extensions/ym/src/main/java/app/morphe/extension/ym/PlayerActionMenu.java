package app.morphe.extension.ym;

import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

/**
 * Wraps an existing player-row click listener with a small action menu.
 *
 * <p>The host row and its song can change between the click and the menu selection, so every
 * custom action re-checks the anchor and {@link Actions#isAvailable(View)}. Unavailable actions
 * and popup failures fall back to the original listener exactly once on the same view.
 */
public final class PlayerActionMenu {
    private static final int MENU_ORIGINAL = 1;
    private static final int MENU_TRACK = 2;

    /** Callbacks supplied by the caller for the row that was clicked. */
    interface Actions {
        /** Re-check that the related song/row can still be acted on. */
        boolean isAvailable(View anchor);

        /** Open the track actions for the still-available anchor. */
        void open(View anchor);
    }

    private PlayerActionMenu() {}

    /** Preserves the native registration until a verified action-row provider is available. */
    public static void bind(View anchor, View.OnClickListener original) {
        anchor.setOnClickListener(original);
    }

    /**
     * Returns the original listener unchanged when no custom actions can be offered, otherwise
     * a listener that shows the native-menu/track-actions choice.
     */
    static View.OnClickListener wrap(View.OnClickListener original, Actions actions) {
        if (original == null || actions == null) return original;
        return anchor -> {
            if (!isUsable(anchor, actions)) {
                original.onClick(anchor);
                return;
            }
            showMenu(anchor, original, actions);
        };
    }

    private static void showMenu(View anchor, View.OnClickListener original, Actions actions) {
        try {
            PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
            popup.getMenu().add(Menu.NONE, MENU_ORIGINAL, Menu.NONE, "標準メニュー");
            popup.getMenu().add(Menu.NONE, MENU_TRACK, Menu.NONE, "曲の操作");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == MENU_ORIGINAL) {
                    original.onClick(anchor);
                    return true;
                }
                if (item.getItemId() == MENU_TRACK) {
                    // The row may have been recycled or the track may have changed while open.
                    if (isUsable(anchor, actions)) {
                        actions.open(anchor);
                    } else {
                        original.onClick(anchor);
                    }
                    return true;
                }
                return false;
            });
            popup.show();
        } catch (RuntimeException popupUnavailable) {
            // Never swallow an original-listener failure: this call is outside the catch's path.
            original.onClick(anchor);
        }
    }

    /** Safe availability probe; a detached/hidden anchor or a throwing check is unavailable. */
    private static boolean isUsable(View anchor, Actions actions) {
        if (anchor == null || !anchor.isShown() || !anchor.isAttachedToWindow()) return false;
        try {
            return actions.isAvailable(anchor);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
