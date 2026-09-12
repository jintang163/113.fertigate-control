package com.farm.irrigation.control;

import com.farm.irrigation.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when an OPEN request is rejected by cloud-side hard interlocks
 * (hardMax / stale data / CRITICAL alarm / valve or gateway offline).
 */
public class SafetyBlockedException extends ApiException {

    public SafetyBlockedException(String message) {
        super(HttpStatus.CONFLICT, 409, message);
    }

    public static SafetyBlockedException of(String message) {
        return new SafetyBlockedException(message);
    }
}
