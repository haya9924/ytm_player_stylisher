package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val DATA_DIAG = "Lapp/morphe/extension/ym/DataSavingDiagnostics;"

internal fun enableDataSavingDiagnostics() {
    val startup = Fingerprint(definingClass = "Ljbg;", name = "onCreate", returnType = "V",
        parameters = emptyList()).method
    startup.addInstruction(0, "invoke-static {}, $DATA_DIAG->enable()V")
}

/** After the host publishes a saved paused queue, omit only its optional follow-up fetch. */
internal fun installLowDataPlaybackResumeHook(save: Boolean, diagnostics: Boolean) {
    val restore = Fingerprint(definingClass = "Logl;", name = "f", returnType = "V",
        parameters = listOf("Logv;", "Logh;", "Z"), filters = listOf(string("restoreLocalQueue"))).method
    val implementation = restore.implementation!!
    val instructions = implementation.instructions.toList()
    check(implementation.registerCount == 21)
    fun reference(i: Int) = (instructions[i] as? ReferenceInstruction)?.reference?.toString()
    val start = instructions.indices.single { i ->
        i + 2 < instructions.size && reference(i) == "Logl;->e(Logv;Z)V" &&
            instructions[i + 1].opcode == Opcode.IF_NEZ &&
            reference(i + 2) == "Logl;->d:Lckup;"
    } + 2
    val continuation = (start + 1 until instructions.size).first { reference(it) == "Logl;->s:Lckup;" }
    val request = (start until continuation).single { reference(it) ==
        "Lojo;->a(Ljava/util/concurrent/Executor;)Lcom/google/common/util/concurrent/ListenableFuture;" }
    check(instructions[request + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
    check(reference(continuation - 1) ==
        "Lbjkn;->l(Lcom/google/common/util/concurrent/ListenableFuture;Lblhh;Ljava/util/concurrent/Executor;)V")
    if (diagnostics) restore.addInstruction(request + 2, "invoke-static {}, $DATA_DIAG->onRestoreFollowupSubmitted()V")
    if (!save) return
    // v1 still holds Logv, and v2/v3 are scratch here. Both native continuation
    // and the normal server path overwrite these scratch registers before use.
    val logSkip = if (diagnostics) "invoke-static {}, $DATA_DIAG->onRestoreFollowupSkipped()V" else ""
    restore.addInstructionsWithLabels(start, """
        move-object/from16 v2, p2
        sget-object v3, Logh;->a:Logh;
        if-ne v2, v3, :fetch_server
        invoke-virtual {v1}, Logv;->D()Lj$/util/Optional;
        move-result-object v2
        invoke-virtual {v2}, Lj$/util/Optional;->isPresent()Z
        move-result v2
        if-eqz v2, :fetch_server
        $logSkip
        goto :continue_local
    """, ExternalLabel("continue_local", instructions[continuation]),
        ExternalLabel("fetch_server", instructions[start]))
}
