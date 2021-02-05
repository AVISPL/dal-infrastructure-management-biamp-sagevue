/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.Collections.*;

public class SageVueCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {

    class SageVueDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public SageVueDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
           mainloop: while(inProgress) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if(!inProgress){
                    break mainloop;
                }

               // next line will determine whether XiO monitoring was paused
               updateAggregatorStatus();
               if (devicePaused) {
                   if (logger.isDebugEnabled()) {
                       logger.debug(String.format(
                               "Device adapter did not receive retrieveMultipleStatistics call in %s s. Statistics retrieval and device metadata retrieval is suspended.",
                               retrieveStatisticsTimeOut / 1000));
                   }
                   continue mainloop;
               }

                try {
                    if(logger.isDebugEnabled()) {
                        logger.debug("SageVue: fetching devices list");
                    }
                    fetchDevicesList();
                    if(logger.isDebugEnabled()) {
                        logger.debug("SageVue: fetched devices list: " + aggregatedDevices);
                    }
                } catch (Exception e) {
                    logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage());
                }

                if(!inProgress){
                    break mainloop;
                }

                int aggregatedDevicesCount = aggregatedDevices.size();
                if(aggregatedDevicesCount == 0 || nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        //
                    }
                    continue mainloop;
                }

                List<String> scannedDevicesSerialNumberList = aggregatedDevices.values().stream().map(AggregatedDevice::getSerialNumber).collect(Collectors.toList());

                for(String serialNumber : scannedDevicesSerialNumberList){
                    if(!inProgress){
                        break;
                    }
                    devicesExecutionPool.add(executorService.submit(() -> fetchDeviceDetails(serialNumber)));
                }

                do {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e){
                        if(!inProgress){
                            break;
                        }
                    }
                    devicesExecutionPool.removeIf(Future::isDone);
                } while (!devicesExecutionPool.isEmpty());

                // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                // launches devices detailed statistics collection
               nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

               if (logger.isDebugEnabled()) {
                   logger.debug("Finished collecting devices statistics cycle at " + new Date());
               }
            }

            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            inProgress = false;
        }
    }
    private String loginId;
    private ObjectMapper objectMapper;
    /**
     * If the {@link SageVueCommunicator#deviceMetaDataInformationRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetaDataTimeout = 2 * 60 * 1000;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataInformationRetrievalTimeout = 5 * 60 * 1000;
    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * If ignored if device list is not yet retrieved or the cached device list is empty {@link SageVueCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Aggregator inactivity timeout. If the {@link SageVueCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private static long nextDevicesCollectionIterationTimestamp;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link SageVueCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;


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

    private Map<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    private static final String BASE_URL = "biampsagevue/api/";

    private static ExecutorService executorService;
    private List<Future> devicesExecutionPool = new ArrayList<>();
    private SageVueDeviceDataLoader deviceDataLoader;

    public SageVueCommunicator() {
        super();
        setTrustAllCertificates(true);
        objectMapper = new ObjectMapper();
    }

    /***
     * {@inheritDoc}
     * Initializes AggregatedDeviceProcessor for extracting AggregatedDevice instances out of the
     * devices list, based on model-mapping.yml mapping
     */
    @Override
    protected void internalInit() throws Exception {
        super.internalInit();
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("sagevue/model-mapping.yml", getClass());
        logger.debug("YML mapping: " + mapping);
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        executorService = Executors.newFixedThreadPool(8);
        executorService.submit(deviceDataLoader = new SageVueDeviceDataLoader());
        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
    }

    @Override
    protected void internalDestroy() {
        if (deviceDataLoader != null) {
            deviceDataLoader.stop();
            deviceDataLoader = null;
        }

        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }

        devicesExecutionPool.forEach(future -> future.cancel(true));
        devicesExecutionPool.clear();

        aggregatedDevices.clear();
        super.internalDestroy();
    }

    /**
     * {@inheritDoc
     * Processes control actions for both SageVue systems and SageVue devices.
     * When the system control is activated - systemId is not present
     * within a ControllableProperty instance, since this is a "native" controllable property.
     * So instead, the systemId is being extracted from the controllable property name.
     *
     * Device controllable properties:
     * Reboot - requests a device reboot
     * FirmwareUpdate - requests a device firmware update
     * AvailableFirmwareVersions - this is a dropdown, containing all the options for the firmware update
     * of a particular device model. When the control is triggered for this one - the selected firmware version
     * is put into the map, containing "serialNumber:firmwareVersion" pairs.
     *
     */
    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        lock.lock();
        try {
            String property = controllableProperty.getProperty();
            String deviceId = controllableProperty.getDeviceId();
            String value = String.valueOf(controllableProperty.getValue());
            String modelName = deviceModels.get(deviceId);

            if(property.startsWith("System")) {
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
        } finally {
            lock.unlock();
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
        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
//        return fetchDevicesList();
        updateValidRetrieveStatisticsTimestamp();
        aggregatedDevices.values().forEach(aggregatedDevice -> aggregatedDevice.setTimestamp(System.currentTimeMillis()));
        return new ArrayList<>(aggregatedDevices.values());
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
        ArrayNode systems = (ArrayNode) getSystems(false).withArray("Systems");

        systems.forEach(jsonNode -> {
            String systemId = jsonNode.get("SystemId").asText();
            boolean isProtected = jsonNode.get("IsProtected").asBoolean();

            AdvancedControllableProperty.Switch protectSystemSwitch = new AdvancedControllableProperty.Switch();
            protectSystemSwitch.setLabelOff("Unprotect");
            protectSystemSwitch.setLabelOn("Protect");

            AdvancedControllableProperty protectSystemControl =
                    new AdvancedControllableProperty("System " + systemId, new Date(), protectSystemSwitch, isProtected);

            multipleStatistics.put("System " + systemId, String.valueOf(isProtected));
            controls.add(protectSystemControl);
        });

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
        loginId = authentication.get("LoginId").asText();
    }

    /**
     * Build authentication payload either with or without username/password.
     * This is specifically required by the SageVue API, so when the certain actions
     * do not require the authentication payload sent - they still require the payload to be present.
     *
     * @param populateCredentials whether or not the authentication credentials has to be used
     * @return Map that contains user credentials - username and password
     */
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

    /**
     * Protect SageVue system
     *
     * @param systemId id of the system to request a protect action
     * @return boolean value based on the operation success
     */
    private boolean protectSystem(String systemId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + systemId + "/protect", buildNewAdminPasswordPayload(systemId), String.class);
        return objectMapper.readTree(response).findValue("Protected").booleanValue();
    }

    /**
     * Unprotect SageVue system
     *
     * @param systemId id of the system to request an unprotect action
     * @return boolean value based on the operation success
     */
    private boolean unprotectSystem(String systemId) throws Exception {
        String response = doPut(BASE_URL + "Systems/" + systemId + "/unprotect", buildExistingNewAdminPasswordPayload(systemId), String.class);
        return objectMapper.readTree(response).findValue("Unprotected").booleanValue();
    }

    /**
     * When the SageVue system is being protected - it requires new admin password to be generated.
     *
     * @param password new password to use for the SageVue system protect action
     * @return Map containing the payload needed for the SageVue system protect action
     */
    private Map<String, Map<String, String>> buildNewAdminPasswordPayload(String password){
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
    private Map<String, Map<String, String>> buildExistingNewAdminPasswordPayload(String password){
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
     * @param firmwareVersion firmware version to use
     *
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
     */
    private void fetchDevicesList() throws Exception {
        //TODO if should be synchronized?
        if (aggregatedDevices.size() > 0 && validDeviceMetaDataRetrievalPeriodTimestamp > System.currentTimeMillis()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                        (validDeviceMetaDataRetrievalPeriodTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }

        lock.lock();
        try {
            List<AggregatedDevice> devices = new ArrayList<>();

            deviceModels.clear();
           // JsonNode devicesJson = getDevices(false);
            JsonNode devicesJson = objectMapper.readTree("{   \"TesiraDevices\":[      {         \"Model\":\"FORTE_VT\",         \"ModelDescription\":\"TesiraFORTÉ AVB VT\",         \"SystemDescription\":\"TesiraFORTÉ AVB-VT - Default Configuration 03275657\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"4a7a071b-7331-4849-b842-516bae6f395d\",         \"IsControlled\":false,         \"SystemId\":\"03275657\",         \"SerialNumber\":\"03275657\",         \"HostName\":\"TesiraForte03275657\",         \"Description\":\"\",         \"IsProtected\":false,         \"Faults\":[            {               \"FaultId\":\"123\",               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":\"456\",               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":\"789\",               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":0,         \"Labels\":[                     ]      },      {         \"Model\":\"FORTE_VT\",         \"ModelDescription\":\"TesiraFORTÉ AVB VT Gilbert\",         \"SystemDescription\":\"TesiraFORTÉ AVB-VT - Default Configuration 5ffee89bad26b4e01ffcff56\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"a6c508e7-d3aa-4cd4-a740-057bd4e170ea\",         \"IsControlled\":false,         \"SystemId\":\"5ffee89bcd3a4a87aefa518e\",         \"SerialNumber\":\"5ffee89be665ba7214a57597\",         \"HostName\":\"TesiraForte5ffee89ba1efe4b62c288a3b\",         \"Description\":\"\",         \"IsProtected\":false,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":1,         \"Labels\":[                     ]      },      {         \"Model\":\"FORTE_VT\",         \"ModelDescription\":\"TesiraFORTÉ AVB VT Morales\",         \"SystemDescription\":\"TesiraFORTÉ AVB-VT - Default Configuration 5ffee89ba82db6e6d7263c95\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"dc5b6ac2-3c6a-4f5a-8618-63a25b348ee6\",         \"IsControlled\":false,         \"SystemId\":\"5ffee89b896d8a82524ff858\",         \"SerialNumber\":\"5ffee89b1a51f5271a7e0a12\",         \"HostName\":\"TesiraForte5ffee89bd3f37b11f7c0cd4d\",         \"Description\":\"\",         \"IsProtected\":false,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":1,         \"Labels\":[                     ]      },      {         \"Model\":\"FORTE_VT\",         \"ModelDescription\":\"TesiraFORTÉ AVB VT Shawn\",         \"SystemDescription\":\"TesiraFORTÉ AVB-VT - Default Configuration 5ffee89b8db015a1ca3824b1\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"3a3c6918-9552-4e2f-859c-2a4ce6540958\",         \"IsControlled\":false,         \"SystemId\":\"5ffee89bda7bb08625d13a29\",         \"SerialNumber\":\"5ffee89b2e68dda3816b6b5e\",         \"HostName\":\"TesiraForte5ffee89b613484edafe98b71\",         \"Description\":\"\",         \"IsProtected\":true,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":1,         \"Labels\":[                     ]      }   ],   \"TesiraErrors\":[         ],   \"DevioDevices\":[      {         \"Model\":\"Devio\",         \"ModelDescription\":\"Devio Tasha\",         \"SystemDescription\":\"Devio - Default Configuration 5fff2aa7cb1f6857fea92b62\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"01392003-1139-4156-a908-378745f27417\",         \"IsControlled\":false,         \"SystemId\":\"5fff2aa740297d9f0874bcd6\",         \"SerialNumber\":\"5fff2aa7308cf0a53f5d78a9\",         \"HostName\":\"Devio5fff2aa79acc9e23d43177a5\",         \"Description\":\"\",         \"IsProtected\":false,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":0,         \"Labels\":[                     ]      },      {         \"Model\":\"Devio\",         \"ModelDescription\":\"Devio Janice\",         \"SystemDescription\":\"Devio - Default Configuration 5fff2aa77eeea364cc835e7b\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"81907e23-9c90-47d2-be93-47b1a0a16e14\",         \"IsControlled\":false,         \"SystemId\":\"5fff2aa7c5c13cb0131ad3f1\",         \"SerialNumber\":\"5fff2aa7e4a03201392a7bc4\",         \"HostName\":\"Devio5fff2aa7cc8b6ad56c2ab9ef\",         \"Description\":\"\",         \"IsProtected\":false,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":1,         \"Labels\":[                     ]      },      {         \"Model\":\"Devio\",         \"ModelDescription\":\"Devio Kelsey\",         \"SystemDescription\":\"Devio - Default Configuration 5fff2aa786ee80bc8ed7e722\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"fcdba9e3-d963-4bd4-9f3c-364dab2c80b3\",         \"IsControlled\":false,         \"SystemId\":\"5fff2aa71012e6121eb1a2cc\",         \"SerialNumber\":\"5fff2aa765d76512b19f3430\",         \"HostName\":\"Devio5fff2aa7a5d0aa2a196b8d00\",         \"Description\":\"\",         \"IsProtected\":true,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":1,         \"Labels\":[                     ]      },      {         \"Model\":\"Devio\",         \"ModelDescription\":\"Devio Hahn\",         \"SystemDescription\":\"Devio - Default Configuration 5fff2aa74d280daa24d9a19d\",         \"FirmwareVersion\":\"3.3.0.18\",         \"OccupiedStatus\":\"Rebooting\",         \"AssetGroupId\":\"a018ab8f-af51-4e88-97e7-271284c6cbcf\",         \"IsControlled\":false,         \"SystemId\":\"5fff2aa79b85f426200232a4\",         \"SerialNumber\":\"5fff2aa72ddd381789c3f07e\",         \"HostName\":\"Devio5fff2aa72732a16aeffb6280\",         \"Description\":\"\",         \"IsProtected\":true,         \"Faults\":[            {               \"FaultId\":123,               \"IndicatorId\":\"ERR\",               \"Message\":\"Unable to get information\"            },            {               \"FaultId\":456,               \"IndicatorId\":\"ERR2\",               \"Message\":\"Unable to fetch information\"            },            {               \"FaultId\":789,               \"IndicatorId\":\"FWER\",               \"Message\":\"Firmware error\"            }         ],         \"Status\":0,         \"Labels\":[                     ]      }\t],\t   \"DevioErrors\":[         ]}");
            // TODO: null check
            if(logger.isDebugEnabled()) {
                logger.debug("Devices List: " + devicesJson);
            }
            if(devicesJson == null || devicesJson.isNull() || devicesJson.size() == 0) {
                return;
            }

            validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis() + Math.max(defaultMetaDataTimeout, deviceMetaDataInformationRetrievalTimeout);
            devicesJson.fieldNames().forEachRemaining(s -> {
                logger.debug("Extracting devices with processor: " + aggregatedDeviceProcessor);
                if(s.endsWith("Devices")){
                    devices.addAll(aggregatedDeviceProcessor.extractDevices(devicesJson.get(s)));
                }
            });

            logger.debug("Devices List Extracted: " + devices + " size: " + devices.size());

            protectedDevices.clear();
            devices.forEach(aggregatedDevice -> {
                logger.debug("Aggregated device: " + aggregatedDevice);
                if (Boolean.parseBoolean(aggregatedDevice.getProperties().get("isProtected"))) {
                    protectedDevices.add(aggregatedDevice.getSerialNumber());
                }
                if(aggregatedDevices.containsKey(aggregatedDevice.getSerialNumber())){
                    aggregatedDevices.get(aggregatedDevice.getSerialNumber()).setDeviceOnline(aggregatedDevice.getDeviceOnline());
                } else {
                    aggregatedDevices.put(aggregatedDevice.getSerialNumber(), aggregatedDevice);
                }
            });
            nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link SageVueCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
    }

    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }

    /**
     * Reboot a device based on it's serial number.
     * Requires a device model to be specified to call a specific endpoint based on that.
     * So, if the device is a "Tesira" device - an endpoint will look like /Devices/{serialNumber}/Reboot (default),
     * but if the device is a "Nexia" device, for instance - it has to be reflected in the url:
     * /devices/nexia/{serialNumber}/Reboot
     *
     * @param deviceSerialNumber device serial number
     * @param deviceModel device model to set a proper endpoint
     */
    private void reboot(String deviceSerialNumber, String deviceModel) throws Exception {
        doPut(BASE_URL + "Devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceSerialNumber + "/Reboot",
                buildAuthenticationPayload(protectedDevices.contains(deviceSerialNumber)), String.class);
    }

    private String retrieveDeviceUrlSegment(String deviceModel){
        if(deviceModel.toLowerCase().equals("tesira")){
            return "";
        } else {
            return deviceModel + "/";
        }
    }

    /**
     * Fetch a /devices endpoint to retrieve list of all devices.
     *
     * @return JsonNode instance containing an array of the devices
     */
    private JsonNode getDevices(boolean retryAuthentication) throws Exception {
        JsonNode devices;
        try {
            String devicesResponse = doGet(BASE_URL + "devices", String.class);
            devices = objectMapper.readTree(devicesResponse);
        } catch (FailedLoginException | CommandFailureException fle){
            if(retryAuthentication){
                throw new FailedLoginException("Failed to get list of devices using SessionID: " + loginId);
            }
            authenticate();
            return getDevices(true);
        }

        devices.fieldNames().forEachRemaining(s -> {
            if(s.endsWith("Devices")){
                String modelName = s.replaceAll("Devices", "");
                devices.get(s).forEach(jsonNode -> {
                    String deviceSerialNumber = jsonNode.findValue("SerialNumber").asText();
                    deviceModels.put(deviceSerialNumber, modelName);


                    ArrayNode deviceFaults = (ArrayNode) jsonNode.get("Faults");
                    if(deviceFaults.size() > 0){
                        StringBuilder faultsStringBuilder = new StringBuilder();
                        deviceFaults.forEach(fault -> {
                            faultsStringBuilder.append(fault.get("FaultId").asText()).append("|")
                                    .append(fault.get("IndicatorId").asText()).append(":").append(fault.get("Message").asText()).append("\n");
                        });
                        ((ObjectNode) jsonNode).put("Faults", faultsStringBuilder.toString());
                    }
                });
            }
        });
        return devices;
    }

    private void fetchDeviceDetails(String serialNumber) {
        String modelName = deviceModels.get(serialNumber);
        JsonNode device = getDevice(serialNumber, modelName);


        if (device != null) {
            aggregatedDeviceProcessor.applyProperties(aggregatedDevices.get(serialNumber), device, aggregatedDevices.get(serialNumber).getDeviceModel());
        }

        Set<String> firmwareVersions = new HashSet<>();
        ArrayNode firmwareVersionsResponse = getFirmwareVersions(modelName);
        firmwareVersionsResponse.forEach(firmwareVersion -> firmwareVersions.add(firmwareVersion.get("Version").asText()));
        firmwareVersions.add(aggregatedDevices.get(serialNumber).getProperties().get("FirmwareVersion"));
        aggregatedDevices.get(serialNumber).getProperties().put("AvailableFirmwareVersions", String.join(",", firmwareVersions));
    }

    /**
     * Get information about a particular device based on it's deviceId and deviceModel
     *
     * @param deviceId id of the device
     * @param deviceModel
     *
     * @return JsonNode instance that represents the device
     */
    private JsonNode getDevice(String deviceId, String deviceModel) {
        JsonNode device = null;
        try {
            String deviceResponse = doGet(BASE_URL + "devices/" + retrieveDeviceUrlSegment(deviceModel) + deviceId, String.class);
            device = objectMapper.readTree(deviceResponse).get("Device");
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
            versions = (ArrayNode) objectMapper.readTree(firmwareUpdateVersions).withArray("FirmwareUpdates");
        } catch (Exception e) {
            logger.error("Unable to find a firmware versions for model: " + deviceModel);
        }
        return versions;
    }

    /**
     * Get SageVue systems
     *
     * @return JsonNode instance containing list of SageVue systems
     */
    private JsonNode getSystems(boolean retryAuthentication) throws Exception {
//        try {
           // String devicesResponse = doGet(BASE_URL + "systems", String.class);
            return objectMapper.readTree("{\"Systems\":[{\"AssetGroupId\":\"4a7a071b-7331-4849-b842-516bae6f395d\",\"IsControlled\":false,\"Devices\":[{\"Model\":\"FORTE_VT\",\"ModelDescription\":\"TesiraFORTÉ AVB VT\",\"SystemDescription\":\"TesiraFORTÉ AVB-VT - Default Configuration 03275657\",\"FirmwareVersion\":\"3.3.0.18\",\"OccupiedStatus\":\"Rebooting\",\"AssetGroupId\":\"4a7a071b-7331-4849-b842-516bae6f395d\",\"IsControlled\":false,\"SystemId\":\"03275657\",\"SerialNumber\":\"03275657\",\"HostName\":\"TesiraForte03275657\",\"Description\":\"\",\"IsProtected\":false,\"Faults\":[{\"FaultId\": \"123\",\"IndicatorId\": \"ERR\",\"Message\": \"Unable to get information\"}, {\"FaultId\": \"456\",\"IndicatorId\": \"ERR2\",\"Message\": \"Unable to fetch information\"}, {\"FaultId\": \"789\",\"IndicatorId\": \"FWER\",\"Message\": \"Firmware error\"}],\"Status\":0,\"Labels\":[]}],\"SystemId\":\"03275657\",\"SerialNumber\":\"03275657\",\"HostName\":\"TesiraForte03275657\",\"Description\":\"TesiraFORTÉ AVB-VT - Default Configuration 03275657\",\"IsProtected\":false,\"Faults\":[{\"FaultId\": \"123\",\"IndicatorId\": \"ERR\",\"Message\": \"Unable to get information\"}, {\"FaultId\": \"456\",\"IndicatorId\": \"ERR2\",\"Message\": \"Unable to fetch information\"}, {\"FaultId\": \"789\",\"IndicatorId\": \"FWER\",\"Message\": \"Firmware error\"}],\"Status\":0,\"Labels\":[]}],\"Errors\":[]}");
//        } catch (FailedLoginException | CommandFailureException fle){
//            if(retryAuthentication){
//                throw new FailedLoginException("Failed to get list of systems using SessionID: " + loginId);
//            }
//            authenticate();
//            return getSystems(true);
//        }
    }

    @Override
    public void setPingAttempts(int pingAttempts) {
        super.setPingAttempts(pingAttempts);
    }

    @Override
    public void setPingTimeout(int pingTimeout) {
        super.setPingTimeout(pingTimeout);
    }

    @Override
    public int ping() throws Exception {
        return 60;
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
     * @param firmwareVersion firmware version that should be used
     * @param deviceModel to create a correct request url: tesira devices require using a default url: /firmware/
     *                    whereas other devices request model to be specified explicitly: /firmware/{deviceModel}
     */
    private void requestFirmwareUpdate(String deviceSerialNumber, String firmwareVersion, String deviceModel) throws Exception {
        devicesFirmwareVersions.remove(deviceSerialNumber);
        String response = doPut(BASE_URL + "Firmware/" + retrieveDeviceUrlSegment(deviceModel), buildFirmwareUpdateRequest(deviceSerialNumber, firmwareVersion), String.class);
        if(logger.isDebugEnabled()) {
            logger.trace("SageVue: Firmware update result: " + response + " for device " + deviceModel + deviceSerialNumber);
        }
    }

}
