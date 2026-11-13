package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

/** YouTube Music 9.30.53 player styling, shipped without the upstream patch collection. */
@Suppress("unused")
val classicPlayerPatch = patch(
    name = "ym_morphe",
    description = "Changes the fullscreen player artwork, spacing, title, menu icon and artwork-derived background gradient.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            name = "YouTube Music",
            packageName = "com.google.android.apps.youtube.music",
            apkFileType = ApkFileType.APK_REQUIRED,
            targets = listOf(AppTarget(version = "9.30.53", minSdk = 26)),
        )
    )

    extendWith("extensions/ym.mpe")

    execute {
        // The Settings activity starts preference intents. Make our intent explicit at runtime
        // so it works with Android 14+ and with arbitrary cloned package names.
        Fingerprint(
            definingClass = "Lcom/google/android/apps/youtube/music/settings/SettingsCompatActivity;",
            name = "startActivity",
            returnType = "V",
            parameters = listOf("Landroid/content/Intent;"),
        ).method.addInstruction(
            0,
            "invoke-static {p0, p1}, Lapp/morphe/extension/ym/GradientSettingsActivity;->routeIntent(Landroid/app/Activity;Landroid/content/Intent;)V"
        )
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("activity") {
                setAttribute("android:name", "app.morphe.extension.ym.GradientSettingsActivity")
                setAttribute("android:exported", "false")
                setAttribute("android:theme", "@android:style/Theme.Material.NoActionBar")
                appendChild("intent-filter") {
                    appendChild("action") { setAttribute("android:name", "app.morphe.extension.ym.GRADIENT_SETTINGS") }
                    appendChild("category") { setAttribute("android:name", "android.intent.category.DEFAULT") }
                }
            }
        }
        resourceXml("res/xml/settings_headers.xml").use { settings ->
            settings.root.appendChild("Preference") {
                setAttribute("android:key", "ym_morphe_gradient_settings")
                setAttribute("android:title", "ym_morphe グラデーション設定")
                setAttribute("android:persistent", "false")
                appendChild("intent") { setAttribute("android:action", "app.morphe.extension.ym.GRADIENT_SETTINGS") }
            }
        }
        resourceXml("res/layout/watch_while_layout.xml").use { layout ->
            // Preserve the original zero elevation: raising the artwork above the scrims and
            // controls overrides WatchWhileLayout's state-dependent child drawing order.
            layout.root.setAttribute("yt:watchFragmentCornerRadiusMaximizedLarge", "8dp")
            layout.root.setAttribute("yt:watchFragmentCornerRadiusMaximizedMedium", "8dp")
            // The native background is hidden in minimized states and its Drawable is
            // inspected by the app. Keep it intact; insert our non-interactive layer
            // above it but below the scrims, artwork and controls for every state.
            val nativeBackground = layout.elementByAttribute("android:id", "@id/player_page_background")
            val playerPage = nativeBackground.parent ?: error("player background has no parent")
            check(nativeBackground.index >= 0) { "player background is not a direct child" }
            playerPage.insertChild(nativeBackground.index + 1, "app.morphe.extension.ym.ArtworkGradientView") {
                setAttribute("android:layout_width", "match_parent")
                setAttribute("android:layout_height", "match_parent")
                setAttribute("android:clickable", "false")
                setAttribute("android:focusable", "false")
                setAttribute("android:importantForAccessibility", "no")
            }
            val song = layout.elementByAttribute("android:id", "@id/current_song_view")
            song.setAttribute("android:paddingStart", "24dp")
            song.setAttribute("android:paddingEnd", "24dp")
            layout.elementByAttribute("android:id", "@id/contextual_menu")
                .setAttribute("android:src", "@drawable/yt_outline_experimental_overflow_horizontal_vd_theme_24")
            song.elementByAttribute("android:id", "@id/title")
                .apply {
                    setAttribute("android:textSize", "22sp")
                    setAttribute("android:textStyle", "bold")
                }
        }
        resourceXml("res/layout/playback_controls.xml").use { layout ->
            layout.elementByAttribute("android:id", "@id/time_bar").apply {
                setAttribute("android:paddingStart", "24dp")
                setAttribute("android:paddingEnd", "24dp")
            }
        }
    }
}
