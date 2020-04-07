package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.atlassian.ta.wiremockpactgenerator.WireMockPactGenerator;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.HttpCommunicator;
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
        sageVueCommunicator.setPort(wireMockRule.port());
        sageVueCommunicator.setHost("127.0.0.1");
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
    public void getDevicesTest() throws Exception {
        List<AggregatedDevice> devices = sageVueCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals("03275657", devices.get(0).getSerialNumber());
        Assert.assertEquals("172.31.254.129", devices.get(0).getProperties().get("ipAddress"));
        Assert.assertEquals(19, ((AdvancedControllableProperty.DropDown)devices.get(0).getControllableProperties().get(1).getType()).getOptions().length);
    }

    @Test
    public void getSystemsAndControlsTest() throws Exception {
        List<Statistics> devices = sageVueCommunicator.getMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertFalse(((ExtendedStatistics)devices.get(0)).getControllableProperties().isEmpty());
    }
}
