package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PayeelordDataPurchaseRequest {

    @JsonProperty("network")
    private Integer network;

    @JsonProperty("plan")
    private String plan;

    @JsonProperty("mobile_number")
    private String mobileNumber;

    @JsonProperty("dataType")
    private String dataType;

    public PayeelordDataPurchaseRequest() {}

    // ← Use this constructor
    public PayeelordDataPurchaseRequest(String networkId, String dataId, String dataType, String mobileNumber) {
        this.network = networkId != null ? Integer.parseInt(networkId) : null;
        this.plan = dataId;
        this.dataType = dataType;
        this.mobileNumber = mobileNumber;
    }

    // Getters & Setters
    public Integer getNetwork() { return network; }
    public void setNetwork(Integer network) { this.network = network; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
}