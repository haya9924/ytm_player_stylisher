package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val ARTWORK_THEME = "Lapp/morphe/extension/ym/ArtworkTheme;"

/** Version-specific, fail-closed native artwork palette hook. */
internal fun installArtworkPaletteHooks() {
    val sample = Fingerprint(
        definingClass = "Lrtw;", name = "a", returnType = "V", parameters = listOf("Lqhq;"),
        filters = listOf(
            string("YTM_PLAYER_COLOR_SAMPLE_PALETTE_ENTITY_KEY"),
            methodCall(definingClass = "Lqhq;", name = "a", returnType = "Lckkh;"),
        ),
    ).method
    check(sample.implementation!!.registerCount == 4) { "Unsupported artwork palette registers" }
    check(sample.getInstruction(0).opcode == Opcode.CONST_16) { "Unsupported artwork palette entry" }
    sample.addInstructionsWithLabels(0, """
        if-eqz p1, :fallback
        sget-object v0, Lqhq;->c:Lqhq;
        invoke-virtual {v0, p1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
        move-result v1
        if-nez v1, :fallback
        sget-object v0, Lqhq;->d:Lqhq;
        if-eq p1, v0, :fallback
        sget-object v0, Lqhq;->e:Lqhq;
        if-eq p1, v0, :fallback
        sget-object v0, Lqhq;->f:Lqhq;
        if-eq p1, v0, :fallback
        invoke-virtual {p1}, Lqhq;->a()Lckkh;
        move-result-object v0
        instance-of v1, v0, Lckkd;
        if-eqz v1, :fallback
        check-cast v0, Lckkd;
        iget v1, v0, Lckkd;->a:I
        iget v0, v0, Lckkd;->d:I
        invoke-static {v1, v0}, $ARTWORK_THEME->onNativePalette(II)V
        goto :original
        :fallback
        invoke-static {}, $ARTWORK_THEME->onNativePaletteUnavailable()V
    """, ExternalLabel("original", sample.getInstruction(0)))

    // Keep the native drawables and alpha/state animations; bound only the colors
    // beneath the light player controls (including mini and queue/lyrics states).
    val player = Fingerprint(definingClass = "Lrxx;", name = "B", returnType = "V",
        parameters = listOf("I", "I", "J"),
        filters = listOf(methodCall(definingClass = "Liti;", name = "a", parameters = listOf("[I", "Lj$/time/Duration;"), returnType = "V")),
    ).method
    val impl = player.implementation!!
    check(impl.registerCount == 9 && player.getInstruction(0).opcode == Opcode.IGET)
    // Work at the visual sinks so the original palette still drives the native cache
    // and equality checks. The native central play glyph is drawn within a white disc.
    val instructions = impl.instructions.toList()
    val gradients = instructions.indices.filter { i ->
        instructions[i].opcode == Opcode.FILLED_NEW_ARRAY &&
            (instructions[i] as ReferenceInstruction).reference.toString() == "[I" &&
            instructions.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (instructions.getOrNull(i + 2) as? ReferenceInstruction)?.reference.toString() ==
            "Liti;->a([ILj$/time/Duration;)V"
    }
    fun sink(signature: String) = instructions.withIndex().single { (_, instruction) ->
        (instruction as? ReferenceInstruction)?.reference.toString() == signature
    }
    val mini = sink("Landroid/graphics/drawable/ColorDrawable;-><init>(I)V")
    val glyph = sink("Landroid/content/res/ColorStateList;->valueOf(I)Landroid/content/res/ColorStateList;")
    check(gradients.size == 3 && (mini.value as FiveRegisterInstruction).registerD == 6 &&
        (glyph.value as FiveRegisterInstruction).registerC == 6) { "Unsupported player background sinks" }
    val extra = gradients.associate { i ->
        val dest = (instructions[i + 1] as OneRegisterInstruction).registerA
        i + 2 to "invoke-static {v$dest}, $ARTWORK_THEME->protectPlayerStops([I)V"
    }.toMutableMap()
    // The two sinks read p2. Use a dead local and substitute the sink register, not p2.
    player.replaceInstruction(mini.index, "invoke-direct {v0, v1}, Landroid/graphics/drawable/ColorDrawable;-><init>(I)V")
    player.replaceInstruction(glyph.index, "invoke-static {v1}, Landroid/content/res/ColorStateList;->valueOf(I)Landroid/content/res/ColorStateList;")
    for (index in listOf(mini.index, glyph.index)) {
        extra[index] = """
            invoke-static {p2}, $ARTWORK_THEME->readablePlayerBackground(I)I
            move-result v1
        """
    }
    for ((i, asm) in extra.toSortedMap(reverseOrder())) player.addInstructions(i, asm)
    val sheet = Fingerprint(definingClass = "Lrxu;", name = "b", returnType = "V",
        parameters = listOf("I"),
        filters = listOf(methodCall(smali = "Lcom/google/android/apps/youtube/music/watchpage/ui/PlayerBottomSheet;->setBackgroundColor(I)V")),
    ).method
    check(sheet.implementation!!.registerCount == 2 && sheet.getInstruction(0).opcode == Opcode.IGET_OBJECT)
    sheet.addInstructions(0, """
        invoke-static {p1}, $ARTWORK_THEME->readablePlayerBackground(I)I
        move-result p1
    """)

    val startup = Fingerprint(definingClass = "Ljbg;", name = "onCreate", returnType = "V", parameters = emptyList()).method
    val returns = startup.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
    check(returns.isNotEmpty()) { "Missing application startup return" }
    returns.asReversed().forEach {
        startup.addInstruction(it.index, "invoke-static/range {p0 .. p0}, $ARTWORK_THEME->initialize(Landroid/content/Context;)V")
    }
}
