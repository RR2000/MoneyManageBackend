package com.rondinella.moneymanageapi.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;

// Not exported via Spring Data REST: this repository holds credentials.
@RepositoryRestResource(exported = false)
public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);
}
