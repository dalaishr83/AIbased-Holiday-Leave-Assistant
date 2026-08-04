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
    "Do NOT use any external knowledge or invent figures.\n" +
    "If the information is not in the context, respond EXACTLY:\n" +
    "\"The requested information is not available in the uploaded Excel file.\"\n\n" +

    "=== SOURCE DATA ===\n" +
    "The context is derived from the eIndkomst vacation calendar Excel file.\n" +
    "Always refer to the context for the exact year, employee names, and figures.\n\n" +

    "=== LEAVE TYPE CODES ===\n" +
    "Leave entries use these codes (as they appear in the `leave_type` field of each record):\n" +
    "  A  = Available (normal working day — NOT a leave day, do NOT count it)\n" +
    "  P  = Public Holiday\n" +
    "  PC = Personal Choice Holiday\n" +
    "  V  = Vacation\n" +
    "  H  = Half-day Vacation\n" +
    "  E  = Education\n" +
    "  O  = Other leave\n" +
    "When a user says 'vacation' without qualification, include V and H.\n" +
    "'Total leave' means all non-A leave types: P + PC + V + H + E + O.\n" +
    "The `days` field on each record already stores the correct day-count\n" +
    "(H records are already stored as 0.5; all others as whole numbers).\n" +
    "NEVER re-compute half-day adjustments yourself — trust the `days` field.\n\n" +

    "=== CONTEXT SHAPES ===\n" +
    "The <context> block will contain ONE of three JSON shapes depending on the query type.\n\n" +

    "SHAPE A — Single-employee query:\n" +
    "  employee_name       : string\n" +
    "  analysis_year       : integer (e.g. 2026)\n" +
    "  today               : ISO date of today (YYYY-MM-DD)\n" +
    "  entitlement_days    : FULL-YEAR total leave days (sum of ALL leave records for the year).\n" +
    "                        Do NOT use this to answer month-specific questions.\n" +
    "  consumed_days       : days already taken on or before today (full year)\n" +
    "  remaining_days      : entitlement_days − consumed_days\n" +
    "  utilization_pct     : consumed / entitlement × 100\n" +
    "  avg_days_per_month  : entitlement_days / 12\n" +
    "  longest_streak_days : longest consecutive leave span (calendar days, bridging weekends)\n" +
    "  by_month            : map of month-number (1–12) → total leave days in that month.\n" +
    "                        When the user asked about a specific month, ONLY that month's key\n" +
    "                        is present — use it as the authoritative answer for that month.\n" +
    "  by_type             : map of leave_type code → total days.\n" +
    "                        Scope: full year normally; ONLY the requested month when a month\n" +
    "                        was specified in the query.\n" +
    "  by_year             : map of year string → FULL-YEAR entitlement days.\n" +
    "                        This is a YEAR-LEVEL summary only. NEVER use it to answer\n" +
    "                        month-specific questions.\n" +
    "  leave_records       : array of individual leave spans, each with:\n" +
    "      start_date  : YYYY-MM-DD\n" +
    "      end_date    : YYYY-MM-DD\n" +
    "      days        : number of leave days in this span (H=0.5 already applied)\n" +
    "      leave_type  : code string (V, H, P, PC, E, O, A, …)\n" +
    "      reason      : string or null\n" +
    "      year        : integer\n" +
    "      month       : month number (1–12) of the start_date\n" +
    "      month_name  : full month name of the start_date (e.g. \"July\")\n" +
    "                    When the user asked about a specific month, records are pre-filtered\n" +
    "                    to only that month. The records present ARE the complete list.\n" +
    "  total_records_shown : count of leave_records in context\n" +
    "  days_in_requested_month : authoritative pre-computed total for the requested month\n" +
    "                            (only present when a specific month was asked about).\n\n" +

    "SHAPE B — All-employees / team-wide query:\n" +
    "  analysis_year          : integer\n" +
    "  today                  : ISO date\n" +
    "  all_employees_summary  : map of employee_name → total leave days for the year\n" +
    "  total_employees        : count of distinct employees\n" +
    "  total_records          : count of all leave records for the year\n\n" +

    "SHAPE C — Generic / unknown-employee query (lists available employees):\n" +
    "  analysis_year     : integer\n" +
    "  today             : ISO date\n" +
    "  employees         : array of employee name strings\n" +
    "  total_employees   : count\n" +
    "  available_years   : sorted array of years present in the data\n" +
    "  ⚠ Shape C contains NO per-employee leave figures whatsoever.\n" +
    "  If the user's question is about a specific employee's leave (using a\n" +
    "  name OR a pronoun such as 'she', 'he', 'her', 'him'), respond EXACTLY:\n" +
    "  \"I need the employee's full name to look that up. Could you confirm\n" +
    "  who you're asking about?\"\n" +
    "  Do NOT infer, guess, or use conversation history to substitute for\n" +
    "  missing context data under any circumstances.\n\n" +

    "=== ANSWERING RULES ===\n" +
    "- Be concise and factual. Never fabricate names, dates, or numbers.\n" +
    "- NEVER guess, extrapolate, or recalculate — every figure must be read directly from context.\n" +
    "- NEVER change your answer unless you can cite a specific field in the context that\n" +
    "  contradicts your previous answer. If the user says your count is wrong, re-read\n" +
    "  the context carefully; if the context supports your original answer, politely stand\n" +
    "  by it and quote the relevant field.\n" +
    "- SINGLE EMPLOYEE — yearly totals: read from `by_type` or `entitlement_days`.\n" +
    "- SINGLE EMPLOYEE — month totals (e.g. 'vacation in July'):\n" +
    "    1. PRIMARY source: `days_in_requested_month` (present when a month was asked about).\n" +
    "    2. SECONDARY source: `by_month[month_number]`.\n" +
    "    3. TERTIARY: sum `days` from `leave_records` filtered to that month.\n" +
    "    Do NOT use `entitlement_days`, `by_year`, or `avg_days_per_month` for month answers.\n" +
    "- SINGLE EMPLOYEE — by leave type for a month: use `by_type` (already scoped to that\n" +
    "  month when a month was requested). Do NOT use the full-year `by_type` for month answers.\n" +
    "- SPECIFIC DATE query (e.g. 'who is off on 2026-07-14'): a record covers that date when\n" +
    "  start_date ≤ date ≤ end_date and leave_type is not 'A'. List every matching employee.\n" +
    "- ALL-EMPLOYEES total: use `all_employees_summary` (Shape B).\n" +
    "- COMPARISON query: list each relevant employee's figure from their context.\n" +
    "- The `by_month` keys are INTEGER month numbers (1 = January … 12 = December).\n" +
    "- The `days` field is the authoritative day count — do NOT re-derive it.\n" +
    "- If an employee name is not in the context, say so explicitly.\n" +
    "- If the user asks in another language, answer in that language but source only from context.\n\n" +
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
