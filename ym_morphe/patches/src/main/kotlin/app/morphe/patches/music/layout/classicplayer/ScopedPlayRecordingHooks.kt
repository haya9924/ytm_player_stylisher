package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val RECORD = "Lapp/morphe/extension/ym/ScopedPlaybackRecordingBridge;"
private const val HOST = "Lapp/morphe/extension/ym/ScopedPlayHost;"
private const val FRESH_FACTORY = "Lbbxu;->a(Ljava/lang/String;Laqdn;Ljava/lang/String;I)Lbbxt;"

internal fun installScopedPlayRecordingHooks() {
    val creation = Fingerprint(definingClass = "Lbbwy;", name = "i", returnType = "V",
        parameters = listOf("Ljava/lang/String;", "Laqdn;", "I")).method
    val createBody = creation.implementation!!
    val created = createBody.instructions.indices.single { i ->
        val a = createBody.instructions
        i > 1 && a[i].opcode == Opcode.IPUT_OBJECT &&
            (a[i] as ReferenceInstruction).reference.toString() == "Lbbwy;->b:Lbbxt;" &&
            a[i - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            a[i - 2].opcode == Opcode.INVOKE_VIRTUAL &&
            (a[i - 2] as ReferenceInstruction).reference.toString() == FRESH_FACTORY
    }
    val slot = createBody.instructions[created] as TwoRegisterInstruction
    check(slot.registerA == 3 && slot.registerB == createBody.registerCount - 4)
    val local = Fingerprint(definingClass = "Lbckg;", name = "bD", returnType = "V",
        parameters = listOf("Lbcre;", "J", "J", "J", "J", "Z", "I", "I"),
        filters = listOf(string("Media progress reported outside media playback: %s"))).method
    val dispatch = local.implementation!!.instructions.withIndex().single { (_, insn) ->
        insn.opcode == Opcode.INVOKE_VIRTUAL && (insn as ReferenceInstruction).reference.toString() ==
            "Lbbwy;->n(Lazmp;)V"
    }
    check((dispatch.value as FiveRegisterInstruction).registerC == 1)
    val time = Fingerprint(definingClass = "Lbbxt;", name = "t", returnType = "V",
        parameters = listOf("Lazmp;")).method
    val timeBody = time.implementation!!
    val updated = timeBody.instructions.indices.single { i ->
        val a = timeBody.instructions
        i > 0 && a[i].opcode == Opcode.IPUT_WIDE && a[i - 1].opcode == Opcode.ADD_LONG_2ADDR &&
            (a[i] as ReferenceInstruction).reference.toString() == "Lbbxt;->u:J" &&
            (a[i] as TwoRegisterInstruction).registerB == timeBody.registerCount - 2
    }
    val release = Fingerprint(definingClass = "Lbbxt;", name = "u", returnType = "V",
        parameters = emptyList(), filters = listOf(string("VSS2 client released unexpectedly"))).method
    val startup = Fingerprint(definingClass = "Ljbg;", name = "onCreate", returnType = "V",
        parameters = emptyList()).method
    val startReturn = startup.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    check(startReturn.isNotEmpty())
    val atomicAccount = Fingerprint(definingClass = "Lagcy;", name = "t", returnType = "Z",
        parameters = listOf("Lagcx;", "Lagcw;")).method
    val accountCode = atomicAccount.implementation!!.instructions
    val accountSuccess = accountCode.indices.single { index ->
        index > 0 && accountCode[index].opcode == Opcode.RETURN &&
            accountCode[index - 1].opcode == Opcode.CONST_4 &&
            (accountCode[index - 1] as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)
                .narrowLiteral == 1
    }
    val legacy = Fingerprint(definingClass = "Lagdw;", name = "e", returnType = "Lawyd;",
        parameters = emptyList()).matchOrNull() ?: error("Native recording: missing legacy account provider")
    val accountWrites = legacy.originalClassDef.methods.mapNotNull { original ->
        val writes = original.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
            instruction.opcode == Opcode.IPUT_OBJECT && (instruction as ReferenceInstruction)
                .reference.toString() == "Lagdw;->j:Lagat;"
        }?.map { it.index }.orEmpty()
        if (writes.isEmpty()) null else original to writes
    }
    check(accountWrites.sumOf { it.second.size } == 4)
    atomicAccount.addInstruction(accountSuccess - 1,
        "invoke-static/range {p1 .. p2}, $HOST->accountStateCommitted(Ljava/lang/Object;Ljava/lang/Object;)V")
    accountWrites.forEach { (original, writes) ->
        val changed = Fingerprint(definingClass = "Lagdw;", name = original.name,
            returnType = original.returnType, parameters = original.parameterTypes.map { it.toString() }).method
        writes.asReversed().forEach { index -> changed.addInstruction(index + 1,
            "invoke-static {}, $HOST->accountChanged()V") }
    }

    creation.addInstruction(created + 1,
        "invoke-static/range {v3 .. v3}, $HOST->fresh(Ljava/lang/Object;)V")
    local.addInstruction(dispatch.index + 1, "invoke-static {}, $HOST->endLocal()V")
    local.addInstruction(dispatch.index,
        "invoke-static/range {p0 .. p1}, $HOST->local(Ljava/lang/Object;Ljava/lang/Object;)V")
    time.addInstruction(updated + 1,
        "invoke-static/range {p0 .. p0}, $HOST->sample(Ljava/lang/Object;)V")
    release.addInstruction(0, "invoke-static/range {p0 .. p0}, $HOST->release(Ljava/lang/Object;)V")
    startReturn.asReversed().forEach { startup.addInstruction(it,
        "invoke-static/range {p0 .. p0}, $RECORD->initialize(Landroid/content/Context;)V") }
}
