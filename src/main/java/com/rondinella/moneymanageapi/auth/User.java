package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.persistence.*;

@Entity
@Table(name = "users", schema = TenantContext.SHARED)
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(unique = true, nullable = false)
  private String username;

  @Column(nullable = false)
  private String password;

  @Column(nullable = false)
  private String role = "USER";

  /** Verified email from GitHub, only for accounts created with "Accedi con GitHub". */
  @Column(unique = true)
  private String email;

  /** Subject at the site's OpenID provider (login unico), set at the first login with it; null if never used. */
  @Column(name = "idp_subject", unique = true)
  private String idpSubject;

  public String getIdpSubject() {
    return idpSubject;
  }

  public void setIdpSubject(String idpSubject) {
    this.idpSubject = idpSubject;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
