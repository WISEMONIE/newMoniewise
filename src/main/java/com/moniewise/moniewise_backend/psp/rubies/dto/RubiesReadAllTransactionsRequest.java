package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-wallet/read-all-wallet-transactions.
 */
public class RubiesReadAllTransactionsRequest {

    @JsonProperty("startDate")
    private String startDate;

    @JsonProperty("endDate")
    private String endDate;

    @JsonProperty("searchItem")
    private String searchItem;

    @JsonProperty("page")
    private int page;

    @JsonProperty("pageSize")
    private int pageSize;

    public RubiesReadAllTransactionsRequest() {}

    public RubiesReadAllTransactionsRequest(
            String startDate,
            String endDate,
            String searchItem,
            int page,
            int pageSize
    ) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.searchItem = searchItem;
        this.page = page;
        this.pageSize = pageSize;
    }

    public String getStartDate()              { return startDate; }
    public void setStartDate(String v)        { this.startDate = v; }

    public String getEndDate()                { return endDate; }
    public void setEndDate(String v)          { this.endDate = v; }

    public String getSearchItem()             { return searchItem; }
    public void setSearchItem(String v)       { this.searchItem = v; }

    public int getPage()                      { return page; }
    public void setPage(int v)                { this.page = v; }

    public int getPageSize()                  { return pageSize; }
    public void setPageSize(int v)            { this.pageSize = v; }
}
