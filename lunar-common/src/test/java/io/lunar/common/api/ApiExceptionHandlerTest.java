package io.lunar.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 平台级错误契约：任何异常出口都必须落进 ApiResponse 信封（docs/api.md 通用约定）。 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void domainExceptionKeepsRegisteredCodeAndStatus() {
        ApiException conflict = new ApiException("TEST_CONFLICT", 409, "状态冲突") {
        };

        ResponseEntity<ApiResponse<Void>> response = handler.handleDomainException(conflict);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("TEST_CONFLICT");
        assertThat(response.getBody().error().message()).isEqualTo("状态冲突");
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutLeakingDetail() throws Exception {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("jdbc:postgresql://lunar:secret@db:5432/lunar"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("服务内部错误");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void unreadableRequestBodyIsValidationFailure() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("malformed json", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void frameworkStatusExceptionIsRethrownToKeepItsStatus() {
        // 带自身状态语义的框架异常（404/405/415…）必须原样抛出，否则会被兜底改写成 500
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> handler.handleUnexpectedException(notFound)).isSameAs(notFound);
    }
}
