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
    //final Properties properties = new Properties();

    public SageVueCommunicator() throws Exception {
        super();
        setTrustAllCertificates(true);
        objectMapper = new ObjectMapper();
        //properties.load(this.getClass().getResourceAsStream("version.properties"));
    }

    public String getLoginId() {
        return loginId;
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        logger.debug("SageVue controllable property : " + controllableProperty.getProperty() + " | " + controllableProperty.getDeviceId() + " | " + controllableProperty.getValue());

        String property = controllableProperty.getProperty();
        //Object value = controllableProperty.getValue();
        String deviceId = controllableProperty.getDeviceId();

        switch (property){
            case "Reboot Tesira":
                rebootTesira(deviceId);
                break;
            default:
                logger.debug("Operation " + property + " is not supported yet. Skipping...");
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
        logger.debug("SageVueCommunicator version : 0.1.25");
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
        Map<String, Map> authenticationBody = new HashMap();
        Map<String, String> credentials = new HashMap();
        credentials.put("userName", this.getLogin());
        credentials.put("password", this.getPassword());
        credentials.put("rememberMe", "false");
        authenticationBody.put("credentials", credentials);

        JsonNode authentication = objectMapper.readTree(doPost(BASE_URL+"login", authenticationBody, String.class));
        loginId = authentication.get("LoginId").asText();
    }

//    @Override
//    protected RestTemplate obtainRestTemplate() throws Exception {
//        RestTemplate restTemplate = super.obtainRestTemplate();
//        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
//        converter.setSupportedMediaTypes(Arrays.asList(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON, MediaType.TEXT_HTML));
//        restTemplate.getMessageConverters().add(0, converter);
//
//        restTemplate.setErrorHandler(new ResponseErrorHandler() {
//            @Override
//            public boolean hasError(ClientHttpResponse clientHttpResponse) throws IOException {
//                if(clientHttpResponse.getStatusCode() == HttpStatus.UNAUTHORIZED){
//                    try {
//                        authenticate();
//                    } catch (Exception e) {
//                        logger.error(e.getMessage());
//                    }
//                }
//                return false;
//            }
//
//            @Override
//            public void handleError(ClientHttpResponse clientHttpResponse) throws IOException {
//            }
//        });
//
//        return restTemplate;
//    }

//    public JsonObject getSystems() throws Exception {
//        logger.debug("SageVueCommunicator: getting list of systems");
//        return doGet(BASE_URL + "systems", JsonObject.class);
//    }

    public JsonNode getDevices() throws Exception {
        authenticate();
        logger.debug("SageVueCommunicator: getting list of devices");
        String devicesResponse = doGet(BASE_URL + "devices", String.class);
        logger.debug("SageVueCommunicator: list of devices: " + devicesResponse);
        return objectMapper.readTree(devicesResponse);
    }

    private List<AggregatedDevice> fetchDevicesList() throws Exception {
        List<AggregatedDevice> aggregatedDevices = new ArrayList<>();
        JsonNode devices = getDevices();
        if(logger.isInfoEnabled()) {
            logger.info(devices);
        }

        logger.debug("SageVueCommunicator: devices list: " + devices);

        devices.fields().forEachRemaining(stringJsonNodeEntry -> {
            if(stringJsonNodeEntry.getKey().endsWith("Devices")){
                logger.debug("SageVueCommunicator: creating device: " + stringJsonNodeEntry.getValue());
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

        aggregatedDevice.setStatistics(extendedStatistics);

        if(deviceInfo.findPath("Status").intValue() == 0) {
            aggregatedDevice.setDeviceOnline(true);
        }

        return aggregatedDevice;
    }

    private void rebootTesira(String deviceSerialNumber) throws Exception {
        doPut(BASE_URL + "Devices" + deviceSerialNumber + "/Reboot", null, String.class);
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.set("Content-Type", "application/json");
        headers.set("SessionID", loginId);
        return headers;
    }
}
