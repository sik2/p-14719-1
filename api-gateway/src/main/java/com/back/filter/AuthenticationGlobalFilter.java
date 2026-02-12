package com.back.filter;

import com.back.client.MemberServiceClient;
import com.back.dto.MemberDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private final MemberServiceClient memberServiceClient;
    private final ObjectMapper objectMapper;

    @Value("${custom.system.apiKey}")
    private String systemApiKey;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/member/members/login",
            "/api/v1/member/members/logout",
            "/api/v1/member/members/join"
    );

    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // /api/ 로 시작하지 않으면 통과
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        // 공개 엔드포인트 통과
        if (isPublicPath(path, method)) {
            return chain.filter(exchange);
        }

        // Authorization 헤더 파싱
        String headerAuthorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String apiKey = "";
        String accessToken = "";

        if (headerAuthorization != null && !headerAuthorization.isBlank()) {
            if (!headerAuthorization.startsWith("Bearer ")) {
                return errorResponse(exchange, HttpStatus.UNAUTHORIZED, "401-2", "Authorization 헤더가 Bearer 형식이 아닙니다.");
            }

            String[] bits = headerAuthorization.split(" ", 3);
            apiKey = bits.length > 1 ? bits[1] : "";
            accessToken = bits.length > 2 ? bits[2] : "";
        } else {
            // 쿠키에서 읽기
            apiKey = getCookieValue(request, "apiKey");
            accessToken = getCookieValue(request, "accessToken");
        }

        boolean isApiKeyExists = !apiKey.isBlank();
        boolean isAccessTokenExists = !accessToken.isBlank();

        // 인증 정보 없으면 통과 (서비스에서 권한 체크)
        if (!isApiKeyExists && !isAccessTokenExists) {
            return chain.filter(exchange);
        }

        // 시스템 API Key인 경우
        if (isApiKeyExists && apiKey.equals(systemApiKey)) {
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", "1")
                    .header("X-User-Name", "system")
                    .header("X-User-Nickname", "시스템")
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        // 토큰 검증
        final String finalApiKey = apiKey;
        final String finalAccessToken = accessToken;

        if (isAccessTokenExists) {
            return memberServiceClient.validateToken(finalAccessToken)
                    .flatMap(member -> proceedWithMember(exchange, chain, member))
                    .switchIfEmpty(
                            isApiKeyExists
                                    ? validateByApiKey(exchange, chain, finalApiKey)
                                    : chain.filter(exchange)
                    );
        } else if (isApiKeyExists) {
            return validateByApiKey(exchange, chain, finalApiKey);
        }

        return chain.filter(exchange);
    }

    private Mono<Void> validateByApiKey(ServerWebExchange exchange, GatewayFilterChain chain, String apiKey) {
        return memberServiceClient.findByApiKey(apiKey)
                .flatMap(member -> proceedWithMember(exchange, chain, member))
                .switchIfEmpty(
                        errorResponse(exchange, HttpStatus.UNAUTHORIZED, "401-3", "API 키가 유효하지 않습니다.")
                );
    }

    private Mono<Void> proceedWithMember(ServerWebExchange exchange, GatewayFilterChain chain, MemberDto member) {
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(member.id()))
                .header("X-User-Name", member.username())
                .header("X-User-Nickname", member.nickname())
                .build();

        log.debug("Authenticated user: {} ({})", member.username(), member.id());

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isPublicPath(String path, HttpMethod method) {
        // 정적 공개 경로
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }

        // GET 요청의 게시물 조회는 공개
        if (method == HttpMethod.GET) {
            if (path.matches("/api/v\\d+/post/posts(/\\d+)?") ||
                path.matches("/api/v\\d+/post/posts/\\d+/comments(/\\d+)?")) {
                return true;
            }
        }

        return false;
    }

    private String getCookieValue(ServerHttpRequest request, String name) {
        HttpCookie cookie = request.getCookies().getFirst(name);
        return cookie != null ? cookie.getValue() : "";
    }

    private Mono<Void> errorResponse(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = Map.of(
                "resultCode", code,
                "msg", message,
                "data", Map.of()
        );

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorBody);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            byte[] bytes = ("{\"resultCode\":\"" + code + "\",\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        }
    }
}
