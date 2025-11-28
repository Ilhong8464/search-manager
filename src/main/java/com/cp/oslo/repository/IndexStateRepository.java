package com.cp.oslo.repository;

import com.cp.oslo.domain.IndexState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndexStateRepository extends JpaRepository<IndexState, String> {
}
