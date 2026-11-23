package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

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

    val capacityRemoval = Fingerprint(
        definingClass = "Laugy;", name = "f", returnType = "Ljava/io/File;",
        parameters = listOf("Ljava/lang/String;", "J", "J", "Laust;"),
    ).method
    val capacityRemovalInstructions = capacityRemoval.implementation!!.instructions
    val capacityRemovalCall = capacityRemovalInstructions.indices.singleOrNull { index ->
        capacityRemovalInstructions[index].opcode == Opcode.INVOKE_INTERFACE &&
            (capacityRemovalInstructions[index] as ReferenceInstruction)
                .reference.toString() == "Lauge;->x()V"
    } ?: error("Cache diagnostics: unsupported native capacity removal call")

    val progressing = Fingerprint(
        definingClass = "Lavdj;", name = "q", returnType = "V", parameters = listOf("J"),
        filters = listOf(string("onProgressing(playbackStartSystemTimeMs=%d)")),
    ).method
    val progress = Fingerprint(
        definingClass = "Lbckg;", name = "bD", returnType = "V",
        parameters = listOf("Lbcre;", "J", "J", "J", "J", "Z", "I", "I"),
        filters = listOf(string("Media progress reported outside media playback: %s")),
    ).method
    val dispatch = progress.implementation!!.instructions.withIndex().single { (_, instruction) ->
        (instruction as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
            ?.reference?.toString() == "Lbbwy;->n(Lazmp;)V"
    }.index
    val stats2Time = Fingerprint(
        definingClass = "Lbbxt;", name = "t", returnType = "V", parameters = listOf("Lazmp;"),
    ).method
    val stats2TimeInstructions = stats2Time.implementation!!.instructions
    val accumulated = stats2TimeInstructions.indices.singleOrNull { index ->
        stats2TimeInstructions[index].opcode == Opcode.IPUT_WIDE &&
            (stats2TimeInstructions[index] as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                .reference.toString() == "Lbbxt;->u:J" &&
            index > 0 && stats2TimeInstructions[index - 1].opcode == Opcode.ADD_LONG_2ADDR
    } ?: error("Cache diagnostics: unsupported stats2 watch time accumulation")
    val stats2Dispatch = Fingerprint(
        definingClass = "Lbbwy;", name = "n", returnType = "V", parameters = listOf("Lazmp;"),
    ).method
    val stats2DispatchInstructions = stats2Dispatch.implementation!!.instructions
    val stats2Call = stats2DispatchInstructions.indices.singleOrNull { index ->
        stats2DispatchInstructions[index].opcode == Opcode.INVOKE_VIRTUAL &&
            (stats2DispatchInstructions[index] as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                .reference.toString() == "Lbbxt;->t(Lazmp;)V"
    } ?: error("Cache diagnostics: unsupported stats2 progress dispatch")
    check(stats2DispatchInstructions[stats2Call + 1].opcode != Opcode.MOVE_RESULT) {
        "Cache diagnostics: unsupported stats2 progress consume"
    }
    val stats2Creation = Fingerprint(
        definingClass = "Lbbwy;", name = "i", returnType = "V",
        parameters = listOf("Ljava/lang/String;", "Laqdn;", "I"),
    ).method
    val stats2CreationInstructions = stats2Creation.implementation!!.instructions
    val stats2Admission = stats2CreationInstructions.indices.singleOrNull { index ->
        // Only the branch guarded by the existing null check reaches this assignment.
        index > 1 && stats2CreationInstructions[index].opcode == Opcode.IPUT_OBJECT &&
            (stats2CreationInstructions[index] as ReferenceInstruction)
                .reference.toString() == "Lbbwy;->b:Lbbxt;" &&
            stats2CreationInstructions[index - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (stats2CreationInstructions[index - 2] as? ReferenceInstruction)
                ?.reference?.toString() == "Lbbxu;->a(Ljava/lang/String;Laqdn;Ljava/lang/String;I)Lbbxt;"
    } ?: error("Cache diagnostics: unsupported fresh stats2 admission")
    val stats2Register =
        (stats2CreationInstructions[stats2Admission - 1] as OneRegisterInstruction).registerA
    check(stats2Register == 3) { "Cache diagnostics: unsupported fresh stats2 register" }
    val read = Fingerprint(definingClass = "Lsga;", name = "a", returnType = "I",
        parameters = listOf("[B", "I", "I")).method
    val readCode = read.implementation!!.instructions
    val delivered = readCode.indices.single { i ->
        i > 0 && readCode[i].opcode == Opcode.IPUT_WIDE &&
            (readCode[i] as ReferenceInstruction).reference.toString() == "Lsga;->t:J" &&
            readCode[i - 1].opcode == Opcode.ADD_LONG_2ADDR
    }
    check(read.implementation!!.registerCount == 15)
    // Only the branch where Lsga.k() selects the cache data source increments t.
    read.addInstruction(delivered + 1, "invoke-static {v1}, $DIAGNOSTICS->cachedBytesDelivered(I)V")
    watch.addInstruction(0, "invoke-static {}, $DIAGNOSTICS->watchTimeCallback()V")
    progressing.addInstruction(0, "invoke-static {}, $DIAGNOSTICS->playerProgressing()V")
    progress.addInstructions(dispatch + 1, """
        invoke-static {}, $DIAGNOSTICS->playerProgressSample()V
        invoke-static {}, $DIAGNOSTICS->sampleUidTraffic()V
    """)
    stats2Time.addInstruction(accumulated + 1,
        "invoke-static/range {p0 .. p0}, $DIAGNOSTICS->stats2Accumulated(Ljava/lang/Object;)V")
    stats2Creation.addInstruction(stats2Admission + 1,
        "invoke-static/range {v3 .. v3}, $DIAGNOSTICS->primaryStats2Created(Ljava/lang/Object;)V")
    stats2Dispatch.addInstruction(stats2Call + 1,
        "invoke-static {}, $DIAGNOSTICS->stats2Dispatched()V")
    alternate.replaceInstruction(hit, "invoke-static {}, $DIAGNOSTICS->alternateCachedSpan()V")
    alternate.addInstruction(hit + 1, "return-object v4")
    // Inserted before the following goto so the original try/handler range keeps its shape.
    val budget = capacityRemovalInstructions.indices.single { i ->
        i + 3 < capacityRemovalInstructions.size && capacityRemovalInstructions[i].opcode == Opcode.INVOKE_INTERFACE &&
            (capacityRemovalInstructions[i] as ReferenceInstruction).reference.toString() == "Laudz;->a(J)J" &&
            capacityRemovalInstructions[i + 1].opcode == Opcode.MOVE_RESULT_WIDE &&
            (capacityRemovalInstructions[i + 1] as OneRegisterInstruction).registerA == 27 &&
            capacityRemovalInstructions[i + 2].opcode == Opcode.CMP_LONG &&
            capacityRemovalInstructions[i + 3].opcode == Opcode.IF_GTZ
    }
    val reread = capacityRemovalInstructions.indices.first { i ->
        i > capacityRemovalCall && capacityRemovalInstructions[i].opcode == Opcode.INVOKE_VIRTUAL_RANGE &&
            (capacityRemovalInstructions[i] as ReferenceInstruction).reference.toString() == "Laued;->a()J"
    }
    check(capacityRemovalInstructions[reread + 1].opcode == Opcode.MOVE_RESULT_WIDE &&
        (capacityRemovalInstructions[reread + 1] as OneRegisterInstruction).registerA == 27)
    // v24/v25 is the required total; v26 is an unrelated live int, ignored by the probe.
    capacityRemoval.addInstruction(reread + 2,
        "invoke-static/range {v27 .. v28}, $DIAGNOSTICS->capacityAfter(J)V")
    capacityRemoval.addInstruction(capacityRemovalCall + 1,
        "invoke-static {}, $DIAGNOSTICS->capacityRemovalReturned()V")
    capacityRemoval.addInstruction(capacityRemovalCall,
        "invoke-static/range {v6 .. v7}, $DIAGNOSTICS->capacityBefore(J)V")
    capacityRemoval.addInstruction(budget + 2,
        "invoke-static/range {v24 .. v28}, $DIAGNOSTICS->capacityBudgetPacked(JIJ)V")
    for ((index, register) in returns.asReversed()) {
        // Replacing rather than inserting keeps existing branch targets on the probe.
        supplier.replaceInstruction(index,
            "invoke-static/range {v$register .. v$register}, $DIAGNOSTICS->cacheSupplierReturned(Ljava/lang/Object;)V")
        supplier.addInstruction(index + 1, "return-object v$register")
    }
}
