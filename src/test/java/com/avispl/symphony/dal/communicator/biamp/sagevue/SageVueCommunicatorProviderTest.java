package com.avispl.symphony.dal.communicator.biamp.sagevue;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.provider.junit.Provider;
import au.com.dius.pact.provider.junit.loader.PactFolder;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.HttpsTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.http.HttpRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@Provider("biamp-sagevue")
@PactFolder("pacts/")
@Tag("localtest")
public class SageVueCommunicatorProviderTest {
    private static final int SAGEVUE_PORT = 80;
    private static final String SAGEVUE_IP_ADDRESS = "172.31.254.17";
    private static final String LOCAL_IP_ADDRESS = "127.0.0.1";
    private static final String SAGEVUE_TEST_USERNAME = "Admin";
    private static final String SAGEVUE_TEST_PASSWORD = "1234";

    private static WireMockServer wireMockServer;

    @BeforeAll
    public static void setup(){
        wireMockServer = new WireMockServer(options().dynamicPort().bindAddress(LOCAL_IP_ADDRESS));
        wireMockServer.start();
    }

    @BeforeEach
    public void setTarget(PactVerificationContext context) {
        HttpTestTarget target = new HttpTestTarget(SAGEVUE_IP_ADDRESS, SAGEVUE_PORT, "/");
        context.setTarget(target);
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void testTemplate(Pact pact, Interaction interaction, HttpRequest request, PactVerificationContext context) {
        context.verifyInteraction();
    }
}
