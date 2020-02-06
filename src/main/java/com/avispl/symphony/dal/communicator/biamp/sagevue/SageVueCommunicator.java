package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.communicator.biamp.sagevue.Constants.*;

public class SageVueCommunicator extends RestCommunicator implements Aggregator, Controller {

    private String loginId;
    private ObjectMapper objectMapper;
    private Map<String, String> faultMessagingStatus = new HashMap<>();

    public SageVueCommunicator() {
        super();
        setTrustAllCertificates(true);
        objectMapper = new ObjectMapper();
    }

    public String getLoginId() {
        return loginId;
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        //Object value = controllableProperty.getValue();
        String deviceId = controllableProperty.getDeviceId();

        switch (property){
            case "Reboot Tesira":
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
        refreshFaultMessagingStatus();
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
        JsonNode authentication = objectMapper.readTree(doPost(BASE_URL+"login", buildAuthenticationPayload(), String.class));
        loginId = authentication.get("LoginId").asText();
    }

    private Map<String, Map> buildAuthenticationPayload(){
        Map<String, Map> authenticationBody = new HashMap();
        Map<String, String> credentials = new HashMap();
        credentials.put("userName", this.getLogin());
        credentials.put("password", this.getPassword());
        credentials.put("rememberMe", "false");
        authenticationBody.put("credentials", credentials);
        return authenticationBody;
    }

    private List<AggregatedDevice> fetchDevicesList() throws Exception {
        List<AggregatedDevice> aggregatedDevices = new ArrayList<>();
        JsonNode devices = getDevices();

        devices.fields().forEachRemaining(stringJsonNodeEntry -> {
            if(stringJsonNodeEntry.getKey().endsWith("Devices")){
                stringJsonNodeEntry.getValue().iterator().forEachRemaining(jsonNode -> {
                    aggregatedDevices.add(createAggregatedDevice(jsonNode));
                });
            }
        });
        return aggregatedDevices;
    }

    private AggregatedDevice createAggregatedDevice(JsonNode deviceInfo){
        AggregatedDevice aggregatedDevice = new AggregatedDevice();
        aggregatedDevice.setDeviceId(deviceInfo.get(SYSTEM_ID).asText());
        aggregatedDevice.setDeviceModel(String.format("%s %s", deviceInfo.get(MODEL).asText(), deviceInfo.get(MODEL_DESCRIPTION).asText()));
        aggregatedDevice.setSerialNumber(deviceInfo.get(SERIAL_NUMBER).asText());

        Map<String, String> extendedStatistics = new HashMap<>();

        boolean isControlled = deviceInfo.findValue("IsControlled").booleanValue();
        if(isControlled) {
            Map<String, String> controls = new HashMap<>();
            extendedStatistics.put("Reboot Tesira", "");
            controls.put("Reboot Tesira", "Push");
            aggregatedDevice.setControl(controls);
        }

        extendedStatistics.put("Is Controlled", String.valueOf(isControlled));
        extendedStatistics.put("Firmware Version", deviceInfo.findPath("FirmwareVersion").asText());
        extendedStatistics.put("Is Protected", deviceInfo.findPath("IsProtected").asText());
        extendedStatistics.putAll(faultMessagingStatus);

        aggregatedDevice.setStatistics(extendedStatistics);

        if(deviceInfo.findPath("Status").intValue() == 0) {
            aggregatedDevice.setDeviceOnline(true);
        }
        aggregatedDevice.setTimestamp(System.currentTimeMillis());

        return aggregatedDevice;
    }

    private void rebootTesira(String deviceSerialNumber) throws Exception {
        doPut(BASE_URL + "Devices" + deviceSerialNumber + "/Reboot", buildAuthenticationPayload(), String.class);
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

//    private void disableFaultMessaging(){
//        Map<String, String> notifications = new HashMap<>();
//
//        notifications.put("turnOffNotifications", "");
//        notifications.put("turnOffEmails", "");
//        notifications.put("turnOffSMS", "");
//    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.set("Content-Type", "application/json");
        headers.set("SessionID", loginId);
        return headers;
    }
}
