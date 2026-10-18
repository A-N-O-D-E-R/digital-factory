package com.anode.factory.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CodeRepositoryRepository extends JpaRepository<CodeRepository, String> {

    List<CodeRepository> findAllByOrderByKey();
}
