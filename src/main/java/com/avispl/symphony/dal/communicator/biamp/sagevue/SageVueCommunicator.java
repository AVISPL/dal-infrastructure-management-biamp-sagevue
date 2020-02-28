package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.communicator.biamp.sagevue.Constants.*;
import static java.util.Collections.emptyMap;

public class SageVueCommunicator extends RestCommunicator implements Aggregator, Controller {

    private String loginId;
    private ObjectMapper objectMapper;
    private Map<String, String> faultMessagingStatus = emptyMap();
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    private boolean deviceIsProtected = false;

    public SageVueCommunicator() {
        super();
        setTrustAllCertificates(true);
        objectMapper = new ObjectMapper();
    }

    public String getLoginId() {
        return loginId;
    }

    @Override
    protected void internalInit() throws Exception {
        super.internalInit();
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("model-mapping.yml");
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String deviceId = controllableProperty.getDeviceId();

        logger.debug("Control operation " + property + " Is called.");
        switch (property){
            case "RebootTesira":
                rebootTesira(deviceId);
                break;
            default:
                logger.warn("Control operation " + property + " is not supported yet. Skipping.");
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: controllablePropertyList){
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        return fetchDevicesList();
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
                .collect(Collectors.toList());
    }

    @Override
    protected void authenticate() throws Exception {
        JsonNode authentication = objectMapper.readTree(doPost(BASE_URL+"login", buildAuthenticationPayload(true), String.class));
        loginId = authentication.get("LoginId").asText();
    }

    private Map<String, Map<String, String>> buildAuthenticationPayload(boolean populateCredentials){
        Map<String, Map<String, String>> authenticationBody = new HashMap<>();
        Map<String, String> credentials = new HashMap<>();

        if(populateCredentials){
            credentials.put("userName", this.getLogin());
            credentials.put("password", this.getPassword());
        } else {
            credentials.put("userName", "");
            credentials.put("password", "");
        }
        authenticationBody.put("credentials", credentials);
        return authenticationBody;
    }

    private List<AggregatedDevice> fetchDevicesList() throws Exception {
        return aggregatedDeviceProcessor.extractDevices(getDevices());
    }

    private void rebootTesira(String deviceSerialNumber) throws Exception {
        logger.debug("Tesira Reboot is requested for " + deviceSerialNumber);
        String response = doPut(BASE_URL + "Devices/" + deviceSerialNumber + "/Reboot", buildAuthenticationPayload(deviceIsProtected), String.class);
        logger.debug("Tesira Reboot is resulted with response " + response);
    }

    public JsonNode getDevices() throws Exception {
        authenticate();
        String devicesResponse = doGet(BASE_URL + "devices", String.class);
        return objectMapper.readTree(devicesResponse);
    }

    public void refreshFaultMessagingStatus() throws Exception {
        authenticate();
        JsonNode faultMessagingResponse = objectMapper.readTree(doGet(BASE_URL + "FaultProfile/GetFaultMessaging", String.class));
        faultMessagingResponse.fields().forEachRemaining(stringJsonNodeEntry -> {
            faultMessagingStatus.put(stringJsonNodeEntry.getKey(), stringJsonNodeEntry.getValue().asText());
        });
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.set("Content-Type", "application/json");
        headers.set("SessionID", loginId);
        return headers;
    }

    /**
     * http://172.31.254.17/biampsagevue/api/devices/03275657/unprotect
     * {
     *     "password": {
     *         "existingAdminPassword": "1234"
     *     }
     * }
     *
     * /api/System/Devices/Secure/{id}
     */

    /*
    * /api/System/Devices/Secure/{id}
    * */
//    private boolean secureTesiraDevice(String deviceId) throws Exception {
//        String response = doPost(BASE_URL + "System/Devices/Secure" + deviceId, buildAuthenticationPayload(), String.class);
//        return objectMapper.readTree(response).findValue("IsControlled").booleanValue();
//    }

    /*
    * /api/Devices/Security/Release
    *
    * Id -> param -> query string??
    * */
//    private boolean releaseTesiraDevice(String deviceId) throws Exception {
//        String response = doGet(BASE_URL + "Devices/Security/Release?id=" + deviceId, String.class);
//        return objectMapper.readTree(response).findValue("IsControlled").booleanValue();
//    }
}
