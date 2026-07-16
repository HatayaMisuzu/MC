package com.mccompanion.runtime.taskgraph;

import java.util.ArrayList;
import java.util.List;

/** Parser for field references, literals, comparisons and boolean operators. It never evaluates code. */
final class SafeExpressionValidator {
    private final List<Token> tokens;
    private int position;

    private SafeExpressionValidator(String expression) {
        String source = expression == null ? "" : expression.strip();
        if (source.startsWith("${") && source.endsWith("}")) source = source.substring(2, source.length() - 1);
        tokens = tokenize(source);
    }

    static String validate(String expression) {
        try {
            SafeExpressionValidator parser = new SafeExpressionValidator(expression);
            parser.or();
            parser.expect(Type.END);
            return null;
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
    }

    private void or() {
        and();
        while (match(Type.OR)) and();
    }

    private void and() {
        comparison();
        while (match(Type.AND)) comparison();
    }

    private void comparison() {
        unary();
        if (match(Type.EQ, Type.NE, Type.LT, Type.LE, Type.GT, Type.GE)) unary();
    }

    private void unary() {
        if (match(Type.NOT)) unary();
        else if (match(Type.LEFT)) {
            or();
            expect(Type.RIGHT);
        } else {
            expect(Type.IDENTIFIER, Type.STRING, Type.NUMBER, Type.BOOLEAN, Type.NULL);
        }
    }

    private boolean match(Type... types) {
        for (Type type : types) {
            if (tokens.get(position).type == type) {
                position++;
                return true;
            }
        }
        return false;
    }

    private void expect(Type... types) {
        if (match(types)) return;
        throw new IllegalArgumentException("unexpected token at offset " + tokens.get(position).offset);
    }

    private static List<Token> tokenize(String source) {
        if (source.isBlank() || source.length() > 2_048) {
            throw new IllegalArgumentException("expression must contain 1..2048 characters");
        }
        List<Token> result = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            int start = index;
            if (Character.isLetter(current) || current == '_') {
                index++;
                while (index < source.length()) {
                    char next = source.charAt(index);
                    if (Character.isLetterOrDigit(next) || next == '_' || next == '.' || next == '-') {
                        index++;
                    } else if (next == '[') {
                        index++;
                        int digits = index;
                        while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                        if (digits == index || index >= source.length() || source.charAt(index) != ']') {
                            throw new IllegalArgumentException("invalid array index at offset " + (index - 1));
                        }
                        index++;
                    } else {
                        break;
                    }
                }
                String word = source.substring(start, index);
                Type type = word.equals("true") || word.equals("false") ? Type.BOOLEAN
                        : word.equals("null") ? Type.NULL : Type.IDENTIFIER;
                if (type == Type.IDENTIFIER) {
                    String error = TaskGraphValues.validatePath(word);
                    if (error != null) throw new IllegalArgumentException(error + " at offset " + start);
                }
                result.add(new Token(type, start));
                continue;
            }
            if (Character.isDigit(current) || current == '-') {
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                if (index < source.length() && source.charAt(index) == '.') {
                    index++;
                    int fraction = index;
                    while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                    if (fraction == index) throw new IllegalArgumentException("invalid number at offset " + start);
                }
                result.add(new Token(Type.NUMBER, start));
                continue;
            }
            if (current == '"' || current == '\'') {
                char quote = current;
                index++;
                boolean closed = false;
                while (index < source.length()) {
                    char next = source.charAt(index++);
                    if (next == '\\') {
                        if (index >= source.length()) break;
                        index++;
                    } else if (next == quote) {
                        closed = true;
                        break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("unterminated string at offset " + start);
                result.add(new Token(Type.STRING, start));
                continue;
            }
            Type type;
            if (source.startsWith("&&", index)) type = Type.AND;
            else if (source.startsWith("||", index)) type = Type.OR;
            else if (source.startsWith("==", index)) type = Type.EQ;
            else if (source.startsWith("!=", index)) type = Type.NE;
            else if (source.startsWith("<=", index)) type = Type.LE;
            else if (source.startsWith(">=", index)) type = Type.GE;
            else {
                type = switch (current) {
                    case '!' -> Type.NOT;
                    case '<' -> Type.LT;
                    case '>' -> Type.GT;
                    case '(' -> Type.LEFT;
                    case ')' -> Type.RIGHT;
                    default -> throw new IllegalArgumentException("forbidden token at offset " + start);
                };
                index++;
                result.add(new Token(type, start));
                continue;
            }
            index += 2;
            result.add(new Token(type, start));
        }
        result.add(new Token(Type.END, source.length()));
        return result;
    }

    private enum Type { IDENTIFIER, STRING, NUMBER, BOOLEAN, NULL, AND, OR, EQ, NE, LT, LE, GT, GE, NOT, LEFT, RIGHT, END }
    private record Token(Type type, int offset) { }
}
