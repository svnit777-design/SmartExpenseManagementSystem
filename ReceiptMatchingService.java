package service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import llm.LlmClient;
import model.Payment;
import model.ReceiptItem;

public class ReceiptMatchingService {
    private final LlmClient llmClient;

    public ReceiptMatchingService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public List<Payment> matchReceipts(List<Payment> payments, Path receiptDir) {
        String systemPrompt = "영수증에서 품목명, 단가, 수량, 카테고리를 추출하세요. "
                + "다른 설명 없이 반드시 JSON 배열만 반환하세요. "
                + "예: [{\"itemName\":\"아메리카노\",\"unitPrice\":4500,"
                + "\"quantity\":1,\"category\":\"카페/음료\"}]";

        for (Payment payment : payments) {
            try {
                Path receiptFile = receiptDir.resolve(payment.getTransactionId() + ".txt");
                if (!Files.exists(receiptFile)) {
                    continue;
                }

                String receiptContent = Files.readString(receiptFile, StandardCharsets.UTF_8);
                String response = llmClient.ask(systemPrompt, receiptContent);
                List<ReceiptItem> items = parseItems(response);
                payment.attachItems(items);
            } catch (IOException | RuntimeException e) {
                System.err.println("영수증 매칭 실패 (" + payment.getTransactionId()
                        + "): " + e.getMessage());
            }
        }
        return payments;
    }

    private List<ReceiptItem> parseItems(String response) {
        if (response == null) {
            throw new IllegalArgumentException("LLM 응답이 없습니다.");
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("JSON 배열이 없습니다.");
        }
        return new JsonParser(response.substring(start, end + 1)).parse();
    }

    private static class JsonParser {
        private final String json;
        private int pos;

        JsonParser(String json) {
            this.json = json;
        }

        List<ReceiptItem> parse() {
            List<ReceiptItem> items = new ArrayList<>();
            expect('[');
            if (!take(']')) {
                do {
                    items.add(readItem());
                } while (take(','));
                expect(']');
            }
            skipWhitespace();
            if (pos != json.length()) {
                throw invalid();
            }
            return items;
        }

        private ReceiptItem readItem() {
            Map<String, Object> fields = new HashMap<>();
            expect('{');
            if (!take('}')) {
                do {
                    String key = readString();
                    expect(':');
                    skipWhitespace();
                    Object value;
                    if (pos < json.length() && json.charAt(pos) == '"') {
                        value = readString();
                    } else {
                        value = readInteger();
                    }
                    fields.put(key, value);
                } while (take(','));
                expect('}');
            }
            if (!(fields.get("itemName") instanceof String)
                    || !(fields.get("unitPrice") instanceof Long)
                    || !(fields.get("quantity") instanceof Long)
                    || !(fields.get("category") instanceof String)) {
                throw invalid();
            }
            return new ReceiptItem((String) fields.get("itemName"),
                    (Long) fields.get("unitPrice"),
                    Math.toIntExact((Long) fields.get("quantity")),
                    (String) fields.get("category"));
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\') {
                    if (pos >= json.length()) {
                        throw invalid();
                    }
                    char escaped = json.charAt(pos++);
                    switch (escaped) {
                        case '"': value.append('"'); break;
                        case '\\': value.append('\\'); break;
                        case '/': value.append('/'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'u':
                            if (pos + 4 > json.length()) {
                                throw invalid();
                            }
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = json.charAt(pos++);
                                int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                                if (digit < 0) {
                                    throw invalid();
                                }
                                code = code * 16 + digit;
                            }
                            value.append((char) code);
                            break;
                        default: throw invalid();
                    }
                } else {
                    if (c < 0x20) {
                        throw invalid();
                    }
                    value.append(c);
                }
            }
            throw invalid();
        }

        private long readInteger() {
            skipWhitespace();
            int start = pos;
            if (pos < json.length() && json.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= json.length() || !isDigit(json.charAt(pos))) {
                throw invalid();
            }
            if (json.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < json.length() && isDigit(json.charAt(pos))) {
                    pos++;
                }
            }
            return Long.parseLong(json.substring(start, pos));
        }

        private boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void skipWhitespace() {
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    break;
                }
                pos++;
            }
        }

        private boolean take(char expected) {
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw invalid();
            }
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("영수증 품목 JSON 형식이 올바르지 않습니다. 위치: " + pos);
        }
    }
}
