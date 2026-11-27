package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml

private const val DRAWABLE_CLASS = "app.morphe.extension.ym.ArtworkSurfaceDrawable"
private const val BACKGROUND = "android:background"

/** Bind known dark surfaces, rather than recoloring shared black/white resources used by icons. */
internal fun installArtworkSurfaceResources() {
    fun drawable(name: String, role: Int, stock: String, unthemed: String? = null): String {
        val file = resourceFile("res/drawable/ym_artwork_$name.xml", copy = false)
        file.parentFile.mkdirs()
        val fallback = unthemed?.let { " android:color=\"$it\"" }.orEmpty()
        file.writeText("""<$DRAWABLE_CLASS xmlns:android="http://schemas.android.com/apk/res/android"
            android:level="$role" android:drawable="$stock"$fallback />""".trimIndent())
        return "@drawable/ym_artwork_$name"
    }
    val main = drawable("main", 0, "?attr/musicBackgroundColor", "@color/theme_main_window_background")
    val canvas = drawable("canvas", 0, "@android:color/transparent")
    val library = drawable("library", 0, "@color/theme_main_window_background")
    val preferences = drawable("preferences", 0, "@color/ytm_theme_main_color_dark")
    val chromeEmpty = drawable("chrome_empty", 1, "@android:color/transparent")
    val chromeBlack = drawable("chrome_black", 1, "@color/ytm_color_black")
    val chromePreferences = drawable("chrome_preferences", 1, "@color/ytm_theme_main_color_dark")
    val pivot = drawable("pivot", 1, "@color/ytm_color_grey_12")
    val chromeHeader = drawable("chrome_header", 1, "@color/header_color")
    val sheet = drawable("sheet", 2, "@color/ytm_theme_main_color_primary")
    val square = drawable("sheet_square", 2, "?attr/ytBrandBackgroundSolid", "@color/yt_black3")
    val rounded = drawable("sheet_rounded", 2, "@drawable/bottom_sheet_rounded_corners_background")
    val dialog = drawable("dialog", 2, "@drawable/dialog_background")
    val dialogSolid = drawable("dialog_solid", 2, "@color/header_color")
    val popup = drawable("popup", 2, "@drawable/popup_window_background")

    fun root(file: String, background: String) = resourceXml("res/layout/$file.xml").use {
        it.root.setAttribute(BACKGROUND, background)
    }
    resourceXml("res/layout/main_activity.xml").use {
        it.elementByAttribute("android:id", "@id/watch_while_layout_wrapper").setAttribute(BACKGROUND, main)
    }
    root("pivot_bar", pivot)
    root("explore_browse_fragment", canvas)
    for (name in listOf("browse_fragment", "browse_unlimited_fragment", "library_browse_fragment")) {
        resourceXml("res/layout/$name.xml").use {
            it.root.setAttribute(BACKGROUND, if (name == "library_browse_fragment") library else canvas)
            it.elementByAttribute("android:id", "@id/app_bar").setAttribute(BACKGROUND, chromeEmpty)
        }
    }
    for ((name, toolbarId) in listOf("search_input_fragment" to "search_toolbar", "search_result_fragment" to "search_result_toolbar")) {
        resourceXml("res/layout/$name.xml").use {
            it.root.setAttribute(BACKGROUND, canvas)
            it.elementByAttribute("android:id", "@id/$toolbarId").setAttribute(BACKGROUND, chromeBlack)
        }
    }
    resourceXml("res/layout/detail_page_tabbed_fragment.xml").use {
        it.root.setAttribute(BACKGROUND, canvas)
        it.elementByAttribute("android:id", "@id/detail_page_app_bar").setAttribute(BACKGROUND, chromeEmpty)
    }
    resourceXml("res/layout/detail_page_header.xml").use {
        it.root.children.first { child -> child.attribute(BACKGROUND) == "@color/ytm_color_black" }
            .setAttribute(BACKGROUND, chromeBlack)
        it.elementByAttribute("android:id", "@id/header_info_container").setAttribute(BACKGROUND, chromeHeader)
    }
    for (name in listOf("preference_activity", "two_pane_preference_activity", "browser_activity")) {
        resourceXml("res/layout/$name.xml").use {
            it.root.setAttribute(BACKGROUND, preferences)
            it.elementByAttribute("android:id", "@id/toolbar").setAttribute(BACKGROUND, chromePreferences)
        }
    }
    root("bottom_sheet_modal_container_square_corners", square)
    root("bottom_sheet_modal_container_round_corners", rounded)
    // Wrap the fill shape inside the inset so its stroke remains distinct.
    resourceXml("res/drawable/dialog_inset_background.xml").use { xml ->
        xml.root.setAttribute("android:drawable", dialog)
    }
    // Update only the background item; preserve all other values of each host style.
    resourceXml("res/values/styles.xml").use { xml ->
        fun styleBackground(name: String, item: String, background: String) {
            val style = xml.elementByAttribute("name", name)
            style.children("item").first { it.attribute("name") == item }.text = background
        }
        styleBackground("RemixBottomSheetDialogStyle", BACKGROUND, sheet)
        styleBackground("Theme.YouTubeMusic.Dialog.Solid", "android:windowBackground", dialogSolid)
        styleBackground("Theme.YouTubeMusic.FullscreenDialog.Solid", "android:windowBackground", dialogSolid)
        styleBackground("Theme.YouTubeMusic.Settings.Compat.PopupMenuStyle", "android:popupBackground", popup)
    }
}
