package com.bajaj.hiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class StartupRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

  private final WebClient webClient;

  @Value("${app.candidate.name}")
  private String name;
  @Value("${app.candidate.regNo}")
  private String regNo;
  @Value("${app.candidate.email}")
  private String email;

  @Value("${app.endpoints.generate}")
  private String generateUrl;
  @Value("${app.endpoints.fallbackSubmit}")
  private String fallbackSubmitUrl;
  @Value("${app.storage.outFile}")
  private String outFile;

  public StartupRunner(WebClient.Builder builder) {
    this.webClient = builder.build();
  }

  record GenerateReq(String name, String regNo, String email) {}
  record GenerateRes(String webhook, String accessToken) {}

  @Override
  public void run(ApplicationArguments args) throws Exception {
    log.info("Starting Bajaj Finserv hiring solver...");

    // 1) call generateWebhook
    GenerateRes res = webClient.post()
        .uri(generateUrl)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GenerateReq(name, regNo, email))
        .retrieve()
        .bodyToMono(GenerateRes.class)
        .doOnError(e -> log.error("generateWebhook failed: ", e))
        .block();

    if (res == null || res.accessToken() == null) {
      log.error("No accessToken received. Exiting.");
      throw new IllegalStateException("No accessToken/webhook returned");
    }

    String webhookUrl = (res.webhook() != null && !res.webhook().isBlank()) ? res.webhook() : fallbackSubmitUrl;
    String token = res.accessToken();
    log.info("Received webhookUrl: {}", webhookUrl);

    // 2) Load final SQL from resources/queries/finalQuery.sql
    String finalQuery;
    try (var in = getClass().getClassLoader().getResourceAsStream("queries/finalQuery.sql")) {
      if (in == null) {
        throw new IllegalStateException("queries/finalQuery.sql not found in resources");
      }
      finalQuery = new String(in.readAllBytes());
    }

    // 3) Save to disk (target/finalQuery.sql)
    Path p = Path.of(outFile);
    Files.createDirectories(p.getParent());
    Files.writeString(p, finalQuery);
    log.info("Saved final query to {}", p.toAbsolutePath());

    // 4) Submit final query to the webhook
    String authHeader = token.startsWith("Bearer ") ? token : "Bearer " + token;
    Map<String, String> body = Map.of("finalQuery", finalQuery);

    String submitResp = webClient.post()
        .uri(webhookUrl)
        .header("Authorization", authHeader)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .doOnError(e -> log.error("submit failed: ", e))
        .block();

    log.info("Submission response: {}", submitResp);
    log.info("Done.");
  }
}
