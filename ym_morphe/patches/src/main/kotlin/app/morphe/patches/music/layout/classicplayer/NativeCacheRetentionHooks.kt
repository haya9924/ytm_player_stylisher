package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val POLICY = "Lapp/morphe/extension/ym/NativeCacheRetention;"

/** Only the victim of the existing capacity-eviction call can change. */
internal fun installNativeCacheRetentionHooks(apply: Boolean) {
    val provider = Fingerprint(definingClass = "Lavmo;", name = "X", returnType = "Lawqn;",
        parameters = listOf("Lavhw;")).method
    val body = provider.implementation!!.instructions
    val returned = body.indices.single { index ->
        index + 1 < body.size && body[index].opcode == Opcode.INVOKE_VIRTUAL &&
            (body[index] as ReferenceInstruction).reference.toString() == "Laued;->b(Lawyd;)Lsfx;" &&
            (body[index] as FiveRegisterInstruction).registerD == 2 &&
            body[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (body[index + 1] as OneRegisterInstruction).registerA == 0
    } + 1
    val eviction = Fingerprint(definingClass = "Laugy;", name = "x", returnType = "V",
        parameters = emptyList(), filters = listOf(string("m.noopEvict"))).method
    val implementation = eviction.implementation!!
    val native = implementation.instructions
    check(implementation.registerCount == 7)
    val capacity = native.indices.single { i ->
        i >= 2 && native[i].opcode == Opcode.INVOKE_VIRTUAL &&
            (native[i] as ReferenceInstruction).reference.toString() == "Laugy;->E(Ljava/lang/String;Z)V" &&
            (native[i] as FiveRegisterInstruction).let { it.registerC == 6 && it.registerD == 0 && it.registerE == 2 } &&
            native[i - 1].opcode == Opcode.CONST_4 && (native[i - 1] as OneRegisterInstruction).registerA == 2 &&
            native[i - 2].opcode == Opcode.CHECK_CAST &&
            (native[i - 2] as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            (native[i - 2] as OneRegisterInstruction).registerA == 0
    }
    val lookup = Fingerprint(definingClass = "Laugy;", name = "c", returnType = "Lsgc;",
        parameters = listOf("Ljava/lang/String;", "J")).method
    val lookupCode = lookup.implementation!!.instructions
    check(lookup.implementation!!.registerCount == 15)
    val cached = lookupCode.indices.single { i ->
        i + 1 < lookupCode.size && lookupCode[i].opcode == Opcode.IGET_BOOLEAN &&
            (lookupCode[i] as ReferenceInstruction).reference.toString() == "Lsgc;->d:Z" &&
            (lookupCode[i] as TwoRegisterInstruction).let { it.registerA == 5 && it.registerB == 4 } &&
            lookupCode[i + 1].opcode == Opcode.IF_EQZ &&
            (lookupCode[i + 1] as OneRegisterInstruction).registerA == 5
    }
    val write = Fingerprint(definingClass = "Laugy;", name = "f", returnType = "Ljava/io/File;",
        parameters = listOf("Ljava/lang/String;", "J", "J", "Laust;")).method
    val writeCode = write.implementation!!.instructions
    check(write.implementation!!.registerCount == 37)
    val request = writeCode.indices.single { i ->
        writeCode[i].opcode == Opcode.INVOKE_INTERFACE &&
            (writeCode[i] as ReferenceInstruction).reference.toString() == "Lauge;->x()V"
    }
    val cleanup = writeCode.withIndex().filter { it.index > request && it.value.opcode == Opcode.MOVE_EXCEPTION }
    check(cleanup.size == 2)
    cleanup.asReversed().forEach { write.addInstruction(it.index + 1,
        "invoke-static {}, $POLICY->endCapacityCall()V") }
    write.addInstruction(request + 1, "invoke-static {}, $POLICY->endCapacityCall()V")
    // v1 is the receiver of the original write (p0 is copied here at entry).
    write.addInstruction(request, "invoke-static {v1}, $POLICY->capacityCall(Ljava/lang/Object;)V")
    lookup.addInstruction(cached + 2,
        "invoke-static/range {p0 .. p1}, $POLICY->cachedLookup(Ljava/lang/Object;Ljava/lang/Object;)V")
    provider.addInstruction(returned + 1,
        "invoke-static {v2, v0}, $POLICY->scopedBackend(Ljava/lang/Object;Ljava/lang/Object;)V")
    val selector = if (apply) "selectCapacityVictim" else "observeCapacityVictim"
    eviction.addInstructions(capacity - 1, """
        invoke-static {p0, v2, v0}, $POLICY->$selector(Ljava/lang/Object;Ljava/util/LinkedHashSet;Ljava/lang/String;)Ljava/lang/String;
        move-result-object v0
    """)
}
