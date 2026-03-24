package com.bloodstar.fluxragcompute.utils;

import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.exception.BusinessException;

public final class ThrowUtils {

    private ThrowUtils() {
    }

    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BusinessException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BusinessException(errorCode, message);
        }
    }
}
