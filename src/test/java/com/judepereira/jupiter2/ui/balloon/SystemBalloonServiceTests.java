package com.judepereira.jupiter2.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemBalloonServiceTests {

    @Test
    void publishRecordsBalloonWithoutConnectedClients() {
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper());

        service.publishError("Git error", "Could not check out existing Git branch \"missing\".\n\nGit output:\nfatal: invalid reference: missing");

        assertThat(service.publishedBalloons()).hasSize(1);
        SystemBalloon balloon = service.publishedBalloons().get(0);
        assertThat(balloon.type()).isEqualTo(SystemBalloon.Type.ERROR);
        assertThat(balloon.title()).isEqualTo("Git error");
        assertThat(balloon.body()).contains("Could not check out existing Git branch");
        assertThat(balloon.body()).contains("missing");
        assertThat(balloon.body()).contains("Git output:");
        assertThat(balloon.body()).contains("fatal: invalid reference: missing");
        assertThat(balloon.id()).isNotNull();
        assertThat(balloon.createdAt()).isNotNull();
    }
}
