package com.back.shared.member.out;

import com.back.global.auth.SystemAuthTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MemberApiClient {
    private final RestClient restClient;
    private final SystemAuthTokenProvider systemAuthTokenProvider;

    public MemberApiClient(
            @Value("${custom.services.member-url}") String memberServiceUrl,
            SystemAuthTokenProvider systemAuthTokenProvider
    ) {
        this.systemAuthTokenProvider = systemAuthTokenProvider;
        this.restClient = RestClient.builder()
                .baseUrl(memberServiceUrl + "/api/v1/member/members")
                .build();
    }

    public String getRandomSecureTip() {
        return restClient.get()
                .uri("/randomSecureTip")
                .header("Authorization", systemAuthTokenProvider.getAuthorizationHeader())
                .retrieve()
                .body(String.class);
    }
}
