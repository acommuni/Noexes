package me.mdbell.noexs.code;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.mdbell.noexs.code.opcode.manager.OpCodeManager;

public class TestExplainCheat {

    @Test
    public void testExplainCheatFromElsewhere() {
        // extrait style cheatdb : section [..], commentaires #, opcodes hex
        String cheat = "# commentaire\n"
                + "[Inf money]\n"
                + "580F0000 04BA9228\n"
                + "580F1000 000000B8\n"
                + "C1000F10\n";
        String explanation = OpCodeManager.explainCheat(cheat);
        System.out.println(explanation);
        assertTrue(explanation.contains("[Inf money]"), "la section doit apparaître");
        assertTrue(explanation.contains("Section « Inf money »"), "explication de la section");
        assertTrue(explanation.contains("Charge dans le registre RF"), "explication du 0x5");
        assertTrue(explanation.contains("MAIN"), "région MAIN dans l'explication");
        assertTrue(explanation.contains("Sauvegarde"), "explication du C1 SAVE");
        assertTrue(!explanation.contains("# commentaire"), "les commentaires sont ignorés");
    }
}
