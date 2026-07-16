package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Evaluates only bounded field references, scalar literals, comparisons, and boolean operators. */
final class SafeExpressionEvaluator {
    private final List<Token> tokens;
    private final com.fasterxml.jackson.databind.node.ObjectNode context;
    private int position;

    private SafeExpressionEvaluator(String expression, com.fasterxml.jackson.databind.node.ObjectNode context) {
        this.tokens = tokenize(unwrap(expression));
        this.context = context;
    }

    static boolean evaluateBoolean(String expression, com.fasterxml.jackson.databind.node.ObjectNode context) {
        JsonNode value = evaluate(expression, context);
        if (!value.isBoolean()) throw new IllegalArgumentException("expression result must be boolean");
        return value.asBoolean();
    }

    static JsonNode evaluate(String expression, com.fasterxml.jackson.databind.node.ObjectNode context) {
        SafeExpressionEvaluator evaluator = new SafeExpressionEvaluator(expression, context);
        JsonNode value = evaluator.or();
        evaluator.expect(Type.END);
        return value;
    }

    private JsonNode or() {
        JsonNode value = and();
        while (match(Type.OR)) {
            JsonNode right = and();
            value = BooleanNode.valueOf(booleanValue(value) || booleanValue(right));
        }
        return value;
    }

    private JsonNode and() {
        JsonNode value = comparison();
        while (match(Type.AND)) {
            JsonNode right = comparison();
            value = BooleanNode.valueOf(booleanValue(value) && booleanValue(right));
        }
        return value;
    }

    private JsonNode comparison() {
        JsonNode left = unary();
        Type operator = matchOne(Type.EQ, Type.NE, Type.LT, Type.LE, Type.GT, Type.GE);
        if (operator == null) return left;
        JsonNode right = unary();
        int order = compare(left, right);
        return BooleanNode.valueOf(switch (operator) {
            case EQ -> equal(left, right);
            case NE -> !equal(left, right);
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            case GE -> order >= 0;
            default -> throw new IllegalStateException("not a comparison");
        });
    }

    private JsonNode unary() {
        if (match(Type.NOT)) return BooleanNode.valueOf(!booleanValue(unary()));
        if (match(Type.LEFT)) {
            JsonNode value = or();
            expect(Type.RIGHT);
            return value;
        }
        Token token = advance(Type.IDENTIFIER, Type.STRING, Type.NUMBER, Type.BOOLEAN, Type.NULL);
        return switch (token.type) {
            case IDENTIFIER -> TaskGraphValues.lookup(context, token.value).deepCopy();
            case STRING -> TextNode.valueOf(token.value);
            case NUMBER -> DecimalNode.valueOf(new BigDecimal(token.value));
            case BOOLEAN -> BooleanNode.valueOf(Boolean.parseBoolean(token.value));
            case NULL -> com.mccompanion.runtime.json.Json.MAPPER.nullNode();
            default -> throw new IllegalStateException("not a value");
        };
    }

    private static boolean booleanValue(JsonNode value) {
        if (!value.isBoolean()) throw new IllegalArgumentException("boolean operator requires boolean operands");
        return value.asBoolean();
    }

    private static boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private static int compare(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue());
        if (left.isTextual() && right.isTextual()) return left.asText().compareTo(right.asText());
        if (left.isBoolean() && right.isBoolean()) return Boolean.compare(left.asBoolean(), right.asBoolean());
        if (left.isNull() && right.isNull()) return 0;
        throw new IllegalArgumentException("ordered comparison requires operands of the same scalar type");
    }

    private boolean match(Type type) {
        if (tokens.get(position).type != type) return false;
        position++;
        return true;
    }

    private Type matchOne(Type... types) {
        for (Type type : types) if (match(type)) return type;
        return null;
    }

    private Token advance(Type... types) {
        for (Type type : types) {
            if (tokens.get(position).type == type) return tokens.get(position++);
        }
        throw new IllegalArgumentException("unexpected token at offset " + tokens.get(position).offset);
    }

    private void expect(Type type) {
        if (!match(type)) throw new IllegalArgumentException("unexpected token at offset " + tokens.get(position).offset);
    }

    private static String unwrap(String expression) {
        String source = expression == null ? "" : expression.strip();
        if (source.startsWith("${") && source.endsWith("}")) return source.substring(2, source.length() - 1);
        return source;
    }

    private static List<Token> tokenize(String source) {
        if (source.isBlank() || source.length() > 2_048) {
            throw new IllegalArgumentException("expression must contain 1..2048 characters");
        }
        List<Token> result = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) { index++; continue; }
            int start = index;
            if (Character.isLetter(current) || current == '_') {
                index++;
                while (index < source.length()) {
                    char next = source.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '.' && next != '-') break;
                    index++;
                }
                String value = source.substring(start, index);
                if (value.endsWith(".") || value.contains("..")) {
                    throw new IllegalArgumentException("invalid field reference at offset " + start);
                }
                Type type = value.equals("true") || value.equals("false") ? Type.BOOLEAN
                        : value.equals("null") ? Type.NULL : Type.IDENTIFIER;
                result.add(new Token(type, value, start));
                continue;
            }
            if (Character.isDigit(current)
                    || current == '-' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1))) {
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                if (index < source.length() && source.charAt(index) == '.') {
                    index++;
                    int fraction = index;
                    while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                    if (fraction == index) throw new IllegalArgumentException("invalid number at offset " + start);
                }
                result.add(new Token(Type.NUMBER, source.substring(start, index), start));
                continue;
            }
            if (current == '"' || current == '\'') {
                char quote = current;
                StringBuilder value = new StringBuilder();
                index++;
                boolean closed = false;
                while (index < source.length()) {
                    char next = source.charAt(index++);
                    if (next == '\\') {
                        if (index >= source.length()) break;
                        char escaped = source.charAt(index++);
                        value.append(switch (escaped) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case '\\' -> '\\'; case '"' -> '"'; case '\'' -> '\'';
                            default -> throw new IllegalArgumentException("unsupported escape at offset " + (index - 1));
                        });
                    } else if (next == quote) {
                        closed = true;
                        break;
                    } else value.append(next);
                }
                if (!closed) throw new IllegalArgumentException("unterminated string at offset " + start);
                result.add(new Token(Type.STRING, value.toString(), start));
                continue;
            }
            Type type;
            int width;
            if (source.startsWith("&&", index)) { type = Type.AND; width = 2; }
            else if (source.startsWith("||", index)) { type = Type.OR; width = 2; }
            else if (source.startsWith("==", index)) { type = Type.EQ; width = 2; }
            else if (source.startsWith("!=", index)) { type = Type.NE; width = 2; }
            else if (source.startsWith("<=", index)) { type = Type.LE; width = 2; }
            else if (source.startsWith(">=", index)) { type = Type.GE; width = 2; }
            else {
                type = switch (current) {
                    case '!' -> Type.NOT; case '<' -> Type.LT; case '>' -> Type.GT;
                    case '(' -> Type.LEFT; case ')' -> Type.RIGHT;
                    default -> throw new IllegalArgumentException("forbidden token at offset " + start);
                };
                width = 1;
            }
            result.add(new Token(type, source.substring(index, index + width), start));
            index += width;
        }
        result.add(new Token(Type.END, "", source.length()));
        return result;
    }

    private enum Type { IDENTIFIER, STRING, NUMBER, BOOLEAN, NULL, AND, OR, EQ, NE, LT, LE, GT, GE, NOT, LEFT, RIGHT, END }
    private record Token(Type type, String value, int offset) { }
}
