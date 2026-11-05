package com.pasm.medical.repository;

import com.pasm.medical.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<Department> findAllByOrderBySortOrderAscNameAsc();

    Optional<Department> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
