package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@Log4j2
@ControllerAdvice(assignableTypes = UiController.class)
@RequiredArgsConstructor
public class UiExceptionHandler {

    private final SystemBalloonService systemBalloonService;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUiException(Exception exception) {
        if (exception instanceof AsyncRequestNotUsableException) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body("");
        }
        log.error("Unhandled exception servicing UI request", exception);

        String exceptionDetail = exception.getMessage();
        if (exceptionDetail == null || exceptionDetail.isBlank()) {
            exceptionDetail = exception.getClass().getSimpleName();
        }

        String body = "An internal error occurred.\n\nException: "
                + exceptionDetail
                + "\n\nThe full stacktrace is available in the logs.";
        systemBalloonService.publishError("Internal Error", body);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }
}
