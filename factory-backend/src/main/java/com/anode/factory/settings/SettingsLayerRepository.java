package com.anode.factory.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsLayerRepository extends JpaRepository<SettingsLayer, String> {

    List<SettingsLayer> findByScopeStartingWith(String prefix);
}
