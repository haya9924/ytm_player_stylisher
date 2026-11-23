package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val DIAGNOSTICS = "Lapp/morphe/extension/ym/CacheRetentionDiagnostics;"

/** Opt-in probes only; no access to track metadata and no cache operation. */
internal fun installCacheRetentionDiagnosticHooks() {
    val watch = Fingerprint(
        definingClass = "Lbbyh;", name = "a", returnType = "V", parameters = listOf("Z", "J"),
        filters = listOf(string("finishWatchTimeSegment called after client was already released.")),
    ).method
    val supplier = Fingerprint(
        definingClass = "Laueo;", name = "b", returnType = "Lsfx;", parameters = emptyList(),
    ).method
    val returns = supplier.implementation!!.instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode == Opcode.RETURN_OBJECT) {
            index to (instruction as OneRegisterInstruction).registerA
        } else null
    }
    check(returns.size == 3) { "Cache diagnostics: unsupported cache supplier return layout" }
    val alternate = Fingerprint(
        definingClass = "Laugy;", name = "c", returnType = "Lsgc;",
        parameters = listOf("Ljava/lang/String;", "J"),
        filters = listOf(methodCall(definingClass = "Lsfw;", name = "b",
            parameters = listOf("Lsfx;", "Lsgc;", "Lsgc;"), returnType = "V")),
    ).method
    val instructions = alternate.implementation!!.instructions
    val hit = instructions.indices.singleOrNull { index ->
        // The cached-span branch exits after its listener loop (a back edge).
        // The other return of v4 comes straight after unlock on a hole path.
        index > 0 && instructions[index].opcode == Opcode.RETURN_OBJECT &&
            (instructions[index] as OneRegisterInstruction).registerA == 4 &&
            instructions[index - 1].opcode == Opcode.GOTO
    } ?: error("Cache diagnostics: unsupported alternate cached-span return")

    watch.addInstruction(0, "invoke-static {}, $DIAGNOSTICS->watchTimeCallback()V")
    alternate.replaceInstruction(hit, "invoke-static {}, $DIAGNOSTICS->alternateCachedSpan()V")
    alternate.addInstruction(hit + 1, "return-object v4")
    for ((index, register) in returns.asReversed()) {
        // Replacing rather than inserting keeps existing branch targets on the probe.
        supplier.replaceInstruction(index,
            "invoke-static/range {v$register .. v$register}, $DIAGNOSTICS->cacheSupplierReturned(Ljava/lang/Object;)V")
        supplier.addInstruction(index + 1, "return-object v$register")
    }
}
