package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-wallet/create-wallet
 *
 * <p>Rubies validates firstName/lastName against the BVN/NIN record with a
 * 90% name-similarity threshold — the names passed here must closely match
 * what is on the BVN/NIN record.
 *
 * <p>Field notes (from official Rubies docs):
 * <ul>
 *   <li>{@code bvn} or {@code nin} — at least one is required</li>
 *   <li>{@code dob} — YYYY-MM-DD format (e.g. "1990-01-15")</li>
 *   <li>{@code countryCode} — ISO 3166-1 alpha-2, always "NG" for Nigeria</li>
 *   <li>{@code currency} — defaults to "NGN"</li>
 *   <li>{@code accountPrefix} — if true, account name is prefixed with org name</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RubiesCreateWalletRequest {

    @JsonProperty("bvn")
    private String bvn;

    @JsonProperty("nin")
    private String nin;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phoneNumber")
    private String phoneNumber;

    /** Date of birth in YYYY-MM-DD format (e.g. "1990-01-15"). */
    @JsonProperty("dob")
    private String dob;

    @JsonProperty("countryCode")
    private String countryCode;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("accountPrefix")
    private Boolean accountPrefix;

    public RubiesCreateWalletRequest() {}

    public RubiesCreateWalletRequest(String bvn,
                                     String firstName,
                                     String lastName,
                                     String email,
                                     String phoneNumber,
                                     String dob) {
        this.bvn         = bvn;
        this.firstName   = firstName;
        this.lastName    = lastName;
        this.email       = email;
        this.phoneNumber = phoneNumber;
        this.dob         = dob;
        this.countryCode  = "NG";
        this.currency     = "NGN";
        // Business and merchant wallets stay unprefixed. An individual wallet
        // opts in through forIndividualWallet().
        this.accountPrefix = false;
    }

    /**
     * Individual wallet. The BVN first and last name are sent unchanged so
     * Rubies can match them. {@code accountPrefix} asks Rubies to place the
     * approved business name in front of that legal name.
     */
    public static RubiesCreateWalletRequest forIndividualWallet(String bvn,
                                                                String firstName,
                                                                String lastName,
                                                                String email,
                                                                String phoneNumber,
                                                                String dob) {
        RubiesCreateWalletRequest request = new RubiesCreateWalletRequest(
                bvn, firstName, lastName, email, phoneNumber, dob);
        request.setAccountPrefix(true);
        return request;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getBvn()              { return bvn; }
    public void setBvn(String v)        { this.bvn = v; }

    public String getNin()              { return nin; }
    public void setNin(String v)        { this.nin = v; }

    public String getFirstName()        { return firstName; }
    public void setFirstName(String v)  { this.firstName = v; }

    public String getLastName()         { return lastName; }
    public void setLastName(String v)   { this.lastName = v; }

    public String getEmail()            { return email; }
    public void setEmail(String v)      { this.email = v; }

    public String getPhoneNumber()      { return phoneNumber; }
    public void setPhoneNumber(String v){ this.phoneNumber = v; }

    public String getDob()              { return dob; }
    public void setDob(String v)        { this.dob = v; }

    public String getCountryCode()      { return countryCode; }
    public void setCountryCode(String v){ this.countryCode = v; }

    public String getCurrency()         { return currency; }
    public void setCurrency(String v)   { this.currency = v; }

    public Boolean getAccountPrefix()           { return accountPrefix; }
    public void setAccountPrefix(Boolean v)     { this.accountPrefix = v; }
}
