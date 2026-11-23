package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** After a successful paused local restore only, omit the redundant server queue fetch. */
internal fun installLowDataPlaybackResumeHook() {
    val restore = Fingerprint(definingClass = "Logl;", name = "f", returnType = "V",
        parameters = listOf("Logv;", "Logh;", "Z"), filters = listOf(string("restoreLocalQueue"))).method
    val implementation = restore.implementation!!
    val instructions = implementation.instructions
    check(implementation.registerCount == 21)
    fun reference(i: Int) = (instructions[i] as? ReferenceInstruction)?.reference?.toString()
    val start = instructions.indices.single { i ->
        i + 2 < instructions.size && reference(i) == "Logl;->e(Logv;Z)V" &&
            instructions[i + 1].opcode == Opcode.IF_NEZ &&
            reference(i + 2) == "Logl;->d:Lckup;"
    } + 2
    val continuation = (start + 1 until instructions.size).first { reference(it) == "Logl;->s:Lckup;" }
    check((start until continuation).count { reference(it) ==
        "Lojo;->a(Ljava/util/concurrent/Executor;)Lcom/google/common/util/concurrent/ListenableFuture;" } == 1)
    check(reference(continuation - 1) ==
        "Lbjkn;->l(Lcom/google/common/util/concurrent/ListenableFuture;Lblhh;Ljava/util/concurrent/Executor;)V")
    // v1 still holds Logv, and v2/v3 are scratch on this tail. Preserve the
    // follow-up request unless the host selected paused mode and has a saved item.
    restore.addInstructionsWithLabels(start, """
        move-object/from16 v2, p2
        sget-object v3, Logh;->a:Logh;
        if-ne v2, v3, :fetch_server
        invoke-virtual {v1}, Logv;->D()Lj$/util/Optional;
        move-result-object v2
        invoke-virtual {v2}, Lj$/util/Optional;->isPresent()Z
        move-result v2
        if-nez v2, :continue_local
    """, ExternalLabel("continue_local", instructions[continuation]),
        ExternalLabel("fetch_server", instructions[start]))
}
