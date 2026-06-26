package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevConsoleSmsSenderTest {

    @Test
    void name_is_dev_console() {
        assertThat(new DevConsoleSmsSender().name()).isEqualTo("dev-console");
    }

    @Test
    void send_does_not_throw_for_valid_message() throws SmsSendException {
        // Just log; no assertions beyond "doesn't throw" (declared because the
        // SPI signature permits SmsSendException — dev console doesn't use it).
        new DevConsoleSmsSender().send(new SmsMessage("+15551234567", "+15550100000",
                "Your code is 123456.", "test-idem-1"));
    }

    @Test
    void sms_message_rejects_non_e164_to_phone() {
        assertThatThrownBy(() -> new SmsMessage("not-a-phone", "+15550100000",
                "body", "idem"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    void sms_message_rejects_blank_from_phone() {
        assertThatThrownBy(() -> new SmsMessage("+15551234567", "",
                "body", "idem"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromPhone");
    }
}
