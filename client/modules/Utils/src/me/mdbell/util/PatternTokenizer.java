package me.mdbell.util;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class PatternTokenizer {

    private class Token{
        ETokenType type;
        int width;
        String value;

        @Override
        public String toString() {
            return "Token{" +
                    "type=" + type +
                    ", width=" + width +
                    ", value='" + value + '\'' +
                    '}';
        }
    }

    private enum ETokenType{
        WILDCARD, WHITESPACE, OPERATOR, NUMERIC
    }

    public PatternCompiler.PatternElement[] eval(String str){
        List<Token> tokens = getTokens(str);

        List<PatternCompiler.PatternElement> res = new LinkedList<>();
        PatternCompiler.ECondition condition = PatternCompiler.ECondition.EQUALS;
        for(int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if(t.type == ETokenType.OPERATOR) {
                switch(t.value) {
                    case "!":
                        condition = PatternCompiler.ECondition.NOT_EQUALS;
                        break;
                    case "<":
                        condition = PatternCompiler.ECondition.LESS_THEN;
                        break;
                    case ">":
                        condition = PatternCompiler.ECondition.GREATER_THEN;
                        break;
                }
            }else{
                if(t.type == ETokenType.NUMERIC) {
                    if(t.width > 2) {
                        throw new UnsupportedOperationException("Value to high for a byte!");
                    }
                    res.add(new PatternCompiler.PatternElement(Integer.parseUnsignedInt(t.value, 16), condition));
                }else if(t.type == ETokenType.WILDCARD) {
                    res.add(null);
                }
                condition = PatternCompiler.ECondition.EQUALS;
            }
        }
        return res.toArray(new PatternCompiler.PatternElement[0]);
    }

    private List<Token> getTokens(String str) {
        Iterator<Character> itr = Collections.iterator(Objects.requireNonNull(str));
        List<Token> res = new LinkedList<>();
        boolean ascii=false;
        while(itr.hasNext()) {
            char c = itr.next();
            if(c == '*') {
                ascii = !ascii;
                continue;
            }
            Token t = new Token();
            if(!ascii) {
                t.type = getType(c);
                t.width = 1;
                t.value = String.valueOf(c);
            }else{
                t.type = ETokenType.NUMERIC;
                t.width = 2;
                t.value = HexUtils.pad('0', 2, Integer.toUnsignedString(c & 0xFF, 16));
            }
            res.add(t);
        }

        for(int i = 0; i < res.size(); i++) {
            Token t = res.get(i);
            Token next = i + 1 < res.size() ? res.get(i + 1) : null;
            if(next != null) {
                if (t.type == ETokenType.NUMERIC && next.type == ETokenType.NUMERIC && t.width < 2) {
                    t.value = t.value + next.value;
                    t.width++;
                    res.remove(next);
                    i--;
                }
                else if(t.type == ETokenType.WILDCARD && next.type == ETokenType.WILDCARD && t.width < 2) {
                    t.width++;
                    res.remove(next);
                    i--;
                }
            }
        }

        res.removeIf(t -> t.type == ETokenType.WHITESPACE);
        return res;
    }

    private ETokenType getType(char c) {
        if(Character.isWhitespace(c)) {
            return ETokenType.WHITESPACE;
        }
        if(c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
            return ETokenType.NUMERIC;
        }
        switch(c){
            case '<':
            case '>':
            case '!':
                return ETokenType.OPERATOR;
            case '?':
                return ETokenType.WILDCARD;
            default:
                throw new UnsupportedOperationException("unsupported/invalid char:" + c);
        }
    }
}
