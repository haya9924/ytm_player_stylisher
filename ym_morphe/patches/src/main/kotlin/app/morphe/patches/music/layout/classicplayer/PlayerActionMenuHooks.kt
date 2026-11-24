package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.logging.Logger

private const val CLICK = "Landroid/view/View\$OnClickListener;"
private val overflowSetup = listOf(
    Opcode.CONST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
    Opcode.IPUT_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
    Opcode.INVOKE_VIRTUAL,
)

/** 9.30.53's contextual_menu registration, not WatchWhileLayout's minimize control. */
internal fun installPlayerActionMenuHook() {
    val candidates = Fingerprint(definingClass = "Lrxx;", name = "<init>").matchAllOrNull().orEmpty()
    val targets = candidates.flatMap { match ->
        val method = match.method
        method.implementation?.instructions.orEmpty().windowed(overflowSetup.size).mapIndexedNotNull { index, insns ->
            if (insns.map { it.opcode } != overflowSetup) return@mapIndexedNotNull null
            fun ref(i: Int) = (insns[i] as ReferenceInstruction).reference.toString()
            if ((insns[0] as NarrowLiteralInstruction).narrowLiteral != 0x7f0b02db ||
                ref(1) != "Landroid/view/View;->findViewById(I)Landroid/view/View;" ||
                ref(3) != "Landroid/widget/ImageView;" || ref(4) != "Lrxx;->D:Landroid/widget/ImageView;" ||
                ref(6) != "Lrwg;" || ref(7) != "Lrwg;-><init>(Lrxx;)V" ||
                ref(8) != "Landroid/widget/ImageView;->setOnClickListener($CLICK)V"
            ) return@mapIndexedNotNull null

            val lookup = insns[1] as FiveRegisterInstruction
            val view = (insns[2] as OneRegisterInstruction).registerA
            val store = insns[4] as TwoRegisterInstruction
            val listener = (insns[6] as OneRegisterInstruction).registerA
            val init = insns[7] as FiveRegisterInstruction
            val setter = insns[8] as FiveRegisterInstruction
            if (lookup.registerCount != 2 || lookup.registerD != (insns[0] as OneRegisterInstruction).registerA ||
                (insns[3] as OneRegisterInstruction).registerA != view || store.registerA != view ||
                init.registerCount != 2 || init.registerC != listener || init.registerD != store.registerB ||
                setter.registerCount != 2 || setter.registerC != view || setter.registerD != listener
            ) return@mapIndexedNotNull null
            Pair(method, Triple(index + overflowSetup.lastIndex, view, listener))
        }
    }
    val target = targets.singleOrNull()
    if (target == null) {
        Logger.getLogger("ym_morphe").warning("Player action menu: overflow registration not unique; leaving it untouched")
        return
    }
    val (method, registers) = target
    val (index, view, listener) = registers
    method.replaceInstruction(index,
        "invoke-static {v$view, v$listener}, Lapp/morphe/extension/ym/PlayerActionMenu;->bind(Landroid/view/View;$CLICK)V")
}
