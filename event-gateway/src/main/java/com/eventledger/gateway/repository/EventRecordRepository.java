package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRecordRepository extends JpaRepository<EventRecord, String> {

    /** Events for an account in chronological order by the original event timestamp. */
    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
