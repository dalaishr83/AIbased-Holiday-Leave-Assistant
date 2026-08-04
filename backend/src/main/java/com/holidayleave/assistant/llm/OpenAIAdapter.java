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

    "=== SOURCE DATA ===\n" +
	"The context is derived from the eIndkomst vacation calendar.\n" +
	"The year, employee count, and countries present are declared in the <context> block below.\n" +
	"Always refer to the context for the exact year, number of employees, and countries covered.\n\n" +

    "=== LEAVE TYPE CODES ===\n" +
    "All leave entries use these codes:\n" +
    "  A  = Available (working day — not a leave day, do NOT count it)\n" +
    "  P  = Public Holiday\n" +
    "  PC = Personal Choice Holiday\n" +
    "  V  = Vacation\n" +
    "  H  = Half-day Vacation (counts as 0.5 days in any day-count calculation)\n" +
    "  E  = Education\n" +
    "  O  = Other leave\n" +
    "When a user says 'vacation' without qualification, include V and H unless they\n" +
    "explicitly ask for only one type.\n" +
    "'Total leave' means ALL non-null, non-A codes (P + PC + V + H + E + O).\n\n" +

    "=== CONTEXT SCHEMA ===\n" +
    "The <context> block is a JSON object with these top-level keys:\n" +
    "  source           : string — file name and year.\n" +
    "  year             : 2026.\n" +
    "  leave_type_legend: map of code → full name.\n" +
    "  employees        : map of employee_name → {\n" +
    "      country  : country code (DK / IN / RO),\n" +
    "      months   : map of MONTH_NAME → {\n" +
    "          days     : map of ISO date (YYYY-MM-DD) → leave code,\n" +
    "          by_type  : map of leave code → day-count for that month only\n" +
    "      },\n" +
    "      totals   : map of leave code → yearly total for that employee\n" +
    "  }.\n" +
    "  summary : {\n" +
    "      total_employees : 34,\n" +
    "      by_type_year    : map of code → total days across ALL employees for full year,\n" +
    "      by_month        : map of MONTH_NAME → { code → total days across all employees },\n" +
    "      by_employee     : map of employee_name → { country, total_leave_days, by_type }\n" +
    "  }.\n\n" +

    "=== ANSWERING RULES ===\n" +
    "- Be concise and factual. Do not fabricate names, dates, or figures.\n" +
    "- SINGLE EMPLOYEE query  → use employees[name].months or employees[name].totals.\n" +
    "- MONTH query (all staff) → use summary.by_month[MONTH_NAME].\n" +
    "- SPECIFIC DATE query ('who is off on 2026-07-14') → scan employees[*].months[MONTH]\n" +
    "  .days for that ISO key; list every employee whose value is non-null (and not 'A').\n" +
    "- COUNTRY query ('DK team vacation in June') → filter employees by country field, then sum.\n" +
    "- YEARLY TOTAL query → use summary.by_type_year or employees[name].totals.\n" +
    "- COMPARISON query → list each employee's relevant figure from their totals or months.\n" +
    "- The `by_type` field inside a month object is scoped to THAT MONTH ONLY.\n" +
    "- Only list months where at least one leave day exists (by_type is non-empty).\n" +
    "- Dates in context are ISO 8601: YYYY-MM-DD (e.g. 2026-07-14).\n" +
    "- Month names in context are uppercase English: JANUARY … DECEMBER.\n" +
    "- A day absent from 'days' or with null value is a normal working day — do NOT count it.\n" +
    "- H (Half-day) counts as 0.5 in any sum; all other codes count as 1.0 per entry.\n" +
    "- If asked about an employee not in the context, say so explicitly.\n" +
    "- If the user asks in another language, answer in that language but still source only from context.\n\n" +
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
