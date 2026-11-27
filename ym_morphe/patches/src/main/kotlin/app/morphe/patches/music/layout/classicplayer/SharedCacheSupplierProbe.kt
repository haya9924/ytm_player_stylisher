package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PROBE = "Lapp/morphe/extension/ym/SharedCacheSupplierProbe;"

/** Diagnostic only: observes the shared cache supplier results, never changes them. */
internal fun installSharedCacheSupplierProbe() {
    val alternateSupplier = Fingerprint(definingClass = "Laubi;", name = "b", returnType = "Lsfx;",
        parameters = emptyList()).method
    val body = alternateSupplier.implementation?.instructions
        ?: error("Shared cache probe: unsupported supplier layout")
    val scopedCall = body.indices.singleOrNull { i ->
        i >= 2 && i + 1 < body.size && body[i].opcode == Opcode.INVOKE_VIRTUAL &&
            (body[i] as ReferenceInstruction).reference.toString() == "Laued;->b(Lawyd;)Lsfx;" &&
            (body[i] as FiveRegisterInstruction).registerD == 1 &&
            body[i - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (body[i - 2] as? ReferenceInstruction)?.reference?.toString() == "Lawye;->e()Lawyd;" &&
            body[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (body[i + 1] as OneRegisterInstruction).registerA == 0
    } ?: error("Shared cache probe: unsupported scoped supplier call")
    val unscopedCall = body.indices.singleOrNull { i ->
        i + 1 < body.size && body[i].opcode == Opcode.INVOKE_VIRTUAL &&
            (body[i] as ReferenceInstruction).reference.toString() == "Laueo;->b()Lsfx;" &&
            body[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (body[i + 1] as OneRegisterInstruction).registerA == 0
    } ?: error("Shared cache probe: unsupported unscoped supplier call")
    check(unscopedCall > scopedCall) { "Shared cache probe: unsupported supplier call order" }
    // Inserted from the higher index down so the earlier index stays valid.
    alternateSupplier.addInstruction(unscopedCall + 2,
        "invoke-static {v0}, $PROBE->unscoped(Ljava/lang/Object;)V")
    alternateSupplier.addInstruction(scopedCall + 2,
        "invoke-static {v0}, $PROBE->scoped(Ljava/lang/Object;)V")
}
