package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
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

    watch.addInstruction(0, "invoke-static {}, $DIAGNOSTICS->watchTimeCallback()V")
    for ((index, register) in returns.asReversed()) {
        // Replacing rather than inserting keeps existing branch targets on the probe.
        supplier.replaceInstruction(index,
            "invoke-static/range {v$register .. v$register}, $DIAGNOSTICS->cacheSupplierReturned(Ljava/lang/Object;)V")
        supplier.addInstruction(index + 1, "return-object v$register")
    }
}
