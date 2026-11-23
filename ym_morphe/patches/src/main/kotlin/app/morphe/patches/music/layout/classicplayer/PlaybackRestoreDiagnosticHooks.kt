package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val RESTORE_DIAG = "Lapp/morphe/extension/ym/PlaybackRestoreDiagnostics;"

internal fun installPlaybackRestoreDiagnosticHooks() {
    val restore = Fingerprint(definingClass = "Logl;", name = "f", returnType = "V",
        parameters = listOf("Logv;", "Logh;", "Z"), filters = listOf(string("restoreLocalQueue"))).method
    val code = restore.implementation!!.instructions
    val paused = code.indices.single { i ->
        i + 4 < code.size && code[i].opcode == Opcode.NEW_INSTANCE &&
            (code[i] as ReferenceInstruction).reference.toString() == "Lbajl;" &&
            (code[i + 1] as? ReferenceInstruction)?.reference?.toString() == "Logv;->c()J" &&
            code[i + 2].opcode == Opcode.MOVE_RESULT_WIDE &&
            (code[i + 2] as OneRegisterInstruction).registerA == 13 &&
            (code[i + 3] as? ReferenceInstruction)?.reference?.toString() == "Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;"
    }
    restore.addInstruction(paused + 3, "invoke-static/range {v13 .. v14}, $RESTORE_DIAG->restoredPaused(J)V")

    val expiration = Fingerprint(definingClass = "Lbckg;", name = "bj", returnType = "V",
        parameters = listOf("Lbclk;", "Lbatu;"),
        filters = listOf(string("Max reloads [%s] reached on expired stream load."))).method
    val expiry = expiration.implementation!!.instructions
    val checked = expiry.indices.single { i ->
        i + 1 < expiry.size && expiry[i].opcode == Opcode.INVOKE_STATIC &&
            (expiry[i] as ReferenceInstruction).reference.toString() == "Lbaub;->a(Laqdn;Lwjp;)Z" &&
            expiry[i + 1].opcode == Opcode.MOVE_RESULT && (expiry[i + 1] as OneRegisterInstruction).registerA == 4
    }
    expiration.addInstruction(checked + 2, "invoke-static {v4}, $RESTORE_DIAG->streamExpired(Z)V")
}
