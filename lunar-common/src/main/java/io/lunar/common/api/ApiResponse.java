package io.lunar.common.api;

public record ApiResponse<T>(String requestId, T data, ApiError error) {
    public static <T> ApiResponse<T> success(String requestId, T data) {
        return new ApiResponse<>(requestId, data, null);
    }

    public static <T> ApiResponse<T> failure(String requestId, String code, String message) {
        return new ApiResponse<>(requestId, null, new ApiError(code, message));
    }

    public record ApiError(String code, String message) {}
}
