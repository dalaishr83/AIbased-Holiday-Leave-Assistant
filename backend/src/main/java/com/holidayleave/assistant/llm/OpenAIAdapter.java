package com.holidayleave.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.holidayleave.assistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible LLM adapter.
 * Works with OpenAI, Ollama, OpenRouter, IBM Watsonx, Groq, Gemini by swapping base URL and model.
 */
@Service
public class OpenAIAdapter implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAdapter.class);

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are a helpful assistant for employee leave management.\n" +
            "You MUST answer ONLY from the <context> block provided below.\n" +
            "Do NOT use any external knowledge.\n" +
            "If the information is not in the context, respond EXACTLY:\n" +
            "\"The requested information is not available in the uploaded Excel file.\"\n\n" +
            "Rules:\n" +
            "- Be concise and factual.\n" +
            "- Do not fabricate names, dates, or figures.\n" +
            "- The `by_type` field in context is scoped to the current query (month or year).\n" +
            "- \"vacation\" is colloquial and includes all leave types unless specified.\n" +
            "- Only list months with by_month value > 0.\n\n" +
            "<context>\n%s\n</context>\n";

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String ask(String systemPrompt, String context, String question, List<Map<String, String>> history) {
        String effectivePrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);

        WebClient client = buildClient();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getLlm().getModel());
        body.put("temperature", props.getLlm().getTemperature());
        body.put("max_tokens", props.getLlm().getMaxTokens());

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", effectivePrompt);
        messages.add(sysMsg);

        if (history != null) {
            for (Map<String, String> h : history) {
                ObjectNode hMsg = mapper.createObjectNode();
                hMsg.put("role", h.get("role"));
                hMsg.put("content", h.get("content"));
                messages.add(hMsg);
            }
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", question);
        messages.add(userMsg);

        if (!props.getLlm().getWatsonxProjectId().isEmpty()) {
            body.put("project_id", props.getLlm().getWatsonxProjectId());
        }

        try {
            String uri = "/chat/completions";
            if (!props.getLlm().getWatsonxProjectId().isEmpty()) {
                uri += "?project_id=" + props.getLlm().getWatsonxProjectId();
            }
            String response = client.post()
                    .uri(uri)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (WebClientResponseException e) {
            log.error("LLM API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LLMServiceException("LLM service error: " + e.getMessage());
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            throw new LLMServiceException("LLM call failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return !props.getLlm().getApiKey().isEmpty();
    }

    private WebClient buildClient() {
        return WebClient.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getLlm().getApiKey())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    public static class LLMServiceException extends RuntimeException {
        public LLMServiceException(String msg) { super(msg); }
    }
}
