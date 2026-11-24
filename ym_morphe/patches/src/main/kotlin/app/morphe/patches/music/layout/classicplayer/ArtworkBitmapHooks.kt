package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val BITMAP = "Landroid/graphics/Bitmap;"
private const val THEME = "Lapp/morphe/extension/ym/ArtworkTheme;"

internal fun installArtworkBitmapHooks() {
    // bcgv is also used elsewhere. Bind only the instance injected into playback metadata.
    val owner = Fingerprint(definingClass = "Lnih;", name = "<init>", filters = listOf(
        fieldAccess(definingClass = "Lnih;", name = "m", type = "Lbcgv;", opcode = Opcode.IPUT_OBJECT),
    ))
    val store = owner.instructionMatches.single()
    val loader = (store.instruction as TwoRegisterInstruction).registerA
    owner.method.addInstruction(store.index + 1, "invoke-static/range {v$loader .. v$loader}, $THEME->bindPlaybackArtwork(Ljava/lang/Object;)V")

    // The original URI-checked callbacks and no-art branch converge here. Flags only
    // change the native destination after this point, not our access to the decoded covers.
    val metadata = Fingerprint(definingClass = "Lbcgv;", name = "c", returnType = "V",
        parameters = listOf(BITMAP, BITMAP), filters = listOf(
            methodCall(definingClass = "Lajje;", name = "b", parameters = emptyList(), returnType = "V"),
            methodCall(definingClass = "Lbase;", name = "G", parameters = emptyList(), returnType = "Z"),
        ),
    ).method
    check(metadata.implementation!!.registerCount == 4 && metadata.implementation!!.instructions.first().opcode == Opcode.INVOKE_STATIC)
    metadata.addInstruction(1, "invoke-static {p0, p1, p2}, $THEME->onPlaybackArtwork(Ljava/lang/Object;$BITMAP$BITMAP)V")

    val full = Fingerprint(definingClass = "Lngy;", name = "x", returnType = "V", parameters = listOf(BITMAP),
        filters = listOf(methodCall(definingClass = "Lcom/google/android/apps/youtube/music/ui/image/AutoCropImageView;",
            name = "setImageBitmap", parameters = listOf(BITMAP), returnType = "V")),
    ).method
    check(full.implementation!!.registerCount == 3 && full.implementation!!.instructions.first().opcode == Opcode.IF_NEZ)
    full.addInstruction(0, "invoke-static {p1}, $THEME->onPlayerArtwork($BITMAP)V")
}
