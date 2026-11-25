package service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Stream;
import llm.LlmClient;
import model.Payment;
import model.ReceiptItem;
import model.SampleData;

public final class ReceiptMatchingServiceTest {
    private static int passed;

    public static void main(String[] args) throws IOException {
        Path receiptDir = Files.createTempDirectory("receipt-test-");
        try {
            LlmClient mock = SampleData.createMockLlmClient();

            Payment missing = createPayment("TX-MISSING");
            Payment normal = createPayment("TX-NORMAL");
            writeReceipt(receiptDir, normal, "아이스 아메리카노,4500,1,카페/음료");

            ReceiptMatchingService service = new ReceiptMatchingService(mock);
            service.matchReceipts(Arrays.asList(missing, normal), receiptDir);

            check(normal.isMatched() && normal.getItems().size() == 1,
                    "영수증 파일이 있는 결제 매칭 및 품목 개수 확인");
            ReceiptItem item = normal.getItems().get(0);
            check(item.getItemName().equals("아이스 아메리카노")
                    && item.getUnitPrice() == 4500 && item.getQuantity() == 1
                    && item.getCategory().equals("카페/음료"), "품목 정보 변환 확인");
            check(!missing.isMatched() && missing.getItems().isEmpty(),
                    "영수증 파일이 없는 결제는 미매칭 유지");

            Payment fenced = createPayment("TX-FENCED");
            writeReceipt(receiptDir, fenced, "아이스 아메리카노,4500,1,카페/음료");
            ReceiptMatchingService fencedService = new ReceiptMatchingService(
                    (systemPrompt, userContent) -> "  \n```json\n  "
                            + mock.ask(systemPrompt, userContent) + "\n```  \n");
            fencedService.matchReceipts(Arrays.asList(fenced), receiptDir);
            check(fenced.isMatched() && fenced.getItems().size() == 1,
                    "JSON 코드블록과 공백이 포함된 응답 파싱");

            Payment broken = createPayment("TX-BROKEN");
            Payment next = createPayment("TX-NEXT");
            writeReceipt(receiptDir, broken, "깨진 응답 테스트");
            writeReceipt(receiptDir, next, "정상 응답 테스트");
            ReceiptMatchingService errorService = new ReceiptMatchingService(
                    (systemPrompt, userContent) -> userContent.equals("깨진 응답 테스트")
                            ? "[{\"itemName\":\"커피\",\"unitPrice\":4500,}]"
                            : mock.ask(systemPrompt, userContent));
            errorService.matchReceipts(Arrays.asList(broken, next), receiptDir);
            check(!broken.isMatched() && broken.getItems().isEmpty(),
                    "잘못된 JSON 응답은 미매칭 유지");
            check(next.isMatched() && next.getItems().size() == 1,
                    "JSON 파싱 실패 이후 다음 결제 매칭 정상 진행");

            Payment multiple = createPayment("TX-MULTIPLE");
            writeReceipt(receiptDir, multiple, "커피,4500,1,음료\n빵,2000,2,간식");
            ReceiptMatchingService multipleService = new ReceiptMatchingService(
                    (systemPrompt, userContent) -> "[ \n"
                            + "{\"itemName\": \"커피\", \"unitPrice\": 4500, "
                            + "\"quantity\": 1, \"category\": \"음료\"},\n"
                            + "{\"itemName\": \"빵\", \"unitPrice\": 2000, "
                            + "\"quantity\": 2, \"category\": \"간식\"} ]");
            multipleService.matchReceipts(Arrays.asList(multiple), receiptDir);
            check(multiple.isMatched() && multiple.getItems().size() == 2
                    && multiple.getItems().get(1).getAmount() == 4000,
                    "복수 품목 및 JSON 내부 공백 파싱");

            System.out.println("\n통과: " + passed + "개 / 실패: 0개");
        } finally {
            try (Stream<Path> files = Files.list(receiptDir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(receiptDir);
        }
    }

    private static Payment createPayment(String transactionId) {
        return new Payment(transactionId, LocalDateTime.of(2026, 9, 1, 13, 20),
                "카페", 4500);
    }

    private static void writeReceipt(Path receiptDir, Payment payment, String content)
            throws IOException {
        Files.writeString(receiptDir.resolve(payment.getTransactionId() + ".txt"),
                content, StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("실패: " + label);
        }
        passed++;
        System.out.println("[통과] " + label);
    }
}
