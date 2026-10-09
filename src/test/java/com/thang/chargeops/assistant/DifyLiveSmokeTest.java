package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in only: makes two billable calls using the ignored local .env. */
@EnabledIfSystemProperty(named = "bkg067.dify.live", matches = "true")
class DifyLiveSmokeTest {
    @Test
    void controllerServiceAndRealPublishedDifyAnswerBothDeadlineSides() throws Exception {
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { env.load(reader); }
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(env.getProperty("DIFY_BASE_URL", "https://api.dify.ai/v1"));
        properties.setApiKey(env.getProperty("DIFY_API_KEY"));
        assertThat(properties.getApiKey()).as("Local app key configured").isNotBlank();
        CurrentActorContext actors = new CurrentActorContext();
        actors.set(UUID.randomUUID());
        try {
            AssistantService service = new AssistantService(actors, new DifyClient(properties), new AssistantRateLimiter(properties));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new AssistantController(service))
                    .setControllerAdvice(new GlobalHandlerError()).build();
            assertAnswer(mvc, "vi", "Theo ân hạn mặc định 10 phút, booking CONFIRMED chưa check-in, thanh toán xác nhận lúc 08:58, phiên bắt đầu 11:00. Máy chủ xử lý hủy do đổi ý đúng 09:08 thì hoàn bao nhiêu?", "0%");
            assertAnswer(mvc, "en", "With the default 10-minute grace, a CONFIRMED booking has not checked in. Payment was confirmed at 08:58 and the session starts at 11:00. The server processes a change-of-mind cancellation at 09:07:59. What percentage of the package is refunded?", "100%");
        } finally { actors.clear(); }
    }

    private void assertAnswer(MockMvc mvc, String locale, String question, String percentage) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String body = json.writeValueAsString(new AssistantAskRequest(question, locale));
        String response = mvc.perform(post("/api/v1/assistant/ask").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var data = json.readTree(response).path("data");
        String answer = data.path("answer").asText();
        System.out.println("Dify live " + locale + ": " + answer);
        // A valid answer may explain "not 100%, but 0%". This smoke assertion
        // checks the expected percentage, not full natural-language meaning.
        // Review the printed answer and policy sources for semantic acceptance.
        var refund = java.util.regex.Pattern.compile("(?<![\\d.])" + java.util.regex.Pattern.quote(percentage))
                .matcher(answer);
        assertThat(refund.find()).as("Answer includes the expected refund percentage").isTrue();
        assertThat(data.path("citations").size()).isPositive();
        StringBuilder sources = new StringBuilder();
        data.path("citations").forEach(citation -> sources.append(citation.path("content").asText()).append('\n'));
        assertThat(sources.toString()).as("Retrieved sources include the deadline boundary example")
                .contains("09:08", "09:07:59");
        System.out.println("Dify live sources: " + data.path("citations").size());
    }
}
