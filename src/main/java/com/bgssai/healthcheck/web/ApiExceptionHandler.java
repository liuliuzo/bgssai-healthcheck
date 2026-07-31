package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.service.UnknownApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * 把领域异常翻译成 RFC 9457 的 {@code application/problem+json}。
 */
@RestControllerAdvice(assignableTypes = HealthApiController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownApplicationException.class)
    public ProblemDetail handleUnknownApplication(UnknownApplicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("应用不存在");
        problem.setType(URI.create("urn:bgssai:healthcheck:unknown-application"));
        problem.setProperty("applicationId", ex.getApplicationId());
        return problem;
    }
}
