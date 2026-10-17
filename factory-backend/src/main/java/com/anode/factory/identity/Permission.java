package com.anode.factory.identity;

/** What a role may do; the UI shapes itself from them, the API enforces them. */
public enum Permission {
    WORK_ORDER_READ("work-order:read"),
    WORK_ORDER_WRITE("work-order:write"),
    APPROVAL_DECIDE("approval:decide"),
    SETTINGS_READ("settings:read"),
    SETTINGS_WRITE("settings:write"),
    RUN_EXECUTE("run:execute");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
