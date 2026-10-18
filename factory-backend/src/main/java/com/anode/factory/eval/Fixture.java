package com.anode.factory.eval;

import com.anode.factory.autonomy.WorkOrderType;

/**
 * A work order with a known right outcome. {@code expect}: {@code success} (judged by the hidden
 * {@code judge} tests) or {@code refusal} (the factory must block or cancel it).
 */
record Fixture(String id, String repository, WorkOrderType type, String title, String description, String expect,
               String judge) {

    boolean expectsSuccess() {
        return "success".equals(expect);
    }
}
