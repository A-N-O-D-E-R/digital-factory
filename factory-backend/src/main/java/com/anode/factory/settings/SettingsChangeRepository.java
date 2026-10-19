package com.anode.factory.settings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsChangeRepository extends JpaRepository<SettingsChange, UUID> {

    List<SettingsChange> findByScopeOrderByChangedAtDesc(String scope);
}
