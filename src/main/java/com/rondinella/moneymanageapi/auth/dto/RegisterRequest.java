package com.rondinella.moneymanageapi.auth.dto;

public class RegisterRequest {
  private String username;
  private String password;
  /** true: the new account starts with made-up sample data instead of being empty */
  private boolean sampleData;

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

  public boolean isSampleData() {
    return sampleData;
  }

  public void setSampleData(boolean sampleData) {
    this.sampleData = sampleData;
  }
}
