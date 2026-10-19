package com.anode.factory.ticket;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ImportedTicketRepository extends JpaRepository<ImportedTicket, String> {

    List<ImportedTicket> findAllByOrderByImportedAtDesc();

    List<ImportedTicket> findByReportedIsNullOrReportedNot(ImportedTicket.Progress reported);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ImportedTicket t where t.ticket = :ticket")
    Optional<ImportedTicket> lock(String ticket);
}
