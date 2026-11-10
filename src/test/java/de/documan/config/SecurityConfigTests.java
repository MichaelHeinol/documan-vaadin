package de.documan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;

class SecurityConfigTests {

    private static HttpFirewall firewall(String... hosts) {
        DocuManProperties properties = new DocuManProperties();
        properties.setAllowedHosts(List.of(hosts));
        return new SecurityConfig().httpFirewall(properties);
    }

    private static MockHttpServletRequest request(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServerName(host);
        return request;
    }

    @Test
    void everyHostIsAcceptedByDefault() {
        assertThat(firewall("*").getFirewalledRequest(request("nas.fritz.box"))).isNotNull();
    }

    @Test
    void onlyListedHostsAreAcceptedOtherwise() {
        HttpFirewall firewall = firewall("dms.example.de");
        assertThat(firewall.getFirewalledRequest(request("DMS.example.de"))).isNotNull();
        assertThat(firewall.getFirewalledRequest(request("localhost"))).isNotNull();
        assertThatThrownBy(() -> firewall.getFirewalledRequest(request("boese.example")))
                .isInstanceOf(RequestRejectedException.class);
    }
}
