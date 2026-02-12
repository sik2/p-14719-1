package com.back.client;

import com.back.dto.MemberDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class MemberServiceClient {
    private final WebClient webClient;
    private final String systemApiKey;

    public MemberServiceClient(
            @Value("${custom.services.member-url}") String memberServiceUrl,
            @Value("${custom.system.apiKey}") String systemApiKey
    ) {
        this.systemApiKey = systemApiKey;
        this.webClient = WebClient.builder()
                .baseUrl(memberServiceUrl + "/api/v1/member/members")
                .build();
    }

    private String getAuthorizationHeader() {
        return "Bearer " + systemApiKey + " empty";
    }

    public Mono<MemberDto> validateToken(String accessToken) {
        return webClient.post()
                .uri("/validate-token")
                .header("Authorization", getAuthorizationHeader())
                .bodyValue(Map.of("accessToken", accessToken))
                .retrieve()
                .bodyToMono(MemberDto.class)
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<MemberDto> findByApiKey(String apiKey) {
        return webClient.get()
                .uri("/by-apikey/" + apiKey)
                .header("Authorization", getAuthorizationHeader())
                .retrieve()
                .bodyToMono(MemberDto.class)
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<String> generateAccessToken(int memberId) {
        return webClient.post()
                .uri("/" + memberId + "/access-token")
                .header("Authorization", getAuthorizationHeader())
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> (String) response.get("data"))
                .onErrorResume(e -> Mono.empty());
    }
}
