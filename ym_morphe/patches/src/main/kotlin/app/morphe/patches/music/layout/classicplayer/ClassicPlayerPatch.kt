package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

/** YouTube Music 9.30.53 player styling, shipped without the upstream patch collection. */
@Suppress("unused")
val classicPlayerPatch = patch(
    name = "ym_morphe",
    description = "Restyles the fullscreen player and adds an option to hide the audio/video switch.",
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

    val cacheDiagnostics by booleanOption(
        key = "cacheRetentionDiagnostics",
        default = false,
        title = "再生・キャッシュ診断（開発用）",
        description = "観測用ログだけを追加します。再生回数の記録やキャッシュ設定は変更しません。",
    )

    val experimentalRecording by booleanOption(
        key = "experimentalScopedPlayRecording",
        default = false,
        title = "曲別の再生回数を端末内に記録（実験的）",
        description = "対応する通常再生だけを記録します。音声の保持や通信設定は変更しません。",
    )

    val capacityRetention by booleanOption(
        key = "experimentalNativeCacheRetention",
        default = false,
        title = "よく聴く曲の標準キャッシュを優先保持（実験的）",
        description = "曲別の再生記録が必要です。標準キャッシュの容量不足時だけ削除候補を調整します。",
    )

    val lowDataResume by booleanOption(
        key = "lowDataPlaybackResume",
        default = false,
        title = "前回の再生を少ない通信で再開する（実験的）",
        description = "端末に保存済みのキューを一時停止状態で復元できた場合、追加のサーバーキュー取得を省きます。",
    )

    execute {
        if (lowDataResume == true) installLowDataPlaybackResumeHook()
        check(capacityRetention != true || experimentalRecording == true) {
            "Native cache retention requires scoped playback recording"
        }
        if (cacheDiagnostics == true) {
            installCacheRetentionDiagnosticHooks()
            installPlaybackRestoreDiagnosticHooks()
        }
        if (experimentalRecording == true) installScopedPlayRecordingHooks()
        if (experimentalRecording == true && (capacityRetention == true || cacheDiagnostics == true)) {
            installNativeCacheRetentionHooks(apply = capacityRetention == true)
        }
        installPlaybackPriorityHooks()
        installAudioVideoPillHooks()
        installPlayerActionMenuHook()
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
                setAttribute("android:title", "ym_morphe プレイヤー設定")
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
