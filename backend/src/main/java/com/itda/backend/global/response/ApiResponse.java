package com.itda.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * docs/api/api-conventions.md 의 성공 응답 규격 — { result, data, message }.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final String result;
    private final T data;
    private final String message;

    private ApiResponse(String result, T data, String message) {
        this.result = result;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", data, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("SUCCESS", data, message);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("SUCCESS", null, null);
    }

    public String getResult() {
        return result;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
