/*
 * Copyright (c) 2020-2024 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.biamp.sagevue;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.CommandFailureException;
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

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.Collections.*;

/**
 * Communicator based on SageVue REST API
 * It aggregates devices based on /resources/sagevue/model-mapping.yml and populates them as
 * aggregated devices. For models that are defined explicitly (FORTE_VT), specific mapping is used.
 * For the rest - basic mapping with generic information (Serial number, firmware version, online status etc.)
 *
 * @author Maksym.Rossiitsev / Symphony Dev Team<br>
 * Created on May 7, 2020
 * @since 1.0
 */
public class SageVueCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {
    private String loginId;
    private ObjectMapper objectMapper;
    /**
     * List of protected devices within an aggregator to use during control operations
     */
    private List<String> protectedDevices = new ArrayList<>();
    /**
     * Container for the firmware versions selected for an update each device in aggregator.
     */
    private Map<String, String> devicesFirmwareVersions = new HashMap<>();
    /**
     * Container for "deviceId:deviceModel" pairs to use the correct API endpoint during control actions
     */
    private Map<String, String> deviceModels = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * device properties processor for json data extraction, using yml mapping
     * */
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    /**
     * Adapter metadata, collected from the version.properties
     */
    private Properties adapterProperties;
    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

    private static final String BASE_URL = "/biampsagevue/api/";

    /**
     * Setting ignoring certificates to allow all https connections.
     * Instantiating ObjectMapper to deserialize response payloads
     */
    public SageVueCommunicator() throws IOException {
        super();
        setTrustAllCertificates(true);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
        objectMapper = new ObjectMapper();
    }

    /***
     * Initializes AggregatedDeviceProcessor for extracting AggregatedDevice instances out of the
     * devices list, based on model-mapping.yml mapping
     */
    @Override
    protected void internalInit() throws Exception {
        adapterInitializationTimestamp = System.currentTimeMillis();
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("sagevue/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        super.internalInit();
    }

    /**
     * Processes control actions for both SageVue systems and SageVue devices.
     * When the system control is activated - systemId is not present
     * within a ControllableProperty instance, since this is a "native" controllable property.
     * So instead, the systemId is being extracted from the controllable property name.
     * <p>
     * Device controllable properties:
     * Reboot - requests a device reboot
     * FirmwareUpdate - requests a device firmware update
     * AvailableFirmwareVersions - this is a dropdown, containing all the options for the firmware update
     * of a particular device model. When the control is triggered for this one - the selected firmware version
     * is put into the map, containing "serialNumber:firmwareVersion" pairs.
     */
    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        lock.lock();
        try {

            String property = controllableProperty.getProperty();
            String deviceId = controllableProperty.getDeviceId();
            String value = String.valueOf(controllableProperty.getValue());
            String modelName = deviceModels.get(deviceId);

            if (property.startsWith("System")) {
                String systemId = property.replaceAll("[^\\d.]", "");
                switch (value) {
                    case "1":
                        protectSystem(systemId);
                        break;
                    case "0":
                        unprotectSystem(systemId);
                        break;
                    default:
                        break;
                }
            } else {
                switch (property) {
                    case "Reboot":
                        reboot(deviceId, modelName);
                        break;
                    case "FirmwareUpdate":
                        String newFirmwareVersion = devicesFirmwareVersions.get(deviceId);
                        if (StringUtils.isEmpty(newFirmwareVersion)) {
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
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controllablePropertyList) {
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

    /**
     * Since the SageVue api does not only allow to control devices on a per-device basis, but also
     * is able to group devices within certain "Systems" - we need to have an ability to protect/unprotect those systems.
     * And, since the system is rather a SageVue property, rather the device property - the controls for them are
     * put onto the aggregator level. This way, certain actions, meant to change specific device's properties and/or
     * characteristics are present within AggregatedDevice instances, that are populated by the
     * retrieveMultipleStatistics() method.
     *
     * @return List<Statistics> that contains controlled properties (toggles) for SageVue systems protect/unprotect actions.
     */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();
        List<AdvancedControllableProperty> controls = new ArrayList<>();
        Map<String, String> multipleStatistics = new HashMap<>();
        ArrayNode systems = getSystems(false).withArray("Systems");

        systems.forEach(jsonNode -> {
            String systemId = jsonNode.at("/SystemId").asText();
            boolean isProtected = jsonNode.at("/IsProtected").asBoolean();

            AdvancedControllableProperty.Switch protectSystemSwitch = new AdvancedControllableProperty.Switch();
            protectSystemSwitch.setLabelOff("Unprotect");
            protectSystemSwitch.setLabelOn("Protect");

            AdvancedControllableProperty protectSystemControl =
                    new AdvancedControllableProperty("System " + systemId, new Date(), protectSystemSwitch, isProtected);

            multipleStatistics.put("System " + systemId, String.valueOf(isProtected));
            controls.add(protectSystemControl);
        });

        multipleStatistics.put("AdapterVersion", adapterProperties.getProperty("aggregator.version"));
        multipleStatistics.put("AdapterBuildDate", adapterProperties.getProperty("aggregator.build.date"));
        multipleStatistics.put("AdapterUptime", normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        statistics.setStatistics(multipleStatistics);
        statistics.setControllableProperties(controls);
        return singletonList(statistics);
    }

    /**
     * Generate LoginId token for SageVue API based on the user credentials
     */
    @Override
    protected void authenticate() throws Exception {
        JsonNode authentication = objectMapper.readTree(doPost(BASE_URL + "login", buildAuthenticationPayload(true), String.class));
        loginId = authentication.at("/LoginId").asText();
    }

    /**
     * Build authentication payload either with or without username/password.
     * This is specifically required by the SageVue API, so when the certain actions
     * do not require the authentication payload sent - they still require the payload to be present.
     *
     * @param populateCredentials whether or not the authentication credentials has to be used
     * @return Map that contains user credentials - username and password
     */
    private Map<String, Map<String, String>> buildAuthenticationPayload(boolean populateCredentials) {
        Map<String, Map<String, String>> authenticationBody = new HashMap<>();
        Map<String, String> credentials = new HashMap<>();

        if (populateCredentials) {
            credentials.put("userName", this.getLogin());
            credentials.put("password", this.getPassword());
        } else {
            credentials.put("userName", "");
            credentials.put("password", "");
        }
        authenticationBody.put("credentials", credentials);
        return authenticationBody;
    }

    /**
     * Protect SageVue system
     *
     * @param systemId id of the system to request a protect action
     * @return boolean value based on the operation success
     */
    private boolean protectSystem(String systemId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + systemId + "/protect", buildNewAdminPasswordPayload(systemId), String.class);
        return objectMapper.readTree(response).at("/Protected").booleanValue();
    }

    /**
     * Unprotect SageVue system
     *
     * @param systemId id of the system to request an unprotect action
     * @return boolean value based on the operation success
     */
    private boolean unprotectSystem(String systemId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + systemId + "/unprotect", buildExistingNewAdminPasswordPayload(systemId), String.class);
        return objectMapper.readTree(response).at("/Unprotected").booleanValue();
    }

    /**
     * When the SageVue system is being protected - it requires new admin password to be generated.
     *
     * @param password new password to use for the SageVue system protect action
     * @return Map containing the payload needed for the SageVue system protect action
     */
    private Map<String, Map<String, String>> buildNewAdminPasswordPayload(String password) {
        Map<String, String> passwordBody = new HashMap<>();
        passwordBody.put("newAdminPassword", password);

        Map<String, Map<String, String>> payload = new HashMap<>();
        payload.put("password", passwordBody);

        return payload;
    }

    /**
     * In order to apply certain actions to a system after it's been protected - the previously set
     * password has to be used.
     *
     * @param password a password to use for the SageVue system unprotect action
     * @return Map containing the payload needed for the SageVue system unprotect action
     */
    private Map<String, Map<String, String>> buildExistingNewAdminPasswordPayload(String password) {
        Map<String, String> passwordBody = new HashMap<>();
        passwordBody.put("existingAdminPassword", password);

        Map<String, Map<String, String>> payload = new HashMap<>();
        payload.put("password", passwordBody);

        return payload;
    }

    /**
     * Generates a payload for building the firmware update request.
     * SageVue specific also requires having username/password specified at all times,
     * even if the device is not protected.
     *
     * @param deviceSerialNumber serial number of the device to build a firmware update request for
     * @param firmwareVersion    firmware version to use
     * @return Map<String, Object> the request payload, containing the device serial number, firmware version
     * to use and username/password
     */
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

    /**
     * Fetch list of devices, handled by SageVue.
     *
     * @return List<AggregatedDevice> list of AggregatedDevice instances, extracted from the json,
     * provided by SageVue API
     */
    private List<AggregatedDevice> fetchDevicesList() throws Exception {
        List<AggregatedDevice> devices = new ArrayList<>();
        lock.lock();
        try {

            deviceModels.clear();
            JsonNode devicesJson = getDevices(false);
            devices.addAll(aggregatedDeviceProcessor.extractDevices(devicesJson));

            protectedDevices.clear();
            devices.forEach(aggregatedDevice -> {
                if (Boolean.parseBoolean(aggregatedDevice.getProperties().get("isProtected"))) {
                    protectedDevices.add(aggregatedDevice.getSerialNumber());
                }
            });
        } finally {
            lock.unlock();
        }

        return devices;
    }

    /**
     * Reboot a device based on it's serial number.
     * Requires a device model to be specified to call a specific endpoint based on that.
     * So, if the device is a "Tesira" device - an endpoint will look like /Devices/{serialNumber}/Reboot (default),
     * but if the device is a "Nexia" device, for instance - it has to be reflected in the url:
     * /devices/nexia/{serialNumber}/Reboot
     *
     * @param deviceSerialNumber device serial number
     * @param deviceModel        device model to set a proper endpoint
     */
    private void reboot(String deviceSerialNumber, String deviceModel) throws Exception {
        doPut(BASE_URL + "Devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceSerialNumber + "/Reboot",
                buildAuthenticationPayload(protectedDevices.contains(deviceSerialNumber)), String.class);
    }

    private String retrieveDeviceUrlSegment(String deviceModel) {
        if (deviceModel.toLowerCase().equals("tesira")) {
            return "";
        } else {
            return deviceModel + "/";
        }
    }

    /**
     * Fetch a /devices endpoint to retrieve list of all devices.
     *
     * @param reAuthenticated true if this is a second attempt of getting the devices data, with the {@link #authenticate()}
     *                        method issued
     * @return JsonNode instance containing an array of the devices
     */
    private JsonNode getDevices(boolean reAuthenticated) throws Exception {
        JsonNode devices;
        try {
            String devicesResponse = doGet(BASE_URL + "devices", String.class);
            devices = objectMapper.readTree(devicesResponse);
        } catch (FailedLoginException | CommandFailureException fle) {
            if (reAuthenticated) {
                throw new FailedLoginException("Failed to get list of devices using SessionID: " + loginId);
            }
            authenticate();
            return getDevices(true);
        }

        devices.fieldNames().forEachRemaining(s -> {
            if (s.endsWith("Devices")) {
                String modelName = s.replaceAll("Devices", "");
                devices.get(s).forEach(jsonNode -> {
                    String deviceSerialNumber = jsonNode.at("/SerialNumber").asText();
                    JsonNode device = getDevice(deviceSerialNumber, modelName);
                    ArrayNode firmwareVersionsResponse = getFirmwareVersions(modelName);

                    deviceModels.put(deviceSerialNumber, modelName);
                    if (device != null) {
                        ((ObjectNode) jsonNode).put("IpAddress", device.at("/IpAddress").asText());
                    }

                    Set<String> firmwareVersions = new HashSet<>();
                    firmwareVersions.add(jsonNode.at("/FirmwareVersion").asText());

                    firmwareVersionsResponse.forEach(firmwareVersion -> firmwareVersions.add(firmwareVersion.at("/Version").asText()));
                    ((ObjectNode) jsonNode).put("AvailableFirmwareVersions", String.join(",", firmwareVersions));

                    ArrayNode deviceFaults = jsonNode.withArray("Faults");
                    if (deviceFaults.size() > 0) {
                        StringBuilder faultsStringBuilder = new StringBuilder();
                        deviceFaults.forEach(fault -> {
                            faultsStringBuilder.append(fault.at("/FaultId").asText()).append("|")
                                    .append(fault.at("/IndicatorId").asText()).append(":").append(fault.at("/Message").asText()).append("\n");
                        });
                        ((ObjectNode) jsonNode).put("Faults", faultsStringBuilder.toString());
                    }
                });
            }
        });
        return devices;
    }

    /**
     * Get information about a particular device based on it's deviceId and deviceModel
     *
     * @param deviceId    id of the device
     * @param deviceModel
     * @return JsonNode instance that represents the device
     */
    private JsonNode getDevice(String deviceId, String deviceModel) {
        JsonNode device = null;
        try {
            String deviceResponse = doGet(BASE_URL + "devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceId, String.class);
            device = objectMapper.readTree(deviceResponse).at("/Device");
        } catch (Exception e) {
            logger.error("Unable to find a device with id " + deviceId);
        }
        return device;
    }

    /**
     * Get information about the firmware versions available for a certain device model
     *
     * @param deviceModel a model of the device upon which to check for available firmware update options
     * @return ArrayNode instance containing the array of available firmware update options
     */
    private ArrayNode getFirmwareVersions(String deviceModel) {
        ArrayNode versions = JsonNodeFactory.instance.arrayNode();
        try {
            String firmwareUpdateVersions = doGet(BASE_URL + "firmware/" + retrieveDeviceUrlSegment(deviceModel), String.class);
            versions = objectMapper.readTree(firmwareUpdateVersions).withArray("FirmwareUpdates");
        } catch (Exception e) {
            logger.error("Unable to find a firmware versions for model: " + deviceModel);
        }
        return versions;
    }

    /**
     * Get SageVue systems
     *
     * @param reAuthenticated true if this is a second attempt of getting the devices data, with the {@link #authenticate()}
     *                        method issued
     * @return JsonNode instance containing list of SageVue systems
     */
    private JsonNode getSystems(boolean reAuthenticated) throws Exception {
        try {
            String devicesResponse = doGet(BASE_URL + "systems", String.class);
            return objectMapper.readTree(devicesResponse);
        } catch (FailedLoginException | CommandFailureException fle) {
            if (reAuthenticated) {
                throw new FailedLoginException("Failed to get list of systems using SessionID: " + loginId);
            }
            authenticate();
            return getSystems(true);
        }
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.set("Content-Type", "application/json");
        headers.set("SessionID", loginId);
        return headers;
    }

    /**
     * Create a request for a SageVue device firmware update.
     *
     * @param deviceSerialNumber a serial number of the device for which to request a firmware update action
     * @param firmwareVersion    firmware version that should be used
     * @param deviceModel        to create a correct request url: tesira devices require using a default url: /firmware/
     *                           whereas other devices request model to be specified explicitly: /firmware/{deviceModel}
     */
    private void requestFirmwareUpdate(String deviceSerialNumber, String firmwareVersion, String deviceModel) throws Exception {
        devicesFirmwareVersions.remove(deviceSerialNumber);
        String response = doPut(BASE_URL + "Firmware/" + retrieveDeviceUrlSegment(deviceModel), buildFirmwareUpdateRequest(deviceSerialNumber, firmwareVersion), String.class);
        if (logger.isDebugEnabled()) {
            logger.trace("SageVue: Firmware update result: " + response + " for device " + deviceModel + deviceSerialNumber);
        }
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }
}
