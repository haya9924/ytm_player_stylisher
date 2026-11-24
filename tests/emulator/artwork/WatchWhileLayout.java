package app.morphe.extension.ym;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Minimal top-level (not nested) stand-in for the patched player container.
 *
 * <p>{@link ArtworkGradientView#findOwner()} walks up the parent chain and matches the class name
 * suffix {@code ".WatchWhileLayout"}, so the owner must be a top-level class whose fully qualified
 * name ends with exactly that suffix. Kept package-private in the extension package for that
 * reason; this file is a test fixture, not part of the shipped extension.
 */
final class WatchWhileLayout extends FrameLayout {
    WatchWhileLayout(Context context) {
        super(context);
    }
}
