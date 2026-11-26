package me.mdbell.noexs.code.opcode;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import me.mdbell.noexs.code.opcode.annotation.AOpCodeOperation;

public abstract class AOpCode {

    transient AOpCode nextOperation;

    transient AOpCode previousOperation;

    public AOpCode getNextOperation() {
        return nextOperation;
    }

    public void setNextOperation(AOpCode nextOperation) {
        this.nextOperation = nextOperation;
    }

    public AOpCode getPreviousOperation() {
        return previousOperation;
    }

    public void setPreviousOperation(AOpCode previousOperation) {
        this.previousOperation = previousOperation;
    }

    public EOpCode getOperation() {
        return this.getClass().getAnnotation(AOpCodeOperation.class).operation();
    }

    public abstract String abstractInstruction();

    /**
     * Explication en langage clair de l'instruction, pour expliquer un cheat venu d'ailleurs
     * (ex: un fichier cheatdb). Par défaut : l'instruction abstraite si elle existe, sinon toString.
     * Les sous-classes redéfinissent pour une vraie phrase.
     */
    public String explain() {
        String abs = abstractInstruction();
        return (abs != null && !abs.isBlank()) ? abs : toString();
    }

    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE, false);
    }

}
