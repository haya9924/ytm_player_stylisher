package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val ARTWORK_THEME = "Lapp/morphe/extension/ym/ArtworkTheme;"

/** Version-specific, fail-closed artwork paint hook for the Litho artwork drawable. */
internal fun installArtworkLithoHooks() {
    val draw = Fingerprint(
        definingClass = "Lxjy;",
        name = "draw",
        returnType = "V",
        parameters = listOf("Landroid/graphics/Canvas;"),
        filters = listOf(
            methodCall(
                definingClass = "Landroid/graphics/Canvas;",
                name = "drawRect",
                returnType = "V",
                parameters = listOf("Landroid/graphics/RectF;", "Landroid/graphics/Paint;"),
            ),
            methodCall(
                definingClass = "Landroid/graphics/Canvas;",
                name = "drawPath",
                returnType = "V",
                parameters = listOf("Landroid/graphics/Path;", "Landroid/graphics/Paint;"),
            ),
        ),
    ).method

    // .locals 2 with p0 (Drawable) and p1 (Canvas).
    check(draw.implementation!!.registerCount == 4) { "Unsupported artwork litho registers" }

    val entry = draw.getInstruction(0)
    check(entry.opcode == Opcode.IGET) { "Unsupported artwork litho entry" }
    check((entry as TwoRegisterInstruction).registerA == 0) { "Unsupported artwork litho entry" }

    draw.addInstructions(
        0,
        """
            iget-object v0, p0, Lxjy;->f:Landroid/graphics/Paint;
            iget v1, p0, Lxjy;->a:I
            invoke-static {p0, v0, v1}, $ARTWORK_THEME->updateLithoPaint(Landroid/graphics/drawable/Drawable;Landroid/graphics/Paint;I)V
        """,
    )
}
