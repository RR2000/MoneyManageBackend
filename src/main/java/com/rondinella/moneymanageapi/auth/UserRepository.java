package com.rondinella.moneymanageapi.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);

  /** Usernames are unique as typed; the login also accepts another case, when only one account matches. */
  java.util.List<User> findAllByUsernameIgnoreCase(String username);

  boolean existsByUsernameIgnoreCase(String username);

  Optional<User> findFirstByEmailIn(java.util.Collection<String> emails);

  Optional<User> findByEmail(String email);
}
