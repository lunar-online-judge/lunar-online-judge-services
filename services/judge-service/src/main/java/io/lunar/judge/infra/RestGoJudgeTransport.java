package io.lunar.judge.infra;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.List;

/** go-judge HTTP 传输实现：RestClient + 超时配置（GO_JUDGE_CONNECT_TIMEOUT_MS / GO_JUDGE_READ_TIMEOUT_MS）。 */
class RestGoJudgeTransport implements GoJudgeTransport {

    private final RestClient rest;

    RestGoJudgeTransport(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        // go-judge 未来版本可能新增响应字段，反序列化必须容忍未知字段
        JsonMapper mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeoutMs);
        factory.setReadTimeout((int) readTimeoutMs);
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                })
                .build();
    }

    @Override
    public List<GoJudgeModels.RunResult> run(GoJudgeModels.RunRequest request) {
        return rest.post()
                .uri("/run")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GoJudgeModels.RunResult>>() {});
    }

    @Override
    public String version() {
        return rest.get().uri("/version").retrieve().body(String.class);
    }
}
