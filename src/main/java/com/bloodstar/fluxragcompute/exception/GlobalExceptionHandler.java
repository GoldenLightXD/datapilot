package com.bloodstar.fluxragcompute.exception;

import com.bloodstar.fluxragcompute.common.BaseResponse;
import com.bloodstar.fluxragcompute.common.ErrorCode;
import com.bloodstar.fluxragcompute.common.ResultUtils;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<Void> businessExceptionHandler(BusinessException ex) {
        return ResultUtils.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<Void> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorCode.PARAMS_ERROR.getMessage() : fieldError.getDefaultMessage();
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<Void> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<Void> exceptionHandler(Exception ex) {
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
    }
}
