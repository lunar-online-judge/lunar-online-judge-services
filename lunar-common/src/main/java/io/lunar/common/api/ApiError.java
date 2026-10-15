package io.lunar.common.api;

/**
 * 失败响应中的错误体：{@code code} 取自 docs/api.md 错误码注册表，{@code message} 是面向调用方的可读原因。
 *
 * 独立成文件而不是内嵌在 {@link ApiResponse} 里：一个类型一个文件，便于检索与引用。
 */
public record ApiError(String code, String message) {
}
