package app.morphe.patches.music.layout.classicplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal fun installAudioVideoPillHooks() {
    val pillLookup = Fingerprint(
        filters = listOf(
            resourceLiteral(ResourceType.ID, "audio_video_switch_pill_container"),
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                name = "findViewById",
                parameters = listOf("I"),
                returnType = "Landroid/view/View;",
                location = MatchAfterImmediately(),
            ),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        ),
    )

    val matches = pillLookup.matchAllOrNull()
        ?: error("Unsupported APK: audio/video switch pill lookup not found")
    matches.forEach { match ->
        val (literal, call, result) = match.instructionMatches
        val idRegister = (literal.instruction as OneRegisterInstruction).registerA
        check((call.instruction as FiveRegisterInstruction).registerD == idRegister) {
            "Unsupported APK: audio/video switch pill ID is not the findViewById argument"
        }
        val view = (result.instruction as OneRegisterInstruction).registerA
        match.method.addInstruction(
            result.index + 1,
            "invoke-static/range {v$view .. v$view}, Lapp/morphe/extension/ym/AudioVideoPill;->apply(Landroid/view/View;)V",
        )
    }
}
