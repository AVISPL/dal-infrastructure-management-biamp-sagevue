package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.atlassian.ta.wiremockpactgenerator.WireMockPactGenerator;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.HttpCommunicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@Tag("test")
public class SageVueCommunicatorTest {
    static SageVueCommunicator sageVueCommunicator;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort().dynamicHttpsPort().bindAddress("127.0.0.1"));

    {
        wireMockRule.addMockServiceRequestListener(WireMockPactGenerator
                .builder("biamp-sagevue-adapter", "biamp-sagevue")
                .withRequestHeaderWhitelist("authorization", "content-type").build());
        wireMockRule.start();
    }

    @BeforeEach
    public void init() throws Exception {
        sageVueCommunicator = new SageVueCommunicator();
        sageVueCommunicator.setTrustAllCertificates(true);
        sageVueCommunicator.setProtocol("http");
        sageVueCommunicator.setContentType("application/json");
        sageVueCommunicator.setPort(80);
        sageVueCommunicator.setHost("172.31.254.17");
        sageVueCommunicator.setAuthenticationScheme(HttpCommunicator.AuthenticationScheme.Basic);
        sageVueCommunicator.setLogin("Admin");
        sageVueCommunicator.setPassword("1234");
        sageVueCommunicator.init();
    }

    @Test
    public void authenticationIsSuccessful() throws Exception {
        sageVueCommunicator.authenticate();
        Assert.assertFalse(sageVueCommunicator.getLoginId().isEmpty());
    }

    @Test
    public void getSystemsTest() throws Exception {
        List<AggregatedDevice> devices = sageVueCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
    }
}
