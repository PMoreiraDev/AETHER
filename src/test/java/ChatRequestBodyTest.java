import org.junit.jupiter.api.Test;
import util.OllamaService;
import util.OllamaService.ChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the /api/chat request body builder. This is the core of the
 * conversation-memory fix: the request must include the system message first,
 * then the full user/assistant history in order, so the model can recall what
 * was said before. (Without this, each message was sent in isolation via
 * /api/generate and the AI "forgot" everything from previous turns.)
 */
class ChatRequestBodyTest {

    @Test
    void bodyIncludesSystemFirstThenHistoryInOrder() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "O meu irmão chama-se Rafael."),
                new ChatMessage("assistant", "Boa, fiquei a saber do Rafael."),
                new ChatMessage("user", "Quem é o meu irmão?")
        );

        String body = OllamaService.buildChatRequestBody(
                "llama3.2:3b", history, "You are AETHER AI.");

        // System message must come before the first user message.
        int systemIdx = body.indexOf("\"role\":\"system\"");
        int firstUser = body.indexOf("\"role\":\"user\"");
        assertTrue(systemIdx >= 0 && systemIdx < firstUser,
                "system message must precede the user/assistant history");

        // The assistant's prior reply must appear before the current question
        // — this is what gives the model memory of the conversation.
        int assistantReply = body.indexOf("fiquei a saber do Rafael");
        int currentQuestion = body.indexOf("Quem é o meu irmão?");
        assertTrue(assistantReply >= 0 && assistantReply < currentQuestion,
                "prior assistant reply must come before the current user question");

        // The model id and /api/chat-only fields must be present.
        assertTrue(body.contains("\"model\":\"llama3.2:3b\""));
        assertTrue(body.contains("\"messages\":["));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"keep_alive\""));
    }

    @Test
    void bodyOmitsSystemMessageWhenBlank() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "Olá")
        );

        String body = OllamaService.buildChatRequestBody(
                "llama3.2:3b", history, "");

        assertTrue(body.contains("\"role\":\"user\""));
        // No system role when the system prompt is blank.
        assertEquals(-1, body.indexOf("\"role\":\"system\""),
                "a blank system prompt must not emit a system message");
    }

    @Test
    void bodyEscapesQuotesInContent() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "Ela disse \"olá\" e foi-se embora")
        );

        String body = OllamaService.buildChatRequestBody(
                "llama3.2:3b", history, null);

        // The raw quotes in the user's message must be escaped so the JSON
        // stays valid.
        assertTrue(body.contains("\\\"olá\\\""),
                "embedded quotes must be escaped in the JSON body");
    }

    /**
     * Root-cause regression: the JSON extraction request MUST include an explicit
     * options.num_predict. Without it, Ollama applies its default (historically
     * 128 tokens), truncating the JSON after 1-2 entities — so only the first
     * entities (usually PERSON) reached the parser.
     */
    @Test
    void extractionBodyIncludesGenerousNumPredict() {
        String base = OllamaService.buildChatRequestBody("qwen2.5:7b",
                List.of(new ChatMessage("user", "nota")), null);
        String body = OllamaService.applyExtractionOptions(base, true);

        assertTrue(body.contains("\"stream\":false"),
                "extraction must be non-streaming: " + body);
        assertTrue(body.contains("\"format\":\"json\""),
                "json format must be forced: " + body);
        assertTrue(body.contains("\"num_predict\":8192"),
                "num_predict must be generous to avoid truncation: " + body);
        assertTrue(body.contains("\"temperature\":0.1"),
                "low temperature for structured extraction: " + body);
    }
}
