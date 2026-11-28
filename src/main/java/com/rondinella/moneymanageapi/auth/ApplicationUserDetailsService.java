package com.rondinella.moneymanageapi.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ApplicationUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  public ApplicationUserDetailsService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return userRepository.findByUsername(username)
        // a demo sandbox past its time is gone, even before the hourly cleanup removes it: its token stops working
        .filter(user -> !user.isDemo() || user.getExpiresAt() == null || user.getExpiresAt().isAfter(Instant.now()))
        .map(AppUserDetails::new)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }
}
