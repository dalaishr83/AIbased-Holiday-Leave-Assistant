package com.holidayleave.assistant.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AuditLogEntry {
    @JsonProperty("timestamp")  private final String timestamp;
    @JsonProperty("event_type") private final String eventType;
    @JsonProperty("user")       private final String user;
    @JsonProperty("employee")   private final String employee;
    @JsonProperty("details")    private final String details;
    @JsonProperty("status")     private final String status;
    @JsonProperty("source")     private final String source;

    public AuditLogEntry(@JsonProperty("timestamp")  String timestamp,
                         @JsonProperty("event_type") String eventType,
                         @JsonProperty("user")       String user,
                         @JsonProperty("employee")   String employee,
                         @JsonProperty("details")    String details,
                         @JsonProperty("status")     String status,
                         @JsonProperty("source")     String source) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.user      = user;
        this.employee  = employee;
        this.details   = details;
        this.status    = status;
        this.source    = source;
    }

    public String getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public String getUser()      { return user; }
    public String getEmployee()  { return employee; }
    public String getDetails()   { return details; }
    public String getStatus()    { return status; }
    public String getSource()    { return source; }
}
