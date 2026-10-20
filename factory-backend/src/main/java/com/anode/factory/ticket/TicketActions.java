package com.anode.factory.ticket;

import com.anode.factory.workorder.FactoryAction;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Workflow steps acting on the ticket a work order was made from: comment-ticket (with.message, or
 * where the work order is) and transition-ticket (with.status, a status or transition name of the
 * source). A work order made by hand has no ticket: the steps pass. So does a ticket the source cannot
 * reach: the work matters more than its ticket (the error is logged).
 */
@Configuration(proxyBeanMethods = false)
class TicketActions {

    private static final Logger log = LoggerFactory.getLogger(TicketActions.class);

    @Bean
    FactoryAction commentTicket(ImportedTicketRepository imported, TicketSources sources) {
        return action("comment-ticket", (context, ref) -> {
            Object message = context.with().get("message");
            sources.of(ref.source()).comment(ref, message != null ? message.toString()
                    : "The factory is at step " + context.stepId() + " of work order " + context.workOrderId() + ".");
            return FactoryAction.Result.proceed();
        }, imported, sources);
    }

    @Bean
    FactoryAction transitionTicket(ImportedTicketRepository imported, TicketSources sources) {
        return action("transition-ticket", (context, ref) -> {
            if (!(context.with().get("status") instanceof String status) || status.isBlank()) {
                return FactoryAction.Result.fail("transition-ticket needs with.status");
            }
            sources.of(ref.source()).transition(ref, status);
            return FactoryAction.Result.proceed();
        }, imported, sources);
    }

    private interface OnTicket {
        FactoryAction.Result apply(FactoryAction.Context context, TicketRef ref);
    }

    private static FactoryAction action(String name, OnTicket onTicket, ImportedTicketRepository imported,
                                        TicketSources sources) {
        return new FactoryAction() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result execute(Context context) {
                Optional<ImportedTicket> ticket = imported.findFirstByWorkOrderId(context.workOrderId());
                if (ticket.isEmpty()) {
                    return Result.proceed();
                }
                try {
                    return onTicket.apply(context, sources.parse(ticket.get().ticket()));
                } catch (RuntimeException e) {
                    log.warn("{} on {} failed; the work order goes on", name, ticket.get().ticket(), e);
                    return Result.proceed();
                }
            }
        };
    }
}
