/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
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
import org.junit.platform.commons.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

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

    }

    @Test
    public void getDevicesTest() throws Exception {
        sageVueCommunicator.init();
        sageVueCommunicator.retrieveMultipleStatistics();
        Thread.sleep(300000);
        List<AggregatedDevice> devices = sageVueCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(1581, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        List<AggregatedDevice> devicesWithDetails = devices.stream().filter(aggregatedDevice -> StringUtils.isNotBlank(aggregatedDevice.getProperties().get("ipAddress"))).collect(Collectors.toList());
        List<AggregatedDevice> devicesWithFaults = devices.stream().filter(aggregatedDevice -> StringUtils.isNotBlank(aggregatedDevice.getProperties().get("deviceFaults"))).collect(Collectors.toList());
        Assert.assertEquals(1581, devicesWithDetails.size());
        Assert.assertEquals(1581, devices.size());
        Assert.assertEquals(331, devicesWithFaults.size());

        System.out.println("Devices with details: " + devicesWithDetails.size());
        System.out.println("Devices with faults: " + devicesWithFaults.size());
        System.out.println("Devices in general: " + devices.size());

        //Assert.assertEquals("172.31.254.129", devices.get(0).getProperties().get("ipAddress"));
        //Assert.assertEquals(19, ((AdvancedControllableProperty.DropDown)devices.get(0).getControllableProperties().get(1).getType()).getOptions().length);
        //Assert.assertEquals("123|ERR:Unable to get information\n456|ERR2:Unable to fetch information\n789|FWER:Firmware error\n", devices.get(0).getProperties().get("deviceFaults"));
        Assert.assertEquals("Rebooting", devices.get(0).getProperties().get("occupiedStatus"));
    }

}
