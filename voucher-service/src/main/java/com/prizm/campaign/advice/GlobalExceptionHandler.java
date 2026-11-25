package com.prizm.campaign.advice;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.prizm.campaign.exception.VoucherLimitReachException;

@RestControllerAdvice("com.prizm.campaign")
public class GlobalExceptionHandler {

    @ExceptionHandler(VoucherLimitReachException.class)
    public ResponseEntity<Map<String, String>> handleVoucherLimitReachException(VoucherLimitReachException ex) {
        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("message", ex.getMessage());
        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

}
