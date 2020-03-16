package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.*;

public class SageVueCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {

    private String loginId;
    private ObjectMapper objectMapper;
//    private Map<String, String> faultMessagingStatus = new HashMap<>();
    private List<String> protectedSystems = new ArrayList<>();
    private List<String> protectedDevices = new ArrayList<>();
    private Map<String, String> baseControls = new HashMap<>();
    private Map<String, String> devicesFirmwareVersions = new HashMap<>();
    private Map<String, String> deviceModels = new HashMap<>();

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    public static String BASE_URL = "/biampsagevue/api/";

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
        String value = String.valueOf(controllableProperty.getValue());
        String modelName = deviceModels.get(deviceId);

        if(property.startsWith("Protect System")) {
            String systemId = property.replaceAll("[^\\d.]", "");
            switch (value) {
                case "1":
                    protectSystem(systemId);
                    baseControls.put(property, "true");
                    break;
                case "0":
                    unprotectSystem(systemId);
                    baseControls.put(property, "false");
                    break;
                default:
                    break;
            }
        } else {
            switch (property){
                case "Reboot":
                    reboot(deviceId, modelName);
                    break;
                case "FirmwareUpdate":
                    String newFirmwareVersion = devicesFirmwareVersions.get(deviceId);
                    if(StringUtils.isEmpty(newFirmwareVersion)){
                        return;
                    }
                    requestFirmwareUpdate(deviceId, newFirmwareVersion, modelName);
                    break;
                case "AvailableFirmwareVersions":
                    devicesFirmwareVersions.put(deviceId, value);
                    break;
                default:
                    logger.warn("Control operation " + property + " is not supported yet. Skipping.");
                    break;
            }
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
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();
        Map<String, String> controls = new HashMap<>();
        Map<String, String> multipleStatistics = new HashMap<>();
        ArrayNode systems = (ArrayNode) getSystems().withArray("Systems");

        protectedSystems.clear();
        systems.forEach(jsonNode -> {
            String systemId = jsonNode.get("SystemId").asText();
            boolean isProtected = jsonNode.get("IsProtected").asBoolean();

            if(baseControls.isEmpty()){
                baseControls.put("Protect System " + systemId, String.valueOf(isProtected));
            }
            multipleStatistics.putAll(baseControls);
            controls.put("Protect System " + systemId, "Toggle");
            if(isProtected){
                protectedSystems.add(systemId);
            }
        });

        statistics.setStatistics(multipleStatistics);
        statistics.setControl(controls);
        return singletonList(statistics);
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

    private Map<String, Map<String, String>> buildNewAdminPasswordPayload(String password){
        Map<String, String> passwordBody = new HashMap<>();
        passwordBody.put("newAdminPassword", password);

        Map<String, Map<String, String>> payload = new HashMap<>();
        payload.put("password", passwordBody);

        return payload;
    }

    private Map<String, Map<String, String>> buildExistingNewAdminPasswordPayload(String password){
        Map<String, String> passwordBody = new HashMap<>();
        passwordBody.put("existingAdminPassword", password);

        Map<String, Map<String, String>> payload = new HashMap<>();
        payload.put("password", passwordBody);

        return payload;
    }

    private Map<String, Object> buildFirmwareUpdateRequest(String deviceSerialNumber, String firmwareVersion) {
        Map<String, Object> firmwareUpdatePayload = new HashMap<>();
        Map<String, Object> devices = new HashMap<>();
        Map<String, String> deviceUpdatePayload = new HashMap<>();

        boolean deviceIsProtected = protectedDevices.contains(deviceSerialNumber);
        deviceUpdatePayload.put("deviceSerialNumber", deviceSerialNumber);
        deviceUpdatePayload.put("userName", deviceIsProtected ? this.getLogin() : "");
        deviceUpdatePayload.put("password", deviceIsProtected ? this.getPassword() : "");

        devices.put("devices", Collections.singletonList(deviceUpdatePayload));
        devices.put("firmwareVersion", firmwareVersion);

        firmwareUpdatePayload.put("firmwareUpdate", devices);

        return firmwareUpdatePayload;
    }

    private List<AggregatedDevice> fetchDevicesList() throws Exception {
        deviceModels.clear();
        List<AggregatedDevice> devices = aggregatedDeviceProcessor.extractDevices(getDevices());

        protectedDevices.clear();
        devices.forEach(aggregatedDevice -> {
            if(Boolean.parseBoolean(aggregatedDevice.getProperties().get("isProtected"))){
                protectedDevices.add(aggregatedDevice.getSerialNumber());
            }
        });

        return devices;
    }

    private void reboot(String deviceSerialNumber, String deviceModel) throws Exception {
        doPut(BASE_URL + "Devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceSerialNumber + "/Reboot",
                buildAuthenticationPayload(protectedSystems.contains(deviceSerialNumber)), String.class);
    }

    public JsonNode getDevices() throws Exception {
        authenticate();
        String devicesResponse = doGet(BASE_URL + "devices", String.class);
        JsonNode devices = objectMapper.readTree(devicesResponse);
        devices.fieldNames().forEachRemaining(s -> {
            if(s.endsWith("Devices")){
                String modelName = s.replaceAll("Devices", "");
                devices.get(s).forEach(jsonNode -> {
                    String deviceSerialNumber = jsonNode.findValue("SerialNumber").asText();
                    JsonNode device = getDevice(deviceSerialNumber, modelName);
                    ArrayNode firmwareVersionsResponse = getFirmwareVersions(modelName);

                    deviceModels.put(deviceSerialNumber, modelName);
                    if (device != null) {
                        ((ObjectNode) jsonNode).put("IpAddress", device.findValue("IpAddress").asText());
                    }
                    List<String> firmwareVersions = new ArrayList<>();
                    firmwareVersions.add(jsonNode.findValue("FirmwareVersion").asText());

                    firmwareVersionsResponse.forEach(firmwareVersion -> firmwareVersions.add(firmwareVersion.get("Version").asText()));
                    ((ObjectNode) jsonNode).put("AvailableFirmwareVersions", String.join(",", firmwareVersions));
                });
            }
        });
        return devices;
    }

    private String retrieveDeviceUrlSegment(String deviceModel){
        if(deviceModel.toLowerCase().equals("tesira")){
            return "";
        } else {
            return deviceModel + "/";
        }
    }

    private JsonNode getDevice(String deviceId, String deviceModel) {
        JsonNode device = null;
        try {
            authenticate();
            String deviceResponse = doGet(BASE_URL + "devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceId, String.class);
            device = objectMapper.readTree(deviceResponse).get("Device");
        } catch (Exception e) {
            logger.error("Unable to find a device with id " + deviceId);
        }
        return device;
    }

    private ArrayNode getFirmwareVersions(String deviceModel) {
        ArrayNode versions = JsonNodeFactory.instance.arrayNode();
        try {
            authenticate();
            String firmwareUpdateVersions = doGet(BASE_URL + "firmware/" + retrieveDeviceUrlSegment(deviceModel), String.class);
            versions = (ArrayNode) objectMapper.readTree(firmwareUpdateVersions).withArray("FirmwareUpdates");
        } catch (Exception e) {
            logger.error("Unable to find a firmware versions for model: " + deviceModel);
        }
        return versions;
    }

    public JsonNode getSystems() throws Exception {
        authenticate();
        String devicesResponse = doGet(BASE_URL + "systems", String.class);
        return objectMapper.readTree(devicesResponse);
    }

//    public void refreshFaultMessagingStatus() throws Exception {
//        authenticate();
//        JsonNode faultMessagingResponse = objectMapper.readTree(doGet(BASE_URL + "FaultProfile/GetFaultMessaging", String.class));
//        faultMessagingResponse.fields().forEachRemaining(stringJsonNodeEntry -> {
//            faultMessagingStatus.put(stringJsonNodeEntry.getKey(), stringJsonNodeEntry.getValue().asText());
//        });
//    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.set("Content-Type", "application/json");
        headers.set("SessionID", loginId);
        return headers;
    }

    private void requestFirmwareUpdate(String deviceSerialNumber, String firmwareVersion, String deviceModel) throws Exception {
        String reponse = doPut(BASE_URL + "Firmware/" + retrieveDeviceUrlSegment(deviceModel), buildFirmwareUpdateRequest(deviceSerialNumber, firmwareVersion), String.class);
        if(logger.isTraceEnabled()) {
            logger.trace("SageVue: Firmware update result: " + reponse + " for device " + deviceModel + deviceSerialNumber);
        }
    }

    private boolean protectSystem(String deviceId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + deviceId + "/protect", buildNewAdminPasswordPayload(deviceId), String.class);
        protectedSystems.add(deviceId);
        return objectMapper.readTree(response).findValue("Protected").booleanValue();
    }

    /**
     * http://172.31.254.17/biampsagevue/api/system/03275657/unprotect
     * {
     *     "password": {
     *         "existingAdminPassword": "1234"
     *     }
     * }
     *
     * /api/System/Devices/Secure/{id}
     */
    private boolean unprotectSystem(String deviceId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + deviceId + "/unprotect", buildExistingNewAdminPasswordPayload(deviceId), String.class);
        protectedSystems.remove(deviceId);
        return objectMapper.readTree(response).findValue("Unprotected").booleanValue();
    }
}
