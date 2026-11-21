package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode

private const val POLICY = "Lapp/morphe/extension/ym/PlaybackNetworkPolicy;"

/** Version-specific, fail-closed hooks. No generic HTTP or current-stream interception. */
internal fun installPlaybackPriorityHooks() {
    val admission = Fingerprint(
        definingClass = "Lbbir;", name = "a", returnType = "Lj$/util/Optional;",
        parameters = listOf("Lbbiq;", "Lbbil;", "Lbbio;", "Z"),
        filters = listOf(methodCall(definingClass = "Lbbip;", name = "a",
            parameters = listOf("Lbbio;", "Lbbie;"),
            returnType = "Lcom/google/common/util/concurrent/ListenableFuture;")),
    ).method
    check(admission.implementation!!.registerCount == 10) { "Unsupported prefetch admission registers" }
    check(admission.getInstruction(0).opcode == Opcode.INVOKE_VIRTUAL) { "Unsupported prefetch entry" }
    // bbil.b is the OUTER sequence index. Equal-index nested segments must pass.
    // Both callers already handle Optional.empty without creating a future.
    admission.addInstructionsWithLabels(0, """
        if-eqz p1, :original
        if-eqz p2, :original
        iget-object v0, p1, Lbbiq;->a:Lbbil;
        if-eqz v0, :original
        iget v0, v0, Lbbil;->b:I
        iget v1, p2, Lbbil;->b:I
        if-eq v0, v1, :original
        invoke-static {}, $POLICY->shouldSuppress()Z
        move-result v0
        if-eqz v0, :original
        invoke-static {}, Lj$/util/Optional;->empty()Lj$/util/Optional;
        move-result-object v0
        return-object v0
    """, ExternalLabel("original", admission.getInstruction(0)))

    data class Event(val method: String, val parameters: List<String>, val evidence: String, val hook: String)
    listOf(
        Event("d", emptyList(), "onBuffering()", "onUrgent"),
        Event("p", emptyList(), "onPreparing()", "onUrgent"),
        Event("l", emptyList(), "onPausedBuffering()", "onUrgent"),
        Event("t", listOf("J", "Lcbxe;"), "onSeeking(positionMillis=%d)", "onUrgent"),
        Event("m", listOf("J", "Lcbxe;"), "onPausedSeeking(positionMillis=%d)", "onUrgent"),
        Event("o", emptyList(), "onPlaying()", "onPlaying"),
        Event("k", emptyList(), "onPaused()", "onTerminal"),
        Event("f", emptyList(), "onEnded()", "onTerminal"),
        Event("w", emptyList(), "onStopped()", "onTerminal"),
        Event("g", listOf("Lawbo;"), "MedialibPlayerEvents[%s].onError(code=%s detail=%s)", "onTerminal"),
    ).forEach { event ->
        Fingerprint(definingClass = "Lavdj;", name = event.method,
            returnType = "V", parameters = event.parameters,
            filters = listOf(string(event.evidence)),
        ).method.addInstruction(0, "invoke-static/range {p0 .. p0}, $POLICY->${event.hook}(Ljava/lang/Object;)V")
    }
    // Concrete inherited Application implementation (root -> jax -> jbe -> jbg).
    val startup = Fingerprint(definingClass = "Ljbg;", name = "onCreate", returnType = "V", parameters = emptyList()).method
    val returns = startup.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    check(returns.isNotEmpty()) { "Application initialization has no normal return" }
    returns.asReversed().forEach { index ->
        startup.addInstruction(index, "invoke-static/range {p0 .. p0}, $POLICY->initialize(Landroid/content/Context;)V")
    }
}
