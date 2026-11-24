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
    val accountRead = (maxOf(0, returned - 6) until returned).single { i ->
        body[i].opcode == Opcode.INVOKE_INTERFACE && (body[i] as ReferenceInstruction)
            .reference.toString() == "Lawye;->e()Lawyd;"
    }
    val provider2 = Fingerprint(definingClass = "Laubi;", name = "b", returnType = "Lsfx;",
        parameters = emptyList()).method
    val alternateSupplier = provider2.implementation!!.instructions
    val scopedCall = alternateSupplier.indices.single { i ->
        i >= 2 && i + 1 < alternateSupplier.size && alternateSupplier[i].opcode == Opcode.INVOKE_VIRTUAL &&
            (alternateSupplier[i] as ReferenceInstruction).reference.toString() == "Laued;->b(Lawyd;)Lsfx;" &&
            (alternateSupplier[i] as FiveRegisterInstruction).registerD == 1 &&
            alternateSupplier[i - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (alternateSupplier[i - 2] as? ReferenceInstruction)?.reference?.toString() == "Lawye;->e()Lawyd;" &&
            alternateSupplier[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (alternateSupplier[i + 1] as OneRegisterInstruction).registerA == 0
    }
    val unscopedCall = alternateSupplier.indices.single { i ->
        alternateSupplier[i].opcode == Opcode.INVOKE_VIRTUAL &&
            (alternateSupplier[i] as ReferenceInstruction).reference.toString() == "Laueo;->b()Lsfx;" &&
            alternateSupplier[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (alternateSupplier[i + 1] as OneRegisterInstruction).registerA == 0
    }
    check(unscopedCall > scopedCall)
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
    val budgetComparison = writeCode.indices.mapNotNull { i ->
        if (i + 3 >= writeCode.size || writeCode[i].opcode != Opcode.INVOKE_INTERFACE ||
            (writeCode[i] as ReferenceInstruction).reference.toString() != "Laudz;->a(J)J" ||
            writeCode[i + 1].opcode != Opcode.MOVE_RESULT_WIDE) return@mapNotNull null
        val observed = (writeCode[i + 2] as? ReferenceInstruction)?.reference?.toString()
            ?.endsWith("CacheRetentionDiagnostics;->capacityBudgetPacked(JIJ)V") == true
        val cmp = i + if (observed) 3 else 2
        (cmp + 1).takeIf { it < writeCode.size && writeCode[cmp].opcode == Opcode.CMP_LONG &&
            (writeCode[cmp] as OneRegisterInstruction).registerA == 15 && writeCode[it].opcode == Opcode.IF_GTZ }
    }.single()
    check(budgetComparison < request)
    val cleanup = writeCode.withIndex().filter { it.index > request && it.value.opcode == Opcode.MOVE_EXCEPTION }
    check(cleanup.size == 2)
    cleanup.asReversed().forEach { write.addInstruction(it.index + 1,
        "invoke-static {}, $POLICY->endCapacityCall()V") }
    write.addInstruction(request + 1, "invoke-static {}, $POLICY->endCapacityCall()V")
    // Native used+requested vs budget, not later opportunistic reclamation.
    write.addInstruction(budgetComparison,
        "invoke-static {v1, v15}, $POLICY->capacityCall(Ljava/lang/Object;I)V")
    lookup.addInstruction(cached + 2,
        "invoke-static/range {p0 .. p1}, $POLICY->cachedLookup(Ljava/lang/Object;Ljava/lang/Object;)V")
    provider.addInstruction(returned + 1,
        "invoke-static {v2, v0}, $POLICY->scopedBackend(Ljava/lang/Object;Ljava/lang/Object;)V")
    provider.addInstruction(accountRead, "invoke-static {}, $POLICY->beginScopeRead()V")
    provider2.addInstruction(unscopedCall + 2, "invoke-static {}, $POLICY->unscopedSupplier()V")
    provider2.addInstruction(scopedCall + 2,
        "invoke-static {v1, v0}, $POLICY->scopedBackend(Ljava/lang/Object;Ljava/lang/Object;)V")
    provider2.addInstruction(scopedCall - 2, "invoke-static {}, $POLICY->beginScopeRead()V")
    if (apply) eviction.addInstruction(capacity + 1, "invoke-static {}, $POLICY->capacityVictimRemoved()V")
    val selector = if (apply) "selectCapacityVictim" else "observeCapacityVictim"
    eviction.addInstructions(capacity - 1, """
        invoke-static {p0, v2, v0}, $POLICY->$selector(Ljava/lang/Object;Ljava/util/LinkedHashSet;Ljava/lang/String;)Ljava/lang/String;
        move-result-object v0
    """)
}
