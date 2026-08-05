package com.judepereira.jupiter.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.ui.UiExceptionHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiExceptionHandlerTests {

    @Test
    void handlesUiExceptionByPublishingInternalErrorBalloonAndReturning500() {
        SystemBalloonService balloonService = new SystemBalloonService(new ObjectMapper());
        UiExceptionHandler handler = new UiExceptionHandler(balloonService);

        var response = handler.handleUiException(new IllegalStateException("Boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).contains("An internal error occurred.");
        assertThat(response.getBody()).contains("Exception: Boom");
        assertThat(response.getBody()).contains("The full stacktrace is available in the logs.");

        assertThat(balloonService.publishedBalloons()).hasSize(1);
        SystemBalloon balloon = balloonService.publishedBalloons().get(0);
        assertThat(balloon.type()).isEqualTo(SystemBalloon.Type.ERROR);
        assertThat(balloon.title()).isEqualTo("Internal Error");
        assertThat(balloon.body()).contains("An internal error occurred.");
        assertThat(balloon.body()).contains("Exception: Boom");
        assertThat(balloon.body()).contains("The full stacktrace is available in the logs.");
    }
}
