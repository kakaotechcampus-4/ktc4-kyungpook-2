package com.itda.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdAndDeletedAtIsNull(Long id);
}
